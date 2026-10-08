package com.videobrowser.app

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    inner class LocalBinder : Binder() {
        fun getService(): PlaybackService = this@PlaybackService
    }

    private val localBinder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == SERVICE_INTERFACE) {
            return super.onBind(intent)
        }
        return localBinder
    }

    fun registerSession(session: MediaSession) {
        mediaSession?.let { removeSession(it) }
        mediaSession = session
        addSession(session)
    }

    fun unregisterSession() {
        mediaSession?.let {
            removeSession(it)
            mediaSession = null
        }
        stopSelf()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.let {
            removeSession(it)
            mediaSession = null
        }
        super.onDestroy()
    }
}
