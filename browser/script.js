/* ============================================================
   MyBrowser — script.js
   Supports: Tabs, Address Bar, Google Search,
             In-App Video Streaming Player, Direct Download
   ============================================================ */

const $ = (id) => document.getElementById(id);

// ── Helpers ───────────────────────────────────────────────────────────────────
const VIDEO_EXTENSIONS = ['.mp4', '.mkv', '.webm', '.m3u8', '.mpd', '.mov', '.avi', '.ts', '.flv', '.ogg'];

function isVideoUrl(url) {
  if (!url) return false;
  try {
    const parsed = new URL(url);
    const pathname = parsed.pathname.toLowerCase();
    const hostname = parsed.hostname.toLowerCase();
    return VIDEO_EXTENSIONS.some(ext => pathname.endsWith(ext)) ||
           url.includes('.m3u8') ||
           url.includes('.mp4') ||
           url.includes('.mkv') ||
           url.includes('.webm') ||
           hostname.includes('googleusercontent.com') ||
           hostname.includes('drive.google.com') ||
           url.includes('videoplayback') ||
           url.includes('video-downloads');
  } catch {
    return VIDEO_EXTENSIONS.some(ext => url.toLowerCase().includes(ext));
  }
}

function parseInput(raw) {
  const trimmed = raw.trim();
  if (!trimmed) return null;
  if (/^[a-zA-Z][a-zA-Z0-9+\-.]*:\/\//.test(trimmed)) return trimmed;
  if (/^(localhost|[\w-]+(\.[\w-]+)+)(:\d+)?(\/.*)?$/.test(trimmed)) return 'https://' + trimmed;
  return 'https://www.google.com/search?q=' + encodeURIComponent(trimmed);
}

function displayUrl(url) {
  if (!url) return '';
  return url.replace(/^https?:\/\//, '');
}

function extractFilename(url) {
  try {
    const parsed = new URL(url);
    const filenameParam = parsed.searchParams.get('filename') || parsed.searchParams.get('title');
    if (filenameParam) return decodeURIComponent(filenameParam);
    if (parsed.hostname.includes('googleusercontent')) return 'Google Stream Video (HD)';
    const pathname = parsed.pathname;
    const name = pathname.substring(pathname.lastIndexOf('/') + 1);
    return decodeURIComponent(name) || 'Video Stream';
  } catch {
    return 'Online Video';
  }
}

// ── State ─────────────────────────────────────────────────────────────────────
let tabs = [];
let activeTabId = null;
let tabCounter = 0;
let currentModalUrl = '';
let hlsInstance = null;

// ── DOM refs ──────────────────────────────────────────────────────────────────
const tabBar             = $('tabBar');
const btnNewTab          = $('btnNewTab');
const btnBack            = $('btnBack');
const btnForward         = $('btnForward');
const btnRefresh         = $('btnRefresh');
const btnHome            = $('btnHome');
const btnOpenTab         = $('btnOpenTab');
const btnTheme           = $('btnTheme');
const addressBar         = $('addressBar');
const lockIcon           = $('lockIcon');
const btnAddressGo       = $('btnAddressGo');

const homePage           = $('homePage');
const homeSearchForm     = $('homeSearchForm');
const homeSearchInput    = $('homeSearchInput');

const videoInputForm     = $('videoInputForm');
const videoLinkInput     = $('videoLinkInput');
const btnPasteClipboard  = $('btnPasteClipboard');

const videoPlayerView    = $('videoPlayerView');
const videoElement       = $('videoElement');
const videoPlayerTitle   = $('videoPlayerTitle');
const btnSeekBackward    = $('btnSeekBackward');
const btnSeekForward     = $('btnSeekForward');
const btnBackFromPlayer  = $('btnBackFromPlayer');
const btnPlayerDownload  = $('btnPlayerDownload');
const btnPlayerNewTab    = $('btnPlayerNewTab');
const qualitySelect      = $('qualitySelect');
const deviceModeSelect   = $('deviceModeSelect');
const videoErrorMsg      = $('videoErrorMsg');
const btnErrorDownload   = $('btnErrorDownload');
const btnErrorRetryPhone = $('btnErrorRetryPhone');
const btnErrorRetryPc    = $('btnErrorRetryPc');
const videoSpinner       = $('videoSpinner');
const transcodeControls  = $('transcodeControls');
const tcScrubber         = $('tcScrubber');
const tcTimeCurrent      = $('tcTimeCurrent');
const tcTimeTotal        = $('tcTimeTotal');
const btnTcPlayPause     = $('btnTcPlayPause');
const btnTcFullscreen    = $('btnTcFullscreen');
const btnHeaderFullscreen = $('btnHeaderFullscreen');

const videoModal         = $('videoModal');
const modalUrlPreview    = $('modalUrlPreview');
const btnModalClose      = $('btnModalClose');
const btnActionPlayPhone = $('btnActionPlayPhone');
const btnActionPlayPc    = $('btnActionPlayPc');
const btnActionPlay      = $('btnActionPlay');
const btnActionDownload  = $('btnActionDownload');
const btnActionExternal  = $('btnActionExternal');

// ── Tab Management ────────────────────────────────────────────────────────────
function createTab(switchTo = true) {
  const id = ++tabCounter;
  const tab = {
    id,
    title: 'New Tab',
    type: 'home', // 'home' | 'video' | 'external'
    url: null,
    history: [],
    historyIdx: -1
  };
  tabs.push(tab);

  const el = document.createElement('div');
  el.className = 'tab';
  el.setAttribute('role', 'tab');
  el.setAttribute('aria-selected', 'false');
  el.dataset.tabId = id;

  const favicon = document.createElement('img');
  favicon.className = 'tab-favicon';
  favicon.src = 'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><rect width="16" height="16" rx="2" fill="%23dadce0"/></svg>';
  favicon.alt = '';

  const titleEl = document.createElement('span');
  titleEl.className = 'tab-title';
  titleEl.textContent = 'New Tab';

  const closeBtn = document.createElement('button');
  closeBtn.className = 'tab-close';
  closeBtn.innerHTML = '&times;';
  closeBtn.setAttribute('aria-label', 'Close tab');
  closeBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    closeTab(id);
  });

  el.appendChild(favicon);
  el.appendChild(titleEl);
  el.appendChild(closeBtn);
  el.addEventListener('click', () => switchTab(id));
  tabBar.insertBefore(el, btnNewTab);

  if (switchTo) switchTab(id);
  return id;
}

function switchTab(id) {
  activeTabId = id;
  tabs.forEach((t) => {
    const el = tabBar.querySelector(`[data-tab-id="${t.id}"]`);
    if (!el) return;
    const isActive = t.id === id;
    el.classList.toggle('active', isActive);
    el.setAttribute('aria-selected', String(isActive));
  });

  const tab = getActiveTab();
  if (!tab) return;

  // Restore tab state
  addressBar.value = tab.url ? displayUrl(tab.url) : '';
  const isSecure = tab.url?.startsWith('https://');
  lockIcon.textContent = isSecure ? '🔒' : (tab.url ? '🔓' : '🔒');

  if (tab.type === 'video' && tab.url) {
    showPlayerView(tab.url, false);
  } else {
    showHomeView();
  }

  updateNavBtns(tab);
}

function closeTab(id) {
  const idx = tabs.findIndex((t) => t.id === id);
  if (idx === -1) return;

  if (activeTabId === id) {
    stopCurrentVideo();
  }

  tabs.splice(idx, 1);
  tabBar.querySelector(`[data-tab-id="${id}"]`)?.remove();

  if (tabs.length === 0) {
    createTab();
  } else if (activeTabId === id) {
    switchTab(tabs[Math.min(idx, tabs.length - 1)].id);
  }
}

function getActiveTab() {
  return tabs.find((t) => t.id === activeTabId) || null;
}

function updateTabDOM(tab, title, icon = 'web') {
  tab.title = title;
  const el = tabBar.querySelector(`[data-tab-id="${tab.id}"]`);
  if (!el) return;
  el.querySelector('.tab-title').textContent = title;
  const img = el.querySelector('.tab-favicon');
  if (icon === 'video') {
    img.src = 'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><polygon points="4,2 14,8 4,14" fill="%23e50914"/></svg>';
  } else {
    img.src = 'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 16 16"><rect width="16" height="16" rx="2" fill="%231a73e8"/></svg>';
  }
}

function updateNavBtns(tab) {
  btnBack.disabled    = !tab || tab.historyIdx <= 0;
  btnForward.disabled = !tab || tab.historyIdx >= tab.history.length - 1;
}

// ── History & Navigation ──────────────────────────────────────────────────────
function recordHistory(tab, url, type) {
  if (tab.url !== url) {
    tab.history = tab.history.slice(0, tab.historyIdx + 1);
    tab.history.push({ url, type });
    tab.historyIdx = tab.history.length - 1;
  }
  tab.url = url;
  tab.type = type;
  updateNavBtns(tab);
}

function goBack() {
  const tab = getActiveTab();
  if (!tab || tab.historyIdx <= 0) return;
  tab.historyIdx--;
  const prev = tab.history[tab.historyIdx];
  restoreHistoryItem(tab, prev);
}

function goForward() {
  const tab = getActiveTab();
  if (!tab || tab.historyIdx >= tab.history.length - 1) return;
  tab.historyIdx++;
  const next = tab.history[tab.historyIdx];
  restoreHistoryItem(tab, next);
}

function restoreHistoryItem(tab, item) {
  tab.url = item.url;
  tab.type = item.type;
  if (item.type === 'video') {
    showPlayerView(item.url, false);
  } else {
    showHomeView();
    if (item.url) window.open(item.url, '_blank', 'noopener');
  }
  updateNavBtns(tab);
}

// ── Views ─────────────────────────────────────────────────────────────────────
function showHomeView() {
  stopCurrentVideo();
  videoPlayerView.style.display = 'none';
  homePage.style.display = 'flex';
  const tab = getActiveTab();
  if (tab) {
    tab.type = 'home';
    updateTabDOM(tab, 'New Tab', 'web');
  }
}

function showPlayerView(url, pushHist = true) {
  homePage.style.display = 'none';
  videoPlayerView.style.display = 'flex';
  videoErrorMsg.style.display = 'none';

  const filename = extractFilename(url);
  videoPlayerTitle.textContent = filename;
  addressBar.value = displayUrl(url);

  const tab = getActiveTab();
  if (tab) {
    if (pushHist) recordHistory(tab, url, 'video');
    updateTabDOM(tab, `🎬 ${filename}`, 'video');
  }

  loadAndPlayVideo(url);
}

// ── Video Engine (HTML5 + HLS.js with Smooth Seeking & MKV Probe) ───────────
let wasPlayingBeforeSeek = false;
let stallWatchdogTimer = null;
let isSeeking = false;
let currentPlayingOriginalUrl = '';
let mode = 'native'; // 'native' | 'transcode' | 'hls'
let totalDuration = 0;
let seekOffset = 0;
let currentLoadId = 0;
let targetSeekTime = 0;
let activeSeekRequestId = 0;
let currentQuality = 'auto';
let isVideoMkv = false;
let needsTranscode = false;
let currentDeviceMode = /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent) ? 'phone' : 'pc';
let activeHlsSessionId = null;

function formatTime(seconds) {
  if (!seconds || isNaN(seconds) || seconds < 0) return '0:00';
  const s = Math.floor(seconds);
  const m = Math.floor(s / 60);
  const remSec = s % 60;
  const remSecStr = remSec < 10 ? '0' + remSec : remSec;
  if (m < 60) return `${m}:${remSecStr}`;
  const h = Math.floor(m / 60);
  const remMin = m % 60;
  const remMinStr = remMin < 10 ? '0' + remMin : remMin;
  return `${h}:${remMinStr}:${remSecStr}`;
}

function currentAbsTime() {
  return (mode === 'transcode' || mode === 'hls') ? seekOffset + (videoElement.currentTime || 0) : (videoElement.currentTime || 0);
}

function seekToAbsolute(t) {
  const max = totalDuration > 1 ? totalDuration - 1 : (videoElement.duration || 0);
  const clamped = Math.min(Math.max(0, t), max > 0 ? max : Math.max(0, t));

  if (mode === 'native') {
    videoElement.currentTime = clamped;
  } else if (mode === 'hls' && currentDeviceMode === 'phone') {
    // If target timestamp is within already buffered range in this session, seek instantly without reloading
    const relTime = clamped - seekOffset;
    let isBuffered = false;
    if (relTime >= 0 && videoElement.buffered && videoElement.buffered.length > 0) {
      for (let i = 0; i < videoElement.buffered.length; i++) {
        if (relTime >= videoElement.buffered.start(i) && relTime <= videoElement.buffered.end(i) - 0.5) {
          isBuffered = true;
          break;
        }
      }
    }

    if (isBuffered) {
      videoElement.currentTime = relTime;
      if (videoSpinner) videoSpinner.style.display = 'none';
      return;
    }

    seekOffset = clamped;
    targetSeekTime = clamped;
    if (videoSpinner) videoSpinner.style.display = 'flex';

    if (activeHlsSessionId) {
      try { navigator.sendBeacon(`/hls/stop?id=${activeHlsSessionId}`); } catch (_) {}
      activeHlsSessionId = null;
    }
    if (hlsInstance) {
      hlsInstance.destroy();
      hlsInstance = null;
    }

    const sessionId = 'm_' + Date.now() + '_' + Math.random().toString(36).substring(2, 7);
    activeHlsSessionId = sessionId;
    const hlsUrl = `/hls/${sessionId}/index.m3u8?url=${encodeURIComponent(currentPlayingOriginalUrl)}&ss=${Math.floor(clamped)}`;

    if (window.Hls && Hls.isSupported()) {
      hlsInstance = new Hls({
        enableWorker: true,
        lowLatencyMode: false,
        backBufferLength: 90,
        maxBufferLength: 60,
        maxMaxBufferLength: 300,
        maxBufferSize: 60 * 1000 * 1000,
        autoStartLoad: true
      });
      hlsInstance.loadSource(hlsUrl);
      hlsInstance.attachMedia(videoElement);
      hlsInstance.on(Hls.Events.MANIFEST_PARSED, () => {
        if (videoSpinner) videoSpinner.style.display = 'none';
        videoElement.play().catch(() => {});
      });
    } else if (videoElement.canPlayType('application/vnd.apple.mpegurl')) {
      videoElement.src = hlsUrl;
      videoElement.addEventListener('loadedmetadata', () => {
        if (videoSpinner) videoSpinner.style.display = 'none';
        videoElement.play().catch(() => {});
      }, { once: true });
      videoElement.play().catch(() => {});
    }
  } else {
    seekOffset = clamped;
    targetSeekTime = clamped;
    if (videoSpinner) videoSpinner.style.display = 'flex';
    const thisLoadId = ++currentLoadId;
    activeSeekRequestId = thisLoadId;
    let streamUrl = `/stream?url=${encodeURIComponent(currentPlayingOriginalUrl)}&ss=${Math.floor(clamped)}`;
    if (needsTranscode) {
      streamUrl += '&tc=1';
    }
    if (currentQuality && currentQuality !== 'auto') {
      streamUrl += `&quality=${currentQuality}`;
    }
    videoElement.src = streamUrl;
    videoElement.play().catch(() => {});
  }
}

function seekBy(seconds) {
  seekToAbsolute(currentAbsTime() + seconds);
}

function stopCurrentVideo() {
  clearTimeout(stallWatchdogTimer);
  isSeeking = false;
  seekOffset = 0;
  totalDuration = 0;
  currentQuality = 'auto';
  if (qualitySelect) qualitySelect.value = 'auto';
  isVideoMkv = false;
  needsTranscode = false;
  if (hlsInstance) {
    hlsInstance.destroy();
    hlsInstance = null;
  }
  if (activeHlsSessionId) {
    try { navigator.sendBeacon(`/hls/stop?id=${activeHlsSessionId}`); } catch (_) {}
    activeHlsSessionId = null;
  }
  videoElement.pause();
  videoElement.removeAttribute('src');
}

async function loadAndPlayVideoPC(url, thisLoadId) {
  // 1. Call /probe first
  let probe = { isMkv: false, duration: null };
  try {
    const probeRes = await fetch(`/probe?url=${encodeURIComponent(url)}`);
    probe = await probeRes.json();
  } catch (_) {}

  // Ignore stale events if another video loaded while probing
  if (thisLoadId !== currentLoadId) return;

  isVideoMkv = probe.isMkv;
  needsTranscode = (probe.videoCodec === 'hevc');
  currentQuality = 'auto';
  if (qualitySelect) qualitySelect.value = 'auto';

  mode = (probe.isMkv || needsTranscode) ? 'transcode' : 'native';
  totalDuration = probe.duration || 0;
  seekOffset = 0;
  videoErrorMsg.style.display = 'none';

  if (mode === 'transcode') {
    videoElement.controls = false; // Hide native seek via custom UI only in this mode
    if (transcodeControls) {
      transcodeControls.style.display = 'flex';
      tcScrubber.max = totalDuration || 100;
      tcScrubber.value = 0;
      tcTimeCurrent.textContent = formatTime(0);
      tcTimeTotal.textContent = formatTime(totalDuration);
    }
    videoElement.src = `/stream?url=${encodeURIComponent(url)}&ss=0${needsTranscode ? '&tc=1' : ''}`;
  } else {
    videoElement.controls = true; // Native mode unchanged
    if (transcodeControls) transcodeControls.style.display = 'none';
    const streamSource = url.startsWith('http') && !url.includes(location.host)
      ? `/stream?url=${encodeURIComponent(url)}${needsTranscode ? '&tc=1' : ''}`
      : url;
    videoElement.src = streamSource;
  }

  videoElement.preload = 'metadata';
  videoElement.play().catch((err) => {
    console.warn('Playback autoplay was prevented:', err);
    if (btnTcPlayPause) btnTcPlayPause.textContent = '▶';
  });
}

// ── PHONE-SPECIFIC PLAYBACK FUNCTION (HLS STREAMING) ──────────────────────────
async function loadAndPlayVideoPhone(url, thisLoadId) {
  // Ensure native mobile inline attributes
  videoElement.setAttribute('playsinline', 'true');
  videoElement.setAttribute('webkit-playsinline', 'true');
  videoElement.controls = false; // Hide native dynamic controls so locked full duration is displayed

  if (videoSpinner) videoSpinner.style.display = 'flex';
  videoErrorMsg.style.display = 'none';

  let probe = { isMkv: false, duration: null, videoCodec: null };
  try {
    const probeRes = await fetch(`/probe?url=${encodeURIComponent(url)}`);
    probe = await probeRes.json();
  } catch (_) {}

  if (thisLoadId !== currentLoadId) return;

  isVideoMkv = probe.isMkv;
  needsTranscode = true;
  mode = 'hls';
  totalDuration = probe.duration || 0;
  seekOffset = 0;

  if (transcodeControls) {
    transcodeControls.style.display = 'flex';
    tcScrubber.max = totalDuration || 100;
    tcScrubber.value = 0;
    tcTimeCurrent.textContent = formatTime(0);
    tcTimeTotal.textContent = formatTime(totalDuration);
  }

  // Generate HLS playlist URL with unique session id
  const sessionId = 'm_' + Date.now() + '_' + Math.random().toString(36).substring(2, 7);
  activeHlsSessionId = sessionId;
  const hlsUrl = `/hls/${sessionId}/index.m3u8?url=${encodeURIComponent(url)}`;

  if (window.Hls && Hls.isSupported()) {
    if (hlsInstance) {
      hlsInstance.destroy();
      hlsInstance = null;
    }
    hlsInstance = new Hls({
      enableWorker: true,
      lowLatencyMode: false,
      backBufferLength: 90,
      maxBufferLength: 60,
      maxMaxBufferLength: 300,
      maxBufferSize: 60 * 1000 * 1000,
      autoStartLoad: true
    });

    hlsInstance.loadSource(hlsUrl);
    hlsInstance.attachMedia(videoElement);

    hlsInstance.on(Hls.Events.MANIFEST_PARSED, () => {
      if (videoSpinner) videoSpinner.style.display = 'none';
      videoElement.play().catch(() => {});
    });

    hlsInstance.on(Hls.Events.ERROR, (_, data) => {
      console.warn('Phone HLS error:', data);
      if (data.fatal) {
        switch (data.type) {
          case Hls.ErrorTypes.NETWORK_ERROR:
            hlsInstance.startLoad();
            break;
          case Hls.ErrorTypes.MEDIA_ERROR:
            hlsInstance.recoverMediaError();
            break;
          default:
            videoErrorMsg.style.display = 'flex';
            break;
        }
      }
    });
  } else if (videoElement.canPlayType('application/vnd.apple.mpegurl')) {
    // iOS Safari native HLS playback
    videoElement.src = hlsUrl;
    videoElement.addEventListener('loadedmetadata', () => {
      if (videoSpinner) videoSpinner.style.display = 'none';
      videoElement.play().catch(() => {});
    }, { once: true });
    videoElement.play().catch(() => {});
  } else {
    // Fallback to MP4 stream if HLS unsupported
    videoElement.src = `/stream?url=${encodeURIComponent(url)}&ss=0&tc=1`;
    videoElement.play().catch(() => {});
  }
}

async function loadAndPlayVideo(url) {
  stopCurrentVideo();
  currentPlayingOriginalUrl = url;
  videoErrorMsg.style.display = 'none';
  const thisLoadId = ++currentLoadId;

  const isM3U8 = url.includes('.m3u8') || url.includes('/hls');

  if (isM3U8 && window.Hls && Hls.isSupported()) {
    mode = 'hls';
    videoElement.controls = true;
    if (transcodeControls) transcodeControls.style.display = 'none';

    hlsInstance = new Hls({
      enableWorker: true,
      lowLatencyMode: false,
      backBufferLength: 90,
      maxBufferLength: 60,
      maxMaxBufferLength: 600,
      maxBufferSize: 60 * 1000 * 1000,
      maxBufferHole: 0.5,
      highBufferWatchdogPeriod: 2,
      nudgeOffset: 0.2,
      nudgeMaxRetry: 10,
      autoStartLoad: true
    });

    hlsInstance.loadSource(url);
    hlsInstance.attachMedia(videoElement);

    hlsInstance.on(Hls.Events.MANIFEST_PARSED, () => {
      videoElement.play().catch(() => {});
    });

    hlsInstance.on(Hls.Events.LEVEL_LOADED, (_, data) => {
      if (data.details && !data.details.live) {
        if (!isFinite(videoElement.duration) && data.details.totalduration) {
          totalDuration = data.details.totalduration;
        }
      }
    });

    hlsInstance.on(Hls.Events.ERROR, (_, data) => {
      if (data.fatal) {
        switch (data.type) {
          case Hls.ErrorTypes.NETWORK_ERROR:
            hlsInstance.startLoad();
            break;
          case Hls.ErrorTypes.MEDIA_ERROR:
            hlsInstance.recoverMediaError();
            break;
          default:
            videoErrorMsg.style.display = 'flex';
            break;
        }
      } else if (data.details === Hls.ErrorDetails.BUFFER_STALLED_ERROR) {
        if (!videoElement.paused) {
          videoElement.currentTime += 0.1;
        }
      }
    });
  } else if (currentDeviceMode === 'phone') {
    await loadAndPlayVideoPhone(url, thisLoadId);
  } else {
    await loadAndPlayVideoPC(url, thisLoadId);
  }
}

// ── Seeking & Stall Recovery Event Handlers ───────────────────────────────────
videoElement.addEventListener('loadstart', () => {
  if (videoSpinner) videoSpinner.style.display = 'flex';
});

videoElement.addEventListener('waiting', () => {
  if (videoSpinner) videoSpinner.style.display = 'flex';
  clearTimeout(stallWatchdogTimer);
  stallWatchdogTimer = setTimeout(() => {
    if (!videoElement.paused && videoElement.readyState >= 2) {
      videoElement.play().catch(() => {});
    }
  }, 1000);
});

videoElement.addEventListener('seeking', () => {
  isSeeking = true;
  if (videoSpinner) videoSpinner.style.display = 'flex';
  wasPlayingBeforeSeek = !videoElement.paused;
});

videoElement.addEventListener('seeked', () => {
  isSeeking = false;
  clearTimeout(stallWatchdogTimer);
  if (wasPlayingBeforeSeek && videoElement.paused) {
    videoElement.play().catch(() => {});
  }
});

function onMediaReady(reqId) {
  if (reqId !== currentLoadId) return; // Ignore stale events from previous src
  if (videoSpinner) videoSpinner.style.display = 'none';
  if (mode === 'transcode' && activeSeekRequestId !== 0) {
    activeSeekRequestId = 0;
    const effectiveTime = seekOffset + (videoElement.currentTime || 0);
    if (Math.abs(effectiveTime - targetSeekTime) > 3) {
      console.warn(`Effective time ${effectiveTime.toFixed(2)}s does not match target ${targetSeekTime.toFixed(2)}s`);
    }
  }
}

videoElement.addEventListener('playing', () => {
  onMediaReady(currentLoadId);
  if (btnTcPlayPause) btnTcPlayPause.textContent = '⏸';
  clearTimeout(stallWatchdogTimer);
});

videoElement.addEventListener('canplay', () => {
  onMediaReady(currentLoadId);
});

videoElement.addEventListener('timeupdate', () => {
  if (activeSeekRequestId !== 0 && activeSeekRequestId !== currentLoadId) return; // Stale event guard
  if ((mode === 'transcode' || mode === 'hls') && transcodeControls) {
    if (activeSeekRequestId !== 0) return; // Don't overwrite during pending seek transition
    const cur = currentAbsTime();
    if (!isSeeking) {
      tcScrubber.value = Math.floor(cur);
    }
    tcTimeCurrent.textContent = formatTime(cur);
    if (!totalDuration && videoElement.duration) {
      totalDuration = videoElement.duration;
      tcScrubber.max = totalDuration;
    }
    tcTimeTotal.textContent = formatTime(totalDuration);
  }
});

if (tcScrubber) {
  tcScrubber.addEventListener('input', () => {
    isSeeking = true;
    tcTimeCurrent.textContent = formatTime(parseFloat(tcScrubber.value));
  });
  tcScrubber.addEventListener('change', () => {
    isSeeking = false;
    if (videoSpinner) videoSpinner.style.display = 'flex'; // Show instant user releases drag
    const target = parseFloat(tcScrubber.value);
    seekToAbsolute(target);
  });
}

if (btnTcPlayPause) {
  btnTcPlayPause.addEventListener('click', () => {
    if (videoElement.paused) {
      videoElement.play().catch(() => {});
      btnTcPlayPause.textContent = '⏸';
    } else {
      videoElement.pause();
      btnTcPlayPause.textContent = '▶';
    }
  });
}

if (qualitySelect) {
  qualitySelect.addEventListener('change', () => {
    const newQuality = qualitySelect.value;
    currentQuality = newQuality;
    const resumeTime = currentAbsTime();

    if (newQuality === 'auto') {
      mode = (isVideoMkv || needsTranscode) ? 'transcode' : 'native';
    } else {
      mode = 'transcode';
    }

    if (mode === 'transcode') {
      videoElement.controls = false;
      if (transcodeControls) {
        transcodeControls.style.display = 'flex';
        const dur = totalDuration || (videoElement.duration && isFinite(videoElement.duration) ? videoElement.duration : 100);
        tcScrubber.max = dur;
        tcScrubber.value = Math.floor(resumeTime);
        tcTimeCurrent.textContent = formatTime(resumeTime);
        tcTimeTotal.textContent = formatTime(dur);
      }
      seekToAbsolute(resumeTime);
    } else {
      videoElement.controls = true;
      if (transcodeControls) transcodeControls.style.display = 'none';
      if (videoSpinner) videoSpinner.style.display = 'flex';
      const thisLoadId = ++currentLoadId;
      activeSeekRequestId = thisLoadId;
      const streamSource = currentPlayingOriginalUrl.startsWith('http') && !currentPlayingOriginalUrl.includes(location.host)
        ? `/stream?url=${encodeURIComponent(currentPlayingOriginalUrl)}${needsTranscode ? '&tc=1' : ''}`
        : currentPlayingOriginalUrl;
      videoElement.src = streamSource;
      videoElement.addEventListener('loadedmetadata', function onMeta() {
        videoElement.removeEventListener('loadedmetadata', onMeta);
        videoElement.currentTime = resumeTime;
        videoElement.play().catch(() => {});
      }, { once: true });
      videoElement.play().catch(() => {});
    }
  });
}

videoElement.addEventListener('click', (e) => {
  // If clicked directly on the video body in transcode mode, toggle play/pause
  if (videoElement.paused) {
    videoElement.play().catch(() => {});
  } else {
    videoElement.pause();
  }
});

videoElement.addEventListener('pause', () => {
  if (btnTcPlayPause) btnTcPlayPause.textContent = '▶';
});

// Double click to skip -5s on left side, +5s on right side
videoElement.addEventListener('dblclick', (e) => {
  const rect = videoElement.getBoundingClientRect();
  const clickX = e.clientX - rect.left;
  if (clickX < rect.width / 2) {
    seekBy(-5);
  } else {
    seekBy(5);
  }
});

// Auto-recovery watchdog if browser stalls waiting for buffer after seeking
videoElement.addEventListener('waiting', () => {
  clearTimeout(stallWatchdogTimer);
  stallWatchdogTimer = setTimeout(() => {
    if (!videoElement.paused && videoElement.readyState >= 2) {
      videoElement.play().catch(() => {});
    }
  }, 1000);
});

videoElement.addEventListener('stalled', () => {
  clearTimeout(stallWatchdogTimer);
  stallWatchdogTimer = setTimeout(() => {
    if (!videoElement.paused && videoElement.readyState >= 1) {
      videoElement.currentTime += 0.05;
      videoElement.play().catch(() => {});
    }
  }, 1200);
});

// ── Fullscreen Controls ───────────────────────────────────────────────────────
function toggleFullscreen() {
  const container = document.querySelector('.video-player-body');
  const isFs = !!(document.fullscreenElement || document.webkitFullscreenElement);

  if (!isFs) {
    if (container && container.requestFullscreen) {
      container.requestFullscreen().catch(() => {
        if (videoElement.webkitEnterFullscreen) {
          videoElement.webkitEnterFullscreen();
        }
      });
    } else if (videoElement.webkitEnterFullscreen) {
      videoElement.webkitEnterFullscreen();
    }
  } else {
    if (document.exitFullscreen) {
      document.exitFullscreen().catch(() => {});
    } else if (document.webkitExitFullscreen) {
      document.webkitExitFullscreen();
    }
  }
}

if (btnTcFullscreen) {
  btnTcFullscreen.addEventListener('click', (e) => {
    e.stopPropagation();
    toggleFullscreen();
  });
}

if (btnHeaderFullscreen) {
  btnHeaderFullscreen.addEventListener('click', (e) => {
    e.stopPropagation();
    toggleFullscreen();
  });
}

function updateFullscreenIcon() {
  const isFs = !!(document.fullscreenElement || document.webkitFullscreenElement);
  const icon = isFs ? '🗗' : '⛶';
  if (btnTcFullscreen) btnTcFullscreen.textContent = icon;
  if (btnHeaderFullscreen) btnHeaderFullscreen.textContent = icon;
}

document.addEventListener('fullscreenchange', updateFullscreenIcon);
document.addEventListener('webkitfullscreenchange', updateFullscreenIcon);

videoElement.addEventListener('playing', () => {
  if (videoErrorMsg) videoErrorMsg.style.display = 'none';
  clearTimeout(stallWatchdogTimer);
});

videoElement.addEventListener('canplay', () => {
  if (videoErrorMsg) videoErrorMsg.style.display = 'none';
});

videoElement.addEventListener('error', () => {
  const currentSrc = videoElement.getAttribute('src');
  if (!currentSrc || currentSrc === '' || !videoElement.src || videoElement.src === window.location.href) {
    return;
  }
  const code = videoElement.error ? videoElement.error.code : 'unknown';
  const errDesc = videoErrorMsg.querySelector('p');
  if (errDesc) {
    errDesc.textContent = `Error Code: ${code} - वीडियो लोड नहीं हो सका। आप Mode बदल कर (Phone/PC) पुनः प्रयास कर सकते हैं।`;
  }
  videoErrorMsg.style.display = 'flex';
});

if (deviceModeSelect) {
  deviceModeSelect.value = currentDeviceMode;
  deviceModeSelect.addEventListener('change', () => {
    currentDeviceMode = deviceModeSelect.value;
    if (currentPlayingOriginalUrl) {
      loadAndPlayVideo(currentPlayingOriginalUrl);
    }
  });
}

if (btnErrorRetryPhone) {
  btnErrorRetryPhone.addEventListener('click', () => {
    currentDeviceMode = 'phone';
    if (deviceModeSelect) deviceModeSelect.value = 'phone';
    if (currentPlayingOriginalUrl) {
      loadAndPlayVideo(currentPlayingOriginalUrl);
    }
  });
}

if (btnErrorRetryPc) {
  btnErrorRetryPc.addEventListener('click', () => {
    currentDeviceMode = 'pc';
    if (deviceModeSelect) deviceModeSelect.value = 'pc';
    if (currentPlayingOriginalUrl) {
      loadAndPlayVideo(currentPlayingOriginalUrl);
    }
  });
}

// ── Download Functionality ────────────────────────────────────────────────────
function triggerDownload(url) {
  if (!url) return;
  const filename = extractFilename(url);

  // Create an invisible anchor tag to trigger native browser download
  const a = document.createElement('a');
  a.href = url;
  a.download = filename || 'video.mp4';
  a.target = '_blank';
  a.rel = 'noopener noreferrer';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

// ── Video Options Modal ───────────────────────────────────────────────────────
function showVideoOptionsModal(url) {
  currentModalUrl = url;
  modalUrlPreview.textContent = url;
  videoModal.style.display = 'flex';
}

function hideVideoOptionsModal() {
  videoModal.style.display = 'none';
  currentModalUrl = '';
}

// ── Input Handling ────────────────────────────────────────────────────────────
function handleAddressSubmit(raw) {
  if (!raw) return;
  const parsed = parseInput(raw);
  if (!parsed) return;

  // Check if it's a direct video link
  if (isVideoUrl(parsed)) {
    showVideoOptionsModal(parsed);
  } else if (parsed.includes('google.com/search') || parsed.startsWith('http')) {
    // Normal website or search query: open in new tab
    window.open(parsed, '_blank', 'noopener');
    const tab = getActiveTab();
    if (tab) {
      recordHistory(tab, parsed, 'external');
      updateTabDOM(tab, displayUrl(parsed), 'web');
    }
  }
}

// ── Event Listeners ───────────────────────────────────────────────────────────
// Address bar
addressBar.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    handleAddressSubmit(addressBar.value);
    addressBar.blur();
  }
});
btnAddressGo.addEventListener('click', () => {
  handleAddressSubmit(addressBar.value);
});
addressBar.addEventListener('focus', () => addressBar.select());

// Home Search form (Google)
homeSearchForm.addEventListener('submit', (e) => {
  e.preventDefault();
  const q = homeSearchInput.value.trim();
  if (q) {
    const url = parseInput(q);
    window.open(url, '_blank', 'noopener');
    homeSearchInput.value = '';
  }
});

// Video Stream & Download form
videoInputForm.addEventListener('submit', (e) => {
  e.preventDefault();
  const raw = videoLinkInput.value.trim();
  if (raw) {
    const url = parseInput(raw);
    showVideoOptionsModal(url);
  }
});

// Paste from clipboard button
btnPasteClipboard.addEventListener('click', async () => {
  try {
    const text = await navigator.clipboard.readText();
    if (text) {
      videoLinkInput.value = text;
      videoLinkInput.focus();
    }
  } catch {
    alert('क्लिपबोर्ड एक्सेस करने की अनुमति नहीं मिली। आप सीधे Ctrl+V करके पेस्ट कर सकते हैं।');
  }
});

// Sample Test links
document.querySelectorAll('.sample-btn').forEach(btn => {
  btn.addEventListener('click', () => {
    const url = btn.dataset.url;
    videoLinkInput.value = url;
    showVideoOptionsModal(url);
  });
});

// Quick shortcut links
document.querySelectorAll('.quick-link').forEach((link) => {
  link.addEventListener('click', (e) => {
    e.preventDefault();
    if (link.dataset.url) {
      window.open(link.dataset.url, '_blank', 'noopener');
    }
  });
});

// Modal Actions
btnModalClose.addEventListener('click', hideVideoOptionsModal);
videoModal.addEventListener('click', (e) => {
  if (e.target === videoModal) hideVideoOptionsModal();
});

if (btnActionPlayPhone) {
  btnActionPlayPhone.addEventListener('click', () => {
    const url = currentModalUrl;
    hideVideoOptionsModal();
    if (url) {
      currentDeviceMode = 'phone';
      if (deviceModeSelect) deviceModeSelect.value = 'phone';
      showPlayerView(url, true);
    }
  });
}

if (btnActionPlayPc) {
  btnActionPlayPc.addEventListener('click', () => {
    const url = currentModalUrl;
    hideVideoOptionsModal();
    if (url) {
      currentDeviceMode = 'pc';
      if (deviceModeSelect) deviceModeSelect.value = 'pc';
      showPlayerView(url, true);
    }
  });
}

if (btnActionPlay) {
  btnActionPlay.addEventListener('click', () => {
    const url = currentModalUrl;
    hideVideoOptionsModal();
    if (url) {
      showPlayerView(url, true);
    }
  });
}

btnActionDownload.addEventListener('click', () => {
  const url = currentModalUrl;
  hideVideoOptionsModal();
  if (url) triggerDownload(url);
});

btnActionExternal.addEventListener('click', () => {
  const url = currentModalUrl;
  hideVideoOptionsModal();
  if (url) window.open(url, '_blank', 'noopener');
});

// Player Controls
btnBackFromPlayer.addEventListener('click', showHomeView);

if (btnSeekBackward) {
  btnSeekBackward.addEventListener('click', () => seekBy(-5));
}
if (btnSeekForward) {
  btnSeekForward.addEventListener('click', () => seekBy(5));
}

btnPlayerDownload.addEventListener('click', () => {
  const tab = getActiveTab();
  if (tab?.url) triggerDownload(tab.url);
});

btnErrorDownload.addEventListener('click', () => {
  const tab = getActiveTab();
  if (tab?.url) triggerDownload(tab.url);
});

btnPlayerNewTab.addEventListener('click', () => {
  const tab = getActiveTab();
  if (tab?.url) window.open(tab.url, '_blank', 'noopener');
});

// Browser Tabs & Navigation
btnNewTab.addEventListener('click', () => createTab());
btnBack.addEventListener('click', goBack);
btnForward.addEventListener('click', goForward);
btnRefresh.addEventListener('click', () => {
  const tab = getActiveTab();
  if (tab?.type === 'video' && tab.url) {
    loadAndPlayVideo(tab.url);
  }
});
btnHome.addEventListener('click', showHomeView);
btnOpenTab.addEventListener('click', () => {
  const tab = getActiveTab();
  if (tab?.url) window.open(tab.url, '_blank', 'noopener');
});

// Keyboard shortcuts (Ctrl+T, Ctrl+W, Ctrl+L, Arrow keys & Space for Video)
document.addEventListener('keydown', (e) => {
  const isInputFocused = document.activeElement && 
    (document.activeElement.tagName === 'INPUT' || document.activeElement.tagName === 'TEXTAREA');

  if ((e.ctrlKey || e.metaKey) && e.key === 't') { e.preventDefault(); createTab(); return; }
  if ((e.ctrlKey || e.metaKey) && e.key === 'w') { e.preventDefault(); if (activeTabId) closeTab(activeTabId); return; }
  if ((e.ctrlKey || e.metaKey) && e.key === 'l') { e.preventDefault(); addressBar.focus(); addressBar.select(); return; }

  // Video Player specific keyboard controls when watching video (arrows: left/right = -/+5s)
  if (!isInputFocused && videoPlayerView.style.display !== 'none') {
    if (e.key === 'ArrowRight') {
      e.preventDefault();
      seekBy(5);
    } else if (e.key === 'ArrowLeft') {
      e.preventDefault();
      seekBy(-5);
    } else if (e.key === ' ' || e.code === 'Space') {
      e.preventDefault();
      if (videoElement.paused) {
        videoElement.play().catch(() => {});
      } else {
        videoElement.pause();
      }
    } else if (e.key === 'f' || e.key === 'F') {
      e.preventDefault();
      toggleFullscreen();
    }
  }
});

// Dark mode toggle
function applyTheme(theme) {
  document.documentElement.dataset.theme = theme;
  btnTheme.textContent = theme === 'dark' ? '☀️' : '🌙';
  btnTheme.title = theme === 'dark' ? 'Light Mode' : 'Dark Mode';
  localStorage.setItem('browser-theme', theme);
}
btnTheme.addEventListener('click', () => {
  applyTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark');
});
(function initTheme() {
  const saved = localStorage.getItem('browser-theme');
  applyTheme(saved || (window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'));
})();

// ── Initialize App ────────────────────────────────────────────────────────────
createTab();
