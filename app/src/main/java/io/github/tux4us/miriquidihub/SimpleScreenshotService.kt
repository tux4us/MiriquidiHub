package io.github.tux4us.miriquidihub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Toast

class SimpleScreenshotService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")
        val delaySeconds = (intent?.getIntExtra("delaySeconds", 0) ?: 0).coerceIn(0, 30)

        if (data != null) {
            startForegroundService()
            // Die Projektion wird erst nach der Wartezeit eingerichtet, damit das Bild
            // dem Zustand zum Aufnahmezeitpunkt entspricht und nicht einem älteren Frame.
            countdown(delaySeconds) {
                setupMediaProjection(resultCode, data)
                // Verzögerung um sicherzustellen, dass das System den Content gezeichnet hat
                handler.postDelayed({ takeScreenshot(0) }, 500)
            }
        } else {
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun countdown(remaining: Int, onDone: () -> Unit) {
        if (remaining <= 0) {
            onDone()
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(2001, buildNotification(getString(R.string.screenshot_countdown, remaining)))
        handler.postDelayed({ countdown(remaining - 1, onDone) }, 1000)
    }

    private val channelId = "simple_screenshot_service"

    private fun buildNotification(text: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle(getString(R.string.screenshot_toolbar_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOnlyAlertOnce(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.screenshot_toolbar_title))
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .getNotification()
        }
    }

    private fun startForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, getString(R.string.screenshot_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
        startForeground(2001, buildNotification(getString(R.string.screenshot_capturing)))
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        val mpManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpManager.getMediaProjection(resultCode, data)

        // Ab Android 14+ MUSS ein Callback registriert werden, bevor createVirtualDisplay aufgerufen wird.
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)

        val metrics = resources.displayMetrics
        imageReader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2
        )

        imageReader?.setOnImageAvailableListener({ _ ->
            // Listener notwendig, um Datenfluss zu triggern
        }, handler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "SimpleScreenshot",
            metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, null
        )
    }

    private fun takeScreenshot(attempt: Int) {
        val image = imageReader?.acquireLatestImage()
        if (image == null) {
            // Noch kein Frame: kurz warten und erneut versuchen
            if (attempt < 3) {
                handler.postDelayed({ takeScreenshot(attempt + 1) }, 200)
            } else {
                Toast.makeText(this, getString(R.string.error_frame_capture), Toast.LENGTH_SHORT).show()
                stopSelf()
            }
            return
        }
        try {
            val plane = image.planes[0]
            val pixelStride = plane.pixelStride
            val width = image.width
            val height = image.height
            val rowPadding = plane.rowStride - pixelStride * width

            val bitmap = Bitmap.createBitmap(width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888)
            bitmap.copyPixelsFromBuffer(plane.buffer)
            val clean = Bitmap.createBitmap(bitmap, 0, 0, width, height)
            val uri = ScreenshotStore.save(this, clean)
            bitmap.recycle()
            clean.recycle()

            if (uri != null) {
                Toast.makeText(this, getString(R.string.screenshot_saved), Toast.LENGTH_SHORT).show()
                ScreenshotStore.notifyEditable(this, uri)
            } else {
                Toast.makeText(this, getString(R.string.error_saving, "MediaStore"), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, getString(R.string.error_generic, e.message), Toast.LENGTH_SHORT).show()
        } finally {
            image.close()
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        super.onDestroy()
    }
}
