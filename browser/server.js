const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawn } = require('child_process');

const PORT = process.env.PORT || 8080;
const PUBLIC_DIR = __dirname;

const MIME_TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'application/javascript; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.mp4': 'video/mp4',
  '.webm': 'video/webm',
  '.m3u8': 'application/vnd.apple.mpegurl',
  '.ts': 'video/MP2T'
};

const probeCache = new Map();
const activeHlsSessions = new Map();

function cleanHlsSession(sessionId) {
  const session = activeHlsSessions.get(sessionId);
  if (session) {
    try { if (session.proc) session.proc.kill(); } catch (_) {}
    try { fs.rmSync(session.dir, { recursive: true, force: true }); } catch (_) {}
    activeHlsSessions.delete(sessionId);
  }
}

// Clean inactive HLS sessions older than 90s
setInterval(() => {
  const now = Date.now();
  for (const [id, session] of activeHlsSessions.entries()) {
    if (now - session.lastActive > 90000) {
      cleanHlsSession(id);
    }
  }
}, 30000);

function isMkvResponse(contentType, contentDisposition, targetUrl) {
  const rawContentType = (contentType || '').toLowerCase();
  const rawDisposition = (contentDisposition || '').toLowerCase();
  const rawUrl = (targetUrl || '').toLowerCase();
  return rawContentType.includes('mkv') || 
         rawContentType.includes('matroska') || 
         rawUrl.includes('.mkv') || 
         rawDisposition.includes('.mkv');
}

const QUALITY_BITRATES = {
  '1080': '3500k',
  '720': '1800k',
  '480': '900k',
  '360': '500k'
};

const server = http.createServer((req, res) => {
  const parsedUrl = new URL(req.url, `http://${req.headers.host}`);
  const pathname = parsedUrl.pathname;
  // Handle CORS preflight
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, HEAD, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Range, Content-Type, Accept, User-Agent',
      'Access-Control-Expose-Headers': 'Content-Range, Content-Length, Accept-Ranges',
      'Access-Control-Max-Age': '86400'
    });
    return res.end();
  }

  // ── PROBE ROUTE: /probe ───────────────────────────────────────────────────
  if (pathname === '/probe') {
    const targetUrl = parsedUrl.searchParams.get('url');
    if (!targetUrl) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ isMkv: false, duration: null }));
    }

    if (probeCache.has(targetUrl)) {
      res.writeHead(200, {
        'Content-Type': 'application/json',
        'Access-Control-Allow-Origin': '*'
      });
      return res.end(JSON.stringify(probeCache.get(targetUrl)));
    }

    let upstreamUrl;
    try {
      upstreamUrl = new URL(targetUrl);
    } catch {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ isMkv: false, duration: null }));
    }

    function doProbe(reqUrl, redirectCount = 0) {
      if (redirectCount > 5) {
        res.writeHead(200, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
        return res.end(JSON.stringify({ isMkv: false, duration: null }));
      }
      let currentUpstream;
      try {
        currentUpstream = new URL(reqUrl);
      } catch {
        res.writeHead(200, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' });
        return res.end(JSON.stringify({ isMkv: false, duration: null }));
      }
      const client = currentUpstream.protocol === 'https:' ? https : http;
      const pReq = client.request(currentUpstream, {
        method: 'GET',
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
          'Accept': '*/*',
          'Referer': currentUpstream.origin
        }
      }, (pRes) => {
        if ([301, 302, 307, 308].includes(pRes.statusCode) && pRes.headers.location) {
          const nextUrl = new URL(pRes.headers.location, reqUrl).href;
          pRes.destroy();
          return doProbe(nextUrl, redirectCount + 1);
        }

        const isMkv = isMkvResponse(pRes.headers['content-type'], pRes.headers['content-disposition'], targetUrl);
        pRes.destroy();

        const ffprobe = spawn('ffprobe', [
          '-v', 'error',
          '-select_streams', 'v:0',
          '-show_entries', 'stream=codec_name,pix_fmt:format=duration',
          '-of', 'json',
          targetUrl
        ]);

        let probeData = '';
        const timer = setTimeout(() => {
          ffprobe.kill();
        }, 8000);

        ffprobe.stdout.on('data', (chunk) => {
          probeData += chunk.toString();
        });

        ffprobe.on('close', () => {
          clearTimeout(timer);
          let videoCodec = null;
          let pixFmt = null;
          let duration = null;
          try {
            const parsed = JSON.parse(probeData);
            if (parsed.streams && parsed.streams[0]) {
              videoCodec = parsed.streams[0].codec_name || null;
              pixFmt = parsed.streams[0].pix_fmt || null;
            }
            if (parsed.format && parsed.format.duration) {
              const d = parseFloat(parsed.format.duration);
              if (isFinite(d) && d > 0) duration = d;
            }
          } catch (_) {}
          const result = { isMkv, duration, videoCodec, pixFmt };
          probeCache.set(targetUrl, result);

          res.writeHead(200, {
            'Content-Type': 'application/json',
            'Access-Control-Allow-Origin': '*'
          });
          res.end(JSON.stringify(result));
        });

        ffprobe.on('error', () => {
          clearTimeout(timer);
          const result = { isMkv, duration: null, videoCodec: null, pixFmt: null };
          probeCache.set(targetUrl, result);
          res.writeHead(200, {
            'Content-Type': 'application/json',
            'Access-Control-Allow-Origin': '*'
          });
          res.end(JSON.stringify(result));
        });
      });

      pReq.on('error', () => {
        res.writeHead(200, {
          'Content-Type': 'application/json',
          'Access-Control-Allow-Origin': '*'
        });
        res.end(JSON.stringify({ isMkv: false, duration: null, videoCodec: null, pixFmt: null }));
      });

      pReq.end();
    }

    doProbe(targetUrl);
    return;
  }

  // ── HLS STOP ROUTE: /hls/stop ─────────────────────────────────────────────
  if (pathname === '/hls/stop') {
    const id = parsedUrl.searchParams.get('id');
    if (id) cleanHlsSession(id);
    res.writeHead(200, { 'Access-Control-Allow-Origin': '*' });
    return res.end('OK');
  }

  // ── HLS MEDIA ROUTE: /hls/... ─────────────────────────────────────────────
  if (pathname.startsWith('/hls/')) {
    const parts = pathname.split('/').filter(Boolean); // ['hls', sessionId, filename]
    if (parts.length >= 3) {
      const sessionId = parts[1];
      const filename = parts[2];

      // Playlist requested: index.m3u8
      if (filename === 'index.m3u8') {
        const targetUrl = parsedUrl.searchParams.get('url');
        const ss = parsedUrl.searchParams.get('ss') || '0';
        let session = activeHlsSessions.get(sessionId);

        if (!session) {
          if (!targetUrl) {
            res.writeHead(400, { 'Content-Type': 'text/plain', 'Access-Control-Allow-Origin': '*' });
            return res.end('Missing url for HLS session');
          }

          const hlsDir = path.join(os.tmpdir(), 'hls_' + sessionId);
          fs.mkdirSync(hlsDir, { recursive: true });

          const hlsArgs = [
            '-nostdin',
            '-user_agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
            '-multiple_requests', '1',
            '-request_size', '1048576',
            '-fflags', '+genpts+discardcorrupt',
            '-err_detect', 'ignore_err',
            '-reconnect', '1',
            '-reconnect_streamed', '1',
            '-reconnect_on_network_error', '1',
            '-reconnect_delay_max', '5',
            '-threads', '0',
            '-i', targetUrl
          ];

          if (Number(ss) > 0) {
            hlsArgs.push('-ss', ss);
          }

          hlsArgs.push(
            '-map', '0:v:0',
            '-map', '0:a:0',
            '-sn',
            '-c:v', 'libx264',
            '-preset', 'ultrafast',
            '-tune', 'zerolatency',
            '-crf', '28',
            '-pix_fmt', 'yuv420p',
            '-profile:v', 'main',
            '-level', '3.1',
            '-vf', 'scale=-2:480',
            '-g', '48',
            '-keyint_min', '24',
            '-sc_threshold', '0',
            '-c:a', 'aac',
            '-b:a', '128k',
            '-ar', '44100',
            '-ac', '2',
            '-f', 'hls',
            '-hls_init_time', '2',
            '-hls_time', '4',
            '-hls_list_size', '0',
            '-hls_playlist_type', 'event',
            '-hls_segment_filename', path.join(hlsDir, 'seg%04d.ts'),
            path.join(hlsDir, 'index.m3u8')
          );

          console.log(`[HLS] Spawning FFmpeg for session ${sessionId}`);
          const proc = spawn('ffmpeg', hlsArgs, { stdio: ['ignore', 'pipe', 'pipe'] });
          session = { proc, dir: hlsDir, lastActive: Date.now() };
          activeHlsSessions.set(sessionId, session);

          proc.on('exit', (code, signal) => {
            console.log(`[HLS] FFmpeg session ${sessionId} exited code=${code} signal=${signal}`);
          });
        }

        session.lastActive = Date.now();
        const m3u8Path = path.join(session.dir, 'index.m3u8');

        let attempts = 0;
        function servePlaylist() {
          if (fs.existsSync(m3u8Path)) {
            const content = fs.readFileSync(m3u8Path, 'utf8');
            if (content.includes('.ts') || content.includes('#EXT-X-ENDLIST')) {
              res.writeHead(200, {
                'Content-Type': 'application/vnd.apple.mpegurl',
                'Access-Control-Allow-Origin': '*',
                'Cache-Control': 'no-cache, no-store'
              });
              return res.end(content);
            }
          }
          attempts++;
          if (attempts > 50) {
            res.writeHead(504, { 'Content-Type': 'text/plain', 'Access-Control-Allow-Origin': '*' });
            return res.end('HLS playlist generation timed out');
          }
          setTimeout(servePlaylist, 150);
        }

        return servePlaylist();
      }

      // Segment requested: seg0001.ts
      if (filename.endsWith('.ts')) {
        const session = activeHlsSessions.get(sessionId);
        if (!session) {
          res.writeHead(404, { 'Content-Type': 'text/plain', 'Access-Control-Allow-Origin': '*' });
          return res.end('HLS session not found');
        }

        session.lastActive = Date.now();
        const segPath = path.join(session.dir, filename);

        let attempts = 0;
        function serveSegment() {
          if (fs.existsSync(segPath)) {
            const stat = fs.statSync(segPath);
            if (stat.size > 0) {
              res.writeHead(200, {
                'Content-Type': 'video/MP2T',
                'Content-Length': stat.size,
                'Access-Control-Allow-Origin': '*',
                'Cache-Control': 'public, max-age=3600'
              });
              return fs.createReadStream(segPath).pipe(res);
            }
          }
          attempts++;
          if (attempts > 40) {
            res.writeHead(404, { 'Content-Type': 'text/plain', 'Access-Control-Allow-Origin': '*' });
            return res.end('Segment not found');
          }
          setTimeout(serveSegment, 150);
        }

        return serveSegment();
      }
    }
  }

  // ── BACKEND MEDIA ROUTE: /stream ──────────────────────────────────────────
  if (pathname === '/stream') {
    const targetUrl = parsedUrl.searchParams.get('url');
    if (!targetUrl) {
      res.writeHead(400, { 'Content-Type': 'text/plain' });
      return res.end('Missing url parameter');
    }

    let upstreamUrl;
    try {
      upstreamUrl = new URL(targetUrl);
    } catch {
      res.writeHead(400, { 'Content-Type': 'text/plain' });
      return res.end('Invalid URL');
    }

    const clientRange = req.headers.range;

    const requestHeaders = {
      'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
      'Accept': '*/*',
      'Referer': upstreamUrl.origin
    };

    if (clientRange) {
      requestHeaders['Range'] = clientRange;
    }

    const transport = upstreamUrl.protocol === 'https:' ? https : http;

    // Use GET for upstream to reliably fetch range info and headers
    const upstreamReq = transport.request(upstreamUrl, {
      method: req.method === 'HEAD' && !clientRange ? 'HEAD' : (req.method === 'HEAD' ? 'GET' : req.method),
      headers: requestHeaders
    }, (upstreamRes) => {
      // Follow redirects
      if ([301, 302, 307, 308].includes(upstreamRes.statusCode) && upstreamRes.headers.location) {
        const redirectUrl = new URL(upstreamRes.headers.location, targetUrl).href;
        if (probeCache.has(targetUrl)) {
          probeCache.set(redirectUrl, probeCache.get(targetUrl));
        }
        let newLocation = `/stream?url=${encodeURIComponent(redirectUrl)}`;
        const origSs = parsedUrl.searchParams.get('ss');
        if (origSs) newLocation += `&ss=${encodeURIComponent(origSs)}`;
        const origQuality = parsedUrl.searchParams.get('quality');
        if (origQuality) newLocation += `&quality=${encodeURIComponent(origQuality)}`;
        const origTc = parsedUrl.searchParams.get('tc');
        if (origTc) newLocation += `&tc=${encodeURIComponent(origTc)}`;
        res.writeHead(302, {
          'Location': newLocation,
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Headers': '*',
          'Access-Control-Expose-Headers': '*'
        });
        return res.end();
      }

      const cachedProbe = probeCache.get(targetUrl);
      const isHevc = cachedProbe && cachedProbe.videoCodec === 'hevc';
      const tc = parsedUrl.searchParams.get('tc');
      const isMkv = isMkvResponse(upstreamRes.headers['content-type'], upstreamRes.headers['content-disposition'], targetUrl);
      const rawQuality = (parsedUrl.searchParams.get('quality') || '').match(/\d+/);
      const quality = rawQuality ? rawQuality[0] : null;

      if (isMkv || (quality && QUALITY_BITRATES[quality]) || tc === '1' || isHevc) {
        upstreamRes.destroy();
        const ss = parsedUrl.searchParams.get('ss') || '0';
        const ffmpegArgs = [
          '-nostdin',
          '-user_agent', 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36',
          '-multiple_requests', '1',
          '-request_size', '1048576',
          '-fflags', '+genpts+discardcorrupt',
          '-err_detect', 'ignore_err',
          '-reconnect', '1',
          '-reconnect_streamed', '1',
          '-reconnect_on_network_error', '1',
          '-reconnect_delay_max', '5',
          '-threads', '0',
          '-i', targetUrl
        ];

        if (Number(ss) > 0) {
          ffmpegArgs.push('-ss', ss);
        }

        ffmpegArgs.push(
          '-map', '0:v:0',
          '-map', '0:a:0',
          '-sn'
        );

        if (quality && QUALITY_BITRATES[quality]) {
          ffmpegArgs.push(
            '-vf', `scale=-2:${quality}`,
            '-c:v', 'libx264',
            '-preset', 'veryfast',
            '-b:v', QUALITY_BITRATES[quality],
            '-c:a', 'aac',
            '-b:a', '128k',
            '-ar', '44100',
            '-ac', '2'
          );
        } else if (tc === '1' || isHevc) {
          ffmpegArgs.push(
            '-c:v', 'libx264',
            '-preset', 'ultrafast',
            '-tune', 'zerolatency',
            '-crf', '28',
            '-pix_fmt', 'yuv420p',
            '-profile:v', 'main',
            '-level', '3.1',
            '-vf', 'scale=-2:480',
            '-flush_packets', '1',
            '-c:a', 'aac',
            '-b:a', '128k',
            '-ar', '44100',
            '-ac', '2'
          );
        } else {
          ffmpegArgs.push(
            '-c:v', 'copy',
            '-c:a', 'aac',
            '-b:a', '128k',
            '-ar', '44100',
            '-ac', '2'
          );
        }

        ffmpegArgs.push(
          '-loglevel', 'warning',
          '-f', 'mp4',
          '-movflags', 'frag_keyframe+empty_moov+default_base_moof+omit_tfhd_offset',
          'pipe:1'
        );

        // Check upstream reachability; return 502 on 403/404
        const headTransport = upstreamUrl.protocol === 'https:' ? https : http;
        const headReq = headTransport.request(new URL(targetUrl), {
          method: 'HEAD',
          headers: { 'User-Agent': requestHeaders['User-Agent'] }
        }, (headRes) => {
          console.log(`[upstream] ${headRes.statusCode} ${targetUrl.slice(0, 120)}`);
          headRes.destroy();
          if (headRes.statusCode === 403 || headRes.statusCode === 404) {
            if (!res.headersSent) {
              res.writeHead(502, { 'Content-Type': 'text/plain', 'Access-Control-Allow-Origin': '*' });
              res.end(`Upstream returned ${headRes.statusCode}`);
            }
            return;
          }
          _spawnFfmpeg();
        });
        headReq.on('error', () => {
          console.log('[upstream] HEAD request failed, spawning anyway');
          _spawnFfmpeg();
        });
        headReq.end();

        function _spawnFfmpeg() {
          console.log(`[FFmpeg] Final ss: ${ss}, Args:`, ffmpegArgs);
          const ffmpegProcess = spawn('ffmpeg', ffmpegArgs, { stdio: ['ignore', 'pipe', 'pipe'] });

          let bytesSent = 0;
          ffmpegProcess.stderr.on('data', (chunk) => {
            chunk.toString().split('\n').filter(l => l.trim()).forEach(line => {
              console.log(`[ffmpeg-err] ${line}`);
            });
          });

          ffmpegProcess.on('exit', (code, signal) => {
            console.log(`[ffmpeg-exit] code=${code} signal=${signal} bytesSent=${bytesSent}`);
          });

          ffmpegProcess.on('error', (err) => {
            console.error('FFmpeg process error:', err);
            if (!res.headersSent) {
              res.writeHead(500, { 'Content-Type': 'text/plain' });
              res.end(`FFmpeg error: ${err.message}`);
            }
          });

          res.writeHead(200, {
            'Content-Type': 'video/mp4',
            'Access-Control-Allow-Origin': '*',
            'Content-Disposition': 'inline',
            'Cache-Control': 'no-cache, no-store'
          });

          ffmpegProcess.stdout.on('data', (chunk) => { bytesSent += chunk.length; });
          ffmpegProcess.stdout.pipe(res);

          req.on('close', () => { ffmpegProcess.kill(); });
        }

        return;
      }

      const responseHeaders = {
        'Accept-Ranges': 'bytes',
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Headers': 'Range, Content-Type',
        'Access-Control-Expose-Headers': 'Content-Range, Content-Length, Accept-Ranges',
        'Content-Disposition': 'inline'
      };

      if (upstreamRes.headers['content-type']) {
        responseHeaders['Content-Type'] = upstreamRes.headers['content-type'];
      } else {
        responseHeaders['Content-Type'] = 'video/mp4';
      }

      if (clientRange) {
        if (upstreamRes.statusCode === 206) {
          responseHeaders['Content-Range'] = upstreamRes.headers['content-range'];
          responseHeaders['Content-Length'] = upstreamRes.headers['content-length'];
          res.writeHead(206, responseHeaders);
        } else if (upstreamRes.headers['content-length']) {
          const total = parseInt(upstreamRes.headers['content-length'], 10);
          const parts = clientRange.replace(/bytes=/, '').split('-');
          const start = parseInt(parts[0], 10);
          const end = parts[1] ? parseInt(parts[1], 10) : total - 1;

          if (start >= total || end >= total || start > end) {
            res.writeHead(416, {
              'Content-Range': `bytes */${total}`,
              'Accept-Ranges': 'bytes'
            });
            return res.end();
          }

          responseHeaders['Content-Range'] = `bytes ${start}-${end}/${total}`;
          responseHeaders['Content-Length'] = (end - start + 1);
          res.writeHead(206, responseHeaders);
        } else {
          res.writeHead(upstreamRes.statusCode, responseHeaders);
        }
      } else {
        if (upstreamRes.headers['content-length']) {
          responseHeaders['Content-Length'] = upstreamRes.headers['content-length'];
        }
        res.writeHead(upstreamRes.statusCode, responseHeaders);
      }

      if (req.method === 'HEAD') {
        upstreamRes.destroy();
        return res.end();
      }

      upstreamRes.pipe(res);

      upstreamRes.on('error', () => {
        res.end();
      });
    });

    upstreamReq.on('error', (err) => {
      if (!res.headersSent) {
        res.writeHead(502, { 'Content-Type': 'text/plain' });
        res.end(`Upstream error: ${err.message}`);
      }
    });

    req.on('close', () => {
      upstreamReq.destroy();
    });

    upstreamReq.end();
    return;
  }

  // ── STATIC FILE SERVING WITH RANGE SUPPORT ─────────────────────────────────
  let filePath = path.join(PUBLIC_DIR, pathname === '/' ? 'index.html' : pathname);

  fs.stat(filePath, (err, stats) => {
    if (err || !stats.isFile()) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      return res.end('404 Not Found');
    }

    const ext = path.extname(filePath).toLowerCase();
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';
    const totalSize = stats.size;
    const range = req.headers.range;

    if (range) {
      const parts = range.replace(/bytes=/, '').split('-');
      const start = parseInt(parts[0], 10);
      const end = parts[1] ? parseInt(parts[1], 10) : totalSize - 1;

      if (start >= totalSize || end >= totalSize || start > end) {
        res.writeHead(416, {
          'Content-Range': `bytes */${totalSize}`,
          'Accept-Ranges': 'bytes'
        });
        return res.end();
      }

      const chunksize = end - start + 1;

      res.writeHead(206, {
        'Content-Range': `bytes ${start}-${end}/${totalSize}`,
        'Accept-Ranges': 'bytes',
        'Content-Length': chunksize,
        'Content-Type': contentType,
        'Access-Control-Allow-Origin': '*'
      });

      if (req.method === 'HEAD') {
        return res.end();
      }

      const fileStream = fs.createReadStream(filePath, { start, end });
      fileStream.pipe(res);

      req.on('close', () => {
        fileStream.destroy();
      });
    } else {
      res.writeHead(200, {
        'Content-Length': totalSize,
        'Content-Type': contentType,
        'Accept-Ranges': 'bytes',
        'Access-Control-Allow-Origin': '*'
      });

      if (req.method === 'HEAD') {
        return res.end();
      }

      const fileStream = fs.createReadStream(filePath);
      fileStream.pipe(res);

      req.on('close', () => {
        fileStream.destroy();
      });
    }
  });
});

server.listen(PORT, '0.0.0.0', () => {
  const versionProc = spawn('ffmpeg', ['-version']);
  let versionOutput = '';
  versionProc.stdout.on('data', (d) => { versionOutput += d.toString(); });
  versionProc.on('close', () => {
    const firstLine = versionOutput.split('\n')[0] || 'Unknown';
    console.log(`Server listening on 0.0.0.0:${PORT} | ${firstLine.trim()}`);
  });
  versionProc.on('error', (err) => {
    console.log(`Server listening on 0.0.0.0:${PORT} | FFmpeg unavailable: ${err.message}`);
  });
});
