package com.example.macropad

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
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

    private fun firstTransport(): MediaController.TransportControls? {
        val msm = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val sessions = msm.getActiveSessions(ComponentName(this, MediaControlService::class.java))
        return sessions.firstOrNull()?.transportControls
    }

    fun playPause(): Boolean = firstTransport()?.let { it.playPause(); true } ?: false
    fun next(): Boolean = firstTransport()?.let { it.skipToNext(); true } ?: false
    fun prev(): Boolean = firstTransport()?.let { it.skipToPrevious(); true } ?: false
}
