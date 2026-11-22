package com.luciscaelum.slayautoplayer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import kotlin.math.abs
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

class GameAccessibilityService : AccessibilityService() {
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanInFlight = AtomicBoolean(false)
    private val gestureInFlight = AtomicBoolean(false)
    // Reuse detector memory between frames; avoid allocating a full pixel buffer and scan arrays every frame.
    private var detectorPixels = IntArray(0)
    private var detectorColumnCounts = IntArray(0)
    private var detectorCandidateColumns = BooleanArray(0)
    private var detectorVertical = BooleanArray(0)
    @Volatile private var nextScreenshotAllowedAt = 0L
    @Volatile private var screenshotIntervalMs = 650L
    private var previousBirdY = -1
    private var previousBirdX = -1
    private var baselineBirdWidth = -1f
    private var baselineBirdHeight = -1f
    private var lastBirdScaleMode = "UNKNOWN"
    private var previousBirdAt = 0L
    private var lastAutoTapAt = 0L
    private var lastTapFeedbackAt = 0L
    private var lastSceneSeenAt = 0L
    private var previousObstacleX = -1
    private var previousObstacleAt = 0L
    private var smoothedObstacleSpeed = 0f
    private var previousVerticalVelocity = 0f
    private var previousRawVerticalVelocity = 0f
    private var estimatedFlapVelocity = -0.55f
    private var flapCalibrationLoaded = false
    private val flapImpulseSamples = ArrayDeque<Float>()
    private var estimatedGravity = 0.0018f
    // Planned impulse time is a predicted in-game response time, not a blind tap timer.
    private var pendingPipeTapAt = 0L
    private var pendingPipeTapTrackId = -1
    private data class PendingTapObservation(
        val id: Long,
        val source: String,
        val requestedAt: Long,
        val baselineVelocity: Float,
        val baselineY: Int,
        @Volatile var completedAt: Long = 0L
    )
    private data class TapFeedbackResult(
        val pending: Boolean = false,
        val resolved: Boolean = false,
        val confirmedVelocity: Float? = null
    )
    private val tapObservationLock = Any()
    private var pendingTapObservation: PendingTapObservation? = null
    private data class TapPlan(
        val tapTimesMs: FloatArray,
        val entryY: Float,
        val middleY: Float,
        val exitY: Float,
        val worstMiss: Float,
        val meanMiss: Float,
        val score: Float
    )
    private val runLogLock = Any()
    private val runLog = ArrayDeque<String>()
    private val pendingRunLogLines = ArrayList<String>(64)
    private var runStartedAt = 0L
    private var attemptCount = 0
    private var runLogStorageError: String? = null
    private data class QEntry(val values: FloatArray = FloatArray(2), val visits: IntArray = IntArray(2), var safeExploreTried: Boolean = false)
    private data class QTransition(val state: String, val action: Int, val reward: Float, val nextState: String?)
    private val qLearningTable = LinkedHashMap<String, QEntry>()
    private val qLearningEpisode = ArrayList<QTransition>(512)
    private var qLearningLoaded = false
    private var pendingRlState: String? = null
    private var pendingRlAction = RL_WAIT
    private var pendingRlAt = 0L
    private var lastTrackedPipeX = -1
    private var lastTrackedPipeAt = 0L
    private var trackedPipeNearBird = false
    private var qLearningPipePasses = 0
    private var qLearningRevision = 0L
    private var qLearningUpdatedAt = 0L
    private var qLearningChecksum = "EMPTY"
    private var qLearningTotalPipePasses = 0
    private var qLearningTotalEpisodes = 0
    private var qLearningMetaStatus = "NEW"
    private var earlyLaunchTapUsed = false
    private var hasSeenPipeThisAttempt = false
    private var lastTrackedPipeRightX = -1
    private var tapSequence = 0L
    private var lastTapRequestId = 0L
    private var lastGestureCompletedAt = 0L
    private var activePipeTrackId = 0
    private var lastPipeTrackLogAt = 0L
    private var lastPipeTrackGap = "NONE"
    private var attemptDiagnosticsActive = false
    private var attemptDiagnosticsStartedAt = 0L
    private var attemptMinBirdY = Int.MAX_VALUE
    private var attemptMaxBirdY = Int.MIN_VALUE
    private var attemptPipeFrames = 0
    private var attemptUniquePipeTracks = 0
    private var attemptPipePasses = 0
    private var attemptNoTrackFrames = 0
    private var attemptAutoTaps = 0
    private var attemptUnattributedImpulses = 0
    private var attemptRejectedTaps = 0
    private var attemptGestureCompleted = 0
    private var attemptGestureCancelled = 0
    private var attemptFlapDecisions = 0
    private var attemptWaitDecisions = 0
    private var attemptLastDecision = "NONE"
    private var attemptLastAction = "NONE"
    private val attemptUpdatedQStates = HashSet<String>()
    private val gravitySamples = ArrayDeque<Float>()
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
        ensureFlapCalibrationLoaded()
        ensureQLearningLoaded()
        screenStatus = "Служба подключена"
    }

    private fun ensureFlapCalibrationLoaded() {
        if (flapCalibrationLoaded) return
        flapCalibrationLoaded = true
        val stored = getSharedPreferences("bird_play_learning", MODE_PRIVATE)
            .getFloat("estimated_flap_velocity", Float.NaN)
        if (stored.isFinite() && stored in -3.0f..-0.08f) {
            estimatedFlapVelocity = stored
            appendRunLog("FLAP_CALIBRATION_LOAD status=RESTORED value=${fmt(estimatedFlapVelocity)}")
        } else {
            appendRunLog("FLAP_CALIBRATION_LOAD status=DEFAULT value=${fmt(estimatedFlapVelocity)}")
        }
    }

    private fun persistFlapCalibration(source: String) {
        if (!estimatedFlapVelocity.isFinite() || estimatedFlapVelocity !in -3.0f..-0.08f) return
        getSharedPreferences("bird_play_learning", MODE_PRIVATE).edit()
            .putFloat("estimated_flap_velocity", estimatedFlapVelocity)
            .apply()
        appendRunLog(
            "FLAP_CALIBRATION_SAVE source=$source value=${fmt(estimatedFlapVelocity)} status=QUEUED"
        )
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

                    // Analyse the playfield directly from the source bitmap; avoid a full
                    // cropped-bitmap allocation and copy on every legacy screenshot frame.
                    val top = (full.height * 0.117f).toInt().coerceIn(1, full.height - 1)
                    val screenW = full.width
                    val screenH = full.height
                    val fieldHeight = screenH - top
                    try {
                        screenshotIntervalMs = 650L
                        val detection = detectPlayfield(full, top)
                        if (detection.activeGame && !wasActiveGame) {
                            recordAttemptStart(detection)
                            previousBirdY = -1
                            previousBirdX = -1
                            baselineBirdWidth = -1f
                            baselineBirdHeight = -1f
                            lastBirdScaleMode = "UNKNOWN"
                            previousBirdAt = 0L
                            lastAutoTapAt = 0L
                            lastTapFeedbackAt = 0L
                            previousVerticalVelocity = 0f
                            previousRawVerticalVelocity = 0f
                            seedBirdSizeBaseline(detection, "NEW_GAME_CAPTURE")
                            screenStatus = "ИГРА НАЧАЛАСЬ • включаю управление"
                        } else if (!detection.activeGame && wasActiveGame) {
                            recordAttemptEnd("GAME_OVER_OR_SCENE_LOST")
                            previousBirdY = -1
                            previousBirdX = -1
                            baselineBirdWidth = -1f
                            baselineBirdHeight = -1f
                            lastBirdScaleMode = "UNKNOWN"
                            previousBirdAt = 0L
                            screenStatus = "КОНЕЦ ПОПЫТКИ • ожидаю повторный старт"
                        }
                        wasActiveGame = detection.activeGame
                        handleAutoControl(detection, screenW, fieldHeight, top, screenW, screenH)
                        lastFrameWidth = screenW
                        lastFrameHeight = fieldHeight
                        lastBirdX = detection.birdX
                        lastBirdY = detection.birdY
                        lastObstacleX = detection.obstacleX
                        lastGapTop = detection.gapTop
                        lastGapBottom = detection.gapBottom
                    } catch (e: Exception) {
                        screenStatus = "Ошибка анализа кадра: " + e.javaClass.simpleName
                    } finally {
                        if (!full.isRecycled) full.recycle()
                        scanInFlight.set(false)
                    }
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

    fun processCapturedFrame(frame: Bitmap, cropTop: Int, screenW: Int, screenH: Int) {
        val safeCropTop = cropTop.coerceIn(0, frame.height - 1)
        val fieldHeight = (frame.height - safeCropTop).coerceAtLeast(1)
        val detectionStartedAt = SystemClock.elapsedRealtime()
        val detection = detectPlayfield(frame, safeCropTop)
        lastDetectionMs = SystemClock.elapsedRealtime() - detectionStartedAt
        if (detection.activeGame && !wasActiveGame) {
            ensureQLearningLoaded()
            resetQLearningAttempt()
            gravitySamples.clear()
            previousBirdY = -1
            previousBirdX = -1
            baselineBirdWidth = -1f
            baselineBirdHeight = -1f
            lastBirdScaleMode = "UNKNOWN"
            previousBirdAt = 0L
            lastAutoTapAt = 0L
            lastTapFeedbackAt = 0L
            previousObstacleX = -1
            previousObstacleAt = 0L
            smoothedObstacleSpeed = 0f
            previousVerticalVelocity = 0f
            previousRawVerticalVelocity = 0f
            ensureFlapCalibrationLoaded()
            estimatedGravity = 0.0018f
            synchronized(tapObservationLock) { pendingTapObservation = null }
            autoTapCount = 0
            recordAttemptStart(detection)
            resetAttemptDiagnostics()
            seedBirdSizeBaseline(detection, "NEW_GAME_FRAME")
            screenStatus = "ИГРА НАЧАЛАСЬ • MediaProjection"
        } else if (!detection.activeGame && wasActiveGame) {
            appendRunLog("=== КОНЕЦ ПОПЫТКИ #$attemptCount ===")
            finishQLearningAttempt(true)
            finishAttemptDiagnostics("GAME_OVER_OR_SCENE_LOST")
            recordAttemptEnd("GAME_OVER_OR_SCENE_LOST")
            previousBirdY = -1
            previousBirdX = -1
            baselineBirdWidth = -1f
            baselineBirdHeight = -1f
            lastBirdScaleMode = "UNKNOWN"
            previousBirdAt = 0L
            screenStatus = "КОНЕЦ ПОПЫТКИ • ожидаю повторный старт"
        }
        wasActiveGame = detection.activeGame
        if (monitoring && !paused && !manual) {
            handleAutoControl(detection, frame.width, fieldHeight, safeCropTop, screenW, screenH)
        } else {
            screenStatus = "ПРЕДПРОСМОТР • " + detection.message
        }
        lastFrameWidth = frame.width
        lastFrameHeight = fieldHeight
        lastBirdX = detection.birdX
        lastBirdY = detection.birdY
        lastObstacleX = detection.obstacleX
        lastGapTop = detection.gapTop
        lastGapBottom = detection.gapBottom
        screenStatus = "$screenStatus • CV ${lastDetectionMs}мс"
    }

    private data class Detection(
        val birdX: Int = -1, val birdY: Int = -1,
        val obstacleX: Int = -1, val gapTop: Int = -1, val gapBottom: Int = -1,
        val activeGame: Boolean = false, val message: String,
        val birdBounds: String = "NONE", val pipeBounds: String = "NONE",
        val birdLeft: Int = -1, val birdRight: Int = -1,
        val birdTop: Int = -1, val birdBottom: Int = -1,
        val pipeLeft: Int = -1, val pipeRight: Int = -1,
        val pipeRuns: String = "NONE", val pipeConfidence: Float = 0f,
        val birdScale: String = "UNKNOWN"
    )

    private fun seedBirdSizeBaseline(d: Detection, source: String) {
        if (d.birdBounds == "NONE") {
            appendRunLog("BIRD_SCALE_BASELINE source=$source status=WAITING_FOR_BOUNDS")
            return
        }
        val parts = d.birdBounds.split(",")
        val x = parts.getOrNull(0)?.split("..")
        val y = parts.getOrNull(1)?.split("..")
        val width = if (x?.size == 2) (x[1].toIntOrNull() ?: 0) - (x[0].toIntOrNull() ?: 0) else 0
        val height = if (y?.size == 2) (y[1].toIntOrNull() ?: 0) - (y[0].toIntOrNull() ?: 0) else 0
        if (width > 0 && height > 0) {
            baselineBirdWidth = width.toFloat()
            baselineBirdHeight = height.toFloat()
            lastBirdScaleMode = "NORMAL"
            appendRunLog("BIRD_SCALE_BASELINE source=$source size=${width}x$height status=SEEDED")
        } else {
            appendRunLog("BIRD_SCALE_BASELINE source=$source status=INVALID_BOUNDS value=${d.birdBounds}")
        }
    }
    private fun detectPlayfield(bitmap: Bitmap, cropTop: Int = 0): Detection {
        val w = bitmap.width
        val sourceTop = cropTop.coerceIn(0, bitmap.height - 1)
        val h = bitmap.height - sourceTop
        if (h <= 0) return Detection(message = "Пустая область игрового поля")
        val pixelCount = w * h
        if (detectorPixels.size < pixelCount) detectorPixels = IntArray(pixelCount)
        bitmap.getPixels(detectorPixels, 0, w, 0, sourceTop, w, h)
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
                val c = detectorPixels[y * w + x]
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
                    val c = detectorPixels[y * w + x]
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
        val detectedBirdWidth = (maxBirdX - minBirdX).coerceAtLeast(0)
        val detectedBirdHeight = (maxBirdY - minBirdY).coerceAtLeast(0)
        val rawBirdX = if (birdPixels > 0) (minBirdX + maxBirdX) / 2 else -1
        val rawBirdY = if (birdPixels > 0) (minBirdY + maxBirdY) / 2 else -1
        val normalBirdFound = activeGame && birdPixels >= 24 &&
            detectedBirdWidth >= 12 && detectedBirdHeight >= 18
        // The potion shrinks the avatar by roughly 2–3x. Its sampled blue pixels can fall
        // below the old 24-pixel threshold, so accept a smaller sprite only when its centre
        // remains close to the previous frame's avatar location.
        val closeToPreviousBird = previousBirdX >= 0 && previousBirdY >= 0 &&
            rawBirdX >= 0 && rawBirdY >= 0 &&
            kotlin.math.abs(rawBirdX - previousBirdX) <= max(28, (w * 0.06f).toInt()) &&
            kotlin.math.abs(rawBirdY - previousBirdY) <= max(320, (h * 0.18f).toInt())
        val reducedBirdFound = activeGame && closeToPreviousBird &&
            birdPixels >= 3 && detectedBirdWidth >= 3 && detectedBirdHeight >= 5
        val birdFound = normalBirdFound || reducedBirdFound
        val birdX = if (birdFound) rawBirdX else -1
        val birdY = if (birdFound) rawBirdY else -1
        val birdLeft = if (birdFound) minBirdX else -1
        val birdRight = if (birdFound) maxBirdX else -1
        val birdTop = if (birdFound) minBirdY else -1
        val birdBottom = if (birdFound) maxBirdY else -1
        val baselineKnown = baselineBirdWidth > 0f && baselineBirdHeight > 0f
        val visuallyShrunk = baselineKnown &&
            detectedBirdWidth < baselineBirdWidth * 0.72f &&
            detectedBirdHeight < baselineBirdHeight * 0.72f
        val birdScale = when {
            birdFound && (reducedBirdFound && !normalBirdFound || visuallyShrunk) -> "SHRUNK"
            birdFound -> "NORMAL"
            else -> "UNKNOWN"
        }
        if (birdFound && birdScale == "NORMAL") {
            baselineBirdWidth = if (baselineBirdWidth > 0f)
                baselineBirdWidth * 0.85f + detectedBirdWidth * 0.15f else detectedBirdWidth.toFloat()
            baselineBirdHeight = if (baselineBirdHeight > 0f)
                baselineBirdHeight * 0.85f + detectedBirdHeight * 0.15f else detectedBirdHeight.toFloat()
        }
        if (!activeGame) return Detection(message = "Меню / отсчёт • ожидаю игровое поле")

        // Pipe body color sampled from the user's recording: muted purple (not orange decoration).
        // Work on sampled-grid indices throughout, so skipped pixels never create fake gaps.
        if (detectorColumnCounts.size < sampleW) detectorColumnCounts = IntArray(sampleW)
        val columnCounts = detectorColumnCounts
        columnCounts.fill(0, 0, sampleW)
        for (sy in 0 until sampleH) {
            val y = sy * step
            for (sx in 0 until sampleW) {
                val x = sx * step
                if (x < w * 0.12f) continue
                val c = detectorPixels[y * w + x]
                val r = (c ushr 16) and 255
                val gg = (c ushr 8) and 255
                val bl = c and 255
                if (r in 48..190 && bl in 48..195 && gg < 115 &&
                    r > gg * 1.18 && bl > gg * 1.18) columnCounts[sx]++
            }
        }
        val columnThreshold = max(10, (sampleH * 0.12f).toInt())
        if (detectorCandidateColumns.size < sampleW) detectorCandidateColumns = BooleanArray(sampleW)
        val candidateColumns = detectorCandidateColumns
        for (i in 0 until sampleW) candidateColumns[i] = columnCounts[i] >= columnThreshold
        // Bridge a single missed sampled column; this is a grid operation, not raw-pixel adjacency.
        for (i in 1 until sampleW - 1) {
            if (!candidateColumns[i] && candidateColumns[i - 1] && candidateColumns[i + 1]) {
                candidateColumns[i] = true
            }
        }
        // Real pipe bodies span roughly 10–20% of the field width. Small floating pickups
        // and decorative crystals are narrower and must never become obstacle candidates.
        val minPipeWidth = (w * 0.11f).toInt().coerceAtLeast(1)
        val groups = mutableListOf<Pair<Int, Int>>()
        var start = -1
        for (i in 0 until sampleW) {
            if (candidateColumns[i]) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if ((i - start) * step >= minPipeWidth) groups.add(start * step to i * step)
                start = -1
            }
        }
        if (start >= 0 && (sampleW - start) * step >= minPipeWidth) {
            groups.add(start * step to w)
        }
        // Keep tracking the current pipe until its trailing edge clears the bird.
        // This prevents a tracking gap at the exact crossing point.
        val nearest = groups.firstOrNull { group ->
            !birdFound || group.second > birdX - w * 0.035f
        }
        if (nearest == null) {
            return Detection(
                birdX = birdX, birdY = birdY, activeGame = true,
                message = if (birdFound) "ИГРА • персонаж x=$birdX y=$birdY • ожидаю трубы"
                else "ИГРА • счёт распознан, ищу персонажа",
                birdBounds = if (birdFound) "$minBirdX..$maxBirdX,$minBirdY..$maxBirdY" else "NONE",
                birdLeft = birdLeft, birdRight = birdRight, birdTop = birdTop, birdBottom = birdBottom,
                birdScale = birdScale
            )
        }

        val pipeCenterX = (nearest.first + nearest.second) / 2
        val pipeLeadingEdgeX = nearest.first
        if (detectorVertical.size < sampleH) detectorVertical = BooleanArray(sampleH)
        val vertical = detectorVertical
        vertical.fill(false, 0, sampleH)
        for (sy in 0 until sampleH) {
            val y = sy * step
            var count = 0
            for (x in max(0, pipeCenterX - 9)..min(w - 1, pipeCenterX + 9) step step) {
                val c = detectorPixels[y * w + x]
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
                birdX = birdX, birdY = birdY, obstacleX = pipeLeadingEdgeX,
                activeGame = true,
                message = "ТРУБА x=$pipeLeadingEdgeX • ищу верхнюю/нижнюю часть",
                birdBounds = if (birdFound) "$minBirdX..$maxBirdX,$minBirdY..$maxBirdY" else "NONE",
                pipeBounds = "${nearest.first}..${nearest.second}",
                birdLeft = birdLeft, birdRight = birdRight, birdTop = birdTop, birdBottom = birdBottom,
                pipeLeft = nearest.first, pipeRight = nearest.second,
                pipeRuns = runs.joinToString(";") { "${it.first}..${it.second}" },
                pipeConfidence = (0.22f + runs.size * 0.09f).coerceAtMost(0.52f),
                birdScale = birdScale
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
                birdX = birdX, birdY = birdY, obstacleX = pipeLeadingEdgeX,
                activeGame = true,
                message = "ТРУБА x=$pipeLeadingEdgeX • просвет не подтверждён",
                birdBounds = if (birdFound) "$minBirdX..$maxBirdX,$minBirdY..$maxBirdY" else "NONE",
                pipeBounds = "${nearest.first}..${nearest.second}",
                birdLeft = birdLeft, birdRight = birdRight, birdTop = birdTop, birdBottom = birdBottom,
                pipeLeft = nearest.first, pipeRight = nearest.second,
                pipeRuns = runs.joinToString(";") { "${it.first}..${it.second}" },
                pipeConfidence = 0.42f,
                birdScale = birdScale
            )
        }
        // The orange crystal tips protrude into the visual gap; shrink it for collision safety.
        val safety = (h * 0.045f).toInt()
        gapTop = (gapTop + safety).coerceAtMost(h - 1)
        gapBottom = (gapBottom - safety).coerceAtLeast(gapTop + 1)
        val runShapeScore = if (runs.size == 2) 0.88f else (0.78f - (runs.size - 2) * 0.11f).coerceAtLeast(0.34f)
        val gapShapeScore = (1f - kotlin.math.abs(largestGap.toFloat() / h - 0.32f) * 0.55f).coerceIn(0.45f, 1f)
        val pipeConfidence = (runShapeScore * gapShapeScore).coerceIn(0.25f, 0.92f)
        return Detection(
            birdX = birdX,
            birdY = birdY,
            obstacleX = pipeLeadingEdgeX,
            gapTop = gapTop,
            gapBottom = gapBottom,
            activeGame = true,
            message = "ПТИЦА x=$birdX y=$birdY • ТРУБА x=$pipeLeadingEdgeX • ПРОХОД $gapTop..$gapBottom",
            birdBounds = "$minBirdX..$maxBirdX,$minBirdY..$maxBirdY",
            pipeBounds = "${nearest.first}..${nearest.second}",
            birdLeft = birdLeft, birdRight = birdRight, birdTop = birdTop, birdBottom = birdBottom,
            pipeLeft = nearest.first, pipeRight = nearest.second,
            pipeRuns = runs.joinToString(";") { "${it.first}..${it.second}" },
            pipeConfidence = pipeConfidence,
            birdScale = birdScale
        )
    }

    private fun tableChecksum(serialized: String): String =
        Integer.toHexString(serialized.hashCode())

    private fun ensureQLearningLoaded() {
        if (qLearningLoaded) return
        qLearningLoaded = true
        val prefs = getSharedPreferences("bird_play_learning", MODE_PRIVATE)
        val saved = prefs.getString("q_table", null)
        val savedMeta = prefs.getString("q_table_meta", null)
        if (saved.isNullOrBlank()) {
            qLearningMetaStatus = if (savedMeta.isNullOrBlank()) "NEW" else "TABLE_MISSING"
            val meta = try { JSONObject(savedMeta ?: "{}") } catch (_: Exception) { JSONObject() }
            qLearningRevision = meta.optLong("revision", 0L)
            qLearningUpdatedAt = meta.optLong("updated_at", 0L)
            qLearningTotalPipePasses = meta.optInt("total_pipe_passes", 0)
            qLearningTotalEpisodes = meta.optInt("total_episodes", 0)
            learnedStateCount = 0
            learnedRevision = qLearningRevision
            learnedPipeCount = qLearningTotalPipePasses
            learnedUpdatedAt = qLearningUpdatedAt
            learnedChecksum = "EMPTY"
            appendRunLog("RL_TABLE_LOAD status=" + qLearningMetaStatus +
                " revision=" + qLearningRevision + " states=0 previousChecksum=" +
                meta.optString("checksum", "NONE") + " backup_or_data_restore_required=" + (qLearningMetaStatus == "TABLE_MISSING"))
            return
        }
        try {
            val meta = try { JSONObject(savedMeta ?: "{}") } catch (_: Exception) { JSONObject() }
            val actualChecksum = tableChecksum(saved)
            val expectedChecksum = meta.optString("checksum", "")
            val checksumMatches = expectedChecksum.isBlank() || expectedChecksum == actualChecksum
            val rewardSchema = meta.optInt("reward_schema", 0)
            val json = JSONObject(saved)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val row = json.optJSONArray(key) ?: continue
                if (row.length() < 4) continue
                val scale = if (rewardSchema < 2) 0.02f else 1f
                val values = floatArrayOf(
                    row.optDouble(0, 0.0).toFloat() * scale,
                    row.optDouble(1, 0.0).toFloat() * scale
                )
                val visits = intArrayOf(row.optInt(2, 0), row.optInt(3, 0))
                val migratedKey = if (rewardSchema < 2 && !key.startsWith("v2:")) {
                    val bins = key.split(":")
                    if (bins.size == 3) {
                        val oldVelocity = bins[0].toIntOrNull()
                        val oldTime = bins[1].toIntOrNull()
                        val oldVertical = bins[2].toIntOrNull()
                        if (oldVelocity != null && oldTime != null && oldVertical != null) {
                            "v2:" +
                                (oldVelocity * (0.30f / 0.45f)).roundToInt().coerceIn(-7, 8) + ":" +
                                (oldTime * (120f / 180f)).roundToInt().coerceIn(0, 9) + ":" +
                                (oldVertical * (0.15f / 0.25f)).roundToInt().coerceIn(-6, 6)
                        } else key
                    } else key
                } else key
                val existing = qLearningTable[migratedKey]
                if (existing == null) {
                    qLearningTable[migratedKey] = QEntry(values, visits, row.optInt(4, 0) != 0)
                } else {
                    for (action in 0..1) {
                        val oldVisits = existing.visits[action]
                        val newVisits = visits[action]
                        val totalVisits = oldVisits + newVisits
                        if (totalVisits > 0) {
                            existing.values[action] =
                                (existing.values[action] * oldVisits + values[action] * newVisits) / totalVisits
                        }
                        existing.visits[action] = totalVisits.coerceAtMost(1_000_000)
                    }
                    existing.safeExploreTried = existing.safeExploreTried || row.optInt(4, 0) != 0
                }
            }
            qLearningRevision = meta.optLong("revision", 1L)
            qLearningUpdatedAt = meta.optLong("updated_at", 0L)
            qLearningTotalPipePasses = meta.optInt("total_pipe_passes", 0)
            qLearningTotalEpisodes = meta.optInt("total_episodes", 0)
            qLearningChecksum = actualChecksum
            qLearningMetaStatus = when {
                !checksumMatches -> "CHECKSUM_MISMATCH"
                savedMeta.isNullOrBlank() -> "LEGACY_TABLE"
                else -> "RESTORED"
            }
            learnedStateCount = qLearningTable.size
            learnedRevision = qLearningRevision
            learnedPipeCount = qLearningTotalPipePasses
            learnedUpdatedAt = qLearningUpdatedAt
            learnedChecksum = actualChecksum
            appendRunLog("RL_TABLE_LOAD status=" + qLearningMetaStatus +
                " revision=" + qLearningRevision + " states=" + qLearningTable.size +
                " episodes=" + qLearningTotalEpisodes + " totalPipes=" + qLearningTotalPipePasses +
                " updatedAt=" + qLearningUpdatedAt + " checksum=" + actualChecksum +
                " checksumMatches=" + checksumMatches)
            if (rewardSchema < 2) {
                appendRunLog("RL_TABLE_MIGRATION reward_schema=2 valueScale=0.02 oldStatesPreserved=true")
                persistQLearningTable()
            }
        } catch (e: Exception) {
            qLearningTable.clear()
            qLearningMetaStatus = "CORRUPT"
            learnedStateCount = 0
            appendRunLog("RL_TABLE_LOAD status=CORRUPT error=" + e.javaClass.simpleName)
        }
    }

    private fun persistQLearningTable() {
        learnedStateCount = qLearningTable.size
        learnedPipeCount = qLearningTotalPipePasses
        try {
            val json = JSONObject()
            for ((state, entry) in qLearningTable) {
                val row = JSONArray().put(entry.values[0].toDouble()).put(entry.values[1].toDouble())
                    .put(entry.visits[0]).put(entry.visits[1]).put(if (entry.safeExploreTried) 1 else 0)
                json.put(state, row)
            }
            val serialized = json.toString()
            val checksum = tableChecksum(serialized)
            val revision = qLearningRevision + 1L
            val updatedAt = System.currentTimeMillis()
            val meta = JSONObject()
                .put("schema", 1)
                .put("reward_schema", 2)
                .put("revision", revision)
                .put("updated_at", updatedAt)
                .put("states", qLearningTable.size)
                .put("total_pipe_passes", qLearningTotalPipePasses)
                .put("total_episodes", qLearningTotalEpisodes)
                .put("checksum", checksum)
            getSharedPreferences("bird_play_learning", MODE_PRIVATE).edit()
                .putString("q_table", serialized)
                .putString("q_table_meta", meta.toString())
                .apply()
            qLearningRevision = revision
            qLearningUpdatedAt = updatedAt
            qLearningChecksum = checksum
            qLearningMetaStatus = "SAVED_QUEUED"
            learnedRevision = revision
            learnedUpdatedAt = updatedAt
            learnedChecksum = checksum
            learnedStateCount = qLearningTable.size
            learnedPipeCount = qLearningTotalPipePasses
            appendRunLog("RL_TABLE_SAVED revision=" + revision + " states=" + qLearningTable.size +
                " episodes=" + qLearningTotalEpisodes + " totalPipes=" + qLearningTotalPipePasses +
                " checksum=" + checksum + " commit=QUEUED")
        } catch (e: Exception) {
            qLearningMetaStatus = "SAVE_ERROR"
            appendRunLog("RL table save error=" + e.javaClass.simpleName)
        }
    }

    private fun resetAttemptDiagnostics() {
        attemptDiagnosticsActive = true
        attemptDiagnosticsStartedAt = SystemClock.elapsedRealtime()
        attemptMinBirdY = Int.MAX_VALUE
        attemptMaxBirdY = Int.MIN_VALUE
        attemptPipeFrames = 0
        attemptUniquePipeTracks = 0
        attemptPipePasses = 0
        attemptNoTrackFrames = 0
        attemptAutoTaps = 0
        attemptUnattributedImpulses = 0
        attemptRejectedTaps = 0
        attemptGestureCompleted = 0
        attemptGestureCancelled = 0
        attemptFlapDecisions = 0
        attemptWaitDecisions = 0
        attemptLastDecision = "NONE"
        attemptLastAction = "NONE"
        attemptUpdatedQStates.clear()
        activePipeTrackId = 0
        lastTrackedPipeRightX = -1
        hasSeenPipeThisAttempt = false
        lastPipeTrackLogAt = 0L
        lastPipeTrackGap = "NONE"
        synchronized(tapObservationLock) { pendingTapObservation = null }
    }

    private fun finishAttemptDiagnostics(reason: String) {
        if (!attemptDiagnosticsActive) return
        val duration = (SystemClock.elapsedRealtime() - attemptDiagnosticsStartedAt).coerceAtLeast(0L)
        val minY = if (attemptMinBirdY == Int.MAX_VALUE) "NA" else attemptMinBirdY.toString()
        val maxY = if (attemptMaxBirdY == Int.MIN_VALUE) "NA" else attemptMaxBirdY.toString()
        appendRunLog(
            "ATTEMPT_SUMMARY reason=$reason duration=${duration}ms yRange=$minY..$maxY " +
                "pipeFrames=$attemptPipeFrames uniquePipes=$attemptUniquePipeTracks passed=$attemptPipePasses " +
                "noTrackFrames=$attemptNoTrackFrames autoTaps=$attemptAutoTaps unattributedImpulses=$attemptUnattributedImpulses " +
                "rejectedTaps=$attemptRejectedTaps gesturesDone=$attemptGestureCompleted gesturesCancelled=$attemptGestureCancelled " +
                "decisionsFLAP=$attemptFlapDecisions decisionsWAIT=$attemptWaitDecisions " +
                "qStatesUpdated=${attemptUpdatedQStates.size} qEpisode=${qLearningEpisode.size} " +
                "lastDecision=$attemptLastDecision lastAction=$attemptLastAction"
        )
        attemptDiagnosticsActive = false
    }

    private fun resetQLearningAttempt() {
        qLearningEpisode.clear()
        pendingRlState = null
        pendingRlAt = 0L
        lastTrackedPipeX = -1
        lastTrackedPipeAt = 0L
        trackedPipeNearBird = false
        qLearningPipePasses = 0
        earlyLaunchTapUsed = false
        hasSeenPipeThisAttempt = false
        lastTrackedPipeRightX = -1
        pendingPipeTapAt = 0L
        pendingPipeTapTrackId = -1
    }

    private fun encodeQLearningState(velocity: Float, timeToPipe: Float, targetOffset: Float, gapHeight: Float, fieldHeight: Int): String {
        val normalizedVelocity = velocity * 1000f / fieldHeight.coerceAtLeast(1)
        val velocityBin = (normalizedVelocity / 0.45f).roundToInt().coerceIn(-7, 8)
        val timeBin = (timeToPipe / 180f).roundToInt().coerceIn(0, 9)
        val verticalBin = ((targetOffset / gapHeight.coerceAtLeast(1f)) / 0.25f).roundToInt().coerceIn(-6, 6)
        return "v2:" + velocityBin + ":" + timeBin + ":" + verticalBin
    }

    private fun qLearningChoice(state: String, fallback: Int, allowSafeExploration: Boolean): Int {
        val entry = qLearningTable[state] ?: if (allowSafeExploration) qLearningTable.getOrPut(state) { QEntry() } else return fallback
        // The first FLAP sample is explored once per quantized state, and only when both
        // the no-tap and post-flap trajectories fit fully inside the safe gap.
        if (allowSafeExploration && entry.visits[RL_WAIT] >= 2 && entry.visits[RL_FLAP] == 0 && !entry.safeExploreTried) {
            entry.safeExploreTried = true
            return RL_FLAP
        }
        if (entry.visits[RL_FLAP] < 1 || entry.visits[RL_WAIT] < 1) return fallback
        val difference = entry.values[RL_FLAP] - entry.values[RL_WAIT]
        return when { difference > 0.20f -> RL_FLAP; difference < -0.20f -> RL_WAIT; else -> fallback }
    }

    private fun observeQLearningDecision(state: String, action: Int, now: Long, pipePassed: Boolean) {
        val previousState = pendingRlState
        if (previousState != null) {
            val elapsed = (now - pendingRlAt).coerceIn(30L, 240L).toFloat()
            val reward = 0.08f * (elapsed / 80f) + if (pipePassed) 8f else 0f
            qLearningEpisode.add(QTransition(previousState, pendingRlAction, reward, state))
            if (pipePassed) { updateQLearningEpisode("PIPE"); qLearningEpisode.clear() }
            else if (qLearningEpisode.size >= 1800) { updateQLearningEpisode("CHUNK"); qLearningEpisode.clear() }
        }
        pendingRlState = state
        pendingRlAction = action
        pendingRlAt = now
    }

    private fun finishQLearningAttempt(death: Boolean) {
        val state = pendingRlState
        if (death && state != null) qLearningEpisode.add(QTransition(state, pendingRlAction, -10f, null))
        pendingRlState = null
        pendingRlAt = 0L
        if (qLearningEpisode.isNotEmpty()) {
            if (death) qLearningTotalEpisodes++
            updateQLearningEpisode(if (death) "DEATH" else "STOP")
            qLearningEpisode.clear()
        }
    }

    private fun updateQLearningEpisode(reason: String) {
        if (qLearningEpisode.isEmpty()) return
        // Backward Q-Learning propagates pipe-pass/death rewards through the preceding trajectory.
        for (transition in qLearningEpisode.asReversed()) {
            attemptUpdatedQStates.add(transition.state)
            val entry = qLearningTable.getOrPut(transition.state) { QEntry() }
            val nextValue = transition.nextState?.let { next -> qLearningTable[next]?.values?.maxOrNull() ?: 0f } ?: 0f
            val target = transition.reward + 0.92f * nextValue
            val action = transition.action.coerceIn(RL_FLAP, RL_WAIT)
            entry.values[action] += 0.22f * (target - entry.values[action])
            entry.visits[action] = (entry.visits[action] + 1).coerceAtMost(1_000_000)
        }
        while (qLearningTable.size > 5000) {
            val weakest = qLearningTable.entries.minByOrNull { it.value.visits[0] + it.value.visits[1] }?.key ?: break
            qLearningTable.remove(weakest)
        }
        persistQLearningTable()
        appendRunLog("RL BACKWARD_UPDATE reason=" + reason + " states=" + qLearningEpisode.size + " table=" + qLearningTable.size + " pipes=" + qLearningPipePasses)
    }

    private fun trackPipePass(d: Detection, fieldWidth: Int, now: Long): Boolean {
        val currentX = d.obstacleX
        val staleAge = if (lastTrackedPipeAt > 0L) now - lastTrackedPipeAt else 0L

        // The detector deliberately drops a pipe only after its trailing edge clears the bird.
        // Confirm the pass on that disappearance instead of waiting for the next pipe to appear.
        if (currentX < 0 || d.pipeBounds == "NONE") {
            // The previous image may be one capture interval behind the actual crossing.
            // Once a recently tracked pipe disappears from the overlap zone, confirm the pass.
            val cleared = trackedPipeNearBird && lastTrackedPipeRightX >= 0 &&
                staleAge in 0L..260L &&
                lastTrackedPipeRightX < d.birdX + fieldWidth * 0.12f
            if (cleared) {
                qLearningPipePasses++
                qLearningTotalPipePasses++
                attemptPipePasses++
                learnedPipeCount = qLearningTotalPipePasses
                appendRunLog(
                    "EVENT pipe_passed attempt=$qLearningPipePasses total=$qLearningTotalPipePasses " +
                        "trackId=$activePipeTrackId trailingEdge=$lastTrackedPipeRightX birdX=${d.birdX} mode=TRAILING_EDGE_CLEARED"
                )
                trackedPipeNearBird = false
                lastTrackedPipeX = -1
                lastTrackedPipeRightX = -1
                lastTrackedPipeAt = now
                lastPipeTrackGap = "NONE"
                return true
            }
            if (staleAge > 1000L) {
                appendRunLog("PIPE_TRACK_STALE id=$activePipeTrackId age=${staleAge}ms lastX=$lastTrackedPipeX")
                trackedPipeNearBird = false
                lastTrackedPipeX = -1
                lastTrackedPipeRightX = -1
                lastTrackedPipeAt = 0L
            }
            return false
        }

        val currentRightX = d.pipeRight.takeIf { it >= 0 } ?: currentX
        val oldX = lastTrackedPipeX
        val oldRightX = lastTrackedPipeRightX
        val previousWasNear = trackedPipeNearBird
        // A jump to the right means the detector switched to the next visible pipe.
        val changedToNextPipe = oldX >= 0 && currentX > oldX + fieldWidth * 0.14f
        val passedOnSwitch = changedToNextPipe && previousWasNear && oldRightX >= 0 &&
            oldRightX < d.birdX - fieldWidth * 0.02f

        if (oldX < 0 || changedToNextPipe) {
            activePipeTrackId++
            attemptUniquePipeTracks++
            appendRunLog(
                "PIPE_TRACK_START id=$activePipeTrackId x=$currentX box=${d.pipeBounds} gap=${d.gapTop}..${d.gapBottom} " +
                    "score=${fmt(d.pipeConfidence)} runs=${d.pipeRuns} staleAge=${staleAge}ms"
            )
        }
        if (changedToNextPipe) {
            appendRunLog(
                "PIPE_TRACK_SWITCH oldId=${activePipeTrackId - 1} newId=$activePipeTrackId oldX=$oldX newX=$currentX " +
                    "previousNear=$previousWasNear passConfirmed=$passedOnSwitch"
            )
            trackedPipeNearBird = false
        }

        val overlapsBirdZone = currentRightX >= d.birdX - fieldWidth * 0.20f &&
            currentX <= d.birdX + fieldWidth * 0.24f
        if (overlapsBirdZone) trackedPipeNearBird = true
        val gapNow = "${d.gapTop}..${d.gapBottom}"
        if (gapNow != lastPipeTrackGap || now - lastPipeTrackLogAt >= 320L) {
            appendRunLog(
                "PIPE_TRACK id=$activePipeTrackId x=$currentX birdX=${d.birdX} bounds=${d.pipeBounds} " +
                    "gap=$gapNow score=${fmt(d.pipeConfidence)} runs=${d.pipeRuns} " +
                    "nearBird=$trackedPipeNearBird trackAge=${if (lastTrackedPipeAt > 0L) staleAge else 0L}ms"
            )
            lastPipeTrackGap = gapNow
            lastPipeTrackLogAt = now
        }
        if (passedOnSwitch) {
            qLearningPipePasses++
            qLearningTotalPipePasses++
            attemptPipePasses++
            learnedPipeCount = qLearningTotalPipePasses
            appendRunLog(
                "EVENT pipe_passed attempt=$qLearningPipePasses total=$qLearningTotalPipePasses " +
                    "trackId=${activePipeTrackId - 1} previousRightX=$oldRightX nextPipeX=$currentX mode=NEXT_PIPE_SWITCH"
            )
        }
        lastTrackedPipeX = currentX
        lastTrackedPipeRightX = currentRightX
        lastTrackedPipeAt = now
        return passedOnSwitch
    }

    private fun rewardPipePassWithoutGap(now: Long) {
        val state = pendingRlState ?: run {
            appendRunLog("RL pipe_passed without pending state")
            return
        }
        val elapsed = (now - pendingRlAt).coerceIn(30L, 240L).toFloat()
        qLearningEpisode.add(QTransition(state, pendingRlAction, 8f + 0.08f * (elapsed / 80f), null))
        pendingRlState = null
        pendingRlAt = 0L
        updateQLearningEpisode("PIPE_NO_GAP")
        qLearningEpisode.clear()
    }

    private fun predictTrajectoryAtPipe(
        startY: Float,
        startVelocity: Float,
        gravity: Float,
        horizonMs: Float,
        tapTimesMs: FloatArray
    ): Float {
        var y = startY
        var velocity = startVelocity
        var elapsed = 0f
        for (tapAt in tapTimesMs) {
            if (tapAt > horizonMs) break
            val segment = (tapAt - elapsed).coerceAtLeast(0f)
            y += velocity * segment + 0.5f * gravity * segment * segment
            velocity += gravity * segment
            // A flap resets vertical speed to the learned post-flap velocity.
            velocity = estimatedFlapVelocity
            elapsed = tapAt
        }
        val remaining = (horizonMs - elapsed).coerceAtLeast(0f)
        return y + velocity * remaining + 0.5f * gravity * remaining * remaining
    }

    private fun updateTapFeedback(
        now: Long, dt: Float, rawVelocity: Float, gravity: Float
    ): TapFeedbackResult {
        val snapshot = synchronized(tapObservationLock) {
            pendingTapObservation?.let { it to it.completedAt }
        } ?: return TapFeedbackResult()
        val pending = snapshot.first
        val completedAt = snapshot.second
        // Measure only a complete frame interval that begins after Android completed the gesture.
        if (completedAt <= 0L || previousBirdAt < completedAt || now - completedAt < 45L) {
            return TapFeedbackResult(pending = true)
        }

        val observed = (rawVelocity - gravity * dt * 0.5f).coerceIn(-3.2f, 0.8f)
        val oldEstimate = estimatedFlapVelocity
        val sampleMidpointElapsed =
            (now.toFloat() - dt * 0.5f - pending.requestedAt.toFloat()).coerceAtLeast(0f)
        val expectedWithoutFlap = pending.baselineVelocity + gravity * sampleMidpointElapsed
        val responseStrength = expectedWithoutFlap - observed
        val confirmed = observed in -3.0f..-0.08f &&
            responseStrength >= max(0.16f, abs(expectedWithoutFlap) * 0.18f)
        var calibrationRejectReason = "NOT_CONFIRMED"

        if (confirmed) {
            val prior = flapImpulseSamples.toList().sorted()
            val priorMedian = when {
                prior.isEmpty() -> Float.NaN
                prior.size % 2 == 1 -> prior[prior.size / 2]
                else -> (prior[prior.size / 2 - 1] + prior[prior.size / 2]) * 0.5f
            }
            val outlierLimit = max(0.80f, abs(priorMedian) * 0.55f)
            if (priorMedian.isNaN() || abs(observed - priorMedian) <= outlierLimit) {
                flapImpulseSamples.addLast(observed)
                while (flapImpulseSamples.size > 7) flapImpulseSamples.removeFirst()
                val sorted = flapImpulseSamples.toList().sorted()
                val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
                    else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
                estimatedFlapVelocity = if (sorted.size == 1) median
                    else (oldEstimate * 0.35f + median * 0.65f).coerceIn(-3.0f, -0.08f)
                calibrationRejectReason = "ACCEPTED"
                persistFlapCalibration("AUTO_TAP_MEDIAN")
            } else {
                calibrationRejectReason = "OUTLIER"
                appendRunLog(
                    "FLAP_CALIBRATION_SAMPLE_REJECTED tapId=${pending.id} observed=${fmt(observed)} " +
                        "referenceMedian=${fmt(priorMedian)} limit=${fmt(outlierLimit)}"
                )
            }
        }

        lastTapFeedbackAt = now
        appendRunLog(
            "PHYSICS_RESPONSE tapId=${pending.id} source=${pending.source} " +
                "requestAge=${now - pending.requestedAt}ms postGesture=${now - completedAt}ms " +
                "baselineY=${pending.baselineY} baselineV=${fmt(pending.baselineVelocity)} " +
                "expectedNoFlap=${fmt(expectedWithoutFlap)} response=${fmt(responseStrength)} " +
                "raw=${fmt(rawVelocity)} observed=${fmt(observed)} confirmed=$confirmed " +
                "calibration=$calibrationRejectReason samples=${flapImpulseSamples.size} " +
                "flapV=${fmt(oldEstimate)}->${fmt(estimatedFlapVelocity)}"
        )
        synchronized(tapObservationLock) {
            if (pendingTapObservation?.id == pending.id) pendingTapObservation = null
        }
        return TapFeedbackResult(resolved = true, confirmedVelocity = observed.takeIf { confirmed })
    }

    private fun handleAutoControl(
        d: Detection, fieldWidth: Int, fieldHeight: Int, cropTop: Int, screenWidth: Int, screenHeight: Int
    ) {
        if (!monitoring || paused || manual) return
        val now = SystemClock.elapsedRealtime()
        if (!d.activeGame) {
            if (pendingRlState != null || qLearningEpisode.isNotEmpty()) finishQLearningAttempt(true)
            appendRunLog("FRAME state=GAME_OVER msg=" + d.message)
            previousBirdY = -1
            previousBirdX = -1
            baselineBirdWidth = -1f
            baselineBirdHeight = -1f
            lastBirdScaleMode = "UNKNOWN"
            previousBirdAt = 0L
            previousObstacleX = -1
            previousObstacleAt = 0L
            previousVerticalVelocity = 0f
            screenStatus = d.message
            return
        }
        if (d.birdX < 0 || d.birdY < 0) {
            if (attemptDiagnosticsActive) attemptNoTrackFrames++
            appendRunLog("FRAME bird=" + d.birdX + "," + d.birdY + " pipe=" + d.obstacleX + " gap=" + d.gapTop + ".." + d.gapBottom + " state=NO_TRACK msg=" + d.message)
            screenStatus = d.message
            return
        }
        if (attemptDiagnosticsActive) {
            attemptMinBirdY = min(attemptMinBirdY, d.birdY)
            attemptMaxBirdY = max(attemptMaxBirdY, d.birdY)
            if (d.obstacleX >= 0) attemptPipeFrames++
        }

        if (d.obstacleX >= 0 && d.pipeBounds != "NONE") hasSeenPipeThisAttempt = true
        val pipePassed = trackPipePass(d, fieldWidth, now)
        if (pipePassed && pendingPipeTapAt > 0L) {
            appendRunLog("PIPE_TAP_PLAN_CANCEL reason=PIPE_PASSED trackId=$pendingPipeTapTrackId")
            pendingPipeTapAt = 0L
            pendingPipeTapTrackId = -1
        }

        val dt = if (previousBirdAt > 0L) (now - previousBirdAt).coerceAtLeast(1L).toFloat() else 0f
        val rawVelocity = if (previousBirdY >= 0 && dt > 0f)
            (d.birdY - previousBirdY).toFloat() / dt else 0f
        // A sharp upward velocity jump is a flap, not gravity. Exclude it from acceleration learning.
        val impulseDetected = previousBirdAt > 0L && dt in 50f..190f &&
            rawVelocity < previousRawVerticalVelocity - 0.28f
        if (dt > 0f && previousBirdAt > 0L && !impulseDetected) {
            val measuredGravity = (rawVelocity - previousRawVerticalVelocity) / dt
            if (measuredGravity in 0.00025f..0.0080f) {
                gravitySamples.addLast(measuredGravity)
                if (gravitySamples.size > 7) gravitySamples.removeFirst()
                if (gravitySamples.size >= 3) {
                    val robustGravity = gravitySamples.sorted()[gravitySamples.size / 2]
                    estimatedGravity = (estimatedGravity * 0.82f + robustGravity * 0.18f).coerceIn(0.00035f, 0.0060f)
                }
            }
        }
        val gravity = estimatedGravity
        // Smooth sprite-bounds animation noise without adding a long controller delay.
        val instantaneousVelocity = if (dt > 0f) rawVelocity + gravity * dt * 0.5f else 0f
        val smoothedVelocity = if (previousBirdAt > 0L)
            previousVerticalVelocity * 0.32f + instantaneousVelocity * 0.68f else 0f
        val tapFeedback = updateTapFeedback(now, dt, rawVelocity, gravity)
        val tapFeedbackPending = tapFeedback.pending
        val hadTapFeedback = tapFeedback.resolved
        val velocity = tapFeedback.confirmedVelocity ?: smoothedVelocity
        val tapSpacingReady = lastAutoTapAt == 0L ||
            now - lastAutoTapAt >= MIN_TAP_INTERVAL_MS
        if (!tapFeedbackPending && !hadTapFeedback && impulseDetected) {
            // A velocity jump without an owned Bird Play gesture is not proof of a user tap.
            // It may be sprite/CV jitter or game-side motion: log it, but do not train physics.
            attemptUnattributedImpulses++
            appendRunLog(
                "UNATTRIBUTED_VERTICAL_IMPULSE raw=${fmt(rawVelocity)} " +
                    "previousRaw=${fmt(previousRawVerticalVelocity)} dt=${dt.toInt()}ms " +
                    "calibrationChanged=false count=$attemptUnattributedImpulses"
            )
        }
        previousBirdY = d.birdY
        previousBirdX = d.birdX
        previousBirdAt = now
        if (d.birdScale != "UNKNOWN" && d.birdScale != lastBirdScaleMode) {
            appendRunLog(
                "BIRD_SCALE mode=${d.birdScale} bounds=${d.birdBounds} " +
                    "baseline=${baselineBirdWidth.toInt()}x${baselineBirdHeight.toInt()}"
            )
            lastBirdScaleMode = d.birdScale
        }
        previousVerticalVelocity = velocity
        previousRawVerticalVelocity = rawVelocity

        val sx = resources.displayMetrics.widthPixels.toFloat() / screenWidth.coerceAtLeast(1)
        val sy = resources.displayMetrics.heightPixels.toFloat() / screenHeight.coerceAtLeast(1)
        val tapX = (screenWidth * 0.78f * sx).coerceIn(1f, resources.displayMetrics.widthPixels - 1f)
        val tapY = ((cropTop + (screenHeight - cropTop) * 0.52f) * sy).coerceIn(1f, resources.displayMetrics.heightPixels - 1f)

        val fallbackBirdHalfWidth = max(1, (fieldWidth * 0.018f).toInt())
        val birdLeftEdge = d.birdLeft.takeIf { it >= 0 } ?: (d.birdX - fallbackBirdHalfWidth)
        val birdRightEdge = d.birdRight.takeIf { it >= 0 } ?: (d.birdX + fallbackBirdHalfWidth)
        val pipeLeftEdge = d.pipeLeft.takeIf { it >= 0 } ?: d.obstacleX
        val pipeRightEdge = d.pipeRight.takeIf { it >= 0 } ?: d.obstacleX
        val horizontalPipeOverlap = d.obstacleX >= 0 &&
            pipeLeftEdge <= birdRightEdge && pipeRightEdge >= birdLeftEdge
        val hasGap = d.obstacleX > d.birdX && !horizontalPipeOverlap &&
            d.gapTop >= 0 && d.gapBottom > d.gapTop
        if (pendingPipeTapAt > 0L && pendingPipeTapTrackId != activePipeTrackId) {
            appendRunLog(
                "PIPE_TAP_PLAN_CANCEL reason=TRACK_CHANGED oldTrack=$pendingPipeTapTrackId newTrack=$activePipeTrackId"
            )
            pendingPipeTapAt = 0L
            pendingPipeTapTrackId = -1
        }
        if (!hasGap) {
            appendRunLog(
                "FRAME bird=" + d.birdY + " v=" + fmt(velocity) + " pipe=" + d.obstacleX +
                    " gap=" + d.gapTop + ".." + d.gapBottom + " dt=" + dt.toInt() +
                    "ms pipePassed=" + pipePassed + " decision=WAIT_GAP"
            )
            if (pipePassed) rewardPipePassWithoutGap(now)

            // Keep the controller active until the actual sprite rectangles separate.
            val pipeCrossingWithKnownGap = horizontalPipeOverlap &&
                d.gapTop >= 0 && d.gapBottom > d.gapTop
            if (pipeCrossingWithKnownGap) {
                val crossingGapHeight = (d.gapBottom - d.gapTop).toFloat()
                val crossingSafeTop = d.gapTop + crossingGapHeight * 0.18f
                val crossingSafeBottom = d.gapBottom - crossingGapHeight * 0.18f
                val crossingReactionMs = 65f
                val crossingSpeed = if (smoothedObstacleSpeed > 0.05f)
                    smoothedObstacleSpeed else fieldWidth * 0.00075f
                val remainingPipePixels = (pipeRightEdge - birdRightEdge).coerceAtLeast(0)
                val crossingHorizon = (remainingPipePixels / crossingSpeed.coerceAtLeast(0.08f))
                    .coerceIn(0f, 360f)
                val crossingSampleTimes = floatArrayOf(
                    crossingReactionMs,
                    (crossingReactionMs + crossingHorizon) * 0.5f,
                    crossingHorizon
                )
                fun crossingMiss(y: Float): Float = when {
                    y < crossingSafeTop -> crossingSafeTop - y
                    y > crossingSafeBottom -> y - crossingSafeBottom
                    else -> 0f
                }
                val waitYs = FloatArray(3) { i ->
                    predictTrajectoryAtPipe(
                        d.birdY.toFloat(), velocity, gravity, crossingSampleTimes[i], floatArrayOf()
                    )
                }
                val waitMisses = FloatArray(3) { i -> crossingMiss(waitYs[i]) }
                val waitWorstMiss = waitMisses.maxOrNull() ?: 0f
                val waitMeanMiss = waitMisses.average().toFloat()

                val crossingIntervals = floatArrayOf(250f, 320f, 400f, 480f)
                val elapsedSinceLastImpulse = if (lastAutoTapAt > 0L)
                    (now - lastAutoTapAt).toFloat() - crossingReactionMs else Float.POSITIVE_INFINITY
                val pendingResponseDelay = if (pendingPipeTapAt > 0L &&
                    pendingPipeTapTrackId == activePipeTrackId) {
                    (pendingPipeTapAt - now).toFloat().coerceAtLeast(crossingReactionMs)
                } else null
                val initialTapDelays = if (lastAutoTapAt > 0L) {
                    crossingIntervals.map { (it - elapsedSinceLastImpulse).coerceAtLeast(crossingReactionMs) }
                } else listOf(65f, 160f, 220f, 280f, 340f)
                val firstTapChoices = (initialTapDelays + listOfNotNull(pendingResponseDelay))
                    .distinct().sorted()
                val crossingCandidates = ArrayList<TapPlan>(48)
                var crossingSchedulesEvaluated = 0

                fun evaluateCrossingSchedule(schedule: List<Float>) {
                    if (schedule.isEmpty() || schedule.size > 4) return
                    if (schedule.last() + 20f >= crossingHorizon) return
                    val taps = schedule.toFloatArray()
                    val ys = FloatArray(3) { i ->
                        predictTrajectoryAtPipe(
                            d.birdY.toFloat(), velocity, gravity, crossingSampleTimes[i], taps
                        )
                    }
                    val misses = FloatArray(3) { i -> crossingMiss(ys[i]) }
                    val worstMiss = misses.maxOrNull() ?: 0f
                    val meanMiss = misses.average().toFloat()
                    val pendingMatch = pendingResponseDelay != null &&
                        abs(schedule.first() - pendingResponseDelay) <= 12f
                    val score = worstMiss + schedule.size * 6f + meanMiss * 0.12f -
                        min(schedule.first(), 340f) * 0.025f - (if (pendingMatch) 10000f else 0f)
                    crossingCandidates.add(
                        TapPlan(taps, ys[0], ys[1], ys[2], worstMiss, meanMiss, score)
                    )
                    crossingSchedulesEvaluated++
                }

                fun expandCrossingSchedules(schedule: MutableList<Float>) {
                    evaluateCrossingSchedule(schedule)
                    if (schedule.size >= 4) return
                    val previousAt = schedule.last()
                    for (interval in crossingIntervals) {
                        val nextAt = previousAt + interval
                        if (nextAt + 20f >= crossingHorizon) continue
                        schedule.add(nextAt)
                        expandCrossingSchedules(schedule)
                        schedule.removeAt(schedule.lastIndex)
                    }
                }

                if (crossingHorizon > crossingReactionMs + 20f) {
                    for (firstDelay in firstTapChoices) {
                        if (firstDelay + 20f >= crossingHorizon) continue
                        expandCrossingSchedules(mutableListOf(firstDelay))
                    }
                }

                fun sameOrImprovedCrossingSide(candidateY: Float, naturalY: Float): Boolean {
                    val naturalSide = when {
                        naturalY < crossingSafeTop -> -1
                        naturalY > crossingSafeBottom -> 1
                        else -> 0
                    }
                    val candidateSide = when {
                        candidateY < crossingSafeTop -> -1
                        candidateY > crossingSafeBottom -> 1
                        else -> 0
                    }
                    return when (naturalSide) {
                        0 -> candidateSide == 0
                        -1 -> candidateSide != 1
                        else -> candidateSide != -1
                    }
                }
                fun doesNotReverseCrossingCollision(candidate: TapPlan): Boolean =
                    sameOrImprovedCrossingSide(candidate.entryY, waitYs[0]) &&
                        sameOrImprovedCrossingSide(candidate.middleY, waitYs[1]) &&
                        sameOrImprovedCrossingSide(candidate.exitY, waitYs[2])

                val bestSafeCrossingPlan = crossingCandidates
                    .filter { it.worstMiss <= 0f }
                    .minByOrNull { it.score }
                val minRescueImprovement = max(7f, crossingGapHeight * 0.01f)
                val bestRescueCrossingPlan = if (bestSafeCrossingPlan == null) {
                    crossingCandidates.filter {
                        it.worstMiss <= waitWorstMiss + 8f &&
                            it.meanMiss + minRescueImprovement < waitMeanMiss &&
                            doesNotReverseCrossingCollision(it)
                    }.minByOrNull { it.score }
                } else null
                val bestCrossingPlan = bestSafeCrossingPlan ?: bestRescueCrossingPlan
                val selectedTapCount = bestCrossingPlan?.tapTimesMs?.size ?: 0
                val firstTapDelay = bestCrossingPlan?.tapTimesMs?.firstOrNull() ?: 0f
                val selectedSchedule = bestCrossingPlan?.tapTimesMs?.joinToString(",") {
                    it.toInt().toString()
                } ?: "NONE"
                val pendingPlanMatches = pendingResponseDelay != null &&
                    abs(firstTapDelay - pendingResponseDelay) <= 12f
                val crossingPlanValid = crossingHorizon > crossingReactionMs + 20f &&
                    waitWorstMiss > 0f && bestCrossingPlan != null
                if (!crossingPlanValid && pendingPipeTapAt > 0L &&
                    pendingPipeTapTrackId == activePipeTrackId) {
                    appendRunLog("PIPE_TAP_PLAN_CANCEL reason=POST_PIPE_PLAN_NO_LONGER_VALID trackId=$activePipeTrackId")
                    pendingPipeTapAt = 0L
                    pendingPipeTapTrackId = -1
                } else if (crossingPlanValid && firstTapDelay > crossingReactionMs + 45f &&
                    !pendingPlanMatches) {
                    pendingPipeTapAt = now + firstTapDelay.toLong()
                    pendingPipeTapTrackId = activePipeTrackId
                    appendRunLog(
                        "PIPE_TAP_PLAN_SCHEDULED source=POST_PIPE trackId=$activePipeTrackId " +
                            "responseIn=${firstTapDelay.toInt()}ms dispatchIn=${(firstTapDelay - crossingReactionMs).toInt()}ms " +
                            "planN=$selectedTapCount miss=${bestCrossingPlan?.worstMiss?.toInt() ?: -1}"
                    )
                }
                val tapWindowReady = bestCrossingPlan != null &&
                    firstTapDelay <= crossingReactionMs + 45f
                val canDispatchCrossingTap = !tapFeedbackPending && !hadTapFeedback &&
                    tapSpacingReady && !gestureInFlight.get()
                val shouldCrossingTap = crossingPlanValid && tapWindowReady && canDispatchCrossingTap
                val planMiss = bestCrossingPlan?.worstMiss ?: waitWorstMiss
                val planMode = when {
                    bestSafeCrossingPlan != null -> "FULL"
                    bestRescueCrossingPlan != null -> "SAME_SIDE_RESCUE"
                    else -> "NONE"
                }
                appendRunLog(
                    "POST_PIPE_CONTROL trackId=$activePipeTrackId frontX=${d.obstacleX} " +
                        "trailingX=$pipeRightEdge birdRight=$birdRightEdge gap=${d.gapTop}..${d.gapBottom} " +
                        "safe=${crossingSafeTop.toInt()}..${crossingSafeBottom.toInt()} " +
                        "horizon=${crossingHorizon.toInt()}ms waitMiss=${waitWorstMiss.toInt()} " +
                        "planMiss=${planMiss.toInt()} planN=$selectedTapCount intervals=$selectedSchedule " +
                        "samples=${waitYs[0].toInt()}/${waitYs[1].toInt()}/${waitYs[2].toInt()} " +
                        "selected=${if (shouldCrossingTap) "FLAP" else "WAIT"} mode=$planMode " +
                        "schedules=$crossingSchedulesEvaluated feedbackPending=$tapFeedbackPending"
                )
                if (shouldCrossingTap && tap(tapX, tapY, "POST_PIPE", velocity, d.birdY)) {
                    lastAutoTapAt = now
                    pendingPipeTapAt = 0L
                    pendingPipeTapTrackId = -1
                    autoTapCount++
                    attemptAutoTaps++
                    attemptFlapDecisions++
                    attemptLastAction = "FLAP_POST_PIPE"
                    attemptLastDecision = if (planMode == "FULL") "POST_PIPE_FULL_PLAN" else "POST_PIPE_SAME_SIDE_RESCUE"
                    appendRunLog(
                        "ACTION_SPACE selected=FLAP reason=$attemptLastDecision " +
                            "planN=$selectedTapCount nextTapOnly=true replanAfterPhysicsFeedback=true"
                    )
                    screenStatus = "ПРОХОЖУ ТРУБУ • аварийная коррекция $selectedTapCount"
                } else {
                    attemptWaitDecisions++
                    attemptLastAction = "WAIT_POST_PIPE"
                    attemptLastDecision = when {
                        tapFeedbackPending -> "POST_PIPE_WAIT_PHYSICS_FEEDBACK"
                        hadTapFeedback -> "POST_PIPE_WAIT_NEXT_FRAME"
                        !tapSpacingReady -> "POST_PIPE_WAIT_MIN_INTERVAL"
                        gestureInFlight.get() -> "POST_PIPE_WAIT_GESTURE"
                        waitWorstMiss <= 0f -> "POST_PIPE_PATH_SAFE"
                        bestCrossingPlan == null -> "POST_PIPE_NO_NONWORSENING_RESCUE"
                        !tapWindowReady -> "POST_PIPE_WAIT_PLANNED_TAP"
                        else -> "POST_PIPE_TAP_NOT_DISPATCHED"
                    }
                    val reason = when {
                        tapFeedbackPending -> "WAIT_TAP_FEEDBACK"
                        hadTapFeedback -> "WAIT_NEXT_FRAME_AFTER_FEEDBACK"
                        !tapSpacingReady -> "MIN_TAP_INTERVAL"
                        gestureInFlight.get() -> "GESTURE_IN_FLIGHT"
                        waitWorstMiss <= 0f -> "PREDICTED_PATH_IN_GAP"
                        bestCrossingPlan == null -> "NO_NONWORSENING_RESCUE"
                        !tapWindowReady -> "PLANNED_TAP_IN_${firstTapDelay.toInt()}MS"
                        else -> "TAP_NOT_DISPATCHED"
                    }
                    appendRunLog("ACTION_SPACE selected=WAIT reason=$reason planN=$selectedTapCount")
                    screenStatus = "ПРОХОЖУ ТРУБУ • коррекция не нужна / ожидаю окно"
                }
            } else if (d.obstacleX >= 0) {
                // If a real obstacle is visible but its gap is uncertain, do not mistake it
                // for the open interval between pipes and do not blindly flap.
                screenStatus = "ТРУБА x=${d.obstacleX} • уточняю просвет"
                attemptLastDecision = "WAIT_PIPE_GAP_UNCONFIRMED"
                attemptLastAction = "WAIT"
                appendRunLog("ACTION_SPACE selected=WAIT reason=PIPE_GAP_UNCONFIRMED")
            } else if (!hasSeenPipeThisAttempt) {
                // Before the first pipe, intervene only near the lower field boundary.
                // The pipe planner should own positioning; this guard prevents an otherwise
                // unavoidable fall without repeatedly pulling the bird toward screen centre.
                val prepipeHorizon = 220f
                val prepipeTapAt = 65f
                val prepipeUpper = fieldHeight * 0.16f
                val prepipeLower = fieldHeight * 0.78f
                val predictedWithoutTap = predictTrajectoryAtPipe(
                    d.birdY.toFloat(), velocity, gravity, prepipeHorizon, floatArrayOf()
                )
                val predictedWithTap = predictTrajectoryAtPipe(
                    d.birdY.toFloat(), velocity, gravity, prepipeHorizon, floatArrayOf(prepipeTapAt)
                )
                val prepipeTapSpacingReady = tapSpacingReady
                val prepipeNeedsEmergencyLift = predictedWithoutTap > prepipeLower &&
                    velocity > 0.45f
                val prepipeTapKeepsInField = predictedWithTap in prepipeUpper..(prepipeLower + fieldHeight * 0.035f)
                val prepipeTapImproves = predictedWithoutTap - predictedWithTap >= max(22f, fieldHeight * 0.018f)
                val prepipeCanTap = !tapFeedbackPending && !hadTapFeedback &&
                    !gestureInFlight.get() && prepipeTapSpacingReady
                val shouldPrepipeTap = prepipeCanTap && prepipeNeedsEmergencyLift &&
                    prepipeTapKeepsInField && prepipeTapImproves

                val prepipeReason = when {
                    tapFeedbackPending -> "WAIT_TAP_PHYSICS_FEEDBACK"
                    hadTapFeedback -> "WAIT_NEXT_FRAME_AFTER_FEEDBACK"
                    gestureInFlight.get() -> "GESTURE_IN_FLIGHT"
                    !prepipeTapSpacingReady -> "MIN_TAP_INTERVAL"
                    !prepipeNeedsEmergencyLift -> "NO_EMERGENCY_FALL"
                    !prepipeTapKeepsInField -> "FLAP_WOULD_OVERSHOOT"
                    !prepipeTapImproves -> "FLAP_NOT_USEFUL"
                    else -> "EMERGENCY_GUARD"
                }

                if (shouldPrepipeTap && tap(tapX, tapY, "PREPIPE_GUARD", velocity, d.birdY)) {
                    lastAutoTapAt = now
                    autoTapCount++
                    attemptAutoTaps++
                    attemptFlapDecisions++
                    attemptLastAction = "FLAP_PREPIPE_GUARD"
                    attemptLastDecision = "PREPIPE_EMERGENCY_FLAP"
                    appendRunLog(
                        "PREPIPE_CONTROL y=${d.birdY} v=${fmt(velocity)} " +
                            "wait${prepipeHorizon.toInt()}=${predictedWithoutTap.toInt()} " +
                            "flap${prepipeHorizon.toInt()}=${predictedWithTap.toInt()} " +
                            "emergencyBand=${prepipeUpper.toInt()}..${prepipeLower.toInt()} selected=FLAP"
                    )
                    appendRunLog("ACTION_SPACE selected=FLAP reason=PREPIPE_EMERGENCY_GUARD")
                    screenStatus = "ЗАЩИТА ДО ПЕРВОЙ ТРУБЫ • y=${d.birdY}"
                } else {
                    attemptWaitDecisions++
                    attemptLastDecision = if (shouldPrepipeTap) "PREPIPE_TAP_REJECTED" else "PREPIPE_WAIT"
                    attemptLastAction = "WAIT_PREPIPE"
                    appendRunLog(
                        "PREPIPE_CONTROL y=${d.birdY} v=${fmt(velocity)} " +
                            "wait${prepipeHorizon.toInt()}=${predictedWithoutTap.toInt()} " +
                            "flap${prepipeHorizon.toInt()}=${predictedWithTap.toInt()} " +
                            "emergencyBand=${prepipeUpper.toInt()}..${prepipeLower.toInt()} " +
                            "selected=WAIT reason=${if (shouldPrepipeTap) "TAP_REJECTED" else prepipeReason}"
                    )
                    appendRunLog("ACTION_SPACE selected=WAIT reason=${if (shouldPrepipeTap) "TAP_REJECTED" else prepipeReason}")
                    screenStatus = "ДО ПЕРВОЙ ТРУБЫ • аварийный прогноз=${predictedWithoutTap.toInt()}"
                }
            } else {
                // Between pipes, WAIT is an explicit ballistic action; FLAP is evaluated
                // against it before execution. Pickups are deliberately ignored.
                val cruiseHorizon = 300f
                val predictedWithoutTap = predictTrajectoryAtPipe(
                    d.birdY.toFloat(), velocity, gravity, cruiseHorizon, floatArrayOf()
                )
                val predictedWithTap = predictTrajectoryAtPipe(
                    d.birdY.toFloat(), velocity, gravity, cruiseHorizon, floatArrayOf(65f)
                )
                val cruiseUpper = fieldHeight * 0.20f
                val cruiseLower = fieldHeight * 0.72f
                val minImprovement = max(20f, fieldHeight * 0.025f)
                val cruiseNeedsLift = predictedWithoutTap > cruiseLower && velocity > -0.12f
                val tapKeepsInField = predictedWithTap in cruiseUpper..cruiseLower
                val tapMeaningfullyImproves = predictedWithoutTap - predictedWithTap >= minImprovement
                val cruiseTapReady = !tapFeedbackPending && !hadTapFeedback &&
                    tapSpacingReady && !gestureInFlight.get()
                val shouldCruiseTap = cruiseTapReady && cruiseNeedsLift && tapKeepsInField &&
                    tapMeaningfullyImproves

                if (shouldCruiseTap) {
                    if (tap(tapX, tapY, "CRUISE", velocity, d.birdY)) {
                        lastAutoTapAt = now
                        autoTapCount++
                        attemptAutoTaps++
                        attemptFlapDecisions++
                        attemptLastAction = "FLAP_INTERPIPE"
                        attemptLastDecision = "INTERPIPE_FLAP"
                        appendRunLog(
                            "ACTION_SPACE wait300=${predictedWithoutTap.toInt()} " +
                                "flap300=${predictedWithTap.toInt()} selected=FLAP reason=INTERPIPE_LIFT_NEEDED"
                        )
                    } else {
                        attemptLastAction = "FLAP_INTERPIPE_REJECTED"
                        appendRunLog("ACTION_SPACE selected=WAIT reason=TAP_REJECTED")
                    }
                } else {
                    attemptWaitDecisions++
                    attemptLastAction = "WAIT_INTERPIPE"
                    attemptLastDecision = "INTERPIPE_WAIT"
                    val reason = when {
                        tapFeedbackPending -> "WAIT_TAP_FEEDBACK"
                        hadTapFeedback -> "WAIT_NEXT_FRAME_AFTER_FEEDBACK"
                        !tapSpacingReady -> "MIN_TAP_INTERVAL"
                        gestureInFlight.get() -> "GESTURE_IN_FLIGHT"
                        !cruiseNeedsLift -> "TRAJECTORY_IN_BAND_OR_RISING"
                        !tapKeepsInField -> "TAP_WOULD_OVERSHOOT"
                        else -> "TAP_NOT_USEFUL"
                    }
                    appendRunLog(
                        "ACTION_SPACE wait300=${predictedWithoutTap.toInt()} " +
                            "flap300=${predictedWithTap.toInt()} selected=WAIT reason=$reason"
                    )
                    screenStatus = "МЕЖДУ ТРУБАМИ • y=${d.birdY} • прогноз=${predictedWithoutTap.toInt()}"
                }
            }
            previousObstacleX = -1
            previousObstacleAt = 0L
            return
        }

        val gapTop = d.gapTop.toFloat()
        val gapBottom = d.gapBottom.toFloat()
        val gapHeight = (gapBottom - gapTop).coerceAtLeast(1f)
        val targetY = gapTop + gapHeight * 0.50f
        val birdHalfWidth = if (d.birdLeft >= 0 && d.birdRight > d.birdLeft)
            (d.birdRight - d.birdLeft) * 0.5f else fieldWidth * 0.018f
        val distance = (d.obstacleX - d.birdX - birdHalfWidth - fieldWidth * 0.006f).toFloat()

        val obstacleDt = if (previousObstacleAt > 0L) (now - previousObstacleAt).coerceAtLeast(1L).toFloat() else 0f
        val measuredSpeed = if (previousObstacleX > d.obstacleX && obstacleDt in 35f..500f)
            (previousObstacleX - d.obstacleX).toFloat() / obstacleDt else 0f
        if (measuredSpeed in 0.08f..3.5f) {
            smoothedObstacleSpeed = if (smoothedObstacleSpeed > 0f)
                smoothedObstacleSpeed * 0.55f + measuredSpeed * 0.45f else measuredSpeed
        }
        val fallbackSpeed = fieldWidth * 0.00075f
        val obstacleSpeed = if (smoothedObstacleSpeed > 0f) smoothedObstacleSpeed else fallbackSpeed
        previousObstacleX = d.obstacleX
        previousObstacleAt = now

        val timeToPipe = (distance / obstacleSpeed).coerceIn(0f, 1400f)
        // Different inter-tap timings create different lifts. Search complete schedules
        // from one to four taps, execute only the first tap, then re-plan from measured motion.
        val reactionMs = 65f
        val safeMargin = (gapHeight * 0.10f).coerceAtLeast(fieldHeight * 0.010f)
        val birdHalfHeight = if (d.birdTop >= 0 && d.birdBottom > d.birdTop)
            (d.birdBottom - d.birdTop) * 0.5f else fieldHeight * 0.04f
        val safeTop = gapTop + safeMargin + birdHalfHeight
        val safeBottom = gapBottom - safeMargin - birdHalfHeight
        val safeWindowValid = safeBottom > safeTop
        val distanceWindow = distance > 0f && distance < fieldWidth * 1.8f

        fun missDistance(y: Float): Float = when {
            y < safeTop -> safeTop - y
            y > safeBottom -> y - safeBottom
            else -> 0f
        }

        val pipeWidth = if (d.pipeLeft >= 0 && d.pipeRight > d.pipeLeft)
            (d.pipeRight - d.pipeLeft).toFloat() else fieldWidth * 0.08f
        val birdWidth = if (d.birdLeft >= 0 && d.birdRight > d.birdLeft)
            (d.birdRight - d.birdLeft).toFloat() else fieldWidth * 0.04f
        // Test the complete horizontal overlap: pipe entry, middle, and exit.
        val collisionDuration = ((pipeWidth + birdWidth) / obstacleSpeed).coerceIn(90f, 300f)
        val middleTime = timeToPipe + collisionDuration * 0.5f
        val exitTime = timeToPipe + collisionDuration
        val zeroEntryY = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, timeToPipe, floatArrayOf())
        val zeroMiddleY = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, middleTime, floatArrayOf())
        val zeroExitY = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, exitTime, floatArrayOf())
        val zeroMisses = floatArrayOf(missDistance(zeroEntryY), missDistance(zeroMiddleY), missDistance(zeroExitY))
        val zeroWorstMiss = zeroMisses.maxOrNull() ?: 0f
        val zeroMeanMiss = zeroMisses.average().toFloat()

        val bestByTapCount = arrayOfNulls<TapPlan>(5)
        val allTapPlans = ArrayList<TapPlan>(64)
        // WAIT is a first-class action: the zero-tap ballistic trajectory above is its
        // full entry/middle/exit evaluation. FLAP candidates are compared against it.
        val waitActionMiss = zeroWorstMiss
        // Different intervals create different lifts; preserve all schedules rather than
        // retaining only the mathematically best miss for each tap count.
        val interTapIntervals = floatArrayOf(250f, 320f, 400f, 480f)
        val elapsedSinceLastImpulse = if (lastAutoTapAt > 0L)
            (now - lastAutoTapAt).toFloat() - reactionMs else Float.POSITIVE_INFINITY
        val pendingResponseDelay = if (pendingPipeTapAt > 0L &&
            pendingPipeTapTrackId == activePipeTrackId) {
            (pendingPipeTapAt - now).toFloat().coerceAtLeast(reactionMs)
        } else null
        val initialTapChoices = if (lastAutoTapAt > 0L) {
            interTapIntervals.map { (it - elapsedSinceLastImpulse).coerceAtLeast(reactionMs) }
        } else listOf(65f, 160f, 220f, 280f, 340f)
        val firstTapDelays = (initialTapChoices + listOfNotNull(pendingResponseDelay))
            .distinct().sorted().toFloatArray()
        var schedulesEvaluated = 0

        fun evaluateSchedule(schedule: List<Float>) {
            if (schedule.isEmpty() || schedule.size > 4) return
            if (schedule.last() + 35f >= timeToPipe) return
            val taps = schedule.toFloatArray()
            val entryY = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, timeToPipe, taps)
            val middleY = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, middleTime, taps)
            val exitY = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, exitTime, taps)
            val misses = floatArrayOf(missDistance(entryY), missDistance(middleY), missDistance(exitY))
            val worstMiss = misses.maxOrNull() ?: 0f
            val meanMiss = misses.average().toFloat()
            val pendingScheduleMatch = pendingResponseDelay != null &&
                abs(schedule.first() - pendingResponseDelay) <= 12f
            // When safety is otherwise equal, postpone a flap instead of applying it too early.
            // If a response-time was already scheduled for this pipe, preserve that exact plan.
            val delayPreference = min(schedule.first(), 340f) * 0.025f
            val pendingPlanBonus = if (pendingScheduleMatch) 10000f else 0f
            val score = worstMiss + schedule.size * 6f + meanMiss * 0.12f -
                delayPreference - pendingPlanBonus
            val candidate = TapPlan(taps, entryY, middleY, exitY, worstMiss, meanMiss, score)
            allTapPlans.add(candidate)
            schedulesEvaluated++
            val count = schedule.size
            val previous = bestByTapCount[count]
            if (previous == null || score < previous.score) bestByTapCount[count] = candidate
        }

        fun expandSchedules(schedule: MutableList<Float>) {
            evaluateSchedule(schedule)
            if (schedule.size >= 4) return
            val previousAt = schedule.last()
            for (interval in interTapIntervals) {
                val nextAt = previousAt + interval
                if (nextAt + 35f >= timeToPipe) continue
                schedule.add(nextAt)
                expandSchedules(schedule)
                schedule.removeAt(schedule.lastIndex)
            }
        }
        if (distanceWindow && safeWindowValid && timeToPipe > reactionMs + 35f) {
            for (firstDelay in firstTapDelays) {
                if (firstDelay + 35f >= timeToPipe) continue
                expandSchedules(mutableListOf(firstDelay))
            }
        }

        val minUsefulImprovement = max(7f, safeMargin * 0.06f)
        fun sameOrImprovedSide(candidateY: Float, naturalY: Float): Boolean {
            val naturalSide = when {
                naturalY < safeTop -> -1
                naturalY > safeBottom -> 1
                else -> 0
            }
            val candidateSide = when {
                candidateY < safeTop -> -1
                candidateY > safeBottom -> 1
                else -> 0
            }
            return when (naturalSide) {
                0 -> candidateSide == 0
                -1 -> candidateSide != 1
                else -> candidateSide != -1
            }
        }
        fun doesNotReverseIntoOppositeCollision(candidate: TapPlan): Boolean =
            sameOrImprovedSide(candidate.entryY, zeroEntryY) &&
                sameOrImprovedSide(candidate.middleY, zeroMiddleY) &&
                sameOrImprovedSide(candidate.exitY, zeroExitY)

        // Prefer a plan that keeps the entire horizontal overlap inside the safe window.
        // If no fully-safe tap plan exists, permit a same-side rescue only when it improves
        // the natural trajectory and never turns a low-side miss into a high-side collision.
        val bestSafePlan = allTapPlans
            .filter { it.worstMiss <= 0f }
            .minByOrNull { it.score }
        // Progressive recovery only needs a useful same-side improvement; one tap is
        // executed at a time, then the controller re-plans using measured movement.
        val bestRescuePlan = if (bestSafePlan == null) {
            allTapPlans
                .filter {
                    it.worstMiss + minUsefulImprovement < waitActionMiss &&
                        doesNotReverseIntoOppositeCollision(it)
                }
                .minByOrNull { it.score }
        } else null
        val bestPlan = bestSafePlan ?: bestRescuePlan
        val bestPlanCount = bestPlan?.tapTimesMs?.size ?: 0
        val bestPlanScore = bestPlan?.score ?: waitActionMiss
        val bestPlanMiss = bestPlan?.worstMiss ?: waitActionMiss
        val bestPlanEntryY = bestPlan?.entryY ?: zeroEntryY
        val bestPlanMiddleY = bestPlan?.middleY ?: zeroMiddleY
        val bestPlanExitY = bestPlan?.exitY ?: zeroExitY
        val selectedTapTimes = bestPlan?.tapTimesMs ?: floatArrayOf()
        val selectedIntervals = selectedTapTimes.indices.map { i ->
            if (i == 0) selectedTapTimes[i] else selectedTapTimes[i] - selectedTapTimes[i - 1]
        }
        val firstTapDelay = selectedTapTimes.firstOrNull() ?: 0f
        val scheduleText = if (selectedIntervals.isEmpty()) "NONE"
            else selectedIntervals.joinToString(",") { it.toInt().toString() }
        val scenarioText = (1..4).joinToString(" ") { count ->
            val p = bestByTapCount[count]
            if (p == null) "$count=NA" else "$count:${p.worstMiss.toInt()}@[${p.tapTimesMs.joinToString(",") { it.toInt().toString() }}]"
        }
        val safetyMissThreshold = max(gapHeight * 0.12f, fieldHeight * 0.02f)
        val recoveryPossible = bestPlan != null
        val planFullySafe = bestSafePlan != null
        val gapTrusted = d.pipeConfidence >= 0.55f ||
            (timeToPipe <= 250f && d.pipeConfidence >= 0.42f)
        val tapDispatchWindow = reactionMs + 45f
        val planningConditionsValid = distanceWindow && safeWindowValid && gapTrusted &&
            !tapFeedbackPending && waitActionMiss > safetyMissThreshold && recoveryPossible
        val pendingPlanMatchesSelection = pendingResponseDelay != null &&
            abs(firstTapDelay - pendingResponseDelay) <= 12f
        if (!planningConditionsValid && pendingPipeTapAt > 0L) {
            appendRunLog("PIPE_TAP_PLAN_CANCEL reason=PLAN_NO_LONGER_VALID trackId=$pendingPipeTapTrackId")
            pendingPipeTapAt = 0L
            pendingPipeTapTrackId = -1
        } else if (planningConditionsValid && firstTapDelay > tapDispatchWindow &&
            !pendingPlanMatchesSelection) {
            pendingPipeTapAt = now + firstTapDelay.toLong()
            pendingPipeTapTrackId = activePipeTrackId
            appendRunLog(
                "PIPE_TAP_PLAN_SCHEDULED trackId=$activePipeTrackId " +
                    "responseIn=${firstTapDelay.toInt()}ms dispatchIn=${(firstTapDelay - reactionMs).toInt()}ms " +
                    "planN=$bestPlanCount miss=${bestPlanMiss.toInt()}"
            )
        }
        val tapWindowReady = firstTapDelay <= tapDispatchWindow
        val shouldTap = planningConditionsValid && tapWindowReady &&
            !hadTapFeedback && tapSpacingReady

        val actionCandidatesText = "WAIT:${waitActionMiss.toInt()} " +
            "FLAP_SAFE:${bestSafePlan?.let { it.worstMiss.toInt() } ?: "NA"} " +
            "FLAP_RESCUE:${bestRescuePlan?.let { it.worstMiss.toInt() } ?: "NA"}"
        val stateKey = encodeQLearningState(velocity, timeToPipe, targetY - d.birdY, gapHeight, fieldHeight)
        val fallbackAction = if (shouldTap) RL_FLAP else RL_WAIT
        // Disable safe exploratory FLAPs while the controller is being calibrated.
        val qAction = qLearningChoice(stateKey, fallbackAction, false)
        val qEntry = qLearningTable[stateKey]
        val qFlap = qEntry?.values?.get(RL_FLAP) ?: 0f
        val qWait = qEntry?.values?.get(RL_WAIT) ?: 0f
        val qFlapVisits = qEntry?.visits?.get(RL_FLAP) ?: 0
        val qWaitVisits = qEntry?.visits?.get(RL_WAIT) ?: 0

        val wait100 = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, min(100f, timeToPipe), floatArrayOf())
        val wait200 = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, min(200f, timeToPipe), floatArrayOf())
        val wait300 = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, min(300f, timeToPipe), floatArrayOf())
        val wait400 = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, min(400f, timeToPipe), floatArrayOf())
        val decisionReason = when {
            !distanceWindow -> "OUT_OF_RANGE"
            !safeWindowValid -> "INVALID_SAFE_WINDOW"
            !gapTrusted -> "LOW_GAP_CONFIDENCE_WAIT"
            tapFeedbackPending -> "WAIT_TAP_PHYSICS_FEEDBACK"
            waitActionMiss <= safetyMissThreshold -> "PATH_SAFE_WAIT"
            hadTapFeedback -> "WAIT_NEXT_FRAME_AFTER_FEEDBACK"
            !tapSpacingReady -> "WAIT_MIN_TAP_INTERVAL"
            recoveryPossible && !tapWindowReady -> "WAIT_PLANNED_TAP_${firstTapDelay.toInt()}MS"
            planFullySafe && bestPlanCount >= 3 -> "RECOVERY_SAFE_PLAN_${bestPlanCount}_TAPS"
            planFullySafe -> "RECOVERY_SAFE_PLAN_${bestPlanCount}_TAP"
            bestRescuePlan != null && bestPlanCount >= 3 -> "RECOVERY_SAME_SIDE_PLAN_${bestPlanCount}_TAPS"
            bestRescuePlan != null -> "RECOVERY_SAME_SIDE_PLAN_${bestPlanCount}_TAP"
            else -> "WAIT_NO_NONWORSENING_PLAN"
        }
        attemptLastDecision = decisionReason
        if (shouldTap) attemptFlapDecisions++ else attemptWaitDecisions++
        appendRunLog(
            "FRAME bird=${d.birdY} box=${d.birdBounds} v=${fmt(velocity)} raw=${fmt(rawVelocity)} g=${fmt(gravity)}" +
                " pipeX=${d.obstacleX} pipeBox=${d.pipeBounds} runs=${d.pipeRuns} score=${fmt(d.pipeConfidence)} gap=${gapTop.toInt()}..${gapBottom.toInt()}" +
                " dist=${distance.toInt()} speed=${fmt(obstacleSpeed)} t=${timeToPipe.toInt()} overlap=${collisionDuration.toInt()}ms" +
                " wait100=${wait100.toInt()} wait200=${wait200.toInt()} wait300=${wait300.toInt()} wait400=${wait400.toInt()}" +
                " zeroEntry/Mid/Exit=${zeroEntryY.toInt()}/${zeroMiddleY.toInt()}/${zeroExitY.toInt()}" +
                " planN=$bestPlanCount firstDelay=${firstTapDelay.toInt()}ms intervals=$scheduleText" +
                " planEntry/Mid/Exit=${bestPlanEntryY.toInt()}/${bestPlanMiddleY.toInt()}/${bestPlanExitY.toInt()}" +
                " miss0/plan=${zeroWorstMiss.toInt()}/${bestPlanMiss.toInt()}" +
                " actionCandidates={$actionCandidatesText} selected=${if (shouldTap) "FLAP" else "WAIT"} " +
                " planSafety=${if (planFullySafe) "FULL" else if (bestRescuePlan != null) "SAME_SIDE_RESCUE" else "NONE"}" +
                " safe=${safeTop.toInt()}..${safeBottom.toInt()} flapV=${fmt(estimatedFlapVelocity)}" +
                " candidates={$scenarioText} schedules=$schedulesEvaluated elapsedSinceImpulse=${if (lastAutoTapAt > 0L) elapsedSinceLastImpulse.toInt() else -1}ms" +
                " nextImpulseDelay=${firstTapDelay.toInt()}ms intervalReady=$tapWindowReady " +
                "pendingTapIn=${if (pendingPipeTapAt > 0L && pendingPipeTapTrackId == activePipeTrackId) (pendingPipeTapAt - now).coerceAtLeast(0L) else -1L}ms " +
                "feedbackPending=$tapFeedbackPending feedbackResolved=$hadTapFeedback " +
                "tapSpacingReady=$tapSpacingReady feedbackAge=${if (lastTapFeedbackAt > 0L) now - lastTapFeedbackAt else -1L}ms" +
                " fallback=${if (fallbackAction == RL_FLAP) "FLAP" else "WAIT"}" +
                " q=${fmt(qFlap)}/${fmt(qWait)} visits=$qFlapVisits/$qWaitVisits qAction=${if (qAction == RL_FLAP) "FLAP" else "WAIT"}" +
                " recoveryPossible=$recoveryPossible predictedCollision=${zeroWorstMiss > 0f} " +
                " gestureBusy=${gestureInFlight.get()} pipePassed=$pipePassed decision=$decisionReason"
        )
        var actualAction = RL_WAIT
        if (shouldTap) {
            screenStatus = "КОРРЕКЦИЯ • план $bestPlanCount тап(ов) • интервалы $scheduleText • окно " +
                safeTop.toInt() + ".." + safeBottom.toInt()
            if (tap(tapX, tapY, "AUTO", velocity, d.birdY)) {
                actualAction = RL_FLAP
                lastAutoTapAt = now
                val scheduledResponseIn = if (pendingPipeTapAt > 0L &&
                    pendingPipeTapTrackId == activePipeTrackId) (pendingPipeTapAt - now).coerceAtLeast(0L) else -1L
                pendingPipeTapAt = 0L
                pendingPipeTapTrackId = -1
                autoTapCount++
                attemptAutoTaps++
                attemptLastAction = "FLAP_REQUESTED"
                appendRunLog(
                    "ACTION auto_tap accepted id=$lastTapRequestId count=$autoTapCount " +
                        "plan=$bestPlanCount firstDelay=${firstTapDelay.toInt()}ms " +
                        "scheduledResponseIn=${scheduledResponseIn}ms intervals=$scheduleText"
                )
            } else {
                attemptLastAction = "FLAP_REJECTED"
                appendRunLog("ACTION auto_tap rejected reason=see_TAP_REJECTED_EVENT")
            }
        } else {
            attemptLastAction = if (tapFeedbackPending) "WAIT_FEEDBACK" else "WAIT"
            val phase = when { velocity < -0.02f -> "ПОДЪЁМ"; velocity > 0.02f -> "ПАДЕНИЕ"; else -> "ВЕРШИНА ДУГИ" }
            screenStatus = phase + " • y=" + d.birdY + " • v=" + fmt(velocity) + " • t=" + timeToPipe.toInt() +
                "мс • прогноз=" + zeroEntryY.toInt() + " • окно=" + safeTop.toInt() + ".." + safeBottom.toInt()
        }
        observeQLearningDecision(stateKey, actualAction, now, pipePassed)
    }

    private fun startMonitoringInternal() {
        ensureFlapCalibrationLoaded()
        ensureQLearningLoaded()
        if (instance == null) {
            screenStatus = "Включи службу специальных возможностей"
            return
        }
        manual = false
        paused = false
        monitoring = true
        clearRunLogForNewSession()
        runStartedAt = SystemClock.elapsedRealtime()
        attemptCount = 0
        appendRunLog("=== СЕССИЯ МОНИТОРИНГА ===")
        appendRunLog("SESSION_START id=${System.currentTimeMillis()} format=2")
        appendRunLog("RL_TABLE_SESSION status=" + qLearningMetaStatus + " revision=" +
            qLearningRevision + " states=" + qLearningTable.size + " updatedAt=" +
            qLearningUpdatedAt + " checksum=" + qLearningChecksum +
            " totalPipes=" + qLearningTotalPipePasses + " totalEpisodes=" + qLearningTotalEpisodes)
        appendRunLog("FLAP_CALIBRATION_SESSION value=" + fmt(estimatedFlapVelocity) + " loaded=" + flapCalibrationLoaded)
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
        previousBirdX = -1
        baselineBirdWidth = -1f
        baselineBirdHeight = -1f
        lastBirdScaleMode = "UNKNOWN"
        previousBirdAt = 0L
        lastAutoTapAt = 0L
        lastTapFeedbackAt = 0L
        previousObstacleX = -1
        previousObstacleAt = 0L
        smoothedObstacleSpeed = 0f
        previousVerticalVelocity = 0f
        previousRawVerticalVelocity = 0f
        ensureFlapCalibrationLoaded()
        estimatedGravity = 0.0018f
        gravitySamples.clear()
        flapImpulseSamples.clear()
        resetQLearningAttempt()
        synchronized(tapObservationLock) { pendingTapObservation = null }
        lastSceneSeenAt = 0L
        screenStatus = "АВТОПИЛОТ ВКЛЮЧЁН • жду игровую сцену • громкость − = СТОП"
        mainHandler.removeCallbacks(monitorTask)
        mainHandler.removeCallbacks(eventScanTask)
        screenStatus = "АВТОПИЛОТ ВКЛЮЧЁН • ожидаю MediaProjection • громкость − = СТОП"
    }

    private fun stopMonitoring(reason: String = "Мониторинг остановлен") {
        finishQLearningAttempt(false)
        finishAttemptDiagnostics("STOPPED")
        if (wasActiveGame && attemptCount > 0) recordAttemptEnd("INTERRUPTED:" + reason)
        wasActiveGame = false
        appendRunLog("SESSION_END attemptsStarted=" + attemptCount + " reason=" + reason)
        flushRunLogToDisk()
        monitoring = false
        paused = true
        manual = true
        mainHandler.removeCallbacks(monitorTask)
        mainHandler.removeCallbacks(eventScanTask)
        screenStatus = reason
    }

    fun tap(
        x: Float,
        y: Float,
        source: String = "AUTO",
        baselineVelocity: Float = previousVerticalVelocity,
        baselineY: Int = previousBirdY
    ): Boolean {
        val tapId = ++tapSequence
        val requestedAt = SystemClock.elapsedRealtime()
        appendRunLog("TAP_REQUEST id=$tapId source=$source x=${x.toInt()} y=${y.toInt()}")
        if (Build.VERSION.SDK_INT < 24) {
            attemptRejectedTaps++
            appendRunLog("TAP_REJECTED id=$tapId reason=ANDROID_VERSION")
            return false
        }
        if (paused || manual || !monitoring) {
            attemptRejectedTaps++
            appendRunLog("TAP_REJECTED id=$tapId reason=MONITORING_INACTIVE paused=$paused manual=$manual")
            return false
        }
        if (!gestureInFlight.compareAndSet(false, true)) {
            attemptRejectedTaps++
            appendRunLog("TAP_REJECTED id=$tapId reason=GESTURE_IN_FLIGHT")
            return false
        }
        lastTapRequestId = tapId
        synchronized(tapObservationLock) {
            pendingTapObservation = PendingTapObservation(tapId, source, requestedAt, baselineVelocity, baselineY)
        }
        appendRunLog("TAP_QUEUED id=$tapId source=$source")
        mainHandler.post {
            if (paused || manual || !monitoring) {
                gestureInFlight.set(false)
                attemptRejectedTaps++
                synchronized(tapObservationLock) {
                    if (pendingTapObservation?.id == tapId) pendingTapObservation = null
                }
                appendRunLog("TAP_REJECTED id=$tapId reason=MONITORING_STOPPED_BEFORE_DISPATCH")
                return@post
            }
            val path = Path().apply { moveTo(x, y) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 55))
                .build()
            val dispatchAt = SystemClock.elapsedRealtime()
            appendRunLog("GESTURE_DISPATCH_REQUEST id=$tapId source=$source")
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    lastTapStatus = "TAP OK"
                    lastGestureCompletedAt = SystemClock.elapsedRealtime()
                    attemptGestureCompleted++
                    synchronized(tapObservationLock) {
                        if (pendingTapObservation?.id == tapId) {
                            pendingTapObservation?.completedAt = lastGestureCompletedAt
                        }
                    }
                    appendRunLog("GESTURE_COMPLETED id=$tapId elapsed=${lastGestureCompletedAt - dispatchAt}ms awaiting_post_gesture_frame=true")
                    screenStatus = "ANDROID: КАСАНИЕ ВЫПОЛНЕНО"
                    gestureInFlight.set(false)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    lastTapStatus = "TAP CANCEL"
                    attemptGestureCancelled++
                    synchronized(tapObservationLock) {
                        if (pendingTapObservation?.id == tapId) pendingTapObservation = null
                    }
                    appendRunLog("GESTURE_CANCELLED id=$tapId elapsed=${SystemClock.elapsedRealtime() - dispatchAt}ms")
                    screenStatus = "ANDROID: КАСАНИЕ ОТМЕНЕНО"
                    gestureInFlight.set(false)
                }
            }, null)
            if (!accepted) {
                lastTapStatus = "TAP REJECTED"
                attemptRejectedTaps++
                synchronized(tapObservationLock) {
                    if (pendingTapObservation?.id == tapId) pendingTapObservation = null
                }
                appendRunLog("GESTURE_DISPATCH_REJECTED id=$tapId")
                screenStatus = "ANDROID: ЖЕСТ НЕ ПРИНЯТ"
                gestureInFlight.set(false)
            } else {
                appendRunLog("GESTURE_DISPATCH_ACCEPTED id=$tapId")
            }
        }
        return true
    }

    private fun fmt(value: Float): String {
        if (!value.isFinite()) return "NaN"
        val scaled = (value * 1000f).roundToInt()
        val magnitude = abs(scaled)
        val fraction = (magnitude % 1000).toString().padStart(3, '0')
        return (if (scaled < 0) "-" else "") + (magnitude / 1000) + "." + fraction
    }

    private fun runLogFile(): File = File(filesDir, RUN_LOG_FILE_NAME)

    private fun clearRunLogForNewSession() {
        synchronized(runLogLock) {
            runLog.clear()
            pendingRunLogLines.clear()
            runLogStorageError = null
            try {
                runLogFile().writeText("", Charsets.UTF_8)
            } catch (e: Exception) {
                runLogStorageError = e.javaClass.simpleName
            }
        }
    }

    // Flush in small batches so frame-by-frame diagnostics do not cause a disk write per frame.
    private fun flushPendingRunLogLocked() {
        if (pendingRunLogLines.isEmpty()) return
        try {
            FileOutputStream(runLogFile(), true).bufferedWriter(Charsets.UTF_8).use { writer ->
                for (line in pendingRunLogLines) writer.append(line).append('\n')
            }
            pendingRunLogLines.clear()
            runLogStorageError = null
        } catch (e: Exception) {
            runLogStorageError = e.javaClass.simpleName
        }
    }

    private fun flushRunLogToDisk() {
        synchronized(runLogLock) { flushPendingRunLogLocked() }
    }

    private fun appendRunLog(message: String) {
        val elapsed = if (runStartedAt > 0L) SystemClock.elapsedRealtime() - runStartedAt else 0L
        val line = "$elapsed ms | $message"
        synchronized(runLogLock) {
            if (runLog.size >= MAX_RUN_LOG_LINES) runLog.removeFirst()
            runLog.addLast(line)
            pendingRunLogLines.add(line)
            if (pendingRunLogLines.size >= RUN_LOG_FLUSH_BATCH_SIZE) flushPendingRunLogLocked()
        }
    }

    private fun exportRunLog(): String = synchronized(runLogLock) {
        flushPendingRunLogLocked()
        try {
            val file = runLogFile()
            if (file.exists() && file.length() > 0L) file.readText(Charsets.UTF_8)
            else if (runLog.isNotEmpty()) runLog.joinToString(separator = "\n")
            else "Лог пока пуст. Включи автопилот и запусти игру."
        } catch (e: Exception) {
            runLog.joinToString(separator = "\n") + "\nLOG_READ_ERROR=" + e.javaClass.simpleName
        }
    }

    private fun recordAttemptStart(detection: Detection) {
        attemptCount++
        appendRunLog("=== НАЧАЛО ПОПЫТКИ #" + attemptCount + " ===")
        appendRunLog("PLAY_START_DETECTED attempt=" + attemptCount + " trigger=ACTIVE_GAME_TRANSITION scene=" + detection.message)
        appendRunLog("ATTEMPT_START attempt=" + attemptCount + " source=GAMEPLAY_FRAME")
        flushRunLogToDisk()
    }

    private fun recordAttemptEnd(reason: String) {
        appendRunLog("ATTEMPT_END attempt=" + attemptCount + " result=" + reason + " passed=" + attemptPipePasses)
        flushRunLogToDisk()
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
        @Volatile var lastDetectionMs = 0L
        @Volatile var autoTapCount = 0
        @Volatile var lastFrameHeight = 0
        @Volatile var lastBirdX = -1
        @Volatile var lastBirdY = -1
        @Volatile var lastObstacleX = -1
        @Volatile var lastGapTop = -1
        @Volatile var lastGapBottom = -1
        @Volatile var lastTapStatus = "TAP —"
        @Volatile var learnedStateCount = 0
        @Volatile var learnedPipeCount = 0
        @Volatile var learnedRevision = 0L
        @Volatile var learnedUpdatedAt = 0L
        @Volatile var learnedChecksum = "EMPTY"
        const val RL_FLAP = 0
        const val RL_WAIT = 1
        private const val MIN_TAP_INTERVAL_MS = 250L
        private const val MAX_RUN_LOG_LINES = 50000
        private const val RUN_LOG_FLUSH_BATCH_SIZE = 64
        private const val RUN_LOG_FILE_NAME = "bird-play-session.txt"
        @Volatile private var instance: GameAccessibilityService? = null

        fun getRunLog(): String = instance?.exportRunLog() ?: "Служба Bird Play не подключена."

        fun getRunLog(context: Context): String {
            val activeService = instance
            if (activeService != null) return activeService.exportRunLog()
            val file = File(context.applicationContext.filesDir, RUN_LOG_FILE_NAME)
            return try {
                if (file.exists() && file.length() > 0L) file.readText(Charsets.UTF_8)
                else "Лог пока не создан. Включи автопилот и запусти игру."
            } catch (e: Exception) {
                "Не удалось прочитать журнал: " + e.javaClass.simpleName
            }
        }

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
