package com.videobrowser.app

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceResponse
import android.webkit.WebView
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

object AdBlocker {
    private const val TAG = "AdBlocker"
    private const val ASSET_FILE = "adblock_domains.txt"

    private val isInitialized = AtomicBoolean(false)
    private val blockedDomains = ConcurrentHashMap.newKeySet<String>()

    // Core fallback domains pre-seeded in memory to guarantee immediate blocking
    // even before the background asset parsing finishes or if asset loading fails.
    private val CORE_DOMAINS = setOf(
        "doubleclick.net",
        "googleadservices.com",
        "googlesyndication.com",
        "admob.com",
        "adnxs.com",
        "criteo.com",
        "criteo.net",
        "taboola.com",
        "outbrain.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "propellerclick.com",
        "exoclick.com",
        "exosrv.com",
        "adsterra.com",
        "juicyads.com",
        "trafficjunky.com",
        "trafficjunky.net",
        "plugrush.com",
        "clickadu.com",
        "adcash.com",
        "monetag.com",
        "rollerads.com",
        "richads.com",
        "pushground.com",
        "evadav.com",
        "hilltopads.net",
        "admaven.com",
        "revenuehits.com",
        "bidvertiser.com",
        "ero-advertising.com",
        "popunder.net",
        "zergnet.com",
        "coinhive.com",
        "coin-hive.com",
        "authedmine.com",
        "rubiconproject.com",
        "pubmatic.com",
        "openx.net",
        "smartadserver.com",
        "teads.tv",
        "inmobi.com",
        "applifier.com",
        "applovin.com",
        "vungle.com",
        "ironsrc.com",
        "chartboost.com",
        "smaato.net",
        "sharethrough.com",
        "bidswitch.net",
        "3lift.com",
        "yieldmo.com",
        "liveintent.com",
        "revcontent.com",
        "mgid.com",
        "connatix.com",
        "media.net",
        "lijit.com",
        "sovrn.com",
        "spotxchange.com",
        "spotx.tv",
        "fwmrm.net",
        "freewheel.tv",
        "springserve.com",
        "moatads.com",
        "adsafeprotected.com",
        "scorecardresearch.com",
        "quantserve.com",
        "hotjar.com",
        "crazyegg.com"
    )

    init {
        blockedDomains.addAll(CORE_DOMAINS)
    }

    /**
     * Initializes the AdBlocker by loading the domain blocklist from assets in a background thread.
     */
    fun init(context: Context) {
        if (isInitialized.getAndSet(true)) return

        Thread({
            try {
                loadFromAssets(context.applicationContext)
                Log.d(TAG, "Loaded ${blockedDomains.size} blocked domains from assets & core list.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load domain asset list, keeping core fallback", e)
            }
        }, "AdBlocker-Init-Thread").start()
    }

    private fun loadFromAssets(context: Context) {
        val assetManager = context.assets
        val inputStream = assetManager.open(ASSET_FILE)
        BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val cleaned = cleanDomainLine(line)
                if (cleaned != null) {
                    blockedDomains.add(cleaned)
                }
            }
        }
    }

    private fun cleanDomainLine(rawLine: String?): String? {
        if (rawLine == null) return null
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!") || trimmed.startsWith("//")) {
            return null
        }

        var domain = trimmed
        if (domain.startsWith("0.0.0.0 ") || domain.startsWith("127.0.0.1 ")) {
            domain = domain.substringAfter(" ").trim()
        }

        if (domain.startsWith("||")) {
            domain = domain.removePrefix("||")
        }
        if (domain.endsWith("^")) {
            domain = domain.removeSuffix("^")
        }

        domain = domain.trim().trimEnd('.').lowercase(Locale.ROOT)
        if (domain.contains("/") || domain.contains(":") || domain.contains(" ") || !domain.contains(".")) {
            return null
        }

        return domain
    }

    /**
     * Checks if a URL or host matches a blocked ad/tracker/popup domain.
     * Supports subdomain matching (e.g. "ads.doubleclick.net" matches "doubleclick.net").
     */
    fun isBlocked(urlOrHost: String?): Boolean {
        if (urlOrHost.isNullOrBlank()) return false
        return try {
            val host = extractHost(urlOrHost) ?: return false
            isDomainBlocked(host)
        } catch (_: Exception) {
            false
        }
    }

    fun isDomainBlocked(host: String): Boolean {
        var current = host
        while (current.isNotEmpty()) {
            if (blockedDomains.contains(current)) {
                return true
            }
            val dotIndex = current.indexOf('.')
            if (dotIndex == -1) break
            current = current.substring(dotIndex + 1)
        }
        return false
    }

    private fun extractHost(urlOrHost: String): String? {
        val trimmed = urlOrHost.trim()
        if (trimmed.isEmpty()) return null

        val candidate = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            Uri.parse(trimmed).host
        } else if (trimmed.contains("/")) {
            Uri.parse("http://$trimmed").host
        } else {
            trimmed
        }

        return candidate?.lowercase(Locale.ROOT)?.trimEnd('.')
    }

    /**
     * Returns an empty 200 OK WebResourceResponse to block the request without network traffic.
     */
    fun createEmptyResourceResponse(): WebResourceResponse {
        return WebResourceResponse(
            "text/plain",
            "UTF-8",
            200,
            "OK",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private const val COSMETIC_CSS_JS = """
        (function() {
            try {
                if (document.getElementById('__adblock_css__')) return;
                var style = document.createElement('style');
                style.id = '__adblock_css__';
                style.type = 'text/css';
                style.textContent = '[id*="banner-ad"], [class*="banner-ad"], [id*="sponsored"], [class*="sponsored"], [id^="ad-"], [class^="ad-"], [id^="ads-"], [class^="ads-"], [class*="google-auto-placed"], .adsbygoogle, .ad-container, .ad-wrapper, .ad-slot, .adbox, #popunder, .popunder { display: none !important; height: 0 !important; max-height: 0 !important; visibility: hidden !important; pointer-events: none !important; }';
                (document.head || document.documentElement).appendChild(style);
            } catch(e) {}
        })();
    """

    /**
     * Safely injects CSS to hide common ad layout slots on the page.
     */
    fun injectCosmeticCss(view: WebView?) {
        try {
            view?.evaluateJavascript(COSMETIC_CSS_JS, null)
        } catch (_: Exception) {}
    }

    fun getBlockedDomainCount(): Int = blockedDomains.size
}
