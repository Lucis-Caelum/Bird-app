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
    private var previousBirdAt = 0L
    private var lastAutoTapAt = 0L
    private var lastSceneSeenAt = 0L
    private var previousObstacleX = -1
    private var previousObstacleAt = 0L
    private var smoothedObstacleSpeed = 0f
    private var previousVerticalVelocity = 0f
    private var previousRawVerticalVelocity = 0f
    private var estimatedFlapVelocity = -0.55f
    private var estimatedGravity = 0.0018f
    private data class PendingTapObservation(
        val id: Long,
        val source: String,
        val requestedAt: Long,
        val baselineVelocity: Float,
        val baselineY: Int,
        @Volatile var completedAt: Long = 0L
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
    private var runStartedAt = 0L
    private var attemptCount = 0
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
    private var attemptManualTaps = 0
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
        ensureQLearningLoaded()
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
        val detectionStartedAt = SystemClock.elapsedRealtime()
        val detection = detectPlayfield(field)
        lastDetectionMs = SystemClock.elapsedRealtime() - detectionStartedAt
        if (detection.activeGame && !wasActiveGame) {
            ensureQLearningLoaded()
            resetQLearningAttempt()
            gravitySamples.clear()
            previousBirdY = -1
            previousBirdAt = 0L
            lastAutoTapAt = 0L
            previousObstacleX = -1
            previousObstacleAt = 0L
            smoothedObstacleSpeed = 0f
            previousVerticalVelocity = 0f
            previousRawVerticalVelocity = 0f
            estimatedFlapVelocity = -0.55f
            estimatedGravity = 0.0018f
            synchronized(tapObservationLock) { pendingTapObservation = null }
            autoTapCount = 0
            manualTapCount = 0
            attemptCount++
            resetAttemptDiagnostics()
            appendRunLog("=== НАЧАЛО ПОПЫТКИ #$attemptCount ===")
            screenStatus = "ИГРА НАЧАЛАСЬ • MediaProjection"
        } else if (!detection.activeGame && wasActiveGame) {
            appendRunLog("=== КОНЕЦ ПОПЫТКИ #$attemptCount ===")
            finishQLearningAttempt(true)
            finishAttemptDiagnostics("GAME_OVER_OR_SCENE_LOST")
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
        screenStatus = "$screenStatus • CV ${lastDetectionMs}мс"
    }

    private data class Detection(val birdX:Int=-1,val birdY:Int=-1,val obstacleX:Int=-1,val gapTop:Int=-1,val gapBottom:Int=-1,val activeGame:Boolean=false,val message:String,val birdBounds:String="NONE",val pipeBounds:String="NONE",val pipeRuns:String="NONE",val pipeConfidence:Float=0f)
    private fun detectPlayfield(bitmap: Bitmap): Detection {
        val w = bitmap.width
        val h = bitmap.height
        val pixelCount = w * h
        if (detectorPixels.size < pixelCount) detectorPixels = IntArray(pixelCount)
        bitmap.getPixels(detectorPixels, 0, w, 0, 0, w, h)
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
        val birdFound = activeGame && birdPixels >= 24 &&
            maxBirdX - minBirdX >= 12 && maxBirdY - minBirdY >= 18
        val birdX = if (birdFound) (minBirdX + maxBirdX) / 2 else -1
        val birdY = if (birdFound) (minBirdY + maxBirdY) / 2 else -1
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
                else "ИГРА • счёт распознан, ищу персонажа"
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
                pipeRuns = runs.joinToString(";") { "${it.first}..${it.second}" },
                pipeConfidence = (0.22f + runs.size * 0.09f).coerceAtMost(0.52f)
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
                pipeRuns = runs.joinToString(";") { "${it.first}..${it.second}" },
                pipeConfidence = 0.42f
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
            birdX, birdY, pipeLeadingEdgeX, gapTop, gapBottom, true,
            "ПТИЦА x=$birdX y=$birdY • ТРУБА x=$pipeLeadingEdgeX • ПРОХОД $gapTop..$gapBottom",
            birdBounds = "$minBirdX..$maxBirdX,$minBirdY..$maxBirdY",
            pipeBounds = "${nearest.first}..${nearest.second}",
            pipeRuns = runs.joinToString(";") { "${it.first}..${it.second}" },
            pipeConfidence = pipeConfidence
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
            val committed = getSharedPreferences("bird_play_learning", MODE_PRIVATE).edit()
                .putString("q_table", serialized)
                .putString("q_table_meta", meta.toString())
                .commit()
            if (committed) {
                qLearningRevision = revision
                qLearningUpdatedAt = updatedAt
                qLearningChecksum = checksum
                qLearningMetaStatus = "SAVED"
                learnedRevision = revision
                learnedUpdatedAt = updatedAt
                learnedChecksum = checksum
                learnedStateCount = qLearningTable.size
                learnedPipeCount = qLearningTotalPipePasses
                appendRunLog("RL_TABLE_SAVED revision=" + revision + " states=" + qLearningTable.size +
                    " episodes=" + qLearningTotalEpisodes + " totalPipes=" + qLearningTotalPipePasses +
                    " checksum=" + checksum + " commit=OK")
            } else {
                qLearningMetaStatus = "SAVE_FAILED"
                appendRunLog("RL_TABLE_SAVED revision=" + revision + " states=" + qLearningTable.size +
                    " checksum=" + checksum + " commit=FAILED")
            }
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
        attemptManualTaps = 0
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
                "noTrackFrames=$attemptNoTrackFrames autoTaps=$attemptAutoTaps manualTaps=$attemptManualTaps " +
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

        val currentRightX = d.pipeBounds.substringAfter("..").toIntOrNull() ?: currentX
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
    ): Pair<Boolean, Boolean> {
        val snapshot = synchronized(tapObservationLock) {
            pendingTapObservation?.let { it to it.completedAt }
        } ?: return false to false
        val pending = snapshot.first
        val completedAt = snapshot.second
        // Ignore a frame overlapping the gesture. Measure a complete bird-motion interval
        // that starts after Android confirms completion.
        if (completedAt <= 0L || previousBirdAt < completedAt || now - completedAt < 45L) {
            return true to true
        }
        val observed = (rawVelocity - gravity * dt * 0.5f).coerceIn(-3.2f, 0.8f)
        val oldEstimate = estimatedFlapVelocity
        val confirmed = observed < -0.08f
        if (confirmed) {
            estimatedFlapVelocity = (estimatedFlapVelocity * 0.35f + observed * 0.65f)
                .coerceIn(-3.0f, -0.08f)
        }
        appendRunLog(
            "PHYSICS_RESPONSE tapId=${pending.id} source=${pending.source} " +
                "requestAge=${now - pending.requestedAt}ms postGesture=${now - completedAt}ms " +
                "baselineY=${pending.baselineY} baselineV=${fmt(pending.baselineVelocity)} " +
                "raw=${fmt(rawVelocity)} observed=${fmt(observed)} confirmed=$confirmed " +
                "flapV=${fmt(oldEstimate)}->${fmt(estimatedFlapVelocity)}"
        )
        synchronized(tapObservationLock) {
            if (pendingTapObservation?.id == pending.id) pendingTapObservation = null
        }
        // This sample belongs to the automated tap, not to a manual flap.
        return false to true
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
        val velocity = if (previousBirdAt > 0L)
            previousVerticalVelocity * 0.32f + instantaneousVelocity * 0.68f else 0f
        val (tapFeedbackPending, hadTapFeedback) = updateTapFeedback(now, dt, rawVelocity, gravity)
        if (!tapFeedbackPending && !hadTapFeedback && impulseDetected &&
            (lastAutoTapAt == 0L || now - lastAutoTapAt > 280L)) {
            val manualFlapVelocity = (rawVelocity - gravity * dt * 0.5f).coerceIn(-3.0f, -0.08f)
            estimatedFlapVelocity = (estimatedFlapVelocity * 0.35f + manualFlapVelocity * 0.65f)
                .coerceIn(-3.0f, -0.08f)
            manualTapCount++
            attemptManualTaps++
            if (pendingRlState != null) pendingRlAction = RL_FLAP
            appendRunLog("ACTION manual_tap_detected estimatedFlapV=" + fmt(estimatedFlapVelocity) + " count=" + manualTapCount + " rl_action=FLAP")
        }
        previousBirdY = d.birdY
        previousBirdAt = now
        previousVerticalVelocity = velocity
        previousRawVerticalVelocity = rawVelocity

        val sx = resources.displayMetrics.widthPixels.toFloat() / screenWidth.coerceAtLeast(1)
        val sy = resources.displayMetrics.heightPixels.toFloat() / screenHeight.coerceAtLeast(1)
        val tapX = (screenWidth * 0.78f * sx).coerceIn(1f, resources.displayMetrics.widthPixels - 1f)
        val tapY = ((cropTop + (screenHeight - cropTop) * 0.52f) * sy).coerceIn(1f, resources.displayMetrics.heightPixels - 1f)

        val hasGap = d.obstacleX > d.birdX && d.gapTop >= 0 && d.gapBottom > d.gapTop
        if (!hasGap) {
            appendRunLog(
                "FRAME bird=" + d.birdY + " v=" + fmt(velocity) + " pipe=" + d.obstacleX +
                    " gap=" + d.gapTop + ".." + d.gapBottom + " dt=" + dt.toInt() +
                    "ms pipePassed=" + pipePassed + " decision=WAIT_GAP"
            )
            if (pipePassed) rewardPipePassWithoutGap(now)

            val pipeVisibleButUnconfirmed = d.obstacleX >= 0 && d.pipeBounds != "NONE"
            if (!hasSeenPipeThisAttempt && !pipeVisibleButUnconfirmed) {
                // The launch correction is allowed only before the first pipe is ever seen.
                val launchHorizon = 120f
                val projectedLaunchY = d.birdY + velocity * launchHorizon +
                    0.5f * gravity * launchHorizon * launchHorizon
                val launchGuardY = fieldHeight * 0.68f
                if (!earlyLaunchTapUsed && projectedLaunchY > launchGuardY && velocity > 0.08f) {
                    if (tap(tapX, tapY, "LAUNCH", velocity, d.birdY)) {
                        earlyLaunchTapUsed = true
                        lastAutoTapAt = now
                        autoTapCount++
                        attemptAutoTaps++
                        attemptLastAction = "FLAP_LAUNCH"
                        appendRunLog("ACTION single_launch_tap accepted id=$lastTapRequestId y=" + d.birdY +
                            " projected=" + projectedLaunchY.toInt())
                        screenStatus = "ПЕРВЫЙ ПОДЪЁМ • одна стартовая коррекция"
                    } else {
                        appendRunLog("ACTION single_launch_tap busy_or_rejected")
                    }
                } else {
                    screenStatus = "ПТИЦА y=" + d.birdY + " • ожидаю первую трубу"
                }
            } else if (pipeVisibleButUnconfirmed) {
                // A visible pipe with uncertain geometry is not a cruise-control opportunity.
                screenStatus = "ТРУБА x=" + d.obstacleX + " • уточняю просвет"
                appendRunLog("INTERPIPE_CONTROL action=WAIT reason=PIPE_GAP_UNCONFIRMED")
            } else {
                // Between pipes, ignore all floating pickups and hold a broad central flight band.
                // Re-plan after each measured flap response; never send a blind tap sequence.
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
                val tapSpacingReady = lastAutoTapAt == 0L || now - lastAutoTapAt >= 160L
                val cruiseNeedsLift = predictedWithoutTap > cruiseLower && velocity > -0.12f
                val tapKeepsInField = predictedWithTap in cruiseUpper..cruiseLower
                val tapMeaningfullyImproves = predictedWithoutTap - predictedWithTap >= minImprovement
                val cruiseTapReady = !tapFeedbackPending && !gestureInFlight.get() && tapSpacingReady
                val shouldCruiseTap = cruiseTapReady && cruiseNeedsLift && tapKeepsInField &&
                    tapMeaningfullyImproves

                if (shouldCruiseTap) {
                    if (tap(tapX, tapY, "CRUISE", velocity, d.birdY)) {
                        lastAutoTapAt = now
                        autoTapCount++
                        attemptAutoTaps++
                        attemptFlapDecisions++
                        attemptLastAction = "FLAP_INTERPIPE"
                        appendRunLog(
                            "INTERPIPE_CONTROL action=FLAP y=${d.birdY} v=${fmt(velocity)} " +
                                "wait300=${predictedWithoutTap.toInt()} tap300=${predictedWithTap.toInt()} " +
                                "band=${cruiseUpper.toInt()}..${cruiseLower.toInt()}"
                        )
                    } else {
                        attemptLastAction = "FLAP_INTERPIPE_REJECTED"
                        appendRunLog("INTERPIPE_CONTROL action=WAIT reason=TAP_REJECTED")
                    }
                } else {
                    attemptWaitDecisions++
                    attemptLastAction = "WAIT_INTERPIPE"
                    val reason = when {
                        tapFeedbackPending -> "WAIT_TAP_FEEDBACK"
                        gestureInFlight.get() -> "GESTURE_IN_FLIGHT"
                        !tapSpacingReady -> "INTERTAP_SPACING"
                        !cruiseNeedsLift -> "TRAJECTORY_IN_BAND_OR_RISING"
                        !tapKeepsInField -> "TAP_WOULD_OVERSHOOT"
                        else -> "TAP_NOT_USEFUL"
                    }
                    appendRunLog(
                        "INTERPIPE_CONTROL action=WAIT reason=$reason y=${d.birdY} v=${fmt(velocity)} " +
                            "wait300=${predictedWithoutTap.toInt()} tap300=${predictedWithTap.toInt()} " +
                            "band=${cruiseUpper.toInt()}..${cruiseLower.toInt()}"
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
        val birdHalfWidth = d.birdBounds.substringBefore(',').split("..")
            .takeIf { it.size == 2 }
            ?.let { ((it[1].toIntOrNull() ?: 0) - (it[0].toIntOrNull() ?: 0)).coerceAtLeast(0) / 2f }
            ?.takeIf { it > 0f } ?: fieldWidth * 0.018f
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
        val birdHalfHeight = d.birdBounds.substringAfter(',').split("..")
            .takeIf { it.size == 2 }
            ?.let { ((it[1].toIntOrNull() ?: 0) - (it[0].toIntOrNull() ?: 0)).coerceAtLeast(0) / 2f }
            ?.takeIf { it > 0f } ?: fieldHeight * 0.04f
        val safeTop = gapTop + safeMargin + birdHalfHeight
        val safeBottom = gapBottom - safeMargin - birdHalfHeight
        val safeWindowValid = safeBottom > safeTop
        val distanceWindow = distance > 0f && distance < fieldWidth * 1.8f

        fun missDistance(y: Float): Float = when {
            y < safeTop -> safeTop - y
            y > safeBottom -> y - safeBottom
            else -> 0f
        }

        val pipeWidth = d.pipeBounds.split("..").let {
            if (it.size == 2) ((it[1].toIntOrNull() ?: 0) - (it[0].toIntOrNull() ?: 0)).coerceAtLeast(0).toFloat()
            else fieldWidth * 0.08f
        }
        val birdWidth = d.birdBounds.substringBefore(',').split("..").let {
            if (it.size == 2) ((it[1].toIntOrNull() ?: 0) - (it[0].toIntOrNull() ?: 0)).coerceAtLeast(0).toFloat()
            else fieldWidth * 0.04f
        }
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
        // Android captures every ~80 ms and gesture completion/feedback must be observed before
        // another tap. Use intervals aligned to that cadence so the selected spacing can be executed.
        val interTapIntervals = floatArrayOf(160f, 240f, 320f, 400f)
        val elapsedSinceLastImpulse = if (lastAutoTapAt > 0L)
            (now - lastAutoTapAt).toFloat() - reactionMs else Float.POSITIVE_INFINITY
        val firstTapDelays = if (lastAutoTapAt > 0L) {
            interTapIntervals.map { (it - elapsedSinceLastImpulse).coerceAtLeast(reactionMs) }
                .distinct().sorted().toFloatArray()
        } else floatArrayOf(reactionMs)
        var schedulesEvaluated = 0

        fun evaluateSchedule(schedule: List<Float>) {
            if (schedule.isEmpty() || schedule.size > 4) return
            // Do not count taps scheduled after the pipe starts overlapping the bird.
            if (schedule.last() + 35f >= timeToPipe) return
            val taps = schedule.toFloatArray()
            val entryY = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, timeToPipe, taps)
            val middleY = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, middleTime, taps)
            val exitY = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, exitTime, taps)
            val misses = floatArrayOf(missDistance(entryY), missDistance(middleY), missDistance(exitY))
            val worstMiss = misses.maxOrNull() ?: 0f
            val meanMiss = misses.average().toFloat()
            // Prefer fewer taps when the predicted path safety is similar.
            val score = worstMiss + schedule.size * 6f + meanMiss * 0.12f
            schedulesEvaluated++
            val count = schedule.size
            val previous = bestByTapCount[count]
            if (previous == null || score < previous.score) {
                bestByTapCount[count] = TapPlan(taps, entryY, middleY, exitY, worstMiss, meanMiss, score)
            }
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

        var bestPlan: TapPlan? = null
        var bestPlanCount = 0
        var bestPlanScore = zeroWorstMiss
        for (count in 1..4) {
            val candidate = bestByTapCount[count] ?: continue
            if (candidate.score < bestPlanScore) {
                bestPlan = candidate
                bestPlanCount = count
                bestPlanScore = candidate.score
            }
        }
        val bestPlanMiss = bestPlan?.worstMiss ?: zeroWorstMiss
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
        val minUsefulImprovement = max(7f, safeMargin * 0.06f)
        val safetyMissThreshold = max(gapHeight * 0.12f, fieldHeight * 0.02f)
        val recoveryPossible = bestPlan != null &&
            bestPlanScore + minUsefulImprovement < zeroWorstMiss &&
            bestPlanMiss < zeroWorstMiss * 0.94f
        // Require credible gap geometry except in a close-range emergency.
        val gapTrusted = d.pipeConfidence >= 0.55f ||
            (timeToPipe <= 250f && d.pipeConfidence >= 0.42f)
        val tapWindowReady = firstTapDelay <= reactionMs + 20f
        val shouldTap = distanceWindow && safeWindowValid && gapTrusted && !tapFeedbackPending &&
            tapWindowReady && zeroWorstMiss > safetyMissThreshold && recoveryPossible

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
            recoveryPossible && !tapWindowReady -> "WAIT_INTERTAP_INTERVAL"
            zeroWorstMiss <= safetyMissThreshold -> "PATH_SAFE_WAIT"
            recoveryPossible && bestPlanCount >= 3 -> "RECOVERY_PLAN_${bestPlanCount}_TAPS"
            recoveryPossible -> "RECOVERY_PLAN_${bestPlanCount}_TAP"
            else -> "NO_SAFE_RECOVERY_PLAN"
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
                " safe=${safeTop.toInt()}..${safeBottom.toInt()} flapV=${fmt(estimatedFlapVelocity)}" +
                " candidates={$scenarioText} schedules=$schedulesEvaluated elapsedSinceImpulse=${if (lastAutoTapAt > 0L) elapsedSinceLastImpulse.toInt() else -1}ms" +
                " nextImpulseDelay=${firstTapDelay.toInt()}ms intervalReady=$tapWindowReady feedbackPending=$tapFeedbackPending" +
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
                autoTapCount++
                attemptAutoTaps++
                attemptLastAction = "FLAP_REQUESTED"
                appendRunLog(
                    "ACTION auto_tap accepted id=$lastTapRequestId count=$autoTapCount " +
                        "plan=$bestPlanCount firstDelay=${firstTapDelay.toInt()}ms intervals=$scheduleText"
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
        ensureQLearningLoaded()
        if (instance == null) {
            screenStatus = "Включи службу специальных возможностей"
            return
        }
        manual = false
        paused = false
        monitoring = true
        synchronized(runLogLock) { runLog.clear() }
        runStartedAt = SystemClock.elapsedRealtime()
        attemptCount = 0
        appendRunLog("=== СЕССИЯ МОНИТОРИНГА ===")
        appendRunLog("RL_TABLE_SESSION status=" + qLearningMetaStatus + " revision=" +
            qLearningRevision + " states=" + qLearningTable.size + " updatedAt=" +
            qLearningUpdatedAt + " checksum=" + qLearningChecksum +
            " totalPipes=" + qLearningTotalPipePasses + " totalEpisodes=" + qLearningTotalEpisodes)
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
        smoothedObstacleSpeed = 0f
        previousVerticalVelocity = 0f
        previousRawVerticalVelocity = 0f
        estimatedFlapVelocity = -0.55f
        estimatedGravity = 0.0018f
        gravitySamples.clear()
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

    private fun fmt(value: Float): String = String.format(java.util.Locale.US, "%.3f", value)

    private fun appendRunLog(message: String) {
        val elapsed = if (runStartedAt > 0L) SystemClock.elapsedRealtime() - runStartedAt else 0L
        val line = "$elapsed ms | $message"
        synchronized(runLogLock) {
            if (runLog.size >= 4000) runLog.removeFirst()
            runLog.addLast(line)
        }
    }

    private fun exportRunLog(): String = synchronized(runLogLock) {
        if (runLog.isEmpty()) "Лог пока пуст. Запусти игру с включённым автопилотом."
        else runLog.joinToString(separator = "\n")
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
        @Volatile var manualTapCount = 0
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
        @Volatile private var instance: GameAccessibilityService? = null

        fun getRunLog(): String = instance?.exportRunLog() ?: "Служба Bird Play не подключена."

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
