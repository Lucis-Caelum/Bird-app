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
import android.os.SystemClock
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
    private val gestureInFlight = AtomicBoolean(false)
    @Volatile private var nextScreenshotAllowedAt = 0L
    @Volatile private var screenshotIntervalMs = 650L
    private var previousBirdY = -1
    private var previousBirdAt = 0L
    private var lastAutoTapAt = 0L
    private var lastSceneSeenAt = 0L
    private var previousObstacleX = -1
    private var previousObstacleAt = 0L
    private var previousVerticalVelocity = 0f
    private var lastCaptureStartedAt = 0L
    private var wasActiveGame = false
    private val eventScanTask = Runnable {
        if (monitoring && !paused && !manual) captureScreenInternal()
    }
    private val monitorTask = object : Runnable {
        override fun run() {
            if (monitoring && !paused && !manual && screenStatus.startsWith("АВТОПИЛОТ")) {
                screenStatus = "ОЖИДАЮ ВИДЕОПОТОК MediaProjection"
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        screenStatus = "Служба подключена"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!monitoring || paused || manual || event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return
        // Screen frames now arrive from MediaProjectionCaptureService.
    }
    override fun onInterrupt() { screenStatus = "Служба временно прервана; ожидаю продолжения" }

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
        val now = SystemClock.elapsedRealtime()
        if (now < nextScreenshotAllowedAt || now - lastCaptureStartedAt < 650L) return
        if (!scanInFlight.compareAndSet(false, true)) return
        lastCaptureStartedAt = now
        nextScreenshotAllowedAt = now + screenshotIntervalMs
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
                    val screenW = full.width
                    val screenH = full.height
                    val field = Bitmap.createBitmap(full, 0, top, screenW, screenH - top)
                    full.recycle()

                    screenshotIntervalMs = 650L
                    val detection = detectPlayfield(field)
                    if (detection.activeGame && !wasActiveGame) {
                        previousBirdY = -1
                        previousBirdAt = 0L
                        lastAutoTapAt = 0L
                        screenStatus = "ИГРА НАЧАЛАСЬ • включаю управление"
                    } else if (!detection.activeGame && wasActiveGame) {
                        previousBirdY = -1
                        previousBirdAt = 0L
                        screenStatus = "КОНЕЦ ПОПЫТКИ • ожидаю повторный старт"
                    }
                    wasActiveGame = detection.activeGame
                    handleAutoControl(detection, field.width, field.height, top, screenW, screenH)
                    lastFrameWidth = field.width
                    lastFrameHeight = field.height
                    lastBirdX = detection.birdX
                    lastBirdY = detection.birdY
                    lastObstacleX = detection.obstacleX
                    lastGapTop = detection.gapTop
                    lastGapBottom = detection.gapBottom
                    field.recycle()
                    scanInFlight.set(false)
                }

                override fun onFailure(errorCode: Int) {
                    screenshotIntervalMs = when (errorCode) {
                        3 -> 1400L
                        2 -> 1100L
                        else -> 900L
                    }
                    nextScreenshotAllowedAt = SystemClock.elapsedRealtime() + screenshotIntervalMs
                    screenStatus = when (errorCode) {
                        1 -> "Снимок отклонён системой • повтор через паузу"
                        2 -> "Снимок недоступен • повтор через паузу"
                        3 -> "Лимит снимков Android • пауза 1,4 с"
                        else -> "Ошибка снимка ($errorCode)"
                    }
                    scanInFlight.set(false)
                }
            })
        } catch (e: Exception) {
            screenshotIntervalMs = 1100L
            nextScreenshotAllowedAt = SystemClock.elapsedRealtime() + screenshotIntervalMs
            screenStatus = "Ошибка снимка: " + e.javaClass.simpleName
            scanInFlight.set(false)
        }
    }

    fun processCapturedFrame(field: Bitmap, cropTop: Int, screenW: Int, screenH: Int) {
        val detection = detectPlayfield(field)
        if (detection.activeGame && !wasActiveGame) {
            previousBirdY = -1
            previousBirdAt = 0L
            lastAutoTapAt = 0L
            previousObstacleX = -1
            previousObstacleAt = 0L
            previousVerticalVelocity = 0f
            screenStatus = "ИГРА НАЧАЛАСЬ • MediaProjection"
        } else if (!detection.activeGame && wasActiveGame) {
            previousBirdY = -1
            previousBirdAt = 0L
            screenStatus = "КОНЕЦ ПОПЫТКИ • ожидаю повторный старт"
        }
        wasActiveGame = detection.activeGame
        if (monitoring && !paused && !manual) {
            handleAutoControl(detection, field.width, field.height, cropTop, screenW, screenH)
        } else {
            screenStatus = "ПРЕДПРОСМОТР • " + detection.message
        }
        lastFrameWidth = field.width
        lastFrameHeight = field.height
        lastBirdX = detection.birdX
        lastBirdY = detection.birdY
        lastObstacleX = detection.obstacleX
        lastGapTop = detection.gapTop
        lastGapBottom = detection.gapBottom
    }

    private data class Detection(val birdX:Int=-1,val birdY:Int=-1,val obstacleX:Int=-1,val gapTop:Int=-1,val gapBottom:Int=-1,val activeGame:Boolean=false,val message:String)
    private fun detectPlayfield(bitmap: Bitmap): Detection {
        val w = bitmap.width
        val h = bitmap.height
        val step = 3
        val sampleW = (w + step - 1) / step
        val sampleH = (h + step - 1) / step

        // The score badge exists only during active gameplay; the menu hero is not the bird.
        var scoreOrange = 0
        val scoreY0 = (h * 0.08f).toInt()
        val scoreY1 = (h * 0.24f).toInt()
        val scoreX1 = (w * 0.27f).toInt()
        for (y in scoreY0 until scoreY1 step 2) {
            for (x in 0 until scoreX1 step 2) {
                val c = bitmap.getPixel(x, y)
                val r = (c ushr 16) and 255
                val gg = (c ushr 8) and 255
                val bl = c and 255
                if (r > 165 && gg in 25..145 && bl < 85 && r > gg * 1.35) scoreOrange++
            }
        }
        val activeGame = scoreOrange > 90

        // Avatar is the connected blue/dark-cyan sprite in the left half of the playfield.
        var minBirdX = w
        var maxBirdX = 0
        var minBirdY = h
        var maxBirdY = 0
        var birdPixels = 0
        if (activeGame) {
            for (y in (h * 0.12f).toInt() until (h * 0.97f).toInt() step step) {
                for (x in (w * 0.07f).toInt() until (w * 0.46f).toInt() step step) {
                    val c = bitmap.getPixel(x, y)
                    val r = (c ushr 16) and 255
                    val gg = (c ushr 8) and 255
                    val bl = c and 255
                    if (bl > 55 && bl > r * 1.18 && bl > gg * 1.04 && r < 145 && gg < 170) {
                        minBirdX = min(minBirdX, x)
                        maxBirdX = max(maxBirdX, x)
                        minBirdY = min(minBirdY, y)
                        maxBirdY = max(maxBirdY, y)
                        birdPixels++
                    }
                }
            }
        }
        val birdFound = activeGame && birdPixels >= 24 &&
            maxBirdX - minBirdX >= 12 && maxBirdY - minBirdY >= 18
        val birdX = if (birdFound) (minBirdX + maxBirdX) / 2 else -1
        val birdY = if (birdFound) (minBirdY + maxBirdY) / 2 else -1
        if (!activeGame) return Detection(message = "Меню / отсчёт • ожидаю игровое поле")

        // Pipe body color sampled from the user's recording: muted purple (not orange decoration).
        // Work on sampled-grid indices throughout, so skipped pixels never create fake gaps.
        val columnCounts = IntArray(sampleW)
        for (sy in 0 until sampleH) {
            val y = sy * step
            for (sx in 0 until sampleW) {
                val x = sx * step
                if (x < w * 0.12f) continue
                val c = bitmap.getPixel(x, y)
                val r = (c ushr 16) and 255
                val gg = (c ushr 8) and 255
                val bl = c and 255
                if (r in 48..190 && bl in 48..195 && gg < 115 &&
                    r > gg * 1.18 && bl > gg * 1.18) columnCounts[sx]++
            }
        }
        val columnThreshold = max(10, (sampleH * 0.12f).toInt())
        val candidateColumns = BooleanArray(sampleW) { columnCounts[it] >= columnThreshold }
        // Bridge a single missed sampled column; this is a grid operation, not raw-pixel adjacency.
        for (i in 1 until sampleW - 1) {
            if (!candidateColumns[i] && candidateColumns[i - 1] && candidateColumns[i + 1]) {
                candidateColumns[i] = true
            }
        }
        val groups = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (i in 0 until sampleW) {
            if (candidateColumns[i]) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if ((i - start) * step >= w * 0.035f) groups.add(start * step to i * step)
                start = -1
            }
        }
        if (start >= 0 && (sampleW - start) * step >= w * 0.035f) {
            groups.add(start * step to w)
        }
        val nearest = groups.firstOrNull { group ->
            val centerX = (group.first + group.second) / 2
            !birdFound || centerX > birdX + w * 0.04f
        }
        if (nearest == null) {
            return Detection(
                birdX = birdX, birdY = birdY, activeGame = true,
                message = if (birdFound) "ИГРА • персонаж x=$birdX y=$birdY • ожидаю трубы"
                else "ИГРА • счёт распознан, ищу персонажа"
            )
        }

        val pipeX = (nearest.first + nearest.second) / 2
        val vertical = BooleanArray(sampleH)
        for (sy in 0 until sampleH) {
            val y = sy * step
            var count = 0
            for (x in max(0, pipeX - 9)..min(w - 1, pipeX + 9) step step) {
                val c = bitmap.getPixel(x, y)
                val r = (c ushr 16) and 255
                val gg = (c ushr 8) and 255
                val bl = c and 255
                if (r in 48..190 && bl in 48..195 && gg < 115 &&
                    r > gg * 1.18 && bl > gg * 1.18) count++
            }
            vertical[sy] = count >= 2
        }
        val runs = mutableListOf<Pair<Int, Int>>()
        var runStart = -1
        for (i in 0 until sampleH) {
            if (vertical[i]) {
                if (runStart < 0) runStart = i
            } else if (runStart >= 0) {
                if ((i - runStart) * step >= h * 0.045f) {
                    runs.add(runStart * step to i * step)
                }
                runStart = -1
            }
        }
        if (runStart >= 0 && (sampleH - runStart) * step >= h * 0.045f) {
            runs.add(runStart * step to h)
        }
        if (runs.size < 2) {
            return Detection(
                birdX = birdX, birdY = birdY, obstacleX = pipeX,
                activeGame = true,
                message = "ТРУБА x=$pipeX • ищу верхнюю/нижнюю часть"
            )
        }
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
        if (largestGap < h * 0.08f || largestGap > h * 0.62f) {
            return Detection(
                birdX = birdX, birdY = birdY, obstacleX = pipeX,
                activeGame = true,
                message = "ТРУБА x=$pipeX • просвет не подтверждён"
            )
        }
        // The orange crystal tips protrude into the visual gap; shrink it for collision safety.
        val safety = (h * 0.045f).toInt()
        gapTop = (gapTop + safety).coerceAtMost(h - 1)
        gapBottom = (gapBottom - safety).coerceAtLeast(gapTop + 1)
        return Detection(
            birdX, birdY, pipeX, gapTop, gapBottom, true,
            "ПТИЦА x=$birdX y=$birdY • ТРУБА x=$pipeX • ПРОХОД $gapTop..$gapBottom"
        )
    }

    private fun handleAutoControl(
        d: Detection, fieldWidth: Int, fieldHeight: Int, cropTop: Int, screenWidth: Int, screenHeight: Int
    ) {
        if (!monitoring || paused || manual) return
        val now = SystemClock.elapsedRealtime()
        if (!d.activeGame || d.birdX < 0 || d.birdY < 0) {
            previousBirdY = -1
            previousBirdAt = 0L
            previousObstacleX = -1
            previousObstacleAt = 0L
            previousVerticalVelocity = 0f
            screenStatus = d.message
            return
        }

        val dt = if (previousBirdAt > 0L) (now - previousBirdAt).coerceAtLeast(1L).toFloat() else 0f
        val rawVelocity = if (previousBirdY >= 0 && dt > 0f)
            (d.birdY - previousBirdY).toFloat() / dt else 0f
        // Smooth frame-to-frame noise: positive is falling, negative is rising.
        val velocity = if (previousBirdAt > 0L) previousVerticalVelocity * 0.30f + rawVelocity * 0.70f else 0f
        val rawGravity = if (dt > 0f) ((velocity - previousVerticalVelocity) / dt).coerceIn(-0.004f, 0.004f) else 0f
        val gravity = (rawGravity * 0.35f + 0.0012f * 0.65f).coerceIn(0.00025f, 0.0028f)
        previousBirdY = d.birdY
        previousBirdAt = now
        previousVerticalVelocity = velocity

        val sx = resources.displayMetrics.widthPixels.toFloat() / screenWidth.coerceAtLeast(1)
        val sy = resources.displayMetrics.heightPixels.toFloat() / screenHeight.coerceAtLeast(1)
        val tapX = (screenWidth * 0.78f * sx).coerceIn(1f, resources.displayMetrics.widthPixels - 1f)
        val tapY = ((cropTop + (screenHeight - cropTop) * 0.52f) * sy).coerceIn(1f, resources.displayMetrics.heightPixels - 1f)

        val hasGap = d.obstacleX > d.birdX && d.gapTop >= 0 && d.gapBottom > d.gapTop
        if (!hasGap) {
            // While a pipe is being acquired, use a cautious height guard rather than waiting
            // until the avatar is already at the bottom edge.
            val guardY = fieldHeight * 0.54f
            if (d.birdY > guardY && velocity > 0.015f && now - lastAutoTapAt >= 120L) {
                screenStatus = "ПОДДЕРЖКА • y=${d.birdY} • v=${"%.2f".format(velocity)}"
                if (tap(tapX, tapY)) lastAutoTapAt = now
            } else {
                screenStatus = if (d.obstacleX >= 0) "ТРУБА x=${d.obstacleX} • уточняю просвет"
                    else "ПТИЦА y=${d.birdY} • поиск следующей трубы"
            }
            previousObstacleX = -1
            previousObstacleAt = 0L
            return
        }

        val gapTop = d.gapTop.toFloat()
        val gapBottom = d.gapBottom.toFloat()
        val gapHeight = (gapBottom - gapTop).coerceAtLeast(1f)
        val targetY = gapTop + gapHeight * 0.50f
        val distance = (d.obstacleX - d.birdX).toFloat()

        val obstacleDt = if (previousObstacleAt > 0L) (now - previousObstacleAt).coerceAtLeast(1L).toFloat() else 0f
        val measuredSpeed = if (previousObstacleX > d.obstacleX && obstacleDt > 0f)
            (previousObstacleX - d.obstacleX).toFloat() / obstacleDt else 0f
        val obstacleSpeed = if (measuredSpeed > 0.04f) measuredSpeed else fieldWidth * 0.00135f
        previousObstacleX = d.obstacleX
        previousObstacleAt = now

        // Start correcting while the obstacle is still more than one screen-width away.
        val timeToPipe = (distance / obstacleSpeed).coerceIn(80f, 850f)
        val predictedY = d.birdY + velocity * timeToPipe + 0.5f * gravity * timeToPipe * timeToPipe
        val error = predictedY - targetY
        val distanceWindow = distance in 0f..(fieldWidth * 1.35f)
        // Use a wider neutral band to avoid chasing tiny detector fluctuations.
        val deadBand = gapHeight * 0.055f

        // One flap is a discrete impulse. Do not stack another flap while the previous
        // impulse is still lifting the avatar; re-evaluate only after its effect is visible.
        // In particular, a large position error must NOT bypass this recovery lock.
        val tapCooldown = 120L
        val stillRecoveringFromTap = lastAutoTapAt > 0L && now - lastAutoTapAt < tapCooldown
        // Re-check every incoming frame. Keep allowing a correction during ascent only
        // when the predicted crossing remains substantially below the safe target.
        val needsLift = error > deadBand && (velocity > -0.10f || error > gapHeight * 0.16f)
        if (distanceWindow && needsLift && !stillRecoveringFromTap) {
            screenStatus = "КОРРЕКЦИЯ • прогноз ${predictedY.toInt()} / цель ${targetY.toInt()} • ошибка ${error.toInt()} • v ${"%.2f".format(velocity)}"
            if (tap(tapX, tapY)) lastAutoTapAt = now
        } else {
            screenStatus = "ПТИЦА y=${d.birdY} • G ${d.gapTop}..${d.gapBottom} • цель ${targetY.toInt()} • прогноз ${predictedY.toInt()} • v ${"%.2f".format(velocity)}" + if (stillRecoveringFromTap) " • жду эффект касания" else ""
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
        wasActiveGame = false
        lastCaptureStartedAt = 0L
        nextScreenshotAllowedAt = 0L
        screenshotIntervalMs = 650L
        lastBirdX = -1
        lastBirdY = -1
        lastObstacleX = -1
        lastGapTop = -1
        lastGapBottom = -1
        lastTapStatus = "TAP —"
        previousBirdY = -1
        previousBirdAt = 0L
        lastAutoTapAt = 0L
        previousObstacleX = -1
        previousObstacleAt = 0L
        previousVerticalVelocity = 0f
        lastSceneSeenAt = 0L
        screenStatus = "АВТОПИЛОТ ВКЛЮЧЁН • жду игровую сцену • громкость − = СТОП"
        mainHandler.removeCallbacks(monitorTask)
        mainHandler.removeCallbacks(eventScanTask)
        screenStatus = "АВТОПИЛОТ ВКЛЮЧЁН • ожидаю MediaProjection • громкость − = СТОП"
    }

    private fun stopMonitoring(reason: String = "Мониторинг остановлен") {
        monitoring = false
        paused = true
        manual = true
        mainHandler.removeCallbacks(monitorTask)
        mainHandler.removeCallbacks(eventScanTask)
        screenStatus = reason
    }

    fun tap(x: Float, y: Float, done: (() -> Unit)? = null): Boolean {
        if (Build.VERSION.SDK_INT < 24 || paused || manual || !monitoring) return false
        if (!gestureInFlight.compareAndSet(false, true)) return false
        mainHandler.post {
            if (paused || manual || !monitoring) {
                gestureInFlight.set(false)
                return@post
            }
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 55))
                .build()
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    lastTapStatus = "TAP OK"
                    screenStatus = "ANDROID: КАСАНИЕ ВЫПОЛНЕНО"
                    gestureInFlight.set(false)
                    done?.invoke()
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    lastTapStatus = "TAP CANCEL"
                    screenStatus = "ANDROID: КАСАНИЕ ОТМЕНЕНО"
                    gestureInFlight.set(false)
                }
            }, null)
            if (!accepted) {
                lastTapStatus = "TAP REJECTED"
                screenStatus = "ANDROID: ЖЕСТ НЕ ПРИНЯТ"
                gestureInFlight.set(false)
            }
        }
        return true
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
        @Volatile var lastTapStatus = "TAP —"
        @Volatile private var instance: GameAccessibilityService? = null

        fun processCapturedFrame(field: Bitmap, cropTop: Int, screenW: Int, screenH: Int) {
            instance?.processCapturedFrame(field, cropTop, screenW, screenH)
        }

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
