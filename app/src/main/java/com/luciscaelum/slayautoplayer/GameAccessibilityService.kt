package com.luciscaelum.slayautoplayer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import kotlin.math.max
import kotlin.math.min
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

                    val detection = detectPlayfield(field)
                    lastFrameWidth = field.width
                    lastFrameHeight = field.height
                    lastBirdX = detection.birdX
                    lastBirdY = detection.birdY
                    lastObstacleX = detection.obstacleX
                    lastGapTop = detection.gapTop
                    lastGapBottom = detection.gapBottom
                    field.recycle()
                    screenStatus = detection.message
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

    private data class Detection(val birdX: Int = -1, val birdY: Int = -1, val obstacleX: Int = -1, val gapTop: Int = -1, val gapBottom: Int = -1, val message: String)
    // Conservative diagnostic detector: do not call menu artwork a bird.
    // A scene is considered gameplay only when a paired pipe gap is visible.
    private fun detectPlayfield(bitmap: Bitmap): Detection {
        val w = bitmap.width
        val h = bitmap.height
        val step = 3
        val purpleColumns = IntArray(w)
        val blueColumns = IntArray(w)
        val blueRows = IntArray(h)

        for (y in 0 until h step step) {
            for (x in 0 until w step step) {
                val c = bitmap.getPixel(x, y)
                val r = (c shr 16) and 255
                val green = (c shr 8) and 255
                val b = c and 255

                // Rock/purple pipe body. Restrict to the right side of the playfield.
                if (x > w * 0.52f && r in 55..190 && b in 55..190 &&
                    green < 105 && r > green * 1.25 && b > green * 1.25) {
                    purpleColumns[x]++
                }

                // Bird marker: blue jacket, but only in the expected left-side bird zone.
                if (x in (w * 0.12f).toInt()..(w * 0.46f).toInt() &&
                    y in (h * 0.20f).toInt()..(h * 0.82f).toInt() &&
                    b > 85 && b > r * 1.12 && b > green * 1.03) {
                    blueColumns[x]++
                    blueRows[y]++
                }
            }
        }

        var obstacleX = -1
        var maxCount = 0
        for (x in (w * 0.52f).toInt() until (w * 0.99f).toInt() step step) {
            if (purpleColumns[x] > maxCount) {
                maxCount = purpleColumns[x]
                obstacleX = x
            }
        }

        val occupancy = IntArray(h)
        if (obstacleX >= 0 && maxCount >= 8) {
            for (y in 0 until h step step) {
                var count = 0
                val left = max(0, obstacleX - step * 2)
                val right = min(w, obstacleX + step * 3)
                for (x in left until right step step) {
                    val c = bitmap.getPixel(x, y)
                    val r = (c shr 16) and 255
                    val green = (c shr 8) and 255
                    val b = c and 255
                    if (r in 55..190 && b in 55..190 && green < 105 &&
                        r > green * 1.25 && b > green * 1.25) count++
                }
                occupancy[y] = count
            }
        }

        val runs = mutableListOf<Pair<Int, Int>>()
        var runStart = -1
        for (y in 0 until h step step) {
            if (occupancy[y] >= 1) {
                if (runStart < 0) runStart = y
            } else if (runStart >= 0) {
                if (y - runStart >= h * 0.045f) runs.add(runStart to y)
                runStart = -1
            }
        }
        if (runStart >= 0 && h - runStart >= h * 0.045f) runs.add(runStart to h)

        var gapTop = -1
        var gapBottom = -1
        var largestGap = 0
        for (i in 0 until runs.size - 1) {
            val gap = runs[i + 1].first - runs[i].second
            if (gap > largestGap) {
                largestGap = gap
                gapTop = runs[i].second
                gapBottom = runs[i + 1].first
            }
        }

        // Reject menu/character-selection screens: they may contain blue clothing,
        // but they do not contain two pipe bodies separated by a plausible opening.
        val validPipePair = runs.size >= 2 &&
            largestGap in (h * 0.10f).toInt()..(h * 0.58f).toInt() &&
            obstacleX > (w * 0.52f).toInt()
        if (!validPipePair) {
            return Detection(
                message = if (obstacleX >= 0) "Меню/заставка или трубы не подтверждены — жду игру"
                else "Жду игровую сцену: пара труб пока не найдена"
            )
        }

        val xs = blueColumns.indices.filter { blueColumns[it] >= 3 }
        val ys = blueRows.indices.filter { blueRows[it] >= 3 }
        if (xs.isEmpty() || ys.isEmpty()) {
            return Detection(obstacleX = obstacleX, gapTop = gapTop, gapBottom = gapBottom,
                message = "Трубы и просвет найдены; ищу птицу")
        }

        val birdX = (xs.first() + xs.last()) / 2
        val birdY = (ys.first() + ys.last()) / 2
        return Detection(
            birdX, birdY, obstacleX, gapTop, gapBottom,
            "Игровая сцена: птица-кандидат x=$birdX y=$birdY • просвет $gapTop–$gapBottom"
        )
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
        @Volatile var lastBirdX = -1
        @Volatile var lastBirdY = -1
        @Volatile var lastObstacleX = -1
        @Volatile var lastGapTop = -1
        @Volatile var lastGapBottom = -1
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
