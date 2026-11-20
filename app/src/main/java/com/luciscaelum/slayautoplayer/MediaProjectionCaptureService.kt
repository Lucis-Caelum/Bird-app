package com.luciscaelum.slayautoplayer

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
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.content.pm.ServiceInfo
import kotlin.math.max

class MediaProjectionCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var workerHandler: Handler? = null
    private var lastFrameAt = 0L
    private var screenWidth = 0
    private var screenHeight = 0
    private var density = 0

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
        worker = HandlerThread("BirdPlayFrames").also { it.start() }
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
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            density = metrics.densityDpi
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            }, workerHandler)
            imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ reader ->
                val now = SystemClock.elapsedRealtime()
                val frameInterval = if (GameAccessibilityService.monitoring) FRAME_INTERVAL_MS else PREVIEW_INTERVAL_MS
                if (now - lastFrameAt < frameInterval) {
                    val skipped = reader.acquireLatestImage()
                    skipped?.close()
                    return@setOnImageAvailableListener
                }
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                lastFrameAt = now
                try {
                    val bitmap = imageToBitmap(image) ?: return@setOnImageAvailableListener
                    val top = (bitmap.height * HEADER_CROP_FRACTION).toInt().coerceIn(1, bitmap.height - 1)
                    val field = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, bitmap.height - top)
                    bitmap.recycle()
                    GameAccessibilityService.processCapturedFrame(field, top, screenWidth, screenHeight)
                    field.recycle()
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
            GameAccessibilityService.screenStatus = "MediaProjection готов • ожидаю запуск игры"
        } catch (e: Exception) {
            GameAccessibilityService.screenStatus = "Не удалось запустить захват: ${e.javaClass.simpleName}"
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val plane = image.planes.firstOrNull() ?: return null
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val paddedWidth = rowStride / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        val buffer = plane.buffer
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return padded
        val cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cropped
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Bird Play screen capture", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
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
        private const val FRAME_INTERVAL_MS = 160L
        private const val PREVIEW_INTERVAL_MS = 450L
        private const val HEADER_CROP_FRACTION = 0.117f
    }
}
