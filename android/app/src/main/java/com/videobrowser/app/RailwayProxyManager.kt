package com.videobrowser.app

import android.util.Log
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object RailwayProxyManager {
    const val TAG = "RAILWAY_PROXY"

    // Configured Railway deployment domain
    const val RAILWAY_BASE_URL = "https://stream-re-baba-production.up.railway.app"

    // Global toggle for proxy routing
    var isEnabled = true

    // Short pre-flight / first-response connection timeout (3s)
    const val PROBE_TIMEOUT_MS = 3000

    // Connect timeout for ExoPlayer when using proxy (5s)
    const val PROXY_CONNECT_TIMEOUT_MS = 5000

    /**
     * Constructs the Railway proxy URL for a target video URL.
     */
    fun buildProxyUrl(originalUrl: String): String {
        return try {
            val encoded = URLEncoder.encode(originalUrl, StandardCharsets.UTF_8.toString())
            "$RAILWAY_BASE_URL/proxy?url=$encoded"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode URL for proxy", e)
            originalUrl
        }
    }

    /**
     * Checks if a given URL is currently pointing to our Railway proxy.
     */
    fun isRailwayProxyUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.startsWith(RAILWAY_BASE_URL, ignoreCase = true)
    }
}
