package com.example.macropad

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.ByteArrayOutputStream

class ScreenCaptureService : Service() {

    companion object {
        @Volatile var latestJpeg: ByteArray? = null
        @Volatile var frameW = 0
        @Volatile var frameH = 0
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(2, buildNotification())
        val resultCode = intent.getIntExtra("code", -1)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>("data")
        if (resultCode != -1 && data != null) startCapture(resultCode, data)
        return START_STICKY
    }

    @Suppress("DEPRECATION")
    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = mpm.getMediaProjection(resultCode, data)
            val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(metrics)
            val screenW = metrics.widthPixels
            val screenH = metrics.heightPixels
            val scale = 480f / screenW
            val w = ((screenW * scale).toInt() / 2) * 2
            val h = ((screenH * scale).toInt() / 2) * 2
            val dpi = (metrics.densityDpi * scale).toInt().coerceAtLeast(1)

            imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                var image: Image? = null
                try {
                    image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * w
                    val bmp = Bitmap.createBitmap(w + rowPadding / pixelStride, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, w, h)
                    val out = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, 60, out)
                    latestJpeg = out.toByteArray()
                    frameW = screenW
                    frameH = screenH
                    if (cropped !== bmp) cropped.recycle()
                    bmp.recycle()
                } catch (e: Exception) {
                } finally {
                    image?.close()
                }
            }, Handler(Looper.getMainLooper()))

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "MacroPadView", w, h, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader!!.surface, null, null
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("ScreenShareChannel", "Screen Share", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, "ScreenShareChannel")
        } else {
            Notification.Builder(this)
        }
        return builder.setContentTitle("MacroPad Screen Share")
            .setContentText("Streaming screen...")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .build()
    }

    override fun onDestroy() {
        virtualDisplay?.release()
        mediaProjection?.stop()
        latestJpeg = null
        super.onDestroy()
    }
}
