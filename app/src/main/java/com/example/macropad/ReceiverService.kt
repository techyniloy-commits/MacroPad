package com.example.macropad

import android.app.*
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.util.Locale

class ReceiverService : Service(), TextToSpeech.OnInitListener {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var cameraManager: CameraManager
    private lateinit var audioManager: AudioManager
    private var flashCameraId: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var screenWakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null

    override fun onCreate() {
        super.onCreate()
        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        flashCameraId = findFlashCamera()
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
        }
    }

    private fun findFlashCamera(): String? {
        for (id in cameraManager.cameraIdList) {
            val chars = cameraManager.getCameraCharacteristics(id)
            if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true) return id
        }
        return null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        startForeground(1, createNotification())
        acquireLocks()

        if (!isRunning) {
            isRunning = true
            startServer()
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restart = Intent(applicationContext, ReceiverService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            applicationContext.startForegroundService(restart)
        } else {
            applicationContext.startService(restart)
        }
        super.onTaskRemoved(rootIntent)
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MacroPad::ServerLock").apply { acquire() }
        }
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MacroPad::WifiLock").apply { acquire() }
        }
        if (screenWakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            screenWakeLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "MacroPad::ScreenWake"
            )
        }
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
                        val response = handleCommand(command)
                        client.getOutputStream().write((response + "\n").toByteArray())
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

    @Suppress("DEPRECATION")
    private fun handleCommand(command: String?): String {
        try {
            when {
                command == "FLASH_ON" -> flashCameraId?.let { cameraManager.setTorchMode(it, true) }
                command == "FLASH_OFF" -> flashCameraId?.let { cameraManager.setTorchMode(it, false) }
                command == "BEEP" -> {
                    val tone = ToneGenerator(AudioManager.STREAM_ALARM, 100)
                    tone.startTone(ToneGenerator.TONE_CDMA_ALERT_NETWORK_LITE, 800)
                    mainHandler.postDelayed({ tone.release() }, 1000)
                }
                command == "VOL_UP" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                command == "VOL_DOWN" -> audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                command == "PLAY_PAUSE" -> {
                    if (MediaControlService.instance?.playPause() != true) {
                        sendMediaButton(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
                    }
                }
                command == "NEXT_TRACK" -> {
                    if (MediaControlService.instance?.next() != true) {
                        sendMediaButton(KeyEvent.KEYCODE_MEDIA_NEXT)
                    }
                }
                command == "PREV_TRACK" -> {
                    if (MediaControlService.instance?.prev() != true) {
                        sendMediaButton(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                    }
                }
                command == "SCREEN_ON" -> {
                    mainHandler.post { screenWakeLock?.acquire(5000) }
                }
                command == "VIBRATE" -> {
                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        vibrator.vibrate(500)
                    }
                }
                command == "BATTERY" -> {
                    val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    return "BATTERY:$level%"
                }
                command?.startsWith("APP_") == true -> launchApp(command)
                command?.startsWith("TTS_") == true -> {
                    val text = command.substringAfter("TTS_")
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts")
                }
            }
        } catch (e: Exception) {
            showToast("Action Error: ${e.message}")
            return "ERROR:${e.message}"
        }
        return "OK"
    }

    private fun sendMediaButton(keyCode: Int) {
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun launchApp(command: String) {
        when (command) {
            "APP_CAMERA" -> {
                val intent = Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            }
            "APP_PHONE" -> {
                val intent = Intent(Intent.ACTION_DIAL)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                return
            }
        }
        val packageName = when (command) {
            "APP_YOUTUBE" -> "com.google.android.youtube"
            "APP_WHATSAPP" -> "com.whatsapp"
            "APP_CHROME" -> "com.android.chrome"
            "APP_MAPS" -> "com.google.android.apps.maps"
            else -> return
        }
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            showToast("App not found: $packageName")
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
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        tts?.shutdown()
    }
}
