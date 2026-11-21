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
    private var tapObservationPendingAt = 0L
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
    private var tapSequence = 0L
    private var lastTapRequestId = 0L
    private var pendingTapObservationId = 0L
    private var pendingTapBaselineVelocity = 0f
    private var pendingTapSource = "NONE"
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
            tapObservationPendingAt = 0L
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
        lastPipeTrackLogAt = 0L
        lastPipeTrackGap = "NONE"
        pendingTapObservationId = 0L
        pendingTapBaselineVelocity = 0f
        pendingTapSource = "NONE"
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
        if (currentX < 0) return false
        val staleAge = if (lastTrackedPipeAt > 0L) now - lastTrackedPipeAt else 0L
        val staleTrack = lastTrackedPipeAt > 0L && staleAge > 1200L
        if (staleTrack) {
            appendRunLog("PIPE_TRACK_STALE id=$activePipeTrackId age=${staleAge}ms lastX=$lastTrackedPipeX")
            lastTrackedPipeX = -1
            trackedPipeNearBird = false
        }
        val oldX = lastTrackedPipeX
        val previousWasNear = trackedPipeNearBird
        // A jump to the right means the detector switched to the next visible pipe.
        val changedToNextPipe = oldX >= 0 && currentX > oldX + fieldWidth * 0.14f
        val passed = changedToNextPipe && previousWasNear &&
            oldX < d.birdX + fieldWidth * 0.24f
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
                    "previousNear=$previousWasNear passConfirmed=$passed"
            )
            trackedPipeNearBird = false
        }
        val nearBirdTrackingZone = currentX <= d.birdX + fieldWidth * 0.24f &&
            currentX >= d.birdX - fieldWidth * 0.20f
        if (nearBirdTrackingZone) trackedPipeNearBird = true
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
        if (passed) {
            qLearningPipePasses++
            qLearningTotalPipePasses++
            attemptPipePasses++
            learnedPipeCount = qLearningTotalPipePasses
            appendRunLog(
                "EVENT pipe_passed attempt=$qLearningPipePasses total=$qLearningTotalPipePasses " +
                    "trackId=${activePipeTrackId - 1} previousX=$oldX nextPipeX=$currentX mode=NEXT_PIPE_SWITCH"
            )
        }
        lastTrackedPipeX = currentX
        lastTrackedPipeAt = now
        return passed
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
        for (tapAt in tapTimesMs.sortedArray()) {
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

        val pipePassed = if (d.obstacleX >= 0 && d.pipeBounds != "NONE")
            trackPipePass(d, fieldWidth, now) else false

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
        if (tapObservationPendingAt > 0L && now - tapObservationPendingAt >= 65L && dt > 0f) {
            val observedFlapVelocity = (rawVelocity - gravity * dt * 0.5f).coerceIn(-2.5f, 0.8f)
            val previousFlapEstimate = estimatedFlapVelocity
            if (observedFlapVelocity < -0.03f) {
                estimatedFlapVelocity = (estimatedFlapVelocity * 0.55f + observedFlapVelocity * 0.45f)
                    .coerceIn(-2.5f, -0.08f)
            }
            appendRunLog(
                "PHYSICS_RESPONSE tapId=$pendingTapObservationId source=$pendingTapSource " +
                    "delay=${now - tapObservationPendingAt}ms vBefore=${fmt(pendingTapBaselineVelocity)} " +
                    "raw=${fmt(rawVelocity)} observed=${fmt(observedFlapVelocity)} " +
                    "flapV=${fmt(previousFlapEstimate)}->${fmt(estimatedFlapVelocity)} " +
                    "delta=${fmt(observedFlapVelocity - pendingTapBaselineVelocity)}"
            )
            tapObservationPendingAt = 0L
            pendingTapObservationId = 0L
            pendingTapSource = "NONE"
        } else if (tapObservationPendingAt == 0L && impulseDetected &&
            (lastAutoTapAt == 0L || now - lastAutoTapAt > 280L)) {
            val manualFlapVelocity = (rawVelocity - gravity * dt * 0.5f).coerceIn(-2.5f, -0.08f)
            estimatedFlapVelocity = (estimatedFlapVelocity * 0.55f + manualFlapVelocity * 0.45f)
                .coerceIn(-2.5f, -0.08f)
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
            appendRunLog("FRAME bird=" + d.birdY + " v=" + fmt(velocity) + " pipe=" + d.obstacleX + " gap=UNKNOWN dt=" + dt.toInt() + "ms pipePassed=" + pipePassed + " decision=WAIT_GAP")
            if (pipePassed) rewardPipePassWithoutGap(now)
            // Allow one launch-stabilization flap only; repeated blind flaps caused the previous overshoot.
            val launchHorizon = 120f
            val projectedLaunchY = d.birdY + velocity * launchHorizon + 0.5f * gravity * launchHorizon * launchHorizon
            val launchGuardY = fieldHeight * 0.68f
            if (!earlyLaunchTapUsed && projectedLaunchY > launchGuardY && velocity > 0.08f) {
                if (tap(tapX, tapY, "LAUNCH")) {
                    earlyLaunchTapUsed = true
                    lastAutoTapAt = now
                    tapObservationPendingAt = now
                    pendingTapObservationId = lastTapRequestId
                    pendingTapBaselineVelocity = velocity
                    pendingTapSource = "LAUNCH"
                    autoTapCount++
                    attemptAutoTaps++
                    attemptLastAction = "FLAP_LAUNCH"
                    appendRunLog("ACTION single_launch_tap accepted id=$lastTapRequestId y=" + d.birdY + " projected=" + projectedLaunchY.toInt())
                    screenStatus = "ПЕРВЫЙ ПОДЪЁМ • одна стартовая коррекция"
                } else {
                    appendRunLog("ACTION single_launch_tap busy_or_rejected")
                }
            } else {
                screenStatus = if (d.obstacleX >= 0) "ТРУБА x=" + d.obstacleX + " • уточняю просвет"
                    else "ПТИЦА y=" + d.birdY + " • ожидаю первую трубу"
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
        // Compare wait, one flap, and staged two-flap recovery at the pipe crossing point.
        val reactionMs = 65f
        val noTapPrediction = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, timeToPipe, floatArrayOf())
        val afterTapPrediction = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, timeToPipe, floatArrayOf(reactionMs))
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
        val noTapMissDistance = missDistance(noTapPrediction)
        val afterTapMissDistance = missDistance(afterTapPrediction)
        val earlyCorrectionBand = (gapHeight * 0.10f).coerceAtLeast(fieldHeight * 0.008f)
        val predictedTooLow = noTapPrediction > safeBottom - earlyCorrectionBand

        var bestPlanY = afterTapPrediction
        var bestPlanMiss = afterTapMissDistance
        var bestTwoTapY = noTapPrediction
        var bestTwoTapMiss = Float.POSITIVE_INFINITY
        var plannedTapCount = 1
        var secondTapDelay = 0f
        // A second flap is a candidate, not a pre-committed tap: the next frame re-evaluates
        // the observed motion before any follow-up gesture can be sent.
        for (spacing in floatArrayOf(150f, 210f, 270f, 330f)) {
            val secondAt = reactionMs + spacing
            if (timeToPipe <= secondAt + 45f) continue
            val candidateY = predictTrajectoryAtPipe(
                d.birdY.toFloat(), velocity, gravity, timeToPipe, floatArrayOf(reactionMs, secondAt)
            )
            val candidateMiss = missDistance(candidateY)
            if (candidateMiss < bestTwoTapMiss) {
                bestTwoTapY = candidateY
                bestTwoTapMiss = candidateMiss
            }
            if (candidateMiss < bestPlanMiss) {
                bestPlanY = candidateY
                bestPlanMiss = candidateMiss
                plannedTapCount = 2
                secondTapDelay = spacing
            }
        }
        val minUsefulImprovement = max(7f, safeMargin * 0.06f)
        val stagedRecoveryPossible = bestPlanMiss + minUsefulImprovement < noTapMissDistance &&
            bestPlanMiss < noTapMissDistance * 0.94f
        val qCanFlapWithoutRisk = noTapPrediction >= safeTop && noTapPrediction <= safeBottom &&
            afterTapPrediction >= safeTop && afterTapPrediction <= safeBottom
        val stateKey = encodeQLearningState(velocity, timeToPipe, targetY - d.birdY, gapHeight, fieldHeight)
        val fallbackAction = if (distanceWindow && timeToPipe > reactionMs + 35f &&
            predictedTooLow && stagedRecoveryPossible) RL_FLAP else RL_WAIT
        val qAction = qLearningChoice(stateKey, fallbackAction, qCanFlapWithoutRisk)
        val qEntry = qLearningTable[stateKey]
        val qFlap = qEntry?.values?.get(RL_FLAP) ?: 0f
        val qWait = qEntry?.values?.get(RL_WAIT) ?: 0f
        val qFlapVisits = qEntry?.visits?.get(RL_FLAP) ?: 0
        val qWaitVisits = qEntry?.visits?.get(RL_WAIT) ?: 0
        val safetyRequiresTap = fallbackAction == RL_FLAP
        val shouldTap = distanceWindow && timeToPipe > reactionMs + 35f && safeWindowValid &&
            (safetyRequiresTap || (qAction == RL_FLAP && qCanFlapWithoutRisk))

        val wait100 = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, min(100f, timeToPipe), floatArrayOf())
        val wait200 = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, min(200f, timeToPipe), floatArrayOf())
        val wait300 = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, min(300f, timeToPipe), floatArrayOf())
        val wait400 = predictTrajectoryAtPipe(d.birdY.toFloat(), velocity, gravity, min(400f, timeToPipe), floatArrayOf())
        val decisionReason = when {
            !distanceWindow -> "OUT_OF_RANGE"
            timeToPipe <= reactionMs + 35f -> "TOO_LATE"
            !safeWindowValid -> "INVALID_SAFE_WINDOW"
            noTapPrediction < safeTop -> "TOO_HIGH_WAIT"
            safetyRequiresTap && plannedTapCount == 2 -> "STAGED_RECOVERY_2_TAPS"
            safetyRequiresTap -> "RECOVERY_TAP"
            predictedTooLow && !stagedRecoveryPossible -> "NO_RECOVERY_PATH"
            qAction == RL_FLAP && !qCanFlapWithoutRisk -> "QLEARNING_SHIELDED"
            shouldTap -> "QLEARNING_SAFE_TAP"
            else -> "WAIT"
        }
        attemptLastDecision = decisionReason
        if (shouldTap) attemptFlapDecisions++ else attemptWaitDecisions++
        appendRunLog(
            "FRAME bird=${d.birdY} box=${d.birdBounds} v=${fmt(velocity)} raw=${fmt(rawVelocity)} g=${fmt(gravity)}" +
                " pipeX=${d.obstacleX} pipeBox=${d.pipeBounds} runs=${d.pipeRuns} score=${fmt(d.pipeConfidence)} gap=${gapTop.toInt()}..${gapBottom.toInt()}" +
                " dist=${distance.toInt()} speed=${fmt(obstacleSpeed)} t=${timeToPipe.toInt()}" +
                " wait100=${wait100.toInt()} wait200=${wait200.toInt()} wait300=${wait300.toInt()} wait400=${wait400.toInt()}" +
                " noTap=${noTapPrediction.toInt()} oneTap=${afterTapPrediction.toInt()} twoTap=${bestTwoTapY.toInt()} bestPlan=${bestPlanY.toInt()}" +
                " planTaps=$plannedTapCount secondAfter=${secondTapDelay.toInt()}ms" +
                " missNo/One/Two/Plan=${noTapMissDistance.toInt()}/${afterTapMissDistance.toInt()}/${if (bestTwoTapMiss.isFinite()) bestTwoTapMiss.toInt() else -1}/${bestPlanMiss.toInt()}" +
                " safe=${safeTop.toInt()}..${safeBottom.toInt()} flapV=${fmt(estimatedFlapVelocity)}" +
                " fallback=${if (fallbackAction == RL_FLAP) "FLAP" else "WAIT"} state=$stateKey q=${fmt(qFlap)}/${fmt(qWait)} visits=$qFlapVisits/$qWaitVisits" +
                " tableRev=$qLearningRevision safetyRequired=$safetyRequiresTap qAction=${if (qAction == RL_FLAP) "FLAP" else "WAIT"}" +
                " recoveryPossible=$stagedRecoveryPossible predictedCollision=${bestPlanMiss > 0f} gestureBusy=${gestureInFlight.get()} pipePassed=$pipePassed decision=$decisionReason"
        )
        var actualAction = RL_WAIT
        if (shouldTap) {
            screenStatus = "КОРРЕКЦИЯ • план $plannedTapCount тап(ов) • прогноз $bestPlanY • окно " +
                safeTop.toInt() + ".." + safeBottom.toInt()
            if (tap(tapX, tapY, "AUTO")) {
                actualAction = RL_FLAP
                lastAutoTapAt = now
                tapObservationPendingAt = now
                pendingTapObservationId = lastTapRequestId
                pendingTapBaselineVelocity = velocity
                pendingTapSource = "AUTO"
                autoTapCount++
                attemptAutoTaps++
                attemptLastAction = "FLAP_REQUESTED"
                appendRunLog("ACTION auto_tap accepted id=$lastTapRequestId count=$autoTapCount plannedTaps=$plannedTapCount")
            } else {
                attemptLastAction = "FLAP_REJECTED"
                appendRunLog("ACTION auto_tap rejected reason=see_TAP_REJECTED_EVENT")
            }
        } else {
            attemptLastAction = "WAIT"
            val phase = when { velocity < -0.02f -> "ПОДЪЁМ"; velocity > 0.02f -> "ПАДЕНИЕ"; else -> "ВЕРШИНА ДУГИ" }
            screenStatus = phase + " • y=" + d.birdY + " • v=" + fmt(velocity) + " • t=" + timeToPipe.toInt() +
                "мс • прогноз=" + noTapPrediction.toInt() + " • окно=" + safeTop.toInt() + ".." + safeBottom.toInt()
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
        tapObservationPendingAt = 0L
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

    fun tap(x: Float, y: Float, source: String = "AUTO"): Boolean {
        val tapId = ++tapSequence
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
        appendRunLog("TAP_QUEUED id=$tapId source=$source")
        mainHandler.post {
            if (paused || manual || !monitoring) {
                gestureInFlight.set(false)
                attemptRejectedTaps++
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
                    appendRunLog("GESTURE_COMPLETED id=$tapId elapsed=${lastGestureCompletedAt - dispatchAt}ms redecision=next_capture_frame")
                    screenStatus = "ANDROID: КАСАНИЕ ВЫПОЛНЕНО"
                    gestureInFlight.set(false)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    lastTapStatus = "TAP CANCEL"
                    attemptGestureCancelled++
                    appendRunLog("GESTURE_CANCELLED id=$tapId elapsed=${SystemClock.elapsedRealtime() - dispatchAt}ms")
                    screenStatus = "ANDROID: КАСАНИЕ ОТМЕНЕНО"
                    gestureInFlight.set(false)
                }
            }, null)
            if (!accepted) {
                lastTapStatus = "TAP REJECTED"
                attemptRejectedTaps++
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
