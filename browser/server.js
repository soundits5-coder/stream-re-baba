const http = require('http');
const https = require('https');
const fs = require('fs');
const path = require('path');
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
  '.webm': 'video/webm'
};

const probeCache = new Map();

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
        res.writeHead(302, { 'Location': newLocation });
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
          '-ss', ss,
          '-reconnect', '1',
          '-reconnect_streamed', '1',
          '-reconnect_delay_max', '5',
          '-i', targetUrl,
          '-map', '0:v:0',
          '-map', '0:a:0',
          '-sn'
        ];

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
