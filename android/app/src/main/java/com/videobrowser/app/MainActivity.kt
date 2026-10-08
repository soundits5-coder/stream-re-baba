package com.videobrowser.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webViewContainer: FrameLayout
    private lateinit var editAddressBar: EditText
    private lateinit var btnPastePlay: ImageButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnTabs: Button
    private lateinit var btnMenu: ImageButton
    private lateinit var pageProgressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private lateinit var bannerVideoDetected: CardView
    private lateinit var txtSniffedTitle: TextView
    private lateinit var txtSniffedUrl: TextView
    private lateinit var btnPlaySniffed: Button
    private lateinit var btnCloseBanner: ImageButton

    data class Tab(
        val id: Long,
        var title: String = "New Tab",
        var url: String = "https://www.google.com",
        val webView: WebView
    )

    private val tabs = mutableListOf<Tab>()
    private var currentTabIndex = 0
    private var tabIdCounter = 0L

    private var currentSniffedVideo: MediaSniffer.SniffedVideo? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        setupBackNavigation()

        AdBlocker.init(this)

        // Open initial tab
        createNewTab("https://www.google.com")
    }

    private fun initViews() {
        webViewContainer = findViewById(R.id.webViewContainer)
        editAddressBar = findViewById(R.id.editAddressBar)
        btnBack = findViewById(R.id.btnBack)
        btnForward = findViewById(R.id.btnForward)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnTabs = findViewById(R.id.btnTabs)
        btnMenu = findViewById(R.id.btnMenu)
        pageProgressBar = findViewById(R.id.pageProgressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        bannerVideoDetected = findViewById(R.id.bannerVideoDetected)
        txtSniffedTitle = findViewById(R.id.txtSniffedTitle)
        txtSniffedUrl = findViewById(R.id.txtSniffedUrl)
        btnPlaySniffed = findViewById(R.id.btnPlaySniffed)
        btnCloseBanner = findViewById(R.id.btnCloseBanner)
        btnPastePlay = findViewById(R.id.btnPastePlay)
    }

    private fun setupListeners() {
        editAddressBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                val input = editAddressBar.text.toString().trim()
                if (input.isNotEmpty()) {
                    if (MediaSniffer.isVideoUrl(input) || input.contains(".m3u8", ignoreCase = true)) {
                        playDirectStream(input)
                    } else {
                        loadQueryOrUrl(input)
                    }
                    hideKeyboard()
                }
                true
            } else false
        }

        btnPastePlay.setOnClickListener {
            val text = editAddressBar.text.toString().trim()
            if (text.isNotEmpty() && (MediaSniffer.isVideoUrl(text) || text.contains(".m3u8", ignoreCase = true) || text.contains(".mp4", ignoreCase = true))) {
                playDirectStream(text)
            } else {
                showPastePlayDialog()
            }
        }

        btnBack.setOnClickListener {
            val currentWebView = getCurrentWebView()
            if (currentWebView?.canGoBack() == true) {
                currentWebView.goBack()
            }
        }

        btnForward.setOnClickListener {
            val currentWebView = getCurrentWebView()
            if (currentWebView?.canGoForward() == true) {
                currentWebView.goForward()
            }
        }

        btnRefresh.setOnClickListener {
            getCurrentWebView()?.reload()
        }

        swipeRefresh.setOnRefreshListener {
            getCurrentWebView()?.reload()
        }

        btnTabs.setOnClickListener { showTabsDialog() }

        btnMenu.setOnClickListener { showBrowserMenu() }

        btnPlaySniffed.setOnClickListener {
            currentSniffedVideo?.let { video ->
                val currentWv = getCurrentWebView()
                val cookies = CookieManager.getInstance().getCookie(video.url) ?: CookieManager.getInstance().getCookie(video.pageUrl)
                val userAgent = currentWv?.settings?.userAgentString
                val referer = video.pageUrl

                PlayerActivity.start(
                    context = this,
                    url = video.url,
                    title = video.title,
                    userAgent = userAgent,
                    cookies = cookies,
                    referer = referer
                )
            }
        }

        btnCloseBanner.setOnClickListener {
            bannerVideoDetected.visibility = View.GONE
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val currentWv = getCurrentWebView()
                if (currentWv?.canGoBack() == true) {
                    currentWv.goBack()
                } else if (tabs.size > 1) {
                    closeTab(currentTabIndex)
                } else {
                    finish()
                }
            }
        })
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createNewTab(url: String) {
        val webView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
                mediaPlaybackRequiresUserGesture = false
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
            }

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                mainHandler.post {
                    showPlayOrDownloadDialog(url, userAgent, contentDisposition, mimetype)
                }
            }

            // Inject media sniffer JS bridge
            addJavascriptInterface(object {
                private var lastVideoFoundTime = 0L

                @JavascriptInterface
                fun onVideoFound(videoUrl: String?, title: String?) {
                    if (videoUrl == null) return
                    val trimmed = videoUrl.trim()
                    if ((!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) || trimmed.length >= 2000) {
                        return
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastVideoFoundTime < 500) {
                        return
                    }
                    lastVideoFoundTime = now

                    mainHandler.post {
                        onMediaSniffed(trimmed, title ?: "Video", getUrl() ?: "")
                    }
                }
            }, "AndroidBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val targetUrl = request?.url?.toString() ?: return false
                    val scheme = request?.url?.scheme?.lowercase(Locale.ROOT)
                    if (scheme != "http" && scheme != "https") {
                        Toast.makeText(this@MainActivity, "Blocked redirect", Toast.LENGTH_SHORT).show()
                        Log.e("DLFLOW", "blocked scheme: $targetUrl")
                        return true
                    }
                    return checkAndHandleMediaLink(targetUrl, view)
                }

                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, targetUrl: String?): Boolean {
                    if (targetUrl == null) return false
                    val scheme = try { Uri.parse(targetUrl)?.scheme?.lowercase(Locale.ROOT) } catch (_: Exception) { null }
                    if (scheme != "http" && scheme != "https") {
                        Toast.makeText(this@MainActivity, "Blocked redirect", Toast.LENGTH_SHORT).show()
                        Log.e("DLFLOW", "blocked scheme: $targetUrl")
                        return true
                    }
                    return checkAndHandleMediaLink(targetUrl, view)
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString()
                    try {
                        if (reqUrl != null && AdBlocker.isBlocked(reqUrl)) {
                            return AdBlocker.createEmptyResourceResponse()
                        }
                    } catch (_: Exception) {}

                    if (MediaSniffer.isVideoUrl(reqUrl)) {
                        mainHandler.post {
                            val pageUrl = view?.url ?: ""
                            val pageTitle = view?.title ?: "Video"
                            onMediaSniffed(reqUrl!!, pageTitle, pageUrl)
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    if (view === getCurrentWebView()) {
                        pageProgressBar.visibility = View.VISIBLE
                        url?.let { editAddressBar.setText(it) }
                    }
                    bannerVideoDetected.visibility = View.GONE
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (view === getCurrentWebView()) {
                        pageProgressBar.visibility = View.GONE
                        swipeRefresh.isRefreshing = false
                        updateNavigationButtons()
                    }

                    // Inject DOM video detector
                    view?.evaluateJavascript(MediaSniffer.INJECTION_JS, null)

                    // Inject cosmetic ad element hiding
                    AdBlocker.injectCosmeticCss(view)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message?
                ): Boolean {
                    try {
                        // JavaScript-triggered popups (window.open, target="_blank" attempts)
                        // Discard without spawning windows unless it is a genuine user tap on a real link
                        if (!isUserGesture) {
                            return false
                        }

                        val hitTestResult = view?.hitTestResult
                        val clickedUrl = hitTestResult?.extra
                        val type = hitTestResult?.type

                        val isRealLinkClick = (type == WebView.HitTestResult.SRC_ANCHOR_TYPE ||
                                               type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) &&
                                              !clickedUrl.isNullOrBlank()

                        if (isRealLinkClick) {
                            val target = clickedUrl!!
                            if (AdBlocker.isBlocked(target)) {
                                return false
                            }
                            // Legitimate user link click intended for new window:
                            // Navigate cleanly in the current tab instead of spawning rogue popups
                            view.loadUrl(target)
                        }
                    } catch (_: Exception) {}
                    return false
                }

                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (view === getCurrentWebView()) {
                        pageProgressBar.progress = newProgress
                        if (newProgress == 100) {
                            pageProgressBar.visibility = View.GONE
                            swipeRefresh.isRefreshing = false
                        }
                    }
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    super.onReceivedTitle(view, title)
                    title?.let { t ->
                        val matchingTab = tabs.find { it.webView === view }
                        matchingTab?.title = t
                    }
                }
            }
        }

        val tab = Tab(id = ++tabIdCounter, url = url, webView = webView)
        tabs.add(tab)
        switchToTab(tabs.size - 1)
        webView.loadUrl(url)
    }

    private fun switchToTab(index: Int) {
        if (index !in tabs.indices) return
        currentTabIndex = index

        webViewContainer.removeAllViews()
        val currentTab = tabs[currentTabIndex]
        webViewContainer.addView(currentTab.webView)

        editAddressBar.setText(currentTab.webView.url ?: currentTab.url)
        btnTabs.text = tabs.size.toString()
        updateNavigationButtons()
    }

    private fun closeTab(index: Int) {
        if (index !in tabs.indices) return
        val tab = tabs.removeAt(index)
        tab.webView.destroy()

        if (tabs.isEmpty()) {
            createNewTab("https://www.google.com")
        } else {
            val nextIndex = minOf(index, tabs.size - 1)
            switchToTab(nextIndex)
        }
    }

    private fun getCurrentWebView(): WebView? {
        return if (currentTabIndex in tabs.indices) tabs[currentTabIndex].webView else null
    }

    private fun loadQueryOrUrl(input: String) {
        val trimmed = input.trim()
        if (MediaSniffer.isVideoUrl(trimmed) || trimmed.contains(".m3u8", ignoreCase = true)) {
            playDirectStream(trimmed)
            return
        }

        val url = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else if (trimmed.contains(".") && !trimmed.contains(" ")) {
            "https://$trimmed"
        } else {
            "https://www.google.com/search?q=" + URLEncoder.encode(trimmed, StandardCharsets.UTF_8.toString())
        }
        getCurrentWebView()?.loadUrl(url)
    }

    private fun playDirectStream(rawUrl: String, title: String? = null) {
        var url = rawUrl.trim()
        if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
            url = "https://$url"
        }

        val videoTitle = title ?: ("Direct: " + url.substringAfterLast("/").substringBefore("?").ifEmpty { "Stream Video" })
        val currentWv = getCurrentWebView()

        val urlHost = try { Uri.parse(url).host } catch (_: Exception) { null }
        val loadedHost = try {
            val pageUri = currentWv?.url?.let { Uri.parse(it) }
            pageUri?.host
        } catch (_: Exception) { null }

        val isGoogleUserContent = urlHost?.endsWith("googleusercontent.com", ignoreCase = true) == true

        val cookies = if (isGoogleUserContent) {
            CookieManager.getInstance().getCookie(url)
        } else if (!urlHost.isNullOrBlank() && urlHost.equals(loadedHost, ignoreCase = true)) {
            CookieManager.getInstance().getCookie(url)
        } else {
            null
        }

        val userAgent = currentWv?.settings?.userAgentString
        val referer = if (isGoogleUserContent) "https://drive.google.com/" else null

        Toast.makeText(this, "Playing in Native Player...", Toast.LENGTH_SHORT).show()

        PlayerActivity.start(
            context = this,
            url = url,
            title = videoTitle,
            userAgent = userAgent,
            cookies = cookies,
            referer = referer
        )
    }

    private fun showPastePlayDialog() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""

        val input = EditText(this).apply {
            hint = "https://.../video.m3u8 or .mp4"
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_secondary))
            if (clipText.startsWith("http://", ignoreCase = true) || clipText.startsWith("https://", ignoreCase = true)) {
                setText(clipText)
                selectAll()
            }
            setPadding(40, 30, 40, 30)
        }

        val container = FrameLayout(this).apply {
            addView(input, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 24
                marginEnd = 24
            })
        }

        AlertDialog.Builder(this)
            .setTitle("▶ Paste & Play Stream")
            .setMessage("Paste any direct video link (HLS .m3u8, MP4, MKV, DASH .mpd, or progressive stream):")
            .setView(container)
            .setPositiveButton("Play Now") { _, _ ->
                val link = input.text.toString().trim()
                if (link.isNotEmpty()) {
                    playDirectStream(link)
                } else {
                    Toast.makeText(this, "Please enter a valid video URL", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onMediaSniffed(url: String, title: String, pageUrl: String) {
        currentSniffedVideo = MediaSniffer.SniffedVideo(
            url = url,
            title = if (title.isBlank() || title == "Video") "Stream: " + url.substringAfterLast("/").substringBefore("?") else title,
            pageUrl = pageUrl
        )
        // Stored silently without showing any UI popup/banner
    }

    private var isDownloadDialogShowing = false

    private fun checkAndHandleMediaLink(url: String, view: WebView?): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        val isMedia = lower.endsWith(".mp4") || lower.endsWith(".mkv") ||
                lower.endsWith(".m3u8") || lower.endsWith(".webm") ||
                lower.contains(".mp4?") || lower.contains(".mkv?") ||
                lower.contains(".m3u8?") || lower.contains(".webm?")

        if (isMedia) {
            showPlayOrDownloadDialog(url, view?.settings?.userAgentString)
            return true
        }
        return false
    }

    private fun showPlayOrDownloadDialog(
        url: String,
        userAgent: String? = null,
        contentDisposition: String? = null,
        mimeType: String? = null
    ) {
        Log.e("DLFLOW", "download tap: $url")
        if (isDownloadDialogShowing) return
        isDownloadDialogShowing = true

        val currentWv = getCurrentWebView()
        val pageUrl = currentWv?.url ?: url
        val actualUserAgent = userAgent ?: currentWv?.settings?.userAgentString
        val cookies = CookieManager.getInstance().getCookie(url) ?: CookieManager.getInstance().getCookie(pageUrl)
        val guessedFileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val title = guessedFileName.ifBlank { "Media Stream" }

        Log.e("DLFLOW", "dialog shown: $guessedFileName")

        AlertDialog.Builder(this)
            .setTitle("Media Action")
            .setMessage(guessedFileName)
            .setPositiveButton("Play") { _, _ ->
                PlayerActivity.start(
                    context = this,
                    url = url,
                    title = title,
                    userAgent = actualUserAgent,
                    cookies = cookies,
                    referer = pageUrl
                )
            }
            .setNegativeButton("Download") { _, _ ->
                Log.e("DLFLOW", "Download pressed: $url")
                startDownload(url, actualUserAgent, cookies, pageUrl, guessedFileName)
            }
            .setOnDismissListener {
                isDownloadDialogShowing = false
            }
            .show()
    }

    private fun startDownload(
        url: String,
        userAgent: String?,
        cookies: String?,
        referer: String?,
        fileName: String
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 201)
            }
        }

        try {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                userAgent?.let { addRequestHeader("User-Agent", it) }
                cookies?.let { addRequestHeader("Cookie", it) }
                referer?.let { addRequestHeader("Referer", it) }
                setTitle(fileName)
                setDescription("Downloading media...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            Log.e("DLFLOW", "request enqueued: $fileName")
            Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("DLFLOW", "Download failed: ${e.message}", e)
            Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateNavigationButtons() {
        val currentWv = getCurrentWebView()
        btnBack.alpha = if (currentWv?.canGoBack() == true) 1.0f else 0.4f
        btnForward.alpha = if (currentWv?.canGoForward() == true) 1.0f else 0.4f
    }

    private fun showTabsDialog() {
        val titles = tabs.mapIndexed { i, t ->
            val indicator = if (i == currentTabIndex) "● " else "○ "
            "$indicator ${t.title}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Tabs (${tabs.size})")
            .setItems(titles) { _, which ->
                switchToTab(which)
            }
            .setPositiveButton("+ New Tab") { _, _ ->
                createNewTab("https://www.google.com")
            }
            .setNegativeButton("Close Current Tab") { _, _ ->
                closeTab(currentTabIndex)
            }
            .show()
    }

    private fun showBrowserMenu() {
        val options = arrayOf(
            "▶ Paste & Play Video Link",
            "+ New Tab",
            "Desktop Site",
            "Device Codec Capabilities",
            "Close All Tabs"
        )

        AlertDialog.Builder(this)
            .setTitle("Menu")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showPastePlayDialog()
                    1 -> createNewTab("https://www.google.com")
                    2 -> toggleDesktopMode()
                    3 -> showCodecCapabilities()
                    4 -> {
                        while (tabs.size > 1) closeTab(tabs.size - 1)
                        getCurrentWebView()?.loadUrl("https://www.google.com")
                    }
                }
            }
            .show()
    }

    private fun toggleDesktopMode() {
        val wv = getCurrentWebView() ?: return
        val isDesktop = wv.settings.userAgentString.contains("X11; Linux x86_64")
        if (isDesktop) {
            wv.settings.userAgentString = null // Reset to mobile
            Toast.makeText(this, "Switched to Mobile Site", Toast.LENGTH_SHORT).show()
        } else {
            wv.settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            Toast.makeText(this, "Switched to Desktop Site", Toast.LENGTH_SHORT).show()
        }
        wv.reload()
    }

    private fun showCodecCapabilities() {
        val caps = CodecDetector.getCapabilities()
        val msg = """
            HEVC (H.265): ${if (caps.supportsHevc) "✅ Supported" else "❌ Not Supported"}
            AV1: ${if (caps.supportsAv1) "✅ Supported" else "❌ Not Supported"}
            VP9: ${if (caps.supportsVp9) "✅ Supported" else "❌ Not Supported"}
            10-Bit Video: ${if (caps.supports10Bit) "✅ Supported" else "❌ Not Supported"}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle("Phone Hardware Codecs")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(editAddressBar.windowToken, 0)
        editAddressBar.clearFocus()
    }

    override fun onDestroy() {
        for (tab in tabs) {
            try {
                tab.webView.stopLoading()
                tab.webView.destroy()
            } catch (_: Exception) {}
        }
        tabs.clear()
        webViewContainer.removeAllViews()
        super.onDestroy()
    }
}
