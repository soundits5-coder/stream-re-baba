package com.videobrowser.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.session.MediaSession
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private lateinit var topBarLayout: LinearLayout
    private lateinit var txtPlayerTitle: TextView
    private lateinit var btnAudio: ImageButton
    private lateinit var btnSubtitles: Button
    private lateinit var btnSpeed: Button
    private lateinit var btnAspectRatio: ImageButton
    private lateinit var btnStats: ImageButton
    private lateinit var btnPip: ImageButton
    private lateinit var btnPlayerBack: ImageButton
    private lateinit var gestureIndicatorLayout: LinearLayout
    private lateinit var txtGestureIcon: TextView
    private lateinit var txtGestureValue: TextView
    private lateinit var txtSpeedBoost: TextView
    private lateinit var txtSeekOverlay: TextView

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var playbackService: PlaybackService? = null
    private var isBoundToService = false
    private var isBackgroundPlayEnabled = true

    private var originalUrl: String = ""
    private var videoUrl: String = ""
    private var isUsingRailwayProxy: Boolean = false
    private var videoTitle: String = "Video"
    private var userAgent: String? = null
    private var cookies: String? = null
    private var referer: String? = null

    private lateinit var audioManager: AudioManager
    private var maxVolume = 15
    private var currentVolume = 7
    private var currentBrightness = 0.5f
    private var accumulatedVolumeDelta = 0f

    private var resizeModeIndex = 0
    private val resizeModes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
        AspectRatioFrameLayout.RESIZE_MODE_FILL
    )

    private val handler = Handler(Looper.getMainLooper())
    private var isLongPressingSpeed = false
    private var originalSpeed = 1.0f
    private var hasRetriedError = false
    private val trackDebugLogs = mutableListOf<String>()
    private var prepareStartTimeMs = 0L
    @Volatile private var currentPlaybackStateStr = "IDLE"
    @Volatile private var currentBufferedPositionMs = 0L

    private var debugOverlayText: TextView? = null
    private var isDebugOverlayVisible = false
    private var lastBandwidthEstimateMbps: Double = 0.0
    private val debugAnalyticsLogs = mutableListOf<String>()
    private var hasLoggedFirstTrack = false
    @Volatile private var cacheProgressStr = ""
    private var hasStartedCacheDownload = false
    @Volatile private var currentDownloadPartFile: File? = null
    @Volatile private var cacheCancelled = false
    @Volatile private var cacheConn: HttpURLConnection? = null
    @Volatile var cacheBytesWritten = 0L
    @Volatile var cacheContentLength = 0L

    fun getReadablePartFileLength(): Long {
        val file = currentDownloadPartFile ?: return 0L
        if (cacheCancelled || !file.exists()) return 0L
        val written = cacheBytesWritten
        val fileLen = try { file.length() } catch (_: Exception) { 0L }
        return max(0L, min(written, fileLen))
    }

    fun getCacheProgressFraction(): Float {
        val len = cacheContentLength
        val readable = getReadablePartFileLength()
        return if (len > 0L) (min(readable, len).toFloat() / len.toFloat()) else 0f
    }

    private fun getSeekUnsupportedToastMessage(): String {
        val isDownloading = currentDownloadPartFile != null && !cacheCancelled
        return if (isDownloading) {
            val percent = (getCacheProgressFraction() * 100).toInt()
            "Seek unlocks after download completes ($percent%)"
        } else {
            "Seeking unsupported: server returned HTTP 200 without Range support"
        }
    }

    @Volatile private var isPartialSeekActive = false
    private var lastStreamingPositionMs = 0L

    private class PartialSeekDataSource(
        private val partFile: File,
        private val getReadableLength: () -> Long
    ) : DataSource {
        private var randomAccessFile: RandomAccessFile? = null
        private var bytesRemaining: Long = 0L
        private var currentPosition: Long = 0L

        override fun addTransferListener(transferListener: TransferListener) {}

        override fun open(dataSpec: DataSpec): Long {
            currentPosition = dataSpec.position
            val readable = getReadableLength()
            if (currentPosition < 0L || currentPosition >= readable) {
                throw IOException("Requested position $currentPosition is not yet downloaded (readable: $readable bytes)")
            }

            val raf = RandomAccessFile(partFile, "r")
            randomAccessFile = raf
            raf.seek(currentPosition)

            val available = max(0L, readable - currentPosition)
            bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                min(dataSpec.length, available)
            } else {
                available
            }
            Log.d("PLAYERR", "PartialSeekDataSource: opened at $currentPosition, available=$available, bytesRemaining=$bytesRemaining")
            return bytesRemaining
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (bytesRemaining <= 0L) return C.RESULT_END_OF_INPUT

            val raf = randomAccessFile ?: return C.RESULT_END_OF_INPUT
            val readable = getReadableLength()
            val available = max(0L, readable - currentPosition)
            if (available <= 0L) {
                return C.RESULT_END_OF_INPUT
            }

            val maxToRead = min(length.toLong(), min(bytesRemaining, available)).toInt()
            if (maxToRead <= 0) {
                return C.RESULT_END_OF_INPUT
            }

            val bytesRead = raf.read(buffer, offset, maxToRead)
            if (bytesRead <= 0) {
                return C.RESULT_END_OF_INPUT
            }
            currentPosition += bytesRead
            bytesRemaining -= bytesRead
            return bytesRead
        }

        override fun getUri(): Uri = Uri.fromFile(partFile)

        override fun close() {
            try { randomAccessFile?.close() } catch (_: Exception) {}
            randomAccessFile = null
        }
    }

    private fun performPartialSeek(targetPositionMs: Long) {
        try {
            val p = player ?: return
            val partFile = currentDownloadPartFile ?: return
            val readable = getReadablePartFileLength()
            if (!partFile.exists() || readable < 1024 * 1024) {
                Toast.makeText(this, "Seek failed in partial file", Toast.LENGTH_SHORT).show()
                return
            }

            lastStreamingPositionMs = p.currentPosition
            val playWhenReady = p.playWhenReady

            val mediaItem = MediaItem.fromUri(Uri.fromFile(partFile))
            val dataSourceFactory = DataSource.Factory {
                PartialSeekDataSource(partFile) { getReadablePartFileLength() }
            }
            val extractorsFactory = DefaultExtractorsFactory()
                .setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)

            val partialSource = ProgressiveMediaSource.Factory(dataSourceFactory, extractorsFactory)
                .createMediaSource(mediaItem)

            isPartialSeekActive = true
            p.setMediaSource(partialSource, targetPositionMs)
            p.prepare()
            p.playWhenReady = playWhenReady
            appendDebugLog("PARTIAL SEEK: to ${targetPositionMs}ms via local .part")
        } catch (e: Exception) {
            Log.e("PLAYERR", "Partial seek failed", e)
            Toast.makeText(this, "Seek failed in partial file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun restoreStreamingPlayback(msg: String) {
        try {
            isPartialSeekActive = false
            val p = player ?: return
            val restorePos = lastStreamingPositionMs
            val mediaItem = MediaItem.Builder().setUri(Uri.parse(videoUrl)).build()
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36")
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .setAllowCrossProtocolRedirects(true)
            val headers = mutableMapOf<String, String>()
            cookies?.let { headers["Cookie"] = it }
            referer?.let { headers["Referer"] = it }
            if (headers.isNotEmpty()) {
                httpDataSourceFactory.setDefaultRequestProperties(headers)
            }
            val extractorsFactory = DefaultExtractorsFactory()
                .setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)
            val diagnosticDataSourceFactory = DataSource.Factory {
                SequentialStreamingDataSource(httpDataSourceFactory.createDataSource())
            }
            val mediaSource = DefaultMediaSourceFactory(diagnosticDataSourceFactory, extractorsFactory)
                .createMediaSource(mediaItem)

            p.setMediaSource(mediaSource, restorePos)
            p.prepare()
            p.playWhenReady = true
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            appendDebugLog("CACHE: restored streaming ($msg)")
        } catch (e: Exception) {
            Log.e("PLAYERR", "Failed to restore streaming", e)
        }
    }

    private val debugOverlayRunnable = object : Runnable {
        override fun run() {
            updateDebugOverlay()
            handler.postDelayed(this, 1000)
        }
    }

    private fun appendDebugLog(msg: String) {
        handler.post {
            while (debugAnalyticsLogs.size >= 6) {
                debugAnalyticsLogs.removeAt(0)
            }
            debugAnalyticsLogs.add(msg)
            updateDebugOverlay()
        }
    }

    private fun updateDebugOverlay() {
        val p = player ?: return
        currentBufferedPositionMs = p.bufferedPosition
        val stateStr = when (p.playbackState) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "UNKNOWN"
        }
        val vs = p.videoSize
        val videoSizeStr = "${vs.width}x${vs.height}"
        val bwStr = if (lastBandwidthEstimateMbps > 0) String.format(Locale.US, "%.2f Mbps", lastBandwidthEstimateMbps) else "N/A"

        val cacheLine = if (cacheProgressStr.isNotBlank()) "\n$cacheProgressStr" else ""
        val baseInfo = "State: $stateStr | Loading: ${p.isLoading}\n" +
                "Pos: ${p.currentPosition}ms / ${p.duration}ms | Buf: ${p.bufferedPosition}ms\n" +
                "BW: $bwStr | Size: $videoSizeStr$cacheLine"

        val logsStr = if (debugAnalyticsLogs.isNotEmpty()) {
            "\n" + debugAnalyticsLogs.joinToString("\n")
        } else ""

        debugOverlayText?.text = baseInfo + logsStr
    }

    companion object {
        const val EXTRA_URL = "extra_url"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        const val EXTRA_COOKIES = "extra_cookies"
        const val EXTRA_REFERER = "extra_referer"
        private const val ACTION_PIP_CONTROL = "com.videobrowser.app.ACTION_PIP_CONTROL"

        fun start(
            context: Context,
            url: String,
            title: String,
            userAgent: String? = null,
            cookies: String? = null,
            referer: String? = null
        ) {
            val intent = Intent(context, PlayerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_USER_AGENT, userAgent)
                putExtra(EXTRA_COOKIES, cookies)
                putExtra(EXTRA_REFERER, referer)
            }
            context.startActivity(intent)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service is PlaybackService.LocalBinder) {
                playbackService = service.getService()
                isBoundToService = true
                mediaSession?.let { playbackService?.registerSession(it) }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isBoundToService = false
        }
    }

    private val pipReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PIP_CONTROL) {
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                    updatePipParams()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUi()
        setContentView(R.layout.activity_player)

        originalUrl = intent.getStringExtra(EXTRA_URL) ?: ""
        if (RailwayProxyManager.isEnabled && originalUrl.isNotBlank() && !RailwayProxyManager.isRailwayProxyUrl(originalUrl)) {
            videoUrl = RailwayProxyManager.buildProxyUrl(originalUrl)
            isUsingRailwayProxy = true
            Log.i("RAILWAY_PROXY", "Trying proxy: $videoUrl")
        } else {
            videoUrl = originalUrl
            isUsingRailwayProxy = false
        }
        videoTitle = intent.getStringExtra(EXTRA_TITLE) ?: "Video"
        userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        cookies = intent.getStringExtra(EXTRA_COOKIES)
        referer = intent.getStringExtra(EXTRA_REFERER)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

        val lp = window.attributes
        currentBrightness = if (lp.screenBrightness >= 0f) {
            lp.screenBrightness
        } else {
            try {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            } catch (_: Exception) {
                0.5f
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        ContextCompat.registerReceiver(
            this,
            pipReceiver,
            IntentFilter(ACTION_PIP_CONTROL),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        initViews()
        initPlayer()
        setupGestures()
    }

    private fun initViews() {
        playerView = findViewById(R.id.exoPlayerView)
        topBarLayout = findViewById(R.id.topBarLayout)
        txtPlayerTitle = findViewById(R.id.txtPlayerTitle)
        btnAudio = findViewById(R.id.btnAudio)
        btnSubtitles = findViewById(R.id.btnSubtitles)
        btnSpeed = findViewById(R.id.btnSpeed)
        btnAspectRatio = findViewById(R.id.btnAspectRatio)
        btnPip = findViewById(R.id.btnPip)
        btnStats = findViewById(R.id.btnStats)
        btnPlayerBack = findViewById(R.id.btnPlayerBack)
        gestureIndicatorLayout = findViewById(R.id.gestureIndicatorLayout)
        txtGestureIcon = findViewById(R.id.txtGestureIcon)
        txtGestureValue = findViewById(R.id.txtGestureValue)
        txtSpeedBoost = findViewById(R.id.txtSpeedBoost)
        txtSeekOverlay = findViewById(R.id.txtSeekOverlay)

        txtPlayerTitle.text = videoTitle

        btnPlayerBack.setOnClickListener { finish() }
        btnPip.setOnClickListener { enterPipMode() }
        btnStats.setOnClickListener {
            isDebugOverlayVisible = !isDebugOverlayVisible
            debugOverlayText?.visibility = if (isDebugOverlayVisible) View.VISIBLE else View.GONE
            Toast.makeText(this, if (isDebugOverlayVisible) "Stats Overlay: ON" else "Stats Overlay: OFF", Toast.LENGTH_SHORT).show()
        }
        btnAudio.setOnClickListener { showAudioTrackDialog() }
        btnSubtitles.setOnClickListener { showSubtitleTrackDialog() }

        btnAspectRatio.setOnClickListener {
            resizeModeIndex = (resizeModeIndex + 1) % resizeModes.size
            playerView.resizeMode = resizeModes[resizeModeIndex]
            val modeName = when (resizeModes[resizeModeIndex]) {
                AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Fit"
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom (Crop)"
                else -> "Stretch"
            }
            Toast.makeText(this, "Aspect Ratio: $modeName", Toast.LENGTH_SHORT).show()
            updatePipParams()
        }

        btnSpeed.setOnClickListener { showSpeedDialog() }

        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            topBarLayout.visibility = visibility
        })

        val density = resources.displayMetrics.density
        val tv = TextView(this).apply {
            setBackgroundColor(0x88000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 10f
            val pad = (6 * density).toInt()
            setPadding(pad, pad, pad, pad)
            isClickable = false
            isFocusable = false
            visibility = View.GONE
            text = "Debug overlay init..."
        }
        debugOverlayText = tv
        val params = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
            topMargin = (60 * density).toInt()
            leftMargin = (16 * density).toInt()
        }
        findViewById<android.view.ViewGroup>(android.R.id.content).addView(tv, params)
    }

    private var isRangeSupported = true
    private var currentDataSourceBytesRead = 0L
    private var totalStreamContentLength = C.LENGTH_UNSET.toLong()

    private fun getPlayerStateString(): String = currentPlaybackStateStr

    private fun logDiagnosticResponse(
        responseCode: Int,
        responseHeaders: Map<String, List<String>>,
        rangeHeader: String,
        reqPos: Long
    ) {
        val targetHeaders = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges", "Location")
        val headersList = mutableListOf<String>()
        responseHeaders.forEach { (k, v) ->
            if (targetHeaders.any { it.equals(k, ignoreCase = true) }) {
                headersList.add("$k: ${v.joinToString(",")}")
            }
        }
        val hdrsStr = if (headersList.isNotEmpty()) headersList.joinToString(", ") else "None"
        val rangeStatusStr = when (responseCode) {
            206 -> "206 Partial Content received -> Range supported"
            200 -> "200 OK received -> server ignored Range"
            else -> "HTTP $responseCode received"
        }
        val pState = getPlayerStateString()
        val bufferedMs = currentBufferedPositionMs
        val logMsg = "Requested position: $reqPos | Requested Range: $rangeHeader | HTTP status: $responseCode ($rangeStatusStr) | Current bytes read: $currentDataSourceBytesRead | Buffer size: bounded (${bufferedMs}ms) | Playback state: $pState | Headers: [$hdrsStr]"
        Log.e("PLAYERR", logMsg)
        appendDebugLog(logMsg)
    }

    private inner class SequentialStreamingDataSource(
        private val upstream: HttpDataSource
    ) : HttpDataSource {

        private var isStreamOpen: Boolean = false

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val reqPos = dataSpec.position
            val rangeHeader = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                "bytes=${reqPos}-${reqPos + dataSpec.length - 1}"
            } else {
                "bytes=${reqPos}-"
            }

            Log.e("PLAYERR", "Requested position: $reqPos | Requested Range: $rangeHeader for uri=${dataSpec.uri}")

            // If server returned HTTP 200 (no Range) and stream is actively open
            if (!isRangeSupported && isStreamOpen) {
                if (reqPos == currentDataSourceBytesRead) {
                    val pState = getPlayerStateString()
                    val bufferedMs = currentBufferedPositionMs
                    val logMsg = "Continuing sequential stream: Requested position: $reqPos | HTTP status: 200 | 200 OK received | Current bytes read: $currentDataSourceBytesRead | Buffer size: bounded (${bufferedMs}ms) | Playback state: $pState"
                    Log.e("PLAYERR", logMsg)
                    appendDebugLog(logMsg)
                    return if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                        dataSpec.length
                    } else if (totalStreamContentLength != C.LENGTH_UNSET.toLong()) {
                        totalStreamContentLength - currentDataSourceBytesRead
                    } else {
                        C.LENGTH_UNSET.toLong()
                    }
                }

                val diff = reqPos - currentDataSourceBytesRead
                if (diff in 1..262144) {
                    val pState = getPlayerStateString()
                    Log.e("PLAYERR", "Extractor small forward skip: $diff bytes to $reqPos | State: $pState")
                    var skipped = 0L
                    val skipBuf = ByteArray(min(diff, 8192L).toInt())
                    while (skipped < diff) {
                        val toRead = min(diff - skipped, skipBuf.size.toLong()).toInt()
                        val r = upstream.read(skipBuf, 0, toRead)
                        if (r == -1) break
                        skipped += r
                        currentDataSourceBytesRead += r
                    }
                    val bufferedMs = currentBufferedPositionMs
                    val logMsg = "Skipped forward to position: $currentDataSourceBytesRead | Current bytes read: $currentDataSourceBytesRead | Buffer size: bounded (${bufferedMs}ms) | Playback state: $pState"
                    Log.e("PLAYERR", logMsg)
                    appendDebugLog(logMsg)
                    return if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                        dataSpec.length
                    } else if (totalStreamContentLength != C.LENGTH_UNSET.toLong()) {
                        totalStreamContentLength - currentDataSourceBytesRead
                    } else {
                        C.LENGTH_UNSET.toLong()
                    }
                }

                val pState = getPlayerStateString()
                val seekLog = "Extractor seek request: requested position=$reqPos, currentBytesRead=$currentDataSourceBytesRead | HTTP status: 200 | 200 OK received (non-range) | Playback state: $pState | Seeking unsupported"
                Log.e("PLAYERR", seekLog)
                appendDebugLog(seekLog)
                runOnUiThread {
                    Toast.makeText(this@PlayerActivity, getSeekUnsupportedToastMessage(), Toast.LENGTH_SHORT).show()
                }

                if (reqPos == 0L) {
                    try { upstream.close() } catch (_: Exception) {}
                    isStreamOpen = false
                    currentDataSourceBytesRead = 0L
                } else {
                    throw IOException("Seeking to byte offset $reqPos is unsupported on HTTP 200 stream (server does not support Range)")
                }
            }

            upstream.setRequestProperty("Range", rangeHeader)

            return try {
                val openResult = upstream.open(dataSpec)
                val code = upstream.responseCode
                val headers = upstream.responseHeaders
                val is206 = (code == 206)
                isRangeSupported = is206

                if (code == 200) {
                    currentDataSourceBytesRead = 0L
                    isStreamOpen = true
                    totalStreamContentLength = if (openResult != C.LENGTH_UNSET.toLong()) openResult else {
                        headers["Content-Length"]?.firstOrNull()?.toLongOrNull() ?: C.LENGTH_UNSET.toLong()
                    }
                } else if (code == 206) {
                    isStreamOpen = true
                    currentDataSourceBytesRead = reqPos
                }

                logDiagnosticResponse(code, headers, rangeHeader, reqPos)
                openResult
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                logDiagnosticResponse(e.responseCode, e.headerFields, rangeHeader, reqPos)
                throw e
            } catch (e: Exception) {
                Log.e("PLAYERR", "open failed for Range: $rangeHeader: ${e.message}")
                throw e
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val bytesRead = upstream.read(buffer, offset, length)
            if (bytesRead > 0) {
                currentDataSourceBytesRead += bytesRead
            }
            return bytesRead
        }

        override fun getUri(): Uri? = upstream.uri

        override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

        override fun getResponseCode(): Int = upstream.responseCode

        override fun setRequestProperty(name: String, value: String) {
            upstream.setRequestProperty(name, value)
        }

        override fun clearRequestProperty(name: String) {
            upstream.clearRequestProperty(name)
        }

        override fun clearAllRequestProperties() {
            upstream.clearAllRequestProperties()
        }

        override fun close() {
            try {
                upstream.close()
            } finally {
                isStreamOpen = false
            }
        }
    }

    private fun initPlayer() {
        if (videoUrl.isBlank()) {
            Toast.makeText(this, "Invalid video URL", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val connectTimeout = if (isUsingRailwayProxy) RailwayProxyManager.PROXY_CONNECT_TIMEOUT_MS else 30000
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(connectTimeout)
            .setReadTimeoutMs(30000)

        userAgent?.let { httpDataSourceFactory.setUserAgent(it) }

        val headers = mutableMapOf<String, String>()
        cookies?.let { headers["Cookie"] = it }
        referer?.let { headers["Referer"] = it }
        if (headers.isNotEmpty()) {
            httpDataSourceFactory.setDefaultRequestProperties(headers)
        }

        val extractorsFactory = DefaultExtractorsFactory()
            .setMatroskaExtractorFlags(MatroskaExtractor.FLAG_DISABLE_SEEK_FOR_CUES)

        val diagnosticDataSourceFactory = DataSource.Factory {
            SequentialStreamingDataSource(httpDataSourceFactory.createDataSource())
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(diagnosticDataSourceFactory, extractorsFactory)

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 60000, 1500, 4000)
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(15000, false)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val newPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10000)
            .setSeekForwardIncrementMs(10000)
            .build()

        player = newPlayer
        val forwardingPlayer = object : ForwardingPlayer(newPlayer) {
            override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
                if (!isRangeSupported) {
                    if (currentDownloadPartFile != null && !cacheCancelled) {
                        val dur = wrappedPlayer.duration
                        val downloadFraction = getCacheProgressFraction().toDouble()
                        val safeFraction = Math.max(0.0, Math.min(1.0, downloadFraction - 0.02))
                        val targetFraction = if (dur > 0L) (positionMs.toDouble() / dur.toDouble()) else 1.0
                        if (targetFraction <= safeFraction) {
                            performPartialSeek(positionMs)
                        } else {
                            val percent = (downloadFraction * 100).toInt()
                            Toast.makeText(this@PlayerActivity, "Yahan tak download nahi hua ($percent%)", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        Toast.makeText(this@PlayerActivity, getSeekUnsupportedToastMessage(), Toast.LENGTH_SHORT).show()
                    }
                    return
                }
                super.seekTo(mediaItemIndex, positionMs)
            }

            override fun seekTo(positionMs: Long) {
                seekTo(currentMediaItemIndex, positionMs)
            }
        }
        playerView.player = forwardingPlayer

        val resumeKey = getResumeKey(videoUrl)
        val localMkvFile = File(getExternalFilesDir(null), "stream_$resumeKey.mkv")
        val hasLocalMkv = localMkvFile.exists() && localMkvFile.length() > 0

        val savedPos = getSharedPreferences("resume_prefs", Context.MODE_PRIVATE)
            .getLong(resumeKey, 0L)
        val startPositionMs = if (!hasLocalMkv && videoUrl.contains("googleusercontent.com")) 0L else (if (savedPos > 5000L) savedPos else 0L)

        if (hasLocalMkv) {
            isRangeSupported = true
            cacheProgressStr = "CACHE: local file ready"
            appendDebugLog("CACHE: playing local file")
            val mediaItem = MediaItem.fromUri(Uri.fromFile(localMkvFile))
            val localSource = ProgressiveMediaSource.Factory(
                DefaultDataSource.Factory(this),
                DefaultExtractorsFactory()
            ).createMediaSource(mediaItem)
            prepareStartTimeMs = System.currentTimeMillis()
            newPlayer.setMediaSource(localSource, startPositionMs)
            newPlayer.prepare()
            newPlayer.playWhenReady = true
            Toast.makeText(this, "Seeking unlocked (Local cache)", Toast.LENGTH_SHORT).show()
        } else {
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(videoUrl))
                .build()

            val probeUrl = videoUrl
            val probeUa = userAgent
            val probeCookies = cookies
            val probeReferer = referer
            val probeTimeout = if (isUsingRailwayProxy) RailwayProxyManager.PROBE_TIMEOUT_MS else 15000
            Thread {
                var conn: HttpURLConnection? = null
                var code1 = -1
                var contentLength: Long? = null
                try {
                    conn = URL(probeUrl).openConnection() as HttpURLConnection
                    conn.connectTimeout = probeTimeout
                    conn.readTimeout = probeTimeout
                    conn.instanceFollowRedirects = true
                    probeUa?.let { conn.setRequestProperty("User-Agent", it) }
                    probeCookies?.let { conn.setRequestProperty("Cookie", it) }
                    probeReferer?.let { conn.setRequestProperty("Referer", it) }
                    conn.setRequestProperty("Range", "bytes=1000000-")
                    conn.connect()
                    code1 = conn.responseCode
                    val cr = conn.getHeaderField("Content-Range")
                    val logMsg = "SEEK PROBE: HTTP $code1, Content-Range=$cr"
                    Log.e("PLAYERR", logMsg)
                    handler.post { appendDebugLog(logMsg) }

                    contentLength = conn.getHeaderField("Content-Length")?.toLongOrNull()
                } catch (e: Exception) {
                    val logMsg = "SEEK PROBE: failed ${e.message}"
                    Log.e("PLAYERR", logMsg, e)
                    handler.post { appendDebugLog(logMsg) }
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) {}
                }

                var conn2: HttpURLConnection? = null
                var code2 = -1
                var cr2: String? = null
                try {
                    conn2 = URL(probeUrl).openConnection() as HttpURLConnection
                    conn2.connectTimeout = probeTimeout
                    conn2.readTimeout = probeTimeout
                    conn2.instanceFollowRedirects = true
                    probeUa?.let { conn2.setRequestProperty("User-Agent", it) }
                    probeCookies?.let { conn2.setRequestProperty("Cookie", it) }
                    probeReferer?.let { conn2.setRequestProperty("Referer", it) }
                    conn2.setRequestProperty("Range", "bytes=1000000-1000100")
                    conn2.connect()
                    code2 = conn2.responseCode
                    cr2 = conn2.getHeaderField("Content-Range")
                    val logMsg2 = "SEEK PROBE2: HTTP $code2, Content-Range=$cr2"
                    Log.e("PLAYERR", logMsg2)
                    handler.post { appendDebugLog(logMsg2) }
                } catch (e: Exception) {
                    val logMsg2 = "SEEK PROBE2: failed ${e.message}"
                    Log.e("PLAYERR", logMsg2, e)
                    handler.post { appendDebugLog(logMsg2) }
                } finally {
                    try { conn2?.disconnect() } catch (_: Exception) {}
                }

                if (code2 == 206 && !cr2.isNullOrBlank()) {
                    isRangeSupported = true
                    if (isUsingRailwayProxy) {
                        Log.i("RAILWAY_PROXY", "Railway proxy Range probe succeeded (HTTP 206). Range seeking enabled!")
                    }
                    handler.post { appendDebugLog("SEEK: Range supported, download skipped") }
                    cacheProgressStr = "CACHE: skipped (Range OK)"
                } else {
                    if (isUsingRailwayProxy) {
                        Log.w("RAILWAY_PROXY", "Railway proxy returned non-206 ($code2, code1=$code1). Falling back to direct URL.")
                        handler.post {
                            if (isUsingRailwayProxy) {
                                isUsingRailwayProxy = false
                                videoUrl = originalUrl
                                appendDebugLog("RAILWAY: fallback to direct")
                                restoreStreamingPlayback("Proxy unavailable, direct stream active")
                            }
                        }
                    } else if (code1 == 200 && contentLength != null && contentLength > 0) {
                        synchronized(this@PlayerActivity) {
                            if (!hasStartedCacheDownload) {
                                hasStartedCacheDownload = true
                                startCacheDownloadThread(originalUrl, probeUa, probeCookies, probeReferer, contentLength, resumeKey)
                            }
                        }
                    }
                }
            }.start()

            prepareStartTimeMs = System.currentTimeMillis()
            newPlayer.setMediaItem(mediaItem, startPositionMs)
            newPlayer.prepare()
            newPlayer.playWhenReady = true
        }

        if (startPositionMs > 0) {
            Toast.makeText(this, "Resumed playback", Toast.LENGTH_SHORT).show()
        }

        newPlayer.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                val timeMs = System.currentTimeMillis() - prepareStartTimeMs
                Log.e("PLAYERR", "time to first frame: $timeMs ms")
            }

            override fun onPlaybackStateChanged(state: Int) {
                currentPlaybackStateStr = when (state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                player?.let { currentBufferedPositionMs = it.bufferedPosition }
                if (state == Player.STATE_ENDED) {
                    getSharedPreferences("resume_prefs", Context.MODE_PRIVATE)
                        .edit().remove(getResumeKey(videoUrl)).apply()
                }
                updatePipParams()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePipParams()
            }

            override fun onTracksChanged(tracks: Tracks) {
                trackDebugLogs.clear()
                for (group in tracks.groups) {
                    val typeStr = when (group.type) {
                        C.TRACK_TYPE_AUDIO -> "audio"
                        C.TRACK_TYPE_VIDEO -> "video"
                        C.TRACK_TYPE_TEXT -> "text"
                        else -> "other(${group.type})"
                    }
                    val mediaTrackGroup = group.mediaTrackGroup
                    for (i in 0 until mediaTrackGroup.length) {
                        val format = mediaTrackGroup.getFormat(i)
                        val supported = group.isTrackSupported(i)
                        val line = "type=$typeStr, mimeType=${format.sampleMimeType}, codecs=${format.codecs}, channelCount=${format.channelCount}, ${format.width}x${format.height}, supported=$supported"
                        trackDebugLogs.add(line)
                        Log.e("PLAYERR", line)
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (isUsingRailwayProxy) {
                    Log.w("RAILWAY_PROXY", "Playback error on Railway proxy (${error.errorCodeName}). Falling back to direct URL.")
                    isUsingRailwayProxy = false
                    videoUrl = originalUrl
                    appendDebugLog("RAILWAY: error, fallback to direct")
                    restoreStreamingPlayback("Proxy failed, playing direct stream")
                    return
                }
                if (isPartialSeekActive) {
                    isPartialSeekActive = false
                    restoreStreamingPlayback("Seek failed in partial file")
                    return
                }
                val errorDetails = "ErrorCode: ${error.errorCodeName} (code=${error.errorCode})\nCause: ${error.cause?.javaClass?.name}: ${error.cause?.message}\nMessage: ${error.message}"
                Log.e("PLAYERR", errorDetails, error)

                val tracksInfo = if (trackDebugLogs.isNotEmpty()) {
                    "\n\n--- Tracks ---\n" + trackDebugLogs.joinToString("\n")
                } else {
                    "\n\n--- Tracks ---\n(No tracks)"
                }

                val stackLines = mutableListOf<String>()
                val causes = listOfNotNull(error.cause, error.cause?.cause)
                for (c in causes) {
                    for (el in c.stackTrace) {
                        val s = el.toString()
                        if (s.contains("com.videobrowser") || s.contains("androidx.media3")) {
                            stackLines.add(s)
                            if (stackLines.size >= 12) break
                        }
                    }
                    if (stackLines.size >= 12) break
                }
                val stackInfo = "\n\n--- Stack ---\n" + stackLines.joinToString("\n")

                val fullText = errorDetails + tracksInfo + stackInfo

                val scrollView = ScrollView(this@PlayerActivity).apply {
                    val tv = TextView(this@PlayerActivity).apply {
                        text = fullText
                        setTextIsSelectable(true)
                        setPadding(40, 24, 40, 24)
                        setTextColor(getColor(R.color.white))
                        textSize = 13f
                    }
                    addView(tv)
                }

                AlertDialog.Builder(this@PlayerActivity)
                    .setTitle("Playback Error: ${error.errorCodeName}")
                    .setView(scrollView)
                    .setPositiveButton("OK", null)
                    .show()

                val cause = error.cause
                val isHttp403 = (cause is HttpDataSource.InvalidResponseCodeException && cause.responseCode == 403)
                val isTimeout = (cause is HttpDataSource.HttpDataSourceException && (cause is java.net.SocketTimeoutException || cause.cause is java.net.SocketTimeoutException)) ||
                        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                val isUnsupported = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                        error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED

                val message = when {
                    isHttp403 -> "HTTP 403 Forbidden: Access denied by media server"
                    isTimeout -> "Network Timeout: Connection timed out"
                    isUnsupported -> "Unsupported Format: Device cannot decode this stream"
                    else -> "Playback error: ${error.message ?: "Unknown error"}"
                }
                Toast.makeText(this@PlayerActivity, message, Toast.LENGTH_LONG).show()

                if (!hasRetriedError) {
                    hasRetriedError = true
                    handler.postDelayed({
                        prepareStartTimeMs = System.currentTimeMillis()
                        player?.prepare()
                        player?.play()
                    }, 1500)
                } else {
                    playViaProxy(videoUrl)
                }
            }
        })

        newPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long
            ) {
                handler.post {
                    lastBandwidthEstimateMbps = bitrateEstimate / 1_000_000.0
                }
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                val msg = "onVideoDecoderInitialized: $decoderName"
                Log.e("PLAYERR", msg)
                appendDebugLog(msg)
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                val msg = "onAudioDecoderInitialized: $decoderName"
                Log.e("PLAYERR", msg)
                appendDebugLog(msg)
            }

            override fun onVideoCodecError(
                eventTime: AnalyticsListener.EventTime,
                videoCodecError: Exception
            ) {
                val msg = "onVideoCodecError: ${videoCodecError.message}"
                Log.e("PLAYERR", msg, videoCodecError)
                appendDebugLog(msg)
            }

            override fun onAudioCodecError(
                eventTime: AnalyticsListener.EventTime,
                audioCodecError: Exception
            ) {
                val msg = "onAudioCodecError: ${audioCodecError.message}"
                Log.e("PLAYERR", msg, audioCodecError)
                appendDebugLog(msg)
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: IOException,
                wasCanceled: Boolean
            ) {
                val msg = "onLoadError: ${loadEventInfo.uri}, ${error.message}"
                Log.e("PLAYERR", msg, error)
                appendDebugLog(msg)
            }

            override fun onPlayerError(
                eventTime: AnalyticsListener.EventTime,
                error: PlaybackException
            ) {
                val msg = "onPlayerError: code=${error.errorCode} (${error.errorCodeName}), cause=${error.cause?.javaClass?.name}: ${error.cause?.message}, msg=${error.message}"
                Log.e("PLAYERR", msg, error)
                appendDebugLog(msg)
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                val msg = "onDroppedVideoFrames: $droppedFrames in ${elapsedMs}ms"
                Log.e("PLAYERR", msg)
                appendDebugLog(msg)
            }

            override fun onTracksChanged(
                eventTime: AnalyticsListener.EventTime,
                tracks: Tracks
            ) {
                handler.post {
                    if (!hasLoggedFirstTrack) {
                        for (group in tracks.groups) {
                            val mediaTrackGroup = group.mediaTrackGroup
                            for (i in 0 until mediaTrackGroup.length) {
                                val format = mediaTrackGroup.getFormat(i)
                                val supported = group.isTrackSupported(i)
                                val line = "Track[0]: mime=${format.sampleMimeType}, supported=$supported"
                                Log.e("PLAYERR", line)
                                appendDebugLog(line)
                                hasLoggedFirstTrack = true
                                break
                            }
                            if (hasLoggedFirstTrack) break
                        }
                    }
                }
            }
        })

        handler.post(debugOverlayRunnable)

        // Build MediaSession and connect with PlaybackService
        val session = MediaSession.Builder(this, newPlayer).build()
        mediaSession = session

        val serviceIntent = Intent(this, PlaybackService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        updatePipParams()
    }

    private fun cleanupStalePartFiles() {
        try {
            val extDir = getExternalFilesDir(null) ?: return
            val files = extDir.listFiles() ?: return
            var deletedAny = false
            for (f in files) {
                if (f.isFile && f.name.startsWith("stream_") && f.name.endsWith(".part")) {
                    if (f.delete()) {
                        deletedAny = true
                    }
                }
            }
            if (deletedAny) {
                handler.post { appendDebugLog("CACHE: removed stale .part files") }
            }
        } catch (_: Exception) {}
    }

    private fun startCacheDownloadThread(
        url: String,
        ua: String?,
        cookies: String?,
        referer: String?,
        contentLength: Long,
        resumeKey: String
    ) {
        Thread {
            cleanupStalePartFiles()
            cacheContentLength = contentLength

            val extDir = getExternalFilesDir(null)
            val freeSpace = extDir?.usableSpace ?: 0L
            val requiredSpace = contentLength + (200L * 1024L * 1024L)
            if (freeSpace < requiredSpace) {
                val msg = "CACHE: not enough space"
                Log.e("PLAYERR", msg)
                cacheProgressStr = msg
                handler.post { appendDebugLog(msg) }
                return@Thread
            }

            val partFile = File(extDir, "stream_$resumeKey.part")
            val mkvFile = File(extDir, "stream_$resumeKey.mkv")
            currentDownloadPartFile = partFile

            var dlConn: HttpURLConnection? = null
            var fos: FileOutputStream? = null
            try {
                dlConn = URL(url).openConnection() as HttpURLConnection
                dlConn.connectTimeout = 15000
                dlConn.readTimeout = 30000
                dlConn.instanceFollowRedirects = true
                ua?.let { dlConn.setRequestProperty("User-Agent", it) }
                cookies?.let { dlConn.setRequestProperty("Cookie", it) }
                referer?.let { dlConn.setRequestProperty("Referer", it) }
                cacheConn = dlConn
                dlConn.connect()

                val dlCode = dlConn.responseCode
                handler.post { appendDebugLog("CACHE: download HTTP $dlCode") }

                if (dlCode != 200) {
                    val failMsg = "CACHE: failed HTTP $dlCode"
                    Log.e("PLAYERR", failMsg)
                    cacheProgressStr = failMsg
                    handler.post { appendDebugLog(failMsg) }
                    try { partFile.delete() } catch (_: Exception) {}
                    currentDownloadPartFile = null
                    return@Thread
                }

                fos = FileOutputStream(partFile)
                val buffer = ByteArray(256 * 1024)
                var totalBytesRead = 0L
                var lastProgressTime = System.currentTimeMillis()
                var lastProgressBytes = 0L

                val inputStream = dlConn.inputStream
                while (true) {
                    val r = inputStream.read(buffer)
                    if (r == -1) break
                    fos?.write(buffer, 0, r)
                    fos?.flush()
                    if (cacheCancelled) {
                        try { fos?.close() } catch (_: Exception) {}
                        fos = null
                        try { partFile.delete() } catch (_: Exception) {}
                        currentDownloadPartFile = null
                        return@Thread
                    }
                    totalBytesRead += r
                    cacheBytesWritten = totalBytesRead

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastProgressTime
                    if (elapsed >= 2000L) {
                        val timeElapsedSec = elapsed / 1000.0
                        val bytesInWindow = totalBytesRead - lastProgressBytes
                        val speedMBps = if (timeElapsedSec > 0) (bytesInWindow / (1024.0 * 1024.0)) / timeElapsedSec else 0.0

                        lastProgressTime = now
                        lastProgressBytes = totalBytesRead

                        val percent = if (contentLength > 0) ((totalBytesRead * 100) / contentLength).toInt() else 0
                        val sizeStr = if (totalBytesRead < 1024L * 1024L * 1024L) {
                            val readMb = totalBytesRead / (1024L * 1024L)
                            val totalMb = contentLength / (1024L * 1024L)
                            "${readMb}MB/${totalMb}MB"
                        } else {
                            val readGb = totalBytesRead.toDouble() / (1024.0 * 1024.0 * 1024.0)
                            val totalGb = contentLength.toDouble() / (1024.0 * 1024.0 * 1024.0)
                            String.format(Locale.US, "%.1fGB/%.1fGB", readGb, totalGb)
                        }

                        cacheProgressStr = String.format(Locale.US, "CACHE: %d%% %s | %.1f MB/s", percent, sizeStr, speedMBps)
                    }
                }
                fos?.flush()
                fos?.close()
                fos = null

                if (totalBytesRead == contentLength) {
                    if (mkvFile.exists()) {
                        mkvFile.delete()
                    }
                    if (partFile.renameTo(mkvFile)) {
                        currentDownloadPartFile = null
                        val totalGb = contentLength.toDouble() / (1024.0 * 1024.0 * 1024.0)
                        cacheProgressStr = String.format(Locale.US, "CACHE: 100%% %.1fGB/%.1fGB", totalGb, totalGb)
                        handler.post {
                            if (!cacheCancelled && !isDestroyed && !isFinishing) {
                                switchToLocalFile(mkvFile)
                            }
                        }
                    } else {
                        throw IOException("rename to .mkv failed")
                    }
                } else {
                    throw IOException("incomplete download ($totalBytesRead of $contentLength bytes)")
                }
            } catch (e: Exception) {
                if (cacheCancelled) {
                    try { fos?.close() } catch (_: Exception) {}
                    try { partFile.delete() } catch (_: Exception) {}
                    currentDownloadPartFile = null
                    return@Thread
                }
                try { fos?.close() } catch (_: Exception) {}
                try { partFile.delete() } catch (_: Exception) {}
                currentDownloadPartFile = null
                val errStr = "CACHE: failed ${e.message}"
                Log.e("PLAYERR", errStr, e)
                cacheProgressStr = errStr
                handler.post { appendDebugLog(errStr) }
            } finally {
                try { dlConn?.disconnect() } catch (_: Exception) {}
            }
        }.start()
    }

    private fun switchToLocalFile(file: File) {
        if (isDestroyed) return
        isPartialSeekActive = false
        val p = player ?: return
        val pos = p.currentPosition
        val playWhenReady = p.playWhenReady

        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        val src = ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(this),
            DefaultExtractorsFactory()
        ).createMediaSource(mediaItem)

        p.setMediaSource(src, pos)
        p.prepare()
        p.playWhenReady = playWhenReady
        isRangeSupported = true
        Toast.makeText(this, "Seeking unlocked", Toast.LENGTH_SHORT).show()
        appendDebugLog("CACHE: switched to local file")
    }

    fun playViaProxy(originalUrl: String) {
        // TODO: Proxy playback fallback (stub, no-op)
    }

    private fun getResumeKey(urlStr: String): String {
        val target = if (originalUrl.isNotBlank()) originalUrl else urlStr
        return try {
            val uri = Uri.parse(target)
            val hostAndPath = (uri.host ?: "") + (uri.path ?: "")
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(hostAndPath.toByteArray(Charsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            target.hashCode().toString()
        }
    }

    private fun showAudioTrackDialog() {
        val currentTracks = player?.currentTracks ?: return
        val audioTracks = mutableListOf<Pair<TrackGroup, Int>>()
        val trackNames = mutableListOf<String>()
        var selectedIndex = -1

        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                val mediaTrackGroup = group.mediaTrackGroup
                for (i in 0 until mediaTrackGroup.length) {
                    val format = mediaTrackGroup.getFormat(i)
                    audioTracks.add(Pair(mediaTrackGroup, i))

                    val lang = format.language?.uppercase() ?: "UND"
                    val label = if (!format.label.isNullOrBlank()) " (${format.label})" else ""
                    val mime = format.sampleMimeType?.substringAfterLast("/") ?: ""
                    val channels = if (format.channelCount > 0) " [${format.channelCount}ch]" else ""
                    val codec = if (!format.codecs.isNullOrBlank()) " (${format.codecs})" else ""

                    if (group.isTrackSelected(i)) {
                        selectedIndex = trackNames.size
                    }

                    trackNames.add("$lang$label $mime$codec$channels")
                }
            }
        }

        if (trackNames.isEmpty()) {
            Toast.makeText(this, "No alternative audio tracks found", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Select Audio Track")
            .setSingleChoiceItems(trackNames.toTypedArray(), selectedIndex) { dialog, which ->
                val (group, trackIndex) = audioTracks[which]
                player?.trackSelectionParameters = player?.trackSelectionParameters
                    ?.buildUpon()
                    ?.setOverrideForType(TrackSelectionOverride(group, trackIndex))
                    ?.build() ?: return@setSingleChoiceItems
                dialog.dismiss()
                Toast.makeText(this, "Audio: ${trackNames[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSubtitleTrackDialog() {
        val currentTracks = player?.currentTracks ?: return
        val subTracks = mutableListOf<Pair<TrackGroup, Int>?>()
        val trackNames = mutableListOf<String>()
        var selectedIndex = 0

        subTracks.add(null)
        trackNames.add("Off")

        val isTextDisabled = player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true

        for (group in currentTracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                val mediaTrackGroup = group.mediaTrackGroup
                for (i in 0 until mediaTrackGroup.length) {
                    val format = mediaTrackGroup.getFormat(i)
                    subTracks.add(Pair(mediaTrackGroup, i))

                    val lang = format.language?.uppercase() ?: "UND"
                    val label = if (!format.label.isNullOrBlank()) " (${format.label})" else ""
                    val mime = format.sampleMimeType?.substringAfterLast("/") ?: ""

                    if (group.isTrackSelected(i) && !isTextDisabled) {
                        selectedIndex = trackNames.size
                    }

                    trackNames.add("$lang$label ($mime)")
                }
            }
        }

        if (isTextDisabled) {
            selectedIndex = 0
        }

        AlertDialog.Builder(this)
            .setTitle("Select Subtitles")
            .setSingleChoiceItems(trackNames.toTypedArray(), selectedIndex) { dialog, which ->
                if (which == 0) {
                    player?.trackSelectionParameters = player?.trackSelectionParameters
                        ?.buildUpon()
                        ?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        ?.build() ?: return@setSingleChoiceItems
                    Toast.makeText(this, "Subtitles: Off", Toast.LENGTH_SHORT).show()
                } else {
                    val selected = subTracks[which] ?: return@setSingleChoiceItems
                    val (group, trackIndex) = selected
                    player?.trackSelectionParameters = player?.trackSelectionParameters
                        ?.buildUpon()
                        ?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        ?.setOverrideForType(TrackSelectionOverride(group, trackIndex))
                        ?.build() ?: return@setSingleChoiceItems
                    Toast.makeText(this, "Subtitles: ${trackNames[which]}", Toast.LENGTH_SHORT).show()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    playerView.showController()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isRangeSupported) {
                    val isDownloadActive = (currentDownloadPartFile != null && !cacheCancelled)
                    val p = player
                    val dur = p?.duration ?: C.TIME_UNSET
                    val width = playerView.width
                    val x = e.x
                    val currentPos = p?.currentPosition ?: 0L
                    val newPos = if (x < width / 2) {
                        max(0L, currentPos - 10000L)
                    } else {
                        if (dur > 0L) min(dur, currentPos + 10000L) else currentPos + 10000L
                    }

                    if (isDownloadActive) {
                        val downloadFraction = getCacheProgressFraction().toDouble()
                        val safeFraction = Math.max(0.0, Math.min(1.0, downloadFraction - 0.02))
                        val targetFraction = if (dur > 0L) (newPos.toDouble() / dur.toDouble()) else 1.0
                        if (targetFraction <= safeFraction) {
                            performPartialSeek(newPos)
                            if (x < width / 2) showSeekOverlay("-10s ⏪") else showSeekOverlay("+10s ⏩")
                        } else {
                            val percent = (downloadFraction * 100).toInt()
                            Toast.makeText(this@PlayerActivity, "Yahan tak download nahi hua ($percent%)", Toast.LENGTH_SHORT).show()
                            showSeekOverlay("Yahan tak download nahi hua ($percent%)")
                        }
                    } else {
                        showSeekOverlay("Seeking unsupported 🚫")
                        Toast.makeText(this@PlayerActivity, getSeekUnsupportedToastMessage(), Toast.LENGTH_SHORT).show()
                    }
                    return true
                }
                val width = playerView.width
                val x = e.x
                if (x < width / 2) {
                    player?.let {
                        val newPos = max(0L, it.currentPosition - 10000L)
                        it.seekTo(newPos)
                        showSeekOverlay("-10s ⏪")
                    }
                } else {
                    player?.let {
                        val dur = it.duration
                        val newPos = if (dur == C.TIME_UNSET) {
                            it.currentPosition + 10000L
                        } else {
                            min(dur, it.currentPosition + 10000L)
                        }
                        it.seekTo(newPos)
                        showSeekOverlay("+10s ⏩")
                    }
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                player?.let {
                    if (!isLongPressingSpeed) {
                        isLongPressingSpeed = true
                        originalSpeed = it.playbackParameters.speed
                        it.setPlaybackSpeed(2.0f)
                        txtSpeedBoost.visibility = View.VISIBLE
                    }
                }
            }
        })

        var startY = 0f
        var lastY = 0f
        var startX = 0f
        var isVerticalScroll = false

        playerView.setOnTouchListener { _, event ->
            val gestureHandled = gestureDetector.onTouchEvent(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.y
                    lastY = event.y
                    startX = event.x
                    isVerticalScroll = false
                    accumulatedVolumeDelta = 0f
                    currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

                    val lp = window.attributes
                    if (lp.screenBrightness >= 0f) {
                        currentBrightness = lp.screenBrightness
                    } else {
                        try {
                            currentBrightness = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                        } catch (_: Exception) {
                            currentBrightness = 0.5f
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDeltaY = startY - event.y
                    val totalDeltaX = startX - event.x

                    if (!isVerticalScroll && abs(totalDeltaY) > 30 && abs(totalDeltaY) > abs(totalDeltaX)) {
                        isVerticalScroll = true
                    }

                    if (isVerticalScroll) {
                        val deltaY = lastY - event.y
                        lastY = event.y
                        val percentDelta = deltaY / playerView.height
                        val screenWidth = playerView.width

                        if (startX < screenWidth / 2) {
                            // Left side: Brightness
                            currentBrightness = min(1.0f, max(0.01f, currentBrightness + percentDelta))
                            val lp = window.attributes
                            lp.screenBrightness = currentBrightness
                            window.attributes = lp
                            showGestureIndicator("☀️", "${(currentBrightness * 100).toInt()}%")
                        } else {
                            // Right side: Volume
                            val volStep = percentDelta * maxVolume
                            accumulatedVolumeDelta += volStep
                            if (abs(accumulatedVolumeDelta) >= 1.0f) {
                                val intDelta = accumulatedVolumeDelta.toInt()
                                accumulatedVolumeDelta -= intDelta
                                currentVolume = min(maxVolume, max(0, currentVolume + intDelta))
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                                val volPercent = ((currentVolume.toFloat() / maxVolume) * 100).toInt()
                                showGestureIndicator(if (currentVolume == 0) "🔇" else "🔊", "$volPercent%")
                            }
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isLongPressingSpeed) {
                        isLongPressingSpeed = false
                        player?.setPlaybackSpeed(originalSpeed)
                        txtSpeedBoost.visibility = View.GONE
                    }
                    if (isVerticalScroll) {
                        handler.postDelayed({ gestureIndicatorLayout.visibility = View.GONE }, 600)
                        isVerticalScroll = false
                    }
                }
            }

            if (isVerticalScroll) true else gestureHandled
        }
    }

    private fun showGestureIndicator(icon: String, text: String) {
        txtGestureIcon.text = icon
        txtGestureValue.text = text
        gestureIndicatorLayout.visibility = View.VISIBLE
    }

    private fun showSeekOverlay(text: String) {
        txtSeekOverlay.text = text
        txtSeekOverlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeekOverlayRunnable)
        handler.postDelayed(hideSeekOverlayRunnable, 800)
    }

    private val hideSeekOverlayRunnable = Runnable {
        txtSeekOverlay.visibility = View.GONE
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.25x", "0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x", "3.0x")
        val speedValues = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f, 3.0f)

        AlertDialog.Builder(this)
            .setTitle("Playback Speed")
            .setItems(speeds) { _, which ->
                val speed = speedValues[which]
                originalSpeed = speed
                player?.setPlaybackSpeed(speed)
                btnSpeed.text = speeds[which]
            }
            .show()
    }

    private fun getPipAspectRatio(): Rational {
        val vs = player?.videoSize
        if (vs != null && vs.width > 0 && vs.height > 0) {
            val ratio = vs.width.toFloat() / vs.height.toFloat()
            if (ratio in 0.42f..2.38f) {
                return Rational(vs.width, vs.height)
            }
        }
        return Rational(16, 9)
    }

    private fun getPipActions(): List<RemoteAction> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        val isPlaying = player?.isPlaying == true
        val icon = Icon.createWithResource(
            this,
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        val title = if (isPlaying) "Pause" else "Play"
        val actionIntent = Intent(ACTION_PIP_CONTROL).setPackage(packageName)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            0,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return listOf(RemoteAction(icon, title, title, pendingIntent))
    }

    private fun updatePipParams() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(getPipAspectRatio())
                .setActions(getPipActions())

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true)
            }
            try {
                setPictureInPictureParams(builder.build())
            } catch (_: Exception) {}
        }
    }

    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val builder = PictureInPictureParams.Builder()
                .setAspectRatio(getPipAspectRatio())
                .setActions(getPipActions())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                builder.setAutoEnterEnabled(true)
            }
            enterPictureInPictureMode(builder.build())
        } else {
            Toast.makeText(this, "Picture in Picture not supported", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPipMode()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            topBarLayout.visibility = View.GONE
            playerView.useController = false
            debugOverlayText?.visibility = View.GONE
        } else {
            playerView.useController = true
            debugOverlayText?.visibility = if (isDebugOverlayVisible) View.VISIBLE else View.GONE
        }
    }

    private fun savePlaybackPosition() {
        player?.let {
            if (it.currentPosition > 2000L && (it.duration == C.TIME_UNSET || it.currentPosition < it.duration - 2000L)) {
                getSharedPreferences("resume_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putLong(getResumeKey(videoUrl), it.currentPosition)
                    .apply()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        savePlaybackPosition()
    }

    override fun onStop() {
        super.onStop()
        savePlaybackPosition()
        if (!isBackgroundPlayEnabled && !isInPictureInPictureMode) {
            player?.pause()
        }
    }

    override fun onDestroy() {
        cacheCancelled = true
        val c = cacheConn
        Thread { try { c?.disconnect() } catch (_: Exception) {} }.start()

        handler.removeCallbacks(debugOverlayRunnable)
        savePlaybackPosition()
        try {
            val part = currentDownloadPartFile
            if (part != null && part.exists()) {
                part.delete()
            }
            if (videoUrl.isNotBlank()) {
                val key = getResumeKey(videoUrl)
                val pFile = File(getExternalFilesDir(null), "stream_$key.part")
                if (pFile.exists()) {
                    pFile.delete()
                }
            }
        } catch (_: Exception) {}
        try {
            unregisterReceiver(pipReceiver)
        } catch (_: Exception) {}

        if (isBoundToService) {
            playbackService?.unregisterSession()
            unbindService(serviceConnection)
            isBoundToService = false
        }

        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    private fun hideSystemUi() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }
}
