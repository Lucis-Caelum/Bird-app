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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class GameAccessibilityService : AccessibilityService() {
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanInFlight = AtomicBoolean(false)
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
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
        screenStatus = "Сканирую область счёта…"
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

                    // Score is at the upper-left, below Android's status bar.
                    // Exclude the system clock and the rest of the game UI.
                    val left = 0
                    val top = (full.height * 0.075f).toInt().coerceAtMost(full.height - 1)
                    val right = (full.width * 0.42f).toInt().coerceAtLeast(1)
                    val bottom = (full.height * 0.235f).toInt().coerceAtMost(full.height)
                    val roi = Bitmap.createBitmap(full, left, top, right - left, bottom - top)
                    full.recycle()

                    textRecognizer.process(InputImage.fromBitmap(roi, 0))
                        .addOnSuccessListener { visionText ->
                            val recognized = visionText.text.replace("\\s+".toRegex(), " ").trim()
                            lastOcrText = recognized.take(100)
                            // Use only numeric tokens from the score ROI; never parse status-bar time.
                            val tokens = Regex("\\b\\d{1,4}\\b").findAll(recognized).map { it.value }.toList()
                            lastScoreCandidate = tokens.firstOrNull() ?: "—"
                            screenStatus = if (tokens.isEmpty()) "Мониторинг: счёт в области не найден" else "Мониторинг: область счёта прочитана"
                            roi.recycle()
                            scanInFlight.set(false)
                        }
                        .addOnFailureListener {
                            screenStatus = "Ошибка распознавания области счёта"
                            roi.recycle()
                            scanInFlight.set(false)
                        }
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
        textRecognizer.close()
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
        @Volatile var lastOcrText = ""
        @Volatile var lastScoreCandidate = "—"
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
