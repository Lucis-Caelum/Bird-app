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
    // Q-table JSON serialization and disk commit must never run on the frame-processing thread.
    private val learningPersistExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BirdPlayLearning").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanInFlight = AtomicBoolean(false)
    private val gestureInFlight = AtomicBoolean(false)
    // Reuse detector memory between frames; avoid allocating a full pixel buffer and scan arrays every frame.
    private var detectorPixels = IntArray(0)
    private var detectorColumnCounts = IntArray(0)
    private var detectorCrystalColumnCounts = IntArray(0)
    private var detectorCandidateColumns = BooleanArray(0)
    private var detectorVertical = BooleanArray(0)
    private var cachedGapPipeX = -1
    private var cachedGapPipeLeft = -1
    private var cachedGapPipeRight = -1
    private var cachedGapTop = -1
    private var cachedGapBottom = -1
    private var cachedGapAt = 0L
    private var cachedGapConfidence = 0f
    @Volatile private var nextScreenshotAllowedAt = 0L
    @Volatile private var screenshotIntervalMs = 650L
    private var previousBirdY = -1
    private var previousBirdX = -1
    private var previousObservedBirdWidth = -1
    private var previousObservedBirdHeight = -1
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
    private var trackedPipeGapTop = -1
    private var trackedPipeGapBottom = -1
    private var trackedPipeGapConfidence = 0f
    private var previousPassedGapTop = -1
    private var previousPassedGapBottom = -1
    private var previousPassedGapConfidence = 0f
    private var previousPassedPipeRightX = -1
    private var previousPassedPipeAt = 0L
    private var previousVerticalVelocity = 0f
    private var previousRawVerticalVelocity = 0f
    private var estimatedFlapVelocity = -0.55f
    private var flapCalibrationLoaded = false
    private val flapImpulseSamples = ArrayDeque<Float>()
    private var estimatedGravity = 0.0018f
    // Planned impulse time is a predicted in-game response time, not a blind tap timer.
    private var pendingPipeTapAt = 0L
    private var pendingPipeTapTrackId = -1
    private var estimatedTapResponseLatencyMs = 68f
    private var tapLatencySamples = 0
    private var scheduledInterceptRunnable: Runnable? = null
    private var scheduledInterceptTrackId = -1
    private var scheduledInterceptAt = 0L
    private var lastInterceptPredictionTrackId = -1
    private var lastInterceptPredictedY = Float.NaN
    private var lastInterceptPredictedVelocity = Float.NaN
    private var lastInterceptPredictionAt = 0L
    private var lastInterceptActualLoggedTrackId = -1
    private data class PendingTapObservation(
        val id: Long,
        val source: String,
        val requestedAt: Long,
        val baselineVelocity: Float,
        val baselineY: Int,
        @Volatile var completedAt: Long = 0L,
        var framesObserved: Int = 0
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
    private data class QSnapshotRow(
        val state: String,
        val flapValue: Float,
        val waitValue: Float,
        val flapVisits: Int,
        val waitVisits: Int,
        val safeExploreTried: Boolean
    )
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
    private var sameSideRescueTapCount = 0
    private var tapsOnActivePipe = 0
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
                        if (!wasActiveGame) clearGapCache()
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
        val frameStartedAt = SystemClock.elapsedRealtime()
        val safeCropTop = cropTop.coerceIn(0, frame.height - 1)
        val fieldHeight = (frame.height - safeCropTop).coerceAtLeast(1)
        if (!wasActiveGame) clearGapCache()
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
            val controlStartedAt = SystemClock.elapsedRealtime()
            handleAutoControl(detection, frame.width, fieldHeight, safeCropTop, screenW, screenH)
            lastControlMs = SystemClock.elapsedRealtime() - controlStartedAt
        } else {
            lastControlMs = 0L
            screenStatus = "ПРЕДПРОСМОТР • " + detection.message
        }
        lastFrameWidth = frame.width
        lastFrameHeight = fieldHeight
        lastBirdX = detection.birdX
        lastBirdY = detection.birdY
        lastObstacleX = detection.obstacleX
        lastGapTop = detection.gapTop
        lastGapBottom = detection.gapBottom
        val frameProcessingMs = SystemClock.elapsedRealtime() - frameStartedAt
        if (monitoring && frameProcessingMs >= PERFORMANCE_LOG_THRESHOLD_MS) {
            appendRunLog(
                "PERFORMANCE frameMs=$frameProcessingMs cvMs=$lastDetectionMs " +
                    "controlMs=$lastControlMs targetFrameMs=80"
            )
        }
        screenStatus = "$screenStatus • CV ${lastDetectionMs}мс • CTRL ${lastControlMs}мс"
    }

    private data class Detection(
        val birdX: Int = -1, val birdY: Int = -1,
        val obstacleX: Int = -1, val gapTop: Int = -1, val gapBottom: Int = -1,
        val activeGame: Boolean = false, val message: String,
        val birdBounds: String = "NONE", val pipeBounds: String = "NONE",
        val birdLeft: Int = -1, val birdRight: Int = -1,
        val birdTop: Int = -1, val birdBottom: Int = -1,
        val pipeLeft: Int = -1, val pipeRight: Int = -1,
        val pipeMotionX: Int = -1,
        val pipeRuns: String = "NONE", val pipeConfidence: Float = 0f,
        val birdScale: String = "UNKNOWN"
    )

    private fun resetPipeGeometryMemory() {
        trackedPipeGapTop = -1
        trackedPipeGapBottom = -1
        trackedPipeGapConfidence = 0f
        previousPassedGapTop = -1
        previousPassedGapBottom = -1
        previousPassedGapConfidence = 0f
        previousPassedPipeRightX = -1
        previousPassedPipeAt = 0L
        activePipeTrackId = 0
        sameSideRescueTapCount = 0
        tapsOnActivePipe = 0
        lastTrackedPipeX = -1
        lastTrackedPipeRightX = -1
        lastTrackedPipeAt = 0L
        trackedPipeNearBird = false
        lastPipeTrackGap = "NONE"
        lastInterceptPredictionTrackId = -1
        lastInterceptPredictedY = Float.NaN
        lastInterceptPredictedVelocity = Float.NaN
        lastInterceptPredictionAt = 0L
        lastInterceptActualLoggedTrackId = -1
    }

    private fun rememberTrackedPipeAsPassed(rightX: Int, now: Long, source: String) {
        if (trackedPipeGapTop < 0 || trackedPipeGapBottom <= trackedPipeGapTop || rightX < 0) return
        previousPassedGapTop = trackedPipeGapTop
        previousPassedGapBottom = trackedPipeGapBottom
        previousPassedGapConfidence = trackedPipeGapConfidence
        previousPassedPipeRightX = rightX
        previousPassedPipeAt = now
        appendRunLog(
            "PIPE_GAP_MEMORY source=$source gap=${previousPassedGapTop}..${previousPassedGapBottom} " +
                "rightX=$rightX confidence=${fmt(previousPassedGapConfidence)}"
        )
    }

    private fun clearGapCache() {
        cachedGapPipeX = -1
        cachedGapPipeLeft = -1
        cachedGapPipeRight = -1
        cachedGapTop = -1
        cachedGapBottom = -1
        cachedGapAt = 0L
        cachedGapConfidence = 0f
    }

    private fun seedBirdSizeBaseline(d: Detection, source: String) {
        val width = d.birdRight - d.birdLeft
        val height = d.birdBottom - d.birdTop
        if (d.birdLeft < 0 || d.birdTop < 0 || width <= 0 || height <= 0) {
            appendRunLog("BIRD_SCALE_BASELINE source=$source status=WAITING_FOR_BOUNDS")
            return
        }
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

        if (!activeGame) return Detection(message = "Меню / отсчёт • ожидаю игровое поле")

        // One 1-in-3 sampled pass computes both avatar bounds and pipe-column occupancy.
        // This keeps CV O(W*H) but avoids a second full sampled scan over overlapping regions.
        var minBirdX = w
        var maxBirdX = 0
        var minBirdY = h
        var maxBirdY = 0
        var birdPixels = 0
        if (detectorColumnCounts.size < sampleW) detectorColumnCounts = IntArray(sampleW)
        if (detectorCrystalColumnCounts.size < sampleW) detectorCrystalColumnCounts = IntArray(sampleW)
        val columnCounts = detectorColumnCounts
        val crystalColumnCounts = detectorCrystalColumnCounts
        columnCounts.fill(0, 0, sampleW)
        crystalColumnCounts.fill(0, 0, sampleW)
        val birdX0 = (w * 0.07f).toInt()
        val birdX1 = (w * 0.46f).toInt()
        val birdY0 = (h * 0.12f).toInt()
        val birdY1 = (h * 0.97f).toInt()
        val pipeScanX0 = (w * 0.12f).toInt()
        for (sy in 0 until sampleH) {
            val y = sy * step
            val scanBirdRow = y >= birdY0 && y < birdY1
            for (sx in 0 until sampleW) {
                val x = sx * step
                val scanBird = scanBirdRow && x >= birdX0 && x < birdX1
                val scanPipe = x >= pipeScanX0
                if (!scanBird && !scanPipe) continue
                val c = detectorPixels[y * w + x]
                val r = (c ushr 16) and 255
                val gg = (c ushr 8) and 255
                val bl = c and 255
                if (scanBird && bl > 55 && bl > r * 1.18 && bl > gg * 1.04 && r < 145 && gg < 170) {
                    minBirdX = min(minBirdX, x)
                    maxBirdX = max(maxBirdX, x)
                    minBirdY = min(minBirdY, y)
                    maxBirdY = max(maxBirdY, y)
                    birdPixels++
                }
                if (scanPipe) {
                    val purplePipePixel = r in 48..190 && bl in 48..195 && gg < 115 &&
                        r > gg * 1.18 && bl > gg * 1.18
                    // Old CV saw the purple body but ignored the bright red/orange crystal caps.
                    val orangeCrystalPixel = r >= 125 && gg in 28..190 && bl <= 150 &&
                        r > gg * 1.12 && r > bl * 1.18
                    if (purplePipePixel) columnCounts[sx]++
                    if (orangeCrystalPixel) crystalColumnCounts[sx]++
                }
            }
        }
        val detectedBirdWidth = (maxBirdX - minBirdX).coerceAtLeast(0)
        val detectedBirdHeight = (maxBirdY - minBirdY).coerceAtLeast(0)
        val rawBirdX = if (birdPixels > 0) (minBirdX + maxBirdX) / 2 else -1
        val rawBirdY = if (birdPixels > 0) (minBirdY + maxBirdY) / 2 else -1
        val baselineKnown = baselineBirdWidth > 0f && baselineBirdHeight > 0f
        val baselineAspect = if (baselineKnown) baselineBirdWidth / baselineBirdHeight else 0f
        val detectedAspect = if (detectedBirdHeight > 0) detectedBirdWidth.toFloat() / detectedBirdHeight else 0f
        val plausibleAspect = !baselineKnown ||
            detectedAspect in (baselineAspect * 0.55f)..(baselineAspect * 1.80f)
        val plausibleNormalShape = !baselineKnown || (
            detectedBirdWidth >= baselineBirdWidth * 0.55f &&
                detectedBirdHeight >= baselineBirdHeight * 0.55f && plausibleAspect
            )
        val plausibleReducedShape = !baselineKnown || (
            detectedBirdWidth >= baselineBirdWidth * 0.25f &&
                detectedBirdHeight >= baselineBirdHeight * 0.25f && plausibleAspect
            )
        val normalBirdFound = activeGame && birdPixels >= 24 &&
            detectedBirdWidth >= 12 && detectedBirdHeight >= 18 && plausibleNormalShape
        // Accept a reduced sprite only near the previous bird and with a plausible aspect ratio.
        val closeToPreviousBird = previousBirdX >= 0 && previousBirdY >= 0 &&
            rawBirdX >= 0 && rawBirdY >= 0 &&
            kotlin.math.abs(rawBirdX - previousBirdX) <= max(28, (w * 0.06f).toInt()) &&
            kotlin.math.abs(rawBirdY - previousBirdY) <= max(320, (h * 0.18f).toInt())
        val reducedBirdFound = activeGame && closeToPreviousBird &&
            birdPixels >= 5 && detectedBirdWidth >= 4 && detectedBirdHeight >= 6 &&
            plausibleReducedShape
        val birdFound = normalBirdFound || reducedBirdFound
        val birdX = if (birdFound) rawBirdX else -1
        val birdY = if (birdFound) rawBirdY else -1
        val birdLeft = if (birdFound) minBirdX else -1
        val birdRight = if (birdFound) maxBirdX else -1
        val birdTop = if (birdFound) minBirdY else -1
        val birdBottom = if (birdFound) maxBirdY else -1
        val visuallyShrunk = baselineKnown && plausibleReducedShape &&
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
        // Pipe columns were collected in the same pass above; use the sampled grid below.

        // Pipe occupancy is already present in columnCounts from the merged sampled scan.
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

        val maxCrystalExtension = (w * 0.065f).toInt().coerceAtLeast(step)
        val weakTipSupport = max(2, (sampleH * 0.0035f).toInt())
        var collisionLeft = nearest.first
        var leftExtension = 0
        while (collisionLeft - step >= 0 && leftExtension < maxCrystalExtension) {
            val candidateIndex = ((collisionLeft - step) / step).coerceIn(0, sampleW - 1)
            val support = columnCounts[candidateIndex] + crystalColumnCounts[candidateIndex]
            if (support < weakTipSupport) break
            collisionLeft -= step
            leftExtension += step
        }
        var collisionRight = nearest.second
        var rightExtension = 0
        while (collisionRight < w && rightExtension < maxCrystalExtension) {
            val candidateIndex = (collisionRight / step).coerceIn(0, sampleW - 1)
            val support = columnCounts[candidateIndex] + crystalColumnCounts[candidateIndex]
            if (support < weakTipSupport) break
            collisionRight = (collisionRight + step).coerceAtMost(w)
            rightExtension += step
        }
        val pipeLeadingEdgeX = collisionLeft
        val now = SystemClock.elapsedRealtime()
        fun reuseRecentGap(reason: String): Detection? {
            val age = if (cachedGapAt > 0L) now - cachedGapAt else Long.MAX_VALUE
            val movement = cachedGapPipeX - nearest.first
            val samePipe = cachedGapPipeX >= 0 &&
                movement >= -(w * 0.025f).toInt() &&
                movement <= (w * 0.26f).toInt() &&
                kotlin.math.abs((cachedGapPipeRight - cachedGapPipeLeft) - (collisionRight - collisionLeft)) <= (w * 0.07f).toInt()
            if (!samePipe || age !in 0L..260L ||
                cachedGapTop < 0 || cachedGapBottom <= cachedGapTop || cachedGapBottom > h) return null
            val confidence = (cachedGapConfidence * (if (age <= 150L) 0.92f else 0.82f))
                .coerceIn(0.42f, 0.88f)
            return Detection(
                birdX = birdX, birdY = birdY, obstacleX = pipeLeadingEdgeX,
                gapTop = cachedGapTop, gapBottom = cachedGapBottom,
                activeGame = true,
                message = "ТРУБА x=$pipeLeadingEdgeX • просвет сохранён из соседнего кадра ($reason)",
                birdBounds = if (birdFound) "$minBirdX..$maxBirdX,$minBirdY..$maxBirdY" else "NONE",
                pipeBounds = "${collisionLeft}..${collisionRight}",
                birdLeft = birdLeft, birdRight = birdRight, birdTop = birdTop, birdBottom = birdBottom,
                pipeLeft = collisionLeft, pipeRight = collisionRight, pipeMotionX = nearest.first,
                pipeRuns = "CACHED:$cachedGapTop..$cachedGapBottom",
                pipeConfidence = confidence,
                birdScale = birdScale
            )
        }
        if (detectorVertical.size < sampleH) detectorVertical = BooleanArray(sampleH)
        val vertical = detectorVertical
        vertical.fill(false, 0, sampleH)
        val pipeSampleXs = IntArray(9) { i ->
            (nearest.first + (nearest.second - nearest.first) * (0.02f + i * 0.12f))
                .toInt().coerceIn(0, w - 1)
        }
        var crystalRowsDetected = 0
        for (sy in 0 until sampleH) {
            val y = sy * step
            var purpleVotes = 0
            var crystalVotes = 0
            for (x in pipeSampleXs) {
                val c = detectorPixels[y * w + x]
                val r = (c ushr 16) and 255
                val gg = (c ushr 8) and 255
                val bl = c and 255
                val purplePipePixel = r in 48..190 && bl in 48..195 && gg < 115 &&
                    r > gg * 1.18 && bl > gg * 1.18
                val orangeCrystalPixel = r >= 125 && gg in 28..190 && bl <= 150 &&
                    r > gg * 1.12 && r > bl * 1.18
                if (purplePipePixel) purpleVotes++
                if (orangeCrystalPixel) crystalVotes++
            }
            // Purple body votes define the pipe wall. Orange crystals are sparse;
            // require support from several x samples so one orange pixel cannot split
            // the entire vertical opening into fake wall segments.
            vertical[sy] = purpleVotes >= 3 || crystalVotes >= 3
            if (crystalVotes >= 1) crystalRowsDetected++
        }
        val rawRuns = mutableListOf<Pair<Int, Int>>()
        var runStart = -1
        for (i in 0 until sampleH) {
            if (vertical[i]) {
                if (runStart < 0) runStart = i
            } else if (runStart >= 0) {
                if ((i - runStart) * step >= h * 0.025f) {
                    rawRuns.add(runStart * step to i * step)
                }
                runStart = -1
            }
        }
        if (runStart >= 0 && (sampleH - runStart) * step >= h * 0.025f) {
            rawRuns.add(runStart * step to h)
        }

        // Pipe artwork contains narrow horizontal texture breaks. They are not real openings:
        // merge adjacent vertical runs separated by <=2.5% of field height before choosing
        // the largest gap. Previously 3–9 px stripe breaks counted as extra "pipes",
        // depressing confidence to ~0.328 and disabling the controller during approach.
        val runMergeTolerance = max(step * 2, (h * 0.025f).toInt())
        val runs = ArrayList<Pair<Int, Int>>(rawRuns.size)
        for (run in rawRuns) {
            val previous = runs.lastOrNull()
            if (previous != null && run.first - previous.second <= runMergeTolerance) {
                runs[runs.lastIndex] = previous.first to run.second
            } else {
                runs.add(run)
            }
        }
        if (runs.size < 2) {
            reuseRecentGap("RUNS_" + runs.size)?.let { return it }
            return Detection(
                birdX = birdX, birdY = birdY, obstacleX = pipeLeadingEdgeX,
                activeGame = true,
                message = "ТРУБА x=$pipeLeadingEdgeX • ищу верхнюю/нижнюю часть",
                birdBounds = if (birdFound) "$minBirdX..$maxBirdX,$minBirdY..$maxBirdY" else "NONE",
                pipeBounds = "${collisionLeft}..${collisionRight}",
                birdLeft = birdLeft, birdRight = birdRight, birdTop = birdTop, birdBottom = birdBottom,
                pipeLeft = collisionLeft, pipeRight = collisionRight, pipeMotionX = nearest.first,
                pipeRuns = "raw=${rawRuns.size}->merged=${runs.size}:crystalRows=$crystalRowsDetected " +
                    "tipExt=${leftExtension}/${rightExtension}:" +
                    runs.joinToString(";") { "${it.first}..${it.second}" },
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
            reuseRecentGap("GAP_SIZE_" + largestGap)?.let { return it }
            return Detection(
                birdX = birdX, birdY = birdY, obstacleX = pipeLeadingEdgeX,
                activeGame = true,
                message = "ТРУБА x=$pipeLeadingEdgeX • просвет не подтверждён",
                birdBounds = if (birdFound) "$minBirdX..$maxBirdX,$minBirdY..$maxBirdY" else "NONE",
                pipeBounds = "${collisionLeft}..${collisionRight}",
                birdLeft = birdLeft, birdRight = birdRight, birdTop = birdTop, birdBottom = birdBottom,
                pipeLeft = collisionLeft, pipeRight = collisionRight, pipeMotionX = nearest.first,
                pipeRuns = "raw=${rawRuns.size}->merged=${runs.size}:crystalRows=$crystalRowsDetected " +
                    "tipExt=${leftExtension}/${rightExtension}:" +
                    runs.joinToString(";") { "${it.first}..${it.second}" },
                pipeConfidence = 0.42f,
                birdScale = birdScale
            )
        }
        // Keep detector geometry raw. The collision planner applies the bird-aware
        // safety margin once; shrinking here too would erode the opening twice.
        val runShapeScore = when {
            runs.size == 2 -> 0.90f
            runs.size > 2 -> (0.82f - (runs.size - 2) * 0.08f).coerceAtLeast(0.52f)
            else -> 0.30f
        }
        val gapShapeScore = (1f - kotlin.math.abs(largestGap.toFloat() / h - 0.32f) * 0.45f)
            .coerceIn(0.58f, 1f)
        var pipeConfidence = (runShapeScore * gapShapeScore).coerceIn(0.30f, 0.94f)
        val previousTrackAge = if (lastTrackedPipeAt > 0L) now - lastTrackedPipeAt else Long.MAX_VALUE
        val sameTrackedPipe = lastTrackedPipeX >= 0 && previousTrackAge in 0L..450L &&
            nearest.first <= lastTrackedPipeX + (w * 0.025f).toInt() &&
            lastTrackedPipeX - nearest.first <= (w * 0.26f).toInt()
        if (sameTrackedPipe && trackedPipeGapTop >= 0 && trackedPipeGapBottom > trackedPipeGapTop) {
            val topDelta = abs(gapTop - trackedPipeGapTop)
            val bottomDelta = abs(gapBottom - trackedPipeGapBottom)
            val jumpLimit = max(24, (h * 0.045f).toInt())
            if (topDelta > jumpLimit || bottomDelta > jumpLimit) {
                val strongerTwoWallGeometry = runs.size == 2 &&
                    pipeConfidence >= trackedPipeGapConfidence + 0.12f
                if (strongerTwoWallGeometry) {
                    appendRunLog(
                        "GAP_GEOMETRY_REACQUIRED pipeX=${nearest.first} old=${trackedPipeGapTop}..${trackedPipeGapBottom} " +
                            "new=${gapTop}..${gapBottom} oldConfidence=${fmt(trackedPipeGapConfidence)} " +
                            "newConfidence=${fmt(pipeConfidence)} reason=STRONG_TWO_WALL_DETECTION"
                    )
                } else {
                    appendRunLog(
                        "GAP_GEOMETRY_REJECTED pipeX=${nearest.first} detected=${gapTop}..${gapBottom} " +
                            "held=${trackedPipeGapTop}..${trackedPipeGapBottom} " +
                            "delta=${topDelta}/${bottomDelta} limit=$jumpLimit age=${previousTrackAge}ms"
                    )
                    gapTop = trackedPipeGapTop
                    gapBottom = trackedPipeGapBottom
                    pipeConfidence = min(pipeConfidence, trackedPipeGapConfidence).coerceAtLeast(0.30f)
                }
            } else if (topDelta > 3 || bottomDelta > 3) {
                val rawTop = gapTop
                val rawBottom = gapBottom
                gapTop = (trackedPipeGapTop * 0.72f + gapTop * 0.28f).toInt()
                gapBottom = (trackedPipeGapBottom * 0.72f + gapBottom * 0.28f).toInt()
                appendRunLog(
                    "GAP_GEOMETRY_SMOOTHED pipeX=${nearest.first} raw=${rawTop}..${rawBottom} " +
                        "stable=${gapTop}..${gapBottom} alpha=0.28"
                )
            }
        }
        cachedGapPipeX = nearest.first
        cachedGapPipeLeft = collisionLeft
        cachedGapPipeRight = collisionRight
        cachedGapTop = gapTop
        cachedGapBottom = gapBottom
        cachedGapAt = now
        cachedGapConfidence = pipeConfidence
        return Detection(
            birdX = birdX,
            birdY = birdY,
            obstacleX = pipeLeadingEdgeX,
            gapTop = gapTop,
            gapBottom = gapBottom,
            activeGame = true,
            message = "ПТИЦА x=$birdX y=$birdY • ТРУБА x=$pipeLeadingEdgeX • ПРОХОД $gapTop..$gapBottom",
            birdBounds = "$minBirdX..$maxBirdX,$minBirdY..$maxBirdY",
            pipeBounds = "${collisionLeft}..${collisionRight}",
            birdLeft = birdLeft, birdRight = birdRight, birdTop = birdTop, birdBottom = birdBottom,
            pipeLeft = collisionLeft, pipeRight = collisionRight, pipeMotionX = nearest.first,
            pipeRuns = "raw=${rawRuns.size}->merged=${runs.size}:crystalRows=$crystalRowsDetected " +
                    "tipExt=${leftExtension}/${rightExtension}:" +
                    runs.joinToString(";") { "${it.first}..${it.second}" },
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
            if (rewardSchema < RL_REWARD_SCHEMA) {
                val obsoleteStates = qLearningTable.size
                qLearningTable.clear()
                qLearningChecksum = "EMPTY"
                qLearningMetaStatus = "REWARD_SCHEMA_RESET"
                learnedStateCount = 0
                learnedRevision = qLearningRevision
                learnedPipeCount = qLearningTotalPipePasses
                learnedUpdatedAt = qLearningUpdatedAt
                learnedChecksum = "EMPTY"
                appendRunLog(
                    "RL_TABLE_RESET oldRewardSchema=" + rewardSchema +
                        " newRewardSchema=" + RL_REWARD_SCHEMA +
                        " obsoleteStates=" + obsoleteStates +
                        " reason=previous_Q_values_were_not_connected_to_control"
                )
                persistQLearningTable()
                return
            }
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
        // Copy only primitive row data on the frame thread; JSON construction and durable commit
        // are serialized on a dedicated worker so table size cannot stall CV/control.
        val snapshot = ArrayList<QSnapshotRow>(qLearningTable.size)
        for ((state, entry) in qLearningTable) {
            snapshot.add(
                QSnapshotRow(
                    state,
                    entry.values[RL_FLAP],
                    entry.values[RL_WAIT],
                    entry.visits[RL_FLAP],
                    entry.visits[RL_WAIT],
                    entry.safeExploreTried
                )
            )
        }
        val revision = qLearningRevision + 1L
        val updatedAt = System.currentTimeMillis()
        val totalPipes = qLearningTotalPipePasses
        val totalEpisodes = qLearningTotalEpisodes
        val prefs = getSharedPreferences("bird_play_learning", MODE_PRIVATE)
        qLearningRevision = revision
        qLearningUpdatedAt = updatedAt
        qLearningMetaStatus = "SAVE_QUEUED"
        learnedRevision = revision
        learnedUpdatedAt = updatedAt
        learnedStateCount = snapshot.size
        learnedPipeCount = totalPipes
        try {
            learningPersistExecutor.execute {
                try {
                    val json = JSONObject()
                    for (row in snapshot) {
                        val values = JSONArray()
                            .put(row.flapValue.toDouble())
                            .put(row.waitValue.toDouble())
                            .put(row.flapVisits)
                            .put(row.waitVisits)
                            .put(if (row.safeExploreTried) 1 else 0)
                        json.put(row.state, values)
                    }
                    val serialized = json.toString()
                    val checksum = tableChecksum(serialized)
                    val meta = JSONObject()
                        .put("schema", 1)
                        .put("reward_schema", RL_REWARD_SCHEMA)
                        .put("revision", revision)
                        .put("updated_at", updatedAt)
                        .put("states", snapshot.size)
                        .put("total_pipe_passes", totalPipes)
                        .put("total_episodes", totalEpisodes)
                        .put("checksum", checksum)
                    val committed = prefs.edit()
                        .putString("q_table", serialized)
                        .putString("q_table_meta", meta.toString())
                        .commit()
                    if (!committed) throw IllegalStateException("SharedPreferences commit returned false")
                    qLearningChecksum = checksum
                    qLearningMetaStatus = "SAVED"
                    learnedChecksum = checksum
                    appendRunLog(
                        "RL_TABLE_SAVED revision=" + revision + " states=" + snapshot.size +
                            " episodes=" + totalEpisodes + " totalPipes=" + totalPipes +
                            " checksum=" + checksum + " commit=BACKGROUND_OK"
                    )
                } catch (e: Exception) {
                    qLearningMetaStatus = "SAVE_ERROR"
                    appendRunLog("RL table background save error=" + e.javaClass.simpleName)
                }
            }
        } catch (e: Exception) {
            qLearningMetaStatus = "SAVE_ERROR"
            appendRunLog("RL table queue error=" + e.javaClass.simpleName)
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
        sameSideRescueTapCount = 0
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
        val entry = qLearningTable[state]
            ?: if (allowSafeExploration) qLearningTable.getOrPut(state) { QEntry() } else return fallback
        // Explore exactly once only in a state where both WAIT and FLAP were already
        // predicted to clear the complete pipe-overlap window.
        if (allowSafeExploration && entry.visits[RL_WAIT] >= 2 &&
            entry.visits[RL_FLAP] == 0 && !entry.safeExploreTried) {
            entry.safeExploreTried = true
            appendRunLog("RL_SAFE_EXPLORATION state=" + state + " action=FLAP waitVisits=" + entry.visits[RL_WAIT])
            return RL_FLAP
        }
        if (entry.visits[RL_FLAP] < RL_MIN_ACTION_VISITS ||
            entry.visits[RL_WAIT] < RL_MIN_ACTION_VISITS) return fallback
        val difference = entry.values[RL_FLAP] - entry.values[RL_WAIT]
        return when {
            difference > RL_Q_DECISION_MARGIN -> RL_FLAP
            difference < -RL_Q_DECISION_MARGIN -> RL_WAIT
            else -> fallback
        }
    }

    private fun observeQLearningDecision(state: String, action: Int, now: Long, pipePassed: Boolean) {
        val previousState = pendingRlState
        if (previousState != null) {
            val elapsed = (now - pendingRlAt).coerceIn(30L, 240L).toFloat()
            // Small survival shaping plus a real tap cost; pipe passage is the dominant
            // positive signal, while death is a strong terminal penalty.
            val survivalReward = RL_SURVIVAL_REWARD_PER_80_MS * (elapsed / 80f)
            val tapCost = if (pendingRlAction == RL_FLAP) RL_FLAP_ACTION_COST else 0f
            val reward = survivalReward + tapCost + if (pipePassed) RL_PIPE_PASS_REWARD else 0f
            qLearningEpisode.add(QTransition(previousState, pendingRlAction, reward, state))
            if (pipePassed) {
                updateQLearningEpisode("PIPE")
                qLearningEpisode.clear()
            } else if (qLearningEpisode.size >= RL_MAX_EPISODE_TRANSITIONS) {
                updateQLearningEpisode("CHUNK")
                qLearningEpisode.clear()
            }
        }
        pendingRlState = state
        pendingRlAction = action
        pendingRlAt = now
    }

    private fun finishQLearningAttempt(death: Boolean) {
        val state = pendingRlState
        if (death && state != null) {
            val tapCost = if (pendingRlAction == RL_FLAP) RL_FLAP_ACTION_COST else 0f
            qLearningEpisode.add(QTransition(state, pendingRlAction, RL_DEATH_PENALTY + tapCost, null))
        }
        if (death && (state != null || qLearningEpisode.isNotEmpty())) qLearningTotalEpisodes++
        pendingRlState = null
        pendingRlAt = 0L
        if (qLearningEpisode.isNotEmpty()) {
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
            val nextEntry = transition.nextState?.let { qLearningTable[it] }
            val nextValue = if (nextEntry == null) 0f else {
                val waitKnown = nextEntry.visits[RL_WAIT] > 0
                val flapKnown = nextEntry.visits[RL_FLAP] > 0
                when {
                    waitKnown && flapKnown -> max(nextEntry.values[RL_FLAP], nextEntry.values[RL_WAIT])
                    waitKnown -> nextEntry.values[RL_WAIT]
                    flapKnown -> nextEntry.values[RL_FLAP]
                    else -> 0f
                }
            }
            val target = transition.reward + RL_DISCOUNT * nextValue
            val action = transition.action.coerceIn(RL_FLAP, RL_WAIT)
            entry.values[action] += RL_LEARNING_RATE * (target - entry.values[action])
            entry.visits[action] = (entry.visits[action] + 1).coerceAtMost(1_000_000)
        }
        // The previous loop repeatedly scanned the whole table for each evicted row:
        // O(S * excess). Sort once and remove the least-visited excess rows: O(S log S).
        val excessStates = qLearningTable.size - RL_MAX_Q_STATES
        if (excessStates > 0) {
            val weakestKeys = qLearningTable.entries
                .sortedBy { it.value.visits[RL_FLAP] + it.value.visits[RL_WAIT] }
                .take(excessStates)
                .map { it.key }
            for (key in weakestKeys) qLearningTable.remove(key)
        }
        persistQLearningTable()
        appendRunLog("RL BACKWARD_UPDATE reason=" + reason + " states=" + qLearningEpisode.size + " table=" + qLearningTable.size + " pipes=" + qLearningPipePasses)
    }

    private fun trackPipePass(d: Detection, fieldWidth: Int, now: Long): Boolean {
        val currentX = d.pipeMotionX.takeIf { it >= 0 } ?: d.obstacleX
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
                cancelScheduledInterceptTap("PIPE_PASSED")
                rememberTrackedPipeAsPassed(lastTrackedPipeRightX, now, "TRAILING_EDGE_CLEARED")
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

        if (passedOnSwitch) {
            cancelScheduledInterceptTap("PIPE_PASSED")
            rememberTrackedPipeAsPassed(oldRightX, now, "NEXT_PIPE_SWITCH")
        }
        if (oldX < 0 || changedToNextPipe) {
            activePipeTrackId++
            trackedPipeGapTop = -1
            trackedPipeGapBottom = -1
            trackedPipeGapConfidence = 0f
            sameSideRescueTapCount = 0
            tapsOnActivePipe = 0
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
        if (d.gapTop >= 0 && d.gapBottom > d.gapTop) {
            trackedPipeGapTop = d.gapTop
            trackedPipeGapBottom = d.gapBottom
            trackedPipeGapConfidence = d.pipeConfidence
        }
        return passedOnSwitch
    }

    private fun rewardPipePassWithoutGap(now: Long) {
        val state = pendingRlState ?: run {
            appendRunLog("RL pipe_passed without pending state")
            return
        }
        val elapsed = (now - pendingRlAt).coerceIn(30L, 240L).toFloat()
        val tapCost = if (pendingRlAction == RL_FLAP) RL_FLAP_ACTION_COST else 0f
        val reward = RL_PIPE_PASS_REWARD + RL_SURVIVAL_REWARD_PER_80_MS * (elapsed / 80f) + tapCost
        qLearningEpisode.add(QTransition(state, pendingRlAction, reward, null))
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

    private fun predictTrajectoryVelocityAtPipe(
        startVelocity: Float,
        gravity: Float,
        horizonMs: Float,
        tapTimesMs: FloatArray
    ): Float {
        var velocity = startVelocity
        var elapsed = 0f
        for (tapAt in tapTimesMs.sorted()) {
            if (tapAt > horizonMs) break
            val segment = (tapAt - elapsed).coerceAtLeast(0f)
            velocity += gravity * segment
            velocity = estimatedFlapVelocity
            elapsed = tapAt
        }
        return velocity + gravity * (horizonMs - elapsed).coerceAtLeast(0f)
    }

    private fun trajectorySampleTimes(
        startY: Float,
        startVelocity: Float,
        gravity: Float,
        fromMs: Float,
        toMs: Float,
        tapTimesMs: FloatArray
    ): FloatArray {
        val start = fromMs.coerceAtLeast(0f)
        val end = max(start, toMs)
        val span = end - start
        val times = ArrayList<Float>(12)
        times.add(start)
        times.add(start + span * 0.25f)
        times.add(start + span * 0.50f)
        times.add(start + span * 0.75f)
        times.add(end)

        var segmentStart = 0f
        var y = startY
        var velocity = startVelocity
        val taps = tapTimesMs.filter { it > 0f && it < end }.distinct().sorted()
        for (tapAt in taps) {
            val duration = (tapAt - segmentStart).coerceAtLeast(0f)
            if (gravity > 0f) {
                val apexAt = segmentStart - velocity / gravity
                if (apexAt > max(segmentStart, start) + 0.5f &&
                    apexAt < min(tapAt, end) - 0.5f) times.add(apexAt)
            }
            y += velocity * duration + 0.5f * gravity * duration * duration
            velocity += gravity * duration
            velocity = estimatedFlapVelocity
            segmentStart = tapAt
        }
        val remaining = (end - segmentStart).coerceAtLeast(0f)
        if (gravity > 0f) {
            val apexAt = segmentStart - velocity / gravity
            if (apexAt > max(segmentStart, start) + 0.5f && apexAt < end - 0.5f) times.add(apexAt)
        }
        if (!y.isFinite() || !remaining.isFinite()) return floatArrayOf(start, end)
        return times.filter { it.isFinite() && it >= start && it <= end }
            .distinct().sorted().toFloatArray()
    }

    private fun cancelScheduledInterceptTap(reason: String) {
        val scheduled = scheduledInterceptRunnable ?: return
        mainHandler.removeCallbacks(scheduled)
        val oldTrack = scheduledInterceptTrackId
        scheduledInterceptRunnable = null
        scheduledInterceptTrackId = -1
        scheduledInterceptAt = 0L
        appendRunLog("INTERCEPT_DISPATCH_CANCEL reason=$reason trackId=$oldTrack")
    }

    private fun scheduleInterceptTap(
        trackId: Int,
        responseDelayMs: Float,
        responseLatencyMs: Float,
        tapX: Float,
        tapY: Float,
        baselineVelocity: Float,
        baselineY: Int,
        isRescue: Boolean
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        val dispatchDelayMs = (responseDelayMs - responseLatencyMs).coerceAtLeast(0f)
        val dueAt = now + dispatchDelayMs.toLong()
        if (scheduledInterceptRunnable != null &&
            scheduledInterceptTrackId == trackId &&
            abs((dueAt - scheduledInterceptAt).toFloat()) <= 20f) {
            return true
        }
        cancelScheduledInterceptTap("REPLANNED")
        scheduledInterceptTrackId = trackId
        scheduledInterceptAt = dueAt
        val scheduled = Runnable {
            if (scheduledInterceptTrackId == trackId && scheduledInterceptAt == dueAt) {
                scheduledInterceptRunnable = null
                scheduledInterceptTrackId = -1
                scheduledInterceptAt = 0L
                pendingPipeTapAt = 0L
                pendingPipeTapTrackId = -1
                val fireAt = SystemClock.elapsedRealtime()
                val feedbackStillPending = synchronized(tapObservationLock) {
                    pendingTapObservation != null
                }
                val spacingReady = lastAutoTapAt == 0L ||
                    fireAt - lastAutoTapAt >= MIN_TAP_INTERVAL_MS
                val valid = monitoring && !paused && !manual &&
                    activePipeTrackId == trackId && !gestureInFlight.get() &&
                    !feedbackStillPending && spacingReady
                if (valid && tap(tapX, tapY, "COLLISION_INTERCEPT", baselineVelocity, baselineY)) {
                    lastAutoTapAt = fireAt
                    autoTapCount++
                    attemptAutoTaps++
                    attemptFlapDecisions++
                    if (isRescue) sameSideRescueTapCount++
                    tapsOnActivePipe++
                    attemptLastAction = "FLAP_COLLISION_INTERCEPT"
                    attemptLastDecision = if (isRescue)
                        "COLLISION_INTERCEPT_RESCUE_ONE_TAP" else "COLLISION_INTERCEPT_SAFE_ONE_TAP"
                    appendRunLog(
                        "ACTION_SPACE selected=FLAP reason=$attemptLastDecision " +
                            "plannedResponseIn=${responseDelayMs.toInt()}ms actualDispatchDelay=${dispatchDelayMs.toInt()}ms " +
                            "latencyEstimate=${responseLatencyMs.toInt()}ms replanAfterPhysicsFeedback=true"
                    )
                    screenStatus = "ПЕРЕХВАТ ТРУБЫ • коррекция по прогнозу"
                } else {
                    val reason = when {
                        !monitoring || paused || manual -> "MONITORING_STOPPED"
                        activePipeTrackId != trackId -> "TRACK_CHANGED"
                        gestureInFlight.get() -> "GESTURE_BUSY"
                        feedbackStillPending -> "PHYSICS_FEEDBACK_PENDING"
                        !spacingReady -> "MIN_TAP_INTERVAL"
                        else -> "TAP_REJECTED"
                    }
                    appendRunLog(
                        "INTERCEPT_DISPATCH_SKIPPED trackId=$trackId reason=$reason " +
                            "scheduledDelay=${dispatchDelayMs.toInt()}ms"
                    )
                }
            }
        }
        scheduledInterceptRunnable = scheduled
        mainHandler.postDelayed(scheduled, dispatchDelayMs.toLong())
        pendingPipeTapAt = now + responseDelayMs.toLong()
        pendingPipeTapTrackId = trackId
        appendRunLog(
            "INTERCEPT_DISPATCH_SCHEDULED trackId=$trackId responseIn=${responseDelayMs.toInt()}ms " +
                "dispatchIn=${dispatchDelayMs.toInt()}ms latency=${responseLatencyMs.toInt()}ms " +
                "dueAt=$dueAt rescue=$isRescue"
        )
        return true
    }

    private fun predictSingleTapVelocityAtPipe(
        startVelocity: Float,
        gravity: Float,
        horizonMs: Float,
        tapAtMs: Float
    ): Float {
        return if (tapAtMs >= horizonMs) startVelocity + gravity * horizonMs
        else estimatedFlapVelocity + gravity * (horizonMs - tapAtMs)
    }

    private fun predictSingleTapAtPipe(
        startY: Float,
        startVelocity: Float,
        gravity: Float,
        horizonMs: Float,
        tapAtMs: Float
    ): Float {
        val tapAt = tapAtMs.coerceAtLeast(0f)
        if (tapAt >= horizonMs) {
            return startY + startVelocity * horizonMs + 0.5f * gravity * horizonMs * horizonMs
        }
        val yAtTap = startY + startVelocity * tapAt + 0.5f * gravity * tapAt * tapAt
        val remaining = (horizonMs - tapAt).coerceAtLeast(0f)
        // The learned value is the absolute vertical speed immediately after a flap.
        return yAtTap + estimatedFlapVelocity * remaining + 0.5f * gravity * remaining * remaining
    }

    private fun updateTapFeedback(
        now: Long, dt: Float, rawVelocity: Float, gravity: Float
    ): TapFeedbackResult {
        val pending = synchronized(tapObservationLock) { pendingTapObservation }
            ?: return TapFeedbackResult()
        val completedAt = pending.completedAt
        if (completedAt <= 0L || previousBirdAt < completedAt ||
            now - completedAt < 35L || dt <= 0f) {
            return TapFeedbackResult(pending = true)
        }

        pending.framesObserved++
        val observed = (rawVelocity - gravity * dt * 0.5f).coerceIn(-3.2f, 0.8f)
        val sampleMidpointElapsed = (now.toFloat() - dt * 0.5f -
            pending.requestedAt.toFloat()).coerceAtLeast(0f)
        val expectedWithoutFlap = pending.baselineVelocity + gravity * sampleMidpointElapsed
        val responseStrength = expectedWithoutFlap - observed
        val requiredImpulse = max(0.28f, abs(expectedWithoutFlap) * 0.30f)
        val confirmed = observed in -3.0f..-0.08f && responseStrength >= requiredImpulse

        if (!confirmed) {
            val expired = now - completedAt >= 650L || pending.framesObserved >= 7
            if (!expired) {
                if (pending.framesObserved == 1 || pending.framesObserved % 3 == 0) {
                    appendRunLog(
                        "TAP_FEEDBACK_WAIT tapId=${pending.id} frame=${pending.framesObserved} " +
                            "postGesture=${now - completedAt}ms raw=${fmt(rawVelocity)} " +
                            "observed=${fmt(observed)} expectedNoFlap=${fmt(expectedWithoutFlap)} " +
                            "impulse=${fmt(responseStrength)} required=${fmt(requiredImpulse)}"
                    )
                }
                return TapFeedbackResult(pending = true)
            }
            lastTapFeedbackAt = now
            appendRunLog(
                "TAP_FEEDBACK_TIMEOUT tapId=${pending.id} source=${pending.source} " +
                    "frames=${pending.framesObserved} postGesture=${now - completedAt}ms " +
                    "lastObserved=${fmt(observed)} expectedNoFlap=${fmt(expectedWithoutFlap)} " +
                    "calibrationUnchanged=true"
            )
            synchronized(tapObservationLock) {
                if (pendingTapObservation?.id == pending.id) pendingTapObservation = null
            }
            return TapFeedbackResult(resolved = true)
        }

        // Android's callback is not the game's physics response; use the confirming
        // vertical impulse midpoint to estimate actual tap-to-physics latency.
        val latencySample = sampleMidpointElapsed.coerceIn(45f, 220f)
        val oldLatency = estimatedTapResponseLatencyMs
        estimatedTapResponseLatencyMs = if (tapLatencySamples == 0) latencySample
            else (oldLatency * 0.70f + latencySample * 0.30f).coerceIn(45f, 220f)
        tapLatencySamples++
        val timeAfterResponse = (sampleMidpointElapsed - latencySample).coerceAtLeast(0f)
        val tapAlignedObserved = (observed - gravity * timeAfterResponse).coerceIn(-3.2f, 0.8f)

        val oldEstimate = estimatedFlapVelocity
        val prior = flapImpulseSamples.toList().sorted()
        val priorMedian = when {
            prior.isEmpty() -> Float.NaN
            prior.size % 2 == 1 -> prior[prior.size / 2]
            else -> (prior[prior.size / 2 - 1] + prior[prior.size / 2]) * 0.5f
        }
        val outlierLimit = max(0.90f, abs(priorMedian) * 0.60f)
        var calibrationRejectReason = "ACCEPTED"
        if (priorMedian.isNaN() || abs(tapAlignedObserved - priorMedian) <= outlierLimit) {
            flapImpulseSamples.addLast(tapAlignedObserved)
            while (flapImpulseSamples.size > 7) flapImpulseSamples.removeFirst()
            val sorted = flapImpulseSamples.toList().sorted()
            val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
                else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
            estimatedFlapVelocity = (oldEstimate * 0.30f + median * 0.70f)
                .coerceIn(-3.0f, -0.08f)
            persistFlapCalibration("CONFIRMED_TAP_MEDIAN")
        } else {
            calibrationRejectReason = "OUTLIER"
            appendRunLog(
                "FLAP_CALIBRATION_SAMPLE_REJECTED tapId=${pending.id} observed=${fmt(tapAlignedObserved)} " +
                    "referenceMedian=${fmt(priorMedian)} limit=${fmt(outlierLimit)}"
            )
        }

        lastTapFeedbackAt = now
        appendRunLog(
            "PHYSICS_RESPONSE tapId=${pending.id} source=${pending.source} " +
                "requestAge=${now - pending.requestedAt}ms postGesture=${now - completedAt}ms " +
                "frameCount=${pending.framesObserved} baselineY=${pending.baselineY} " +
                "baselineV=${fmt(pending.baselineVelocity)} expectedNoFlap=${fmt(expectedWithoutFlap)} " +
                "response=${fmt(responseStrength)} raw=${fmt(rawVelocity)} observed=${fmt(observed)} " +
                "tapAligned=${fmt(tapAlignedObserved)} responseLatency=${fmt(oldLatency)}->${fmt(estimatedTapResponseLatencyMs)}ms " +
                "confirmed=true calibration=${calibrationRejectReason} samples=${flapImpulseSamples.size} " +
                "flapV=${fmt(oldEstimate)}->${fmt(estimatedFlapVelocity)}"
        )
        synchronized(tapObservationLock) {
            if (pendingTapObservation?.id == pending.id) pendingTapObservation = null
        }
        return TapFeedbackResult(resolved = true, confirmedVelocity = observed)
    }

    private fun handleAutoControl(
        d: Detection, fieldWidth: Int, fieldHeight: Int, cropTop: Int, screenWidth: Int, screenHeight: Int
    ) {
        if (!monitoring || paused || manual) return
        val now = SystemClock.elapsedRealtime()
        if (!d.activeGame) {
            cancelScheduledInterceptTap("GAME_OVER")
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
        val observedBirdWidth = (d.birdRight - d.birdLeft).coerceAtLeast(0)
        val observedBirdHeight = (d.birdBottom - d.birdTop).coerceAtLeast(0)
        val spriteGeometryStable = previousObservedBirdWidth <= 0 || previousObservedBirdHeight <= 0 || (
            abs(observedBirdWidth - previousObservedBirdWidth) <= max(10, (previousObservedBirdWidth * 0.18f).toInt()) &&
                abs(observedBirdHeight - previousObservedBirdHeight) <= max(16, (previousObservedBirdHeight * 0.18f).toInt())
            )
        val tapObservationActive = synchronized(tapObservationLock) { pendingTapObservation != null }
        if (dt > 0f && previousBirdAt > 0L && !impulseDetected && !tapObservationActive &&
            spriteGeometryStable && abs(rawVelocity - previousRawVerticalVelocity) <= 0.65f) {
            val measuredGravity = (rawVelocity - previousRawVerticalVelocity) / dt
            if (measuredGravity in 0.00025f..0.0060f) {
                val existingGravity = gravitySamples.toList().sorted()
                val referenceGravity = if (existingGravity.isEmpty()) Float.NaN
                    else existingGravity[existingGravity.size / 2]
                val gravityIsPlausible = referenceGravity.isNaN() ||
                    abs(measuredGravity - referenceGravity) <= max(0.0012f, referenceGravity * 0.65f)
                if (gravityIsPlausible) {
                    gravitySamples.addLast(measuredGravity)
                    if (gravitySamples.size > 7) gravitySamples.removeFirst()
                    if (gravitySamples.size >= 3) {
                        val robustGravity = gravitySamples.toList().sorted()[gravitySamples.size / 2]
                        val oldGravity = estimatedGravity
                        estimatedGravity = (estimatedGravity * 0.82f + robustGravity * 0.18f)
                            .coerceIn(0.00035f, 0.0060f)
                        if (abs(estimatedGravity - oldGravity) >= 0.00035f) {
                            appendRunLog(
                                "GRAVITY_CALIBRATION sample=${fmt(measuredGravity)} median=${fmt(robustGravity)} " +
                                    "value=${fmt(oldGravity)}->${fmt(estimatedGravity)} n=${gravitySamples.size}"
                            )
                        }
                    }
                } else {
                    appendRunLog(
                        "GRAVITY_SAMPLE_REJECTED value=${fmt(measuredGravity)} reference=${fmt(referenceGravity)} " +
                            "reason=OUTLIER"
                    )
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
        previousObservedBirdWidth = observedBirdWidth
        previousObservedBirdHeight = observedBirdHeight
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
        val overlapGuard = max(4, (fieldWidth * 0.025f).toInt())
        val horizontalControlOverlap = horizontalPipeOverlap ||
            (d.obstacleX >= 0 && pipeLeftEdge > birdRightEdge &&
                pipeLeftEdge - birdRightEdge <= overlapGuard && pipeRightEdge >= birdLeftEdge)
        val hasGap = d.obstacleX >= 0 && d.pipeLeft >= 0 && d.pipeRight > d.pipeLeft &&
            d.gapTop >= 0 && d.gapBottom > d.gapTop
        if (pendingPipeTapAt > 0L && pendingPipeTapTrackId != activePipeTrackId) {
            cancelScheduledInterceptTap("TRACK_CHANGED")
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
            val pipeCrossingWithKnownGap = horizontalControlOverlap &&
                d.gapTop >= 0 && d.gapBottom > d.gapTop
            if (pipeCrossingWithKnownGap &&
                lastInterceptPredictionTrackId == activePipeTrackId &&
                lastInterceptPredictedY.isFinite()) {
                val predictionAgeMs = if (lastInterceptPredictionAt > 0L)
                    now - lastInterceptPredictionAt else 0L
                val predictionAge = predictionAgeMs.toFloat()
                val adjustedExpectedY = lastInterceptPredictedY +
                    lastInterceptPredictedVelocity * predictionAge +
                    0.5f * estimatedGravity * predictionAge * predictionAge
                appendRunLog(
                    "COLLISION_INTERCEPT_ACTUAL trackId=$activePipeTrackId " +
                        "predictedEntryY=${lastInterceptPredictedY.toInt()} actualY=${d.birdY} " +
                        "rawDelta=${(d.birdY - lastInterceptPredictedY).toInt()}px " +
                        "timeAdjustedDelta=${(d.birdY - adjustedExpectedY).toInt()}px " +
                        "timingError=${predictionAgeMs}ms predictedVelocity=${fmt(lastInterceptPredictedVelocity)}"
                )
                lastInterceptPredictionTrackId = -1
                lastInterceptPredictedY = Float.NaN
                lastInterceptPredictedVelocity = Float.NaN
                lastInterceptPredictionAt = 0L
        lastInterceptActualLoggedTrackId = -1
            }
            if (pipeCrossingWithKnownGap) {
                val crossingGapHeight = (d.gapBottom - d.gapTop).toFloat()
                val crossingSafeTop = d.gapTop + crossingGapHeight * 0.18f
                val crossingSafeBottom = d.gapBottom - crossingGapHeight * 0.18f
                val crossingReactionMs = estimatedTapResponseLatencyMs
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
                    if (schedule.isEmpty() || schedule.size > 4 ||
                        schedule.size > (MAX_PLANNED_TAPS_PER_PIPE - tapsOnActivePipe)) return
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
                val rescueMissLimit = max(fieldHeight * 0.23f, crossingGapHeight * 0.55f)
                val rescueBudgetAvailable = sameSideRescueTapCount < MAX_RESCUE_TAPS_PER_PIPE &&
                    waitWorstMiss <= rescueMissLimit * 1.35f
                val bestRescueCrossingPlan = if (bestSafeCrossingPlan == null && rescueBudgetAvailable) {
                    crossingCandidates.filter {
                        it.worstMiss <= rescueMissLimit &&
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
                    firstTapDelay <= crossingReactionMs + 12f
                val canDispatchCrossingTap = !tapFeedbackPending &&
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
                        "rescueBudget=$sameSideRescueTapCount/$MAX_RESCUE_TAPS_PER_PIPE " +
                        "rescueLimit=${rescueMissLimit.toInt()} schedules=$crossingSchedulesEvaluated " +
                        "feedbackPending=$tapFeedbackPending"
                )
                if (shouldCrossingTap && tap(tapX, tapY, "POST_PIPE", velocity, d.birdY)) {
                    lastAutoTapAt = now
                    pendingPipeTapAt = 0L
                    pendingPipeTapTrackId = -1
                    autoTapCount++
                    attemptAutoTaps++
                    attemptFlapDecisions++
                    if (planMode == "SAME_SIDE_RESCUE") sameSideRescueTapCount++
                    tapsOnActivePipe++
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
                        !tapSpacingReady -> "POST_PIPE_WAIT_MIN_INTERVAL"
                        gestureInFlight.get() -> "POST_PIPE_WAIT_GESTURE"
                        waitWorstMiss <= 0f -> "POST_PIPE_PATH_SAFE"
                        bestCrossingPlan == null -> "POST_PIPE_NO_NONWORSENING_RESCUE"
                        !tapWindowReady -> "POST_PIPE_WAIT_PLANNED_TAP"
                        else -> "POST_PIPE_TAP_NOT_DISPATCHED"
                    }
                    val reason = when {
                        tapFeedbackPending -> "WAIT_TAP_FEEDBACK"
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
                val prepipeHorizon = 280f
                val prepipeTapAt = estimatedTapResponseLatencyMs
                val prepipeUpper = fieldHeight * 0.20f
                val prepipeLower = fieldHeight * 0.88f
                val predictedWithoutTap = predictTrajectoryAtPipe(
                    d.birdY.toFloat(), velocity, gravity, prepipeHorizon, floatArrayOf()
                )
                val predictedWithTap = predictTrajectoryAtPipe(
                    d.birdY.toFloat(), velocity, gravity, prepipeHorizon, floatArrayOf(prepipeTapAt)
                )
                val prepipeTapSpacingReady = tapSpacingReady
                val prepipeNeedsEmergencyLift = predictedWithoutTap > prepipeLower * 0.92f &&
                    velocity > 0.10f
                val prepipeTapKeepsInField = predictedWithTap in prepipeUpper..(prepipeLower + fieldHeight * 0.015f)
                val prepipeTapImproves = predictedWithoutTap - predictedWithTap >= max(30f, fieldHeight * 0.025f)
                val prepipeCanTap = !tapFeedbackPending &&
                    !gestureInFlight.get() && prepipeTapSpacingReady
                val shouldPrepipeTap = prepipeCanTap && prepipeNeedsEmergencyLift &&
                    prepipeTapKeepsInField && prepipeTapImproves

                val prepipeReason = when {
                    tapFeedbackPending -> "WAIT_TAP_PHYSICS_FEEDBACK"
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
                val cruiseHorizon = 420f
                val predictedWithoutTap = predictTrajectoryAtPipe(
                    d.birdY.toFloat(), velocity, gravity, cruiseHorizon, floatArrayOf()
                )
                val predictedWithTap = predictTrajectoryAtPipe(
                    d.birdY.toFloat(), velocity, gravity, cruiseHorizon, floatArrayOf(estimatedTapResponseLatencyMs)
                )
                val previousGapKnown = previousPassedGapTop >= 0 &&
                    previousPassedGapBottom > previousPassedGapTop && previousPassedGapConfidence >= 0.55f
                val cruiseBirdHalf = if (d.birdTop >= 0 && d.birdBottom > d.birdTop)
                    (d.birdBottom - d.birdTop) * 0.5f else fieldHeight * 0.04f
                val cruiseGapMargin = if (previousGapKnown)
                    max(fieldHeight * 0.012f, (previousPassedGapBottom - previousPassedGapTop) * 0.075f)
                else 0f
                val cruiseUpper = if (previousGapKnown)
                    previousPassedGapTop + cruiseGapMargin + cruiseBirdHalf else fieldHeight * 0.36f
                val cruiseLower = if (previousGapKnown)
                    previousPassedGapBottom - cruiseGapMargin - cruiseBirdHalf else fieldHeight * 0.65f
                val minImprovement = max(25f, fieldHeight * 0.03f)
                val cruiseNeedsLift = predictedWithoutTap > cruiseLower && velocity > -0.12f
                val tapKeepsInField = predictedWithTap in cruiseUpper..cruiseLower
                val tapMeaningfullyImproves = predictedWithoutTap - predictedWithTap >= minImprovement
                val cruiseTapReady = !tapFeedbackPending &&
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

        // Time-to-contact is measured from the bird's RIGHT edge to the pipe's LEFT edge.
        // This is the horizontal instant when the two collision rectangles first touch.
        val birdRightEdgeForPlan = d.birdRight.takeIf { it >= 0 } ?: (d.birdX + fieldWidth * 0.018f).toInt()
        val pipeLeftEdgeForPlan = d.pipeLeft.takeIf { it >= 0 } ?: d.obstacleX
        val actualHorizontalOverlap = pipeLeftEdgeForPlan <= birdRightEdgeForPlan && pipeRightEdge >= birdLeftEdge
        if (actualHorizontalOverlap && lastInterceptActualLoggedTrackId != activePipeTrackId &&
            lastInterceptPredictionTrackId == activePipeTrackId && lastInterceptPredictedY.isFinite()) {
            val predictionAgeMs = if (lastInterceptPredictionAt > 0L)
                now - lastInterceptPredictionAt else 0L
            val age = predictionAgeMs.toFloat()
            val adjustedExpectedY = lastInterceptPredictedY +
                lastInterceptPredictedVelocity * age + 0.5f * estimatedGravity * age * age
            appendRunLog(
                "COLLISION_INTERCEPT_ACTUAL trackId=$activePipeTrackId " +
                    "predictedEntryY=${lastInterceptPredictedY.toInt()} actualY=${d.birdY} " +
                    "rawDelta=${(d.birdY - lastInterceptPredictedY).toInt()}px " +
                    "timeAdjustedDelta=${(d.birdY - adjustedExpectedY).toInt()}px " +
                    "timingError=${predictionAgeMs}ms predictedVelocity=${fmt(lastInterceptPredictedVelocity)}"
            )
            lastInterceptActualLoggedTrackId = activePipeTrackId
            lastInterceptPredictionTrackId = -1
            lastInterceptPredictedY = Float.NaN
            lastInterceptPredictedVelocity = Float.NaN
            lastInterceptPredictionAt = 0L
        }
        val distance = (pipeLeftEdgeForPlan - birdRightEdgeForPlan - fieldWidth * 0.006f).toFloat()

        val obstacleDt = if (previousObstacleAt > 0L) (now - previousObstacleAt).coerceAtLeast(1L).toFloat() else 0f
        val motionX = d.pipeMotionX.takeIf { it >= 0 } ?: d.obstacleX
        val measuredSpeed = if (previousObstacleX > motionX && obstacleDt in 35f..500f)
            (previousObstacleX - motionX).toFloat() / obstacleDt else 0f
        if (measuredSpeed in 0.08f..3.5f) {
            smoothedObstacleSpeed = if (smoothedObstacleSpeed > 0f)
                smoothedObstacleSpeed * 0.55f + measuredSpeed * 0.45f else measuredSpeed
        }
        val fallbackSpeed = fieldWidth * 0.00075f
        val obstacleSpeedReady = smoothedObstacleSpeed in 0.08f..3.5f
        val obstacleSpeed = if (obstacleSpeedReady) smoothedObstacleSpeed else fallbackSpeed
        previousObstacleX = motionX
        previousObstacleAt = now

        val timeToPipe = if (actualHorizontalOverlap) 0f
            else (distance / obstacleSpeed).coerceIn(0f, 1400f)
        val reactionMs = estimatedTapResponseLatencyMs
        val confidenceExtraMargin = ((0.55f - d.pipeConfidence).coerceAtLeast(0f) / 0.25f)
            .coerceAtMost(1f) * gapHeight * 0.045f
        val safeMargin = (gapHeight * 0.065f + confidenceExtraMargin)
            .coerceAtLeast(fieldHeight * 0.010f)
        // Use the detected sprite height, not a fixed normal-size bird. This adapts to potions.
        val birdHalfHeight = if (d.birdTop >= 0 && d.birdBottom > d.birdTop)
            (d.birdBottom - d.birdTop) * 0.5f else fieldHeight * 0.04f
        val collisionSafeTop = gapTop + safeMargin
        val collisionSafeBottom = gapBottom - safeMargin
        val safeTop = collisionSafeTop + birdHalfHeight
        val safeBottom = collisionSafeBottom - birdHalfHeight
        val safeWindowValid = safeBottom > safeTop
        val distanceWindow = obstacleSpeedReady &&
            ((distance > 0f && distance < fieldWidth * 2.5f) || actualHorizontalOverlap)
        val gapTrusted = d.pipeConfidence >= MIN_GAP_CONFIDENCE_FOR_CONTROL &&
            gapHeight >= fieldHeight * 0.08f && gapHeight <= fieldHeight * 0.62f

        val pipeWidth = if (d.pipeLeft >= 0 && d.pipeRight > d.pipeLeft)
            (d.pipeRight - d.pipeLeft).toFloat() else fieldWidth * 0.08f
        val birdWidth = if (d.birdLeft >= 0 && d.birdRight > d.birdLeft)
            (d.birdRight - d.birdLeft).toFloat() else fieldWidth * 0.04f
        // Check the whole horizontal overlap, not just the first touch.
        val overlapPixels = if (actualHorizontalOverlap)
            (pipeRightEdge - birdLeftEdge).coerceAtLeast(1).toFloat() else pipeWidth + birdWidth
        val collisionDuration = (overlapPixels / obstacleSpeed).coerceIn(1f, 320f)
        val exitTime = timeToPipe + collisionDuration
        val sampleTimes = trajectorySampleTimes(
            d.birdY.toFloat(), velocity, gravity, timeToPipe, exitTime, floatArrayOf()
        )

        fun missAtCenter(centerY: Float): Float {
            val predictedTop = centerY - birdHalfHeight
            val predictedBottom = centerY + birdHalfHeight
            val topMiss = (collisionSafeTop - predictedTop).coerceAtLeast(0f)
            val bottomMiss = (predictedBottom - collisionSafeBottom).coerceAtLeast(0f)
            return max(topMiss, bottomMiss)
        }
        fun collisionSide(centerY: Float): Int = when {
            centerY < safeTop -> -1
            centerY > safeBottom -> 1
            else -> 0
        }

        // The passed opening and the next visible opening form a sloped corridor.
        // Both pipe edges move at almost the same speed, so their relative progress is predictable.
        val previousMemoryAge = if (previousPassedPipeAt > 0L) now - previousPassedPipeAt else Long.MAX_VALUE
        val previousPipeRightNow = if (previousPassedPipeRightX >= 0 && previousMemoryAge in 0L..3500L)
            previousPassedPipeRightX - obstacleSpeed * previousMemoryAge.toFloat() else -1f
        val corridorSpan = pipeLeftEdgeForPlan - previousPipeRightNow
        val corridorActive = !actualHorizontalOverlap && previousPassedGapTop >= 0 &&
            previousPassedGapBottom > previousPassedGapTop &&
            previousPassedGapConfidence >= 0.55f &&
            previousMemoryAge in 0L..3500L &&
            previousPipeRightNow >= 0f &&
            corridorSpan > fieldWidth * 0.12f &&
            timeToPipe >= 180f
        val corridorTimes = if (corridorActive)
            floatArrayOf(timeToPipe * 0.20f, timeToPipe * 0.40f, timeToPipe * 0.60f, timeToPipe * 0.80f)
        else floatArrayOf()
        val corridorHeight = if (corridorActive)
            min((previousPassedGapBottom - previousPassedGapTop).toFloat(), gapBottom - gapTop)
        else 0f
        val corridorMargin = max(fieldHeight * 0.012f, corridorHeight * 0.075f)
        fun corridorProgressAt(timeMs: Float): Float {
            if (!corridorActive) return 1f
            val projectedPreviousRight = previousPipeRightNow - obstacleSpeed * timeMs
            val projectedNextLeft = pipeLeftEdgeForPlan - obstacleSpeed * timeMs
            val span = (projectedNextLeft - projectedPreviousRight).coerceAtLeast(1f)
            return ((d.birdX - projectedPreviousRight) / span).coerceIn(0f, 1f)
        }
        fun corridorMissAt(centerY: Float, timeMs: Float): Float {
            if (!corridorActive) return 0f
            val progress = corridorProgressAt(timeMs)
            val top = previousPassedGapTop + (gapTop - previousPassedGapTop) * progress
            val bottom = previousPassedGapBottom + (gapBottom - previousPassedGapBottom) * progress
            val allowedTop = top + corridorMargin + birdHalfHeight
            val allowedBottom = bottom - corridorMargin - birdHalfHeight
            if (allowedBottom <= allowedTop) return max(allowedTop - allowedBottom, fieldHeight * 0.1f)
            return max(
                (allowedTop - centerY).coerceAtLeast(0f),
                (centerY - allowedBottom).coerceAtLeast(0f)
            )
        }
        val corridorProgressNow = if (corridorActive) corridorProgressAt(0f) else -1f

        var waitWorstMiss = 0f
        var waitMissSum = 0f
        var waitEntryY = d.birdY.toFloat()
        var waitMinY = Float.POSITIVE_INFINITY
        var waitMaxY = Float.NEGATIVE_INFINITY
        var waitMinTop = Float.POSITIVE_INFINITY
        var waitMaxBottom = Float.NEGATIVE_INFINITY
        for (i in sampleTimes.indices) {
            val horizon = sampleTimes[i]
            val y = d.birdY + velocity * horizon + 0.5f * gravity * horizon * horizon
            if (i == 0) waitEntryY = y
            waitMinY = min(waitMinY, y)
            waitMaxY = max(waitMaxY, y)
            waitMinTop = min(waitMinTop, y - birdHalfHeight)
            waitMaxBottom = max(waitMaxBottom, y + birdHalfHeight)
            val miss = missAtCenter(y)
            waitWorstMiss = max(waitWorstMiss, miss)
            waitMissSum += miss
        }
        var waitCorridorMiss = 0f
        if (corridorActive) {
            for (corridorTime in corridorTimes) {
                val y = d.birdY + velocity * corridorTime + 0.5f * gravity * corridorTime * corridorTime
                val miss = corridorMissAt(y, corridorTime)
                waitCorridorMiss = max(waitCorridorMiss, miss)
                waitWorstMiss = max(waitWorstMiss, miss)
                waitMissSum += miss
            }
        }
        val waitMeanMiss = waitMissSum / (sampleTimes.size + corridorTimes.size).toFloat()
        val missThreshold = if (corridorActive)
            max(gapHeight * 0.05f, fieldHeight * 0.012f)
        else max(gapHeight * 0.12f, fieldHeight * 0.02f)
        val minUsefulImprovement = max(7f, safeMargin * 0.06f)

        val elapsedSinceLastTap = if (lastAutoTapAt > 0L)
            (now - lastAutoTapAt).toFloat() else Float.POSITIVE_INFINITY
        val spacingRemaining = if (elapsedSinceLastTap.isFinite())
            (MIN_TAP_INTERVAL_MS.toFloat() - elapsedSinceLastTap).coerceAtLeast(0f) else 0f
        val minResponseDelay = max(reactionMs, spacingRemaining + reactionMs)
        val maxResponseDelay = min(680f, timeToPipe + collisionDuration - 15f)
        val remainingTapBudget = (MAX_PLANNED_TAPS_PER_PIPE - tapsOnActivePipe).coerceAtLeast(0)
        val bestSafePlans = ArrayList<TapPlan>(24)
        val bestRescuePlans = ArrayList<TapPlan>(24)
        var candidatesEvaluated = 0
        val rescueMissLimit = max(fieldHeight * 0.12f, gapHeight * 0.30f)

        fun evaluateInterceptSchedule(schedule: List<Float>) {
            if (schedule.isEmpty() || schedule.size > remainingTapBudget || schedule.size > 3) return
            if (schedule.first() < minResponseDelay || schedule.last() > maxResponseDelay + 0.5f) return
            if (schedule.last() >= exitTime - 10f) return
            for (i in 1 until schedule.size) {
                if (schedule[i] - schedule[i - 1] < MIN_TAP_INTERVAL_MS + 10f) return
            }
            if (schedule.first() > timeToPipe + 0.5f && missAtCenter(waitEntryY) > 0f) return

            val taps = schedule.toFloatArray()
            val candidateTimes = trajectorySampleTimes(
                d.birdY.toFloat(), velocity, gravity, timeToPipe, exitTime, taps
            )
            var worstMiss = 0f
            var missSum = 0f
            var entryY = d.birdY.toFloat()
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            var minTop = Float.POSITIVE_INFINITY
            var maxBottom = Float.NEGATIVE_INFINITY
            var oppositeCollision = false
            for (i in candidateTimes.indices) {
                val horizon = candidateTimes[i]
                val y = predictTrajectoryAtPipe(
                    d.birdY.toFloat(), velocity, gravity, horizon, taps
                )
                if (i == 0) entryY = y
                minY = min(minY, y)
                maxY = max(maxY, y)
                minTop = min(minTop, y - birdHalfHeight)
                maxBottom = max(maxBottom, y + birdHalfHeight)
                val miss = missAtCenter(y)
                worstMiss = max(worstMiss, miss)
                missSum += miss
                val naturalY = d.birdY + velocity * horizon + 0.5f * gravity * horizon * horizon
                val naturalSide = collisionSide(naturalY)
                val candidateSide = collisionSide(y)
                if ((naturalSide < 0 && candidateSide > 0) ||
                    (naturalSide > 0 && candidateSide < 0)) oppositeCollision = true
            }
            if (corridorActive) {
                for (corridorTime in corridorTimes) {
                    val y = predictTrajectoryAtPipe(
                        d.birdY.toFloat(), velocity, gravity, corridorTime, taps
                    )
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                    minTop = min(minTop, y - birdHalfHeight)
                    maxBottom = max(maxBottom, y + birdHalfHeight)
                    val miss = corridorMissAt(y, corridorTime)
                    worstMiss = max(worstMiss, miss)
                    missSum += miss
                }
            }
            val meanMiss = missSum / (candidateTimes.size + corridorTimes.size).toFloat()
            candidatesEvaluated++
            val score = worstMiss + taps.size * 14f + meanMiss * 0.12f -
                min(taps.first(), 400f) * 0.012f
            val plan = TapPlan(taps, entryY, (minY + maxY) * 0.5f,
                taps.last(), worstMiss, meanMiss, score)
            if (worstMiss <= 0f) {
                bestSafePlans.add(plan)
            } else if (!oppositeCollision && worstMiss <= rescueMissLimit &&
                worstMiss + minUsefulImprovement < waitWorstMiss &&
                sameSideRescueTapCount + taps.size <= MAX_RESCUE_TAPS_PER_PIPE) {
                bestRescuePlans.add(plan)
            }
        }

        val searchWindowExists = safeWindowValid && distanceWindow && gapTrusted &&
            waitWorstMiss > missThreshold && maxResponseDelay >= minResponseDelay &&
            remainingTapBudget > 0
        if (searchWindowExists) {
            fun expandSchedules(schedule: MutableList<Float>) {
                evaluateInterceptSchedule(schedule)
                if (schedule.size >= min(3, remainingTapBudget)) return
                val intervals = floatArrayOf(
                    MIN_TAP_INTERVAL_MS.toFloat() + 20f,
                    MIN_TAP_INTERVAL_MS.toFloat() + 70f,
                    MIN_TAP_INTERVAL_MS.toFloat() + 130f
                )
                for (interval in intervals) {
                    val next = schedule.last() + interval
                    if (next >= maxResponseDelay - 5f) continue
                    schedule.add(next)
                    expandSchedules(schedule)
                    schedule.removeAt(schedule.lastIndex)
                }
            }
            var firstDelay = minResponseDelay
            while (firstDelay <= maxResponseDelay + 0.1f) {
                expandSchedules(mutableListOf(firstDelay))
                firstDelay += 40f
            }
            if (pendingPipeTapAt > now && pendingPipeTapTrackId == activePipeTrackId) {
                val pendingDelay = (pendingPipeTapAt - now).toFloat()
                if (pendingDelay in minResponseDelay..maxResponseDelay) {
                    expandSchedules(mutableListOf(pendingDelay))
                }
            }
        }

        val bestSafePlan = bestSafePlans.minByOrNull { it.score }
        val bestRescuePlan = bestRescuePlans.minByOrNull { it.score }
        val useSafePlan = bestSafePlan != null
        val rescuePlanAvailable = bestRescuePlan != null &&
            sameSideRescueTapCount + (bestRescuePlan?.tapTimesMs?.size ?: 0) <= MAX_RESCUE_TAPS_PER_PIPE
        val selectedPlan = if (useSafePlan) bestSafePlan
            else if (rescuePlanAvailable) bestRescuePlan else null
        val selectedExists = selectedPlan != null
        val selectedDelay = selectedPlan?.tapTimesMs?.firstOrNull() ?: -1f
        val selectedTapCount = selectedPlan?.tapTimesMs?.size ?: 0
        val selectedMiss = selectedPlan?.worstMiss ?: Float.POSITIVE_INFINITY
        val selectedEntryY = selectedPlan?.entryY ?: waitEntryY
        val selectedMinY = selectedPlan?.let { min(it.entryY, it.middleY) } ?: waitMinY
        val selectedMaxY = selectedPlan?.let { max(it.entryY, it.middleY) } ?: waitMaxY
        val selectedMinTop = selectedMinY - birdHalfHeight
        val selectedMaxBottom = selectedMaxY + birdHalfHeight
        val selectedMeanMiss = selectedPlan?.meanMiss ?: waitMeanMiss
        val planMode = when {
            useSafePlan -> "FULL_SAFE_${selectedTapCount}TAP"
            selectedExists -> "BOUNDED_RESCUE_${selectedTapCount}TAP"
            else -> "NO_SAFE_MULTI_TAP_PLAN"
        }
        val planValid = searchWindowExists && selectedExists && remainingTapBudget > 0
        val canDispatchTap = !tapFeedbackPending &&
            tapSpacingReady && !gestureInFlight.get()
        val shouldScheduleTap = planValid && canDispatchTap

        if (!planValid) {
            if (scheduledInterceptRunnable != null) cancelScheduledInterceptTap("PLAN_NO_LONGER_VALID")
            if (pendingPipeTapAt > 0L && pendingPipeTapTrackId == activePipeTrackId) {
                appendRunLog("PIPE_TAP_PLAN_CANCEL reason=INTERCEPT_NO_LONGER_VALID trackId=$activePipeTrackId")
                pendingPipeTapAt = 0L
                pendingPipeTapTrackId = -1
            }
        } else if (shouldScheduleTap) {
            scheduleInterceptTap(
                activePipeTrackId, selectedDelay, reactionMs,
                tapX, tapY, velocity, d.birdY, !useSafePlan
            )
        } else if (tapFeedbackPending || !tapSpacingReady || gestureInFlight.get()) {
            cancelScheduledInterceptTap("ACTION_TEMPORARILY_BLOCKED")
        }

        val predictedEntryY = if (selectedExists) selectedEntryY else waitEntryY
        lastInterceptPredictionTrackId = activePipeTrackId
        lastInterceptPredictedY = predictedEntryY
        lastInterceptPredictedVelocity = if (selectedExists)
            predictTrajectoryVelocityAtPipe(
                velocity, gravity, timeToPipe, selectedPlan?.tapTimesMs ?: floatArrayOf()
            )
        else velocity + gravity * timeToPipe
        lastInterceptPredictionAt = now + timeToPipe.toLong()

        val stateKey = encodeQLearningState(
            velocity, timeToPipe, (gapTop + gapHeight * 0.5f) - d.birdY, gapHeight, fieldHeight
        )
        val actionCandidatesText = "WAIT:${waitWorstMiss.toInt()} " +
            "SAFE_PLANS:${bestSafePlans.size} RESCUE_PLANS:${bestRescuePlans.size} " +
            "SELECTED:${selectedTapCount}TAP@${if (selectedExists) selectedDelay.toInt() else -1}ms " +
            "MISS:${if (selectedExists) selectedMiss.toInt() else -1}"
        val decisionReason = when {
            !obstacleSpeedReady -> "WAIT_PIPE_SPEED_CALIBRATION"
            !distanceWindow -> "OUT_OF_RANGE"
            !safeWindowValid -> "INVALID_SAFE_WINDOW"
            !gapTrusted -> "LOW_GAP_CONFIDENCE_WAIT"
            waitWorstMiss <= missThreshold -> "PREDICTED_COLLISION_PATH_SAFE_WAIT"
            maxResponseDelay < minResponseDelay -> "NO_PRECONTACT_TAP_WINDOW"
            tapFeedbackPending -> "WAIT_TAP_PHYSICS_FEEDBACK"
            !tapSpacingReady -> "WAIT_MIN_TAP_INTERVAL"
            remainingTapBudget <= 0 -> "PIPE_TAP_BUDGET_EXHAUSTED"
            !selectedExists && bestRescuePlans.isNotEmpty() -> "RESCUE_TAP_BUDGET_EXHAUSTED"
            !selectedExists -> "NO_SAFE_MULTI_TAP_PLAN"
            else -> "COLLISION_INTERCEPT_PLANNED"
        }
        attemptLastDecision = decisionReason
        appendRunLog(
            "COLLISION_INTERCEPT trackId=$activePipeTrackId pipeLeft=$pipeLeftEdgeForPlan " +
                "birdRight=$birdRightEdgeForPlan closingSpeed=${fmt(obstacleSpeed)}pxPerMs speedReady=$obstacleSpeedReady " +
                "tCross=${timeToPipe.toInt()}ms overlap=${collisionDuration.toInt()}ms " +
                "birdY=${d.birdY} v=${fmt(velocity)} g=${fmt(gravity)} flapV=${fmt(estimatedFlapVelocity)} " +
                "waitY=${waitEntryY.toInt()} range=${waitMinY.toInt()}..${waitMaxY.toInt()} " +
                "waitTopBottom=${waitMinTop.toInt()}..${waitMaxBottom.toInt()} " +
                "gap=${gapTop.toInt()}..${gapBottom.toInt()} safeCenter=${safeTop.toInt()}..${safeBottom.toInt()} " +
                "margin=${safeMargin.toInt()} confidence=${fmt(d.pipeConfidence)} " +
                "corridor=${if (corridorActive) "ON" else "OFF"} prevGap=${previousPassedGapTop}..${previousPassedGapBottom} " +
                "progress=${fmt(corridorProgressNow)} corridorWaitMiss=${waitCorridorMiss.toInt()} " +
                "candidateDelay=${if (selectedExists) selectedDelay.toInt() else -1}ms " +
                "candidateTapTimes=${selectedPlan?.tapTimesMs?.joinToString(",") { it.toInt().toString() } ?: "NONE"} " +
                "candidateY=${if (selectedExists) selectedEntryY.toInt() else -1} " +
                "candidateRange=${if (selectedExists) "${selectedMinY.toInt()}..${selectedMaxY.toInt()}" else "NONE"} " +
                "candidateTopBottom=${if (selectedExists) "${selectedMinTop.toInt()}..${selectedMaxBottom.toInt()}" else "NONE"} " +
                "candidateMiss=${if (selectedExists) selectedMiss.toInt() else -1} meanMiss=${selectedMeanMiss.toInt()} " +
                "tapCount=$selectedTapCount tapsOnPipe=$tapsOnActivePipe/$MAX_PLANNED_TAPS_PER_PIPE " +
                "candidates=$candidatesEvaluated actions={$actionCandidatesText} scheduled=$shouldScheduleTap " +
                "dispatchIn=${if (selectedExists) (selectedDelay - reactionMs).coerceAtLeast(0f).toInt() else -1}ms " +
                "latency=${fmt(reactionMs)}ms mode=$planMode feedbackPending=$tapFeedbackPending " +
                "spacingReady=$tapSpacingReady pendingTapIn=${if (pendingPipeTapAt > 0L && pendingPipeTapTrackId == activePipeTrackId) (pendingPipeTapAt - now).coerceAtLeast(0L) else -1L}ms " +
                "reason=$decisionReason"
        )

        attemptWaitDecisions++
        attemptLastAction = if (shouldScheduleTap) "SCHEDULE_COLLISION_INTERCEPT" else "WAIT_COLLISION_INTERCEPT"
        val waitReason = when {
            tapFeedbackPending -> "WAIT_TAP_FEEDBACK"
            !tapSpacingReady -> "MIN_TAP_INTERVAL"
            gestureInFlight.get() -> "GESTURE_IN_FLIGHT"
            !obstacleSpeedReady -> "PIPE_SPEED_CALIBRATION"
            !distanceWindow -> "OUT_OF_RANGE"
            !gapTrusted -> "LOW_GAP_CONFIDENCE"
            waitWorstMiss <= missThreshold -> "PREDICTED_PATH_SAFE"
            maxResponseDelay < minResponseDelay -> "NO_PRECONTACT_WINDOW"
            !selectedExists -> decisionReason
            shouldScheduleTap -> "DISPATCH_SCHEDULED_IN_${(selectedDelay - reactionMs).coerceAtLeast(0f).toInt()}MS"
            else -> "NO_DISPATCH"
        }
        appendRunLog("ACTION_SPACE selected=WAIT reason=$waitReason planMode=$planMode")
        screenStatus = when {
            shouldScheduleTap -> "ПОДВОД К ПРОСВЕТУ • тапаю по времени"
            waitWorstMiss <= missThreshold -> "ТРУБА • траектория проходит в просвет"
            else -> "ПРОГНОЗ ТРУБЫ • $waitReason"
        }
        observeQLearningDecision(stateKey, RL_WAIT, now, pipePassed)
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
        cancelScheduledInterceptTap("NEW_SESSION")
        resetPipeGeometryMemory()
        estimatedTapResponseLatencyMs = 68f
        tapLatencySamples = 0
        appendRunLog("TAP_LATENCY_SESSION value=${fmt(estimatedTapResponseLatencyMs)}ms samples=$tapLatencySamples")
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
        previousObservedBirdWidth = -1
        previousObservedBirdHeight = -1
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
        cancelScheduledInterceptTap("MONITORING_STOPPED")
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
        if (source != "COLLISION_INTERCEPT") cancelScheduledInterceptTap("OTHER_TAP_$source")
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
                    var requestAt = 0L
                    synchronized(tapObservationLock) {
                        if (pendingTapObservation?.id == tapId) {
                            requestAt = pendingTapObservation?.requestedAt ?: 0L
                            pendingTapObservation?.completedAt = lastGestureCompletedAt
                        }
                    }
                    val requestToComplete = if (requestAt > 0L)
                        lastGestureCompletedAt - requestAt else lastGestureCompletedAt - dispatchAt
                    appendRunLog(
                        "GESTURE_COMPLETED id=$tapId elapsed=${lastGestureCompletedAt - dispatchAt}ms " +
                            "requestToComplete=${requestToComplete}ms physics_latency_not_calibrated=true " +
                            "awaiting_confirmed_vertical_impulse=true"
                    )
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
        cancelScheduledInterceptTap("NEW_ATTEMPT")
        resetPipeGeometryMemory()
        previousObservedBirdWidth = -1
        previousObservedBirdHeight = -1
        previousObstacleX = -1
        previousObstacleAt = 0L
        smoothedObstacleSpeed = 0f
        gravitySamples.clear()
        estimatedGravity = 0.0018f
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
        learningPersistExecutor.shutdown()
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
        @Volatile var lastControlMs = 0L
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
        // At the 80 ms frame cadence, the feedback-frame gate adds one frame; 200 ms yields an effective ~240–280 ms tap gap.
        private const val MIN_TAP_INTERVAL_MS = 200L
        private const val RL_REWARD_SCHEMA = 3
        private const val MIN_GAP_CONFIDENCE_FOR_CONTROL = 0.30f
        private const val RL_MAX_Q_STATES = 5000
        private const val RL_MAX_EPISODE_TRANSITIONS = 1800
        // One safely explored FLAP gives the first actionable comparison; later policy choices keep updating it.
        private const val RL_MIN_ACTION_VISITS = 1
        private const val RL_Q_DECISION_MARGIN = 0.20f
        private const val RL_DISCOUNT = 0.92f
        private const val RL_LEARNING_RATE = 0.22f
        private const val RL_SURVIVAL_REWARD_PER_80_MS = 0.01f
        private const val RL_FLAP_ACTION_COST = -0.025f
        private const val RL_PIPE_PASS_REWARD = 4.0f
        private const val RL_DEATH_PENALTY = -5.0f
        private const val MAX_RESCUE_TAPS_PER_PIPE = 3
        private const val MAX_PLANNED_TAPS_PER_PIPE = 3
        private const val PERFORMANCE_LOG_THRESHOLD_MS = 45L
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
