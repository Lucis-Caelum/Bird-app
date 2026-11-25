package com.luciscaelum.slayautoplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Process

/**
 * Screen capture. v0.50: NO throttling and NO Bitmap copies.
 *
 * Every frame the system produces is handed to [GameAccessibilityService.processFrame] together with
 * its real capture time (Image.timestamp, same clock as System.nanoTime()). The listener runs on one
 * dedicated thread; if analysis is slower than the screen refresh, acquireLatestImage() silently
 * drops the stale frames, so the controller always works on the newest picture.
 */
class MediaProjectionCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Bird Play")
            .setContentText("Захват игрового экрана активен")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        // Frame thread must not compete with UI threads for CPU time.
        worker = HandlerThread("BirdPlayFrames", Process.THREAD_PRIORITY_URGENT_DISPLAY).also { it.start() }
        workerHandler = Handler(worker!!.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (projection != null) return START_NOT_STICKY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                   else intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode == 0 || data == null) {
            GameAccessibilityService.screenStatus = "Нет разрешения MediaProjection • открой Bird Play"
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val metrics = resources.displayMetrics
            val screenWidth = metrics.widthPixels
            val screenHeight = metrics.heightPixels
            val density = metrics.densityDpi
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, workerHandler)
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3)
            imageReader?.setOnImageAvailableListener({ reader ->
                val image = try { reader.acquireLatestImage() } catch (e: Exception) { null }
                    ?: return@setOnImageAvailableListener
                try {
                    val arrivalMs = System.nanoTime() / 1e6
                    var captureMs = image.timestamp / 1e6
                    val age = arrivalMs - captureMs
                    // Image.timestamp should share System.nanoTime()'s clock. If it does not on some device,
                    // fall back to "arrival minus a typical pipeline delay" instead of trusting nonsense.
                    if (age < 0.0 || age > 400.0) captureMs = arrivalMs - FALLBACK_AGE_MS
                    GameAccessibilityService.processFrame(image, captureMs, screenWidth, screenHeight)
                } catch (e: Exception) {
                    GameAccessibilityService.screenStatus = "Ошибка видеокадра: ${e.javaClass.simpleName}"
                } finally {
                    image.close()
                }
            }, workerHandler)
            virtualDisplay = projection?.createVirtualDisplay(
                "BirdPlayCapture", screenWidth, screenHeight, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface, null, workerHandler
            )
            GameAccessibilityService.captureActive = true
            GameAccessibilityService.screenStatus = "MediaProjection готов • ожидаю запуск игры"
        } catch (e: Exception) {
            GameAccessibilityService.screenStatus = "Не удалось запустить захват: ${e.javaClass.simpleName}"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Bird Play screen capture", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        GameAccessibilityService.captureActive = false
        imageReader?.setOnImageAvailableListener(null, null)
        virtualDisplay?.release()
        imageReader?.close()
        projection?.stop()
        worker?.quitSafely()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RESULT_CODE = "birdplay.resultCode"
        const val EXTRA_RESULT_DATA = "birdplay.resultData"
        private const val CHANNEL_ID = "bird_play_capture"
        private const val NOTIFICATION_ID = 7321
        private const val FALLBACK_AGE_MS = 20.0
    }
}
