package com.videobrowser.app

import android.net.Uri
import android.webkit.WebResourceResponse
import java.util.Locale

object MediaSniffer {

    private val VIDEO_EXTENSIONS = listOf(
        ".m3u8", ".mp4", ".mkv", ".webm", ".mpd", ".mov", ".flv"
    )

    private val VIDEO_MIME_PREFIXES = listOf(
        "video/", "application/x-mpegurl", "application/vnd.apple.mpegurl", "application/dash+xml"
    )

    data class SniffedVideo(
        val url: String,
        val title: String,
        val pageUrl: String,
        val mimeType: String? = null,
        val cookies: String? = null,
        val userAgent: String? = null,
        val referer: String? = null
    )

    fun isVideoUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            if (scheme != "http" && scheme != "https") {
                return false
            }

            val path = uri.path?.lowercase(Locale.ROOT) ?: ""
            val host = uri.host?.lowercase(Locale.ROOT) ?: ""

            // (a) uri.path lowercased ends with one of VIDEO_EXTENSIONS
            if (VIDEO_EXTENSIONS.any { path.endsWith(it) }) {
                return true
            }

            // (b) host ends with "googlevideo.com" or equals "video-downloads.googleusercontent.com"
            if (host.endsWith("googlevideo.com") || host == "video-downloads.googleusercontent.com") {
                return true
            }

            // (c) path contains "videoplayback"
            if (path.contains("videoplayback")) {
                return true
            }

            false
        } catch (_: Exception) {
            false
        }
    }

    fun isVideoMime(mimeType: String?): Boolean {
        if (mimeType.isNullOrBlank()) return false
        val lower = mimeType.lowercase(Locale.ROOT)
        return VIDEO_MIME_PREFIXES.any { lower.startsWith(it) }
    }

    const val INJECTION_JS = """
        (function() {
            if (window.__videoSnifferInjected) return;
            window.__videoSnifferInjected = true;

            function checkVideo(el) {
                if (!el) return;
                var src = el.currentSrc || el.src;
                if (!src) {
                    var sources = el.getElementsByTagName('source');
                    if (sources && sources.length > 0) {
                        src = sources[0].src;
                    }
                }
                if (src && window.AndroidBridge) {
                    window.AndroidBridge.onVideoFound(src, document.title || 'Video');
                }
            }

            document.querySelectorAll('video').forEach(checkVideo);

            var observer = new MutationObserver(function(mutations) {
                document.querySelectorAll('video').forEach(checkVideo);
            });
            observer.observe(document.documentElement, { childList: true, subtree: true });
        })();
    """
}
