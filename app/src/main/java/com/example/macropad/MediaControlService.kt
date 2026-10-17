package com.example.macropad

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService

class MediaControlService : NotificationListenerService() {

    companion object {
        var instance: MediaControlService? = null
            private set
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        instance = null
    }

    private fun firstSession(): MediaController? {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        return msm.getActiveSessions(ComponentName(this, MediaControlService::class.java)).firstOrNull()
    }

    fun playPause(): Boolean {
        val session = firstSession() ?: return false
        val tc = session.transportControls ?: return false
        if (session.playbackState?.state == PlaybackState.STATE_PLAYING) {
            tc.pause()
        } else {
            tc.play()
        }
        return true
    }

    fun next(): Boolean {
        val tc = firstSession()?.transportControls ?: return false
        tc.skipToNext()
        return true
    }

    fun prev(): Boolean {
        val tc = firstSession()?.transportControls ?: return false
        tc.skipToPrevious()
        return true
    }
}
