package com.example.macropad

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

class ReceiverService : Service() {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var cameraManager: CameraManager
    private lateinit var audioManager: AudioManager
    private var flashCameraId: String? = null

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        flashCameraId = findFlashCamera()
    }

    private fun findFlashCamera(): String? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) return id
        }
        return null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(1, createNotification())

        if (!isRunning) {
            isRunning = true
            startServer()
        }
        return START_STICKY
    }

    private fun startServer() {
        Thread {
            try {
                serverSocket = ServerSocket(8080)
                showToast("Server Started on Port 8080")

                while (isRunning) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                        val command = reader.readLine()
                        showToast("Command: $command")
                        handleCommand(command)
                        client.getOutputStream().write("OK\n".toByteArray())
                        client.close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            } catch (e: Exception) {
                showToast("Server Error: ${e.message}")
            }
        }.start()
    }

    private fun handleCommand(command: String?) {
        try {
            when (command) {
                "FLASH_ON" -> flashCameraId?.let { cameraManager.setTorchMode(it, true) }
                "FLASH_OFF" -> flashCameraId?.let { cameraManager.setTorchMode(it, false) }
                "BEEP" -> {
                    val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                    tone.startTone(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 800)
                    mainHandler.postDelayed({ tone.release() }, 1000)
                }
                "VOL_UP" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "VOL_DOWN" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            }
        } catch (e: Exception) {
            showToast("Action Error: ${e.message}")
        }
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("MacroPadChannel", "MacroPad Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "MacroPadChannel")
        } else {
            Notification.Builder(this)
        }
        return builder.setContentTitle("MacroPad Receiver")
            .setContentText("Listening for commands...")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        serverSocket?.close()
    }
}
