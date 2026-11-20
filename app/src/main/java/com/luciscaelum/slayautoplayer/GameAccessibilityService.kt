package com.luciscaelum.slayautoplayer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class GameAccessibilityService : AccessibilityService() {
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanInFlight = AtomicBoolean(false)
    private val monitorTask = object : Runnable {
        override fun run() {
            if (monitoring && !paused && !manual) captureScreenInternal()
            if (monitoring) mainHandler.postDelayed(this, 350)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        screenStatus = "Служба подключена"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopMonitoring("Остановлено системой") }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && monitoring) {
            stopMonitoring("СТОП: кнопка громкости −")
            Toast.makeText(applicationContext, "Bird Play: мониторинг остановлен", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onKeyEvent(event)
    }

    private fun captureScreenInternal() {
        if (Build.VERSION.SDK_INT < 30) {
            screenStatus = "Снимок экрана требует Android 11+"
            return
        }
        if (!scanInFlight.compareAndSet(false, true)) return
        screenStatus = "Считываю игровое поле…"
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer: HardwareBuffer = result.hardwareBuffer
                    val full = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally { buffer.close() }

                    if (full == null) {
                        screenStatus = "Не удалось прочитать снимок"
                        scanInFlight.set(false)
                        return
                    }

                    // Telegram status bar and Mini App header occupy the top ~11.7%.
                    // Keep the complete playfield below them for visual game analysis.
                    val top = (full.height * 0.117f).toInt().coerceIn(1, full.height - 1)
                    val field = Bitmap.createBitmap(full, 0, top, full.width, full.height - top)
                    full.recycle()

                    // Temporary frame-capture stage: image analysis/controller comes next.
                    // Do not run OCR; game score and Mini App title are irrelevant.
                    lastFrameWidth = field.width
                    lastFrameHeight = field.height
                    field.recycle()
                    screenStatus = "Игровое поле захвачено • детектор ещё не включён"
                    scanInFlight.set(false)
                }

                override fun onFailure(errorCode: Int) {
                    screenStatus = when (errorCode) {
                        1 -> "Снимок отклонён системой"
                        2 -> "Снимок недоступен"
                        3 -> "Слишком частые запросы"
                        else -> "Ошибка снимка ($errorCode)"
                    }
                    scanInFlight.set(false)
                }
            })
        } catch (e: Exception) {
            screenStatus = "Ошибка снимка: " + e.javaClass.simpleName
            scanInFlight.set(false)
        }
    }

    private fun startMonitoringInternal() {
        if (instance == null) {
            screenStatus = "Включи службу специальных возможностей"
            return
        }
        manual = false
        paused = false
        monitoring = true
        screenStatus = "Мониторинг запущен • громкость − = СТОП"
        mainHandler.removeCallbacks(monitorTask)
        mainHandler.post(monitorTask)
    }

    private fun stopMonitoring(reason: String = "Мониторинг остановлен") {
        monitoring = false
        paused = true
        manual = true
        mainHandler.removeCallbacks(monitorTask)
        screenStatus = reason
    }

    fun tap(x: Float, y: Float, done: (() -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < 24 || paused || manual || !monitoring) return
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 45))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done?.invoke() }
        }, null)
    }

    override fun onDestroy() {
        stopMonitoring("Служба отключена")
        if (instance === this) instance = null
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        @Volatile var paused = true
            private set
        @Volatile var manual = true
            private set
        @Volatile var monitoring = false
            private set
        @Volatile var screenStatus = "Служба не подключена"
        @Volatile var lastFrameWidth = 0
        @Volatile var lastFrameHeight = 0
        @Volatile private var instance: GameAccessibilityService? = null

        fun togglePause() {
            val service = instance ?: return
            if (monitoring) service.stopMonitoring("Пауза")
            else service.startMonitoringInternal()
        }
        fun manualTakeover() {
            instance?.stopMonitoring("Ручное управление")
            manual = true
            paused = true
        }
        fun requestScreenScan() {
            val service = instance
            if (service == null) screenStatus = "Сначала включи службу специальных возможностей"
            else service.captureScreenInternal()
        }
    }
}
