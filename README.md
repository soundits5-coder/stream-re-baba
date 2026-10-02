# Vidio Streaming Player (MyBrowser) 🎬

A modern built-in web browser & smart video streaming player application. It automatically detects streaming video URLs (MP4, MKV, HLS) and provides seamless playback with HTTP Range seeking, on-the-fly FFmpeg transcoding for MKV/transcoded media, and manual quality selection (Auto / 1080p / 720p / 480p / 360p).

---

## ✨ Features

- **🌐 In-App Browser Experience**: Multiple tab management, address bar, navigation history, and quick search.
- **⚡ Instant Video Detection**: Paste any video link (Google Drive streams, direct MP4, MKV, HLS/m3u8) to get options to **Play Online** or **Download**.
- **⏩ Instant Range Seeking**:
  - Full HTTP 206 Partial Content range forwarding for direct MP4 and remote sources.
  - Accurate `-ss` backend seeking for MKV streams using FFmpeg fMP4 pipe.
- **🔄 On-the-Fly Quality Selection**:
  - Switch qualities between **Auto, 1080p, 720p, 480p, and 360p**.
  - Seamlessly resumes playback at the exact current position with spinner feedback.
- **🎯 Smart Media Probing**: Fast `/probe` endpoint caching format duration via `ffprobe`.
- **⏳ Visual Buffering & Spinner**: High-visibility synchronous buffering spinner during load and seek transitions.

---

## 🚀 Getting Started

### Prerequisites
- [Node.js](https://nodejs.org/) (v16+ recommended)
- [FFmpeg & FFprobe](https://ffmpeg.org/download.html) installed and accessible in your system `PATH`.

### Running the Server
```bash
# 1. Start the proxy and transcoding backend server:
node browser/server.js

# 2. Open index.html in your browser or serve via any static server:
npx serve browser --listen 3000
```

Open `http://localhost:3000` in your web browser.
