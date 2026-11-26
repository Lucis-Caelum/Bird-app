package com.luciscaelum.slayautoplayer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.media.Image
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.luciscaelum.slayautoplayer.core.AutoPilot
import com.luciscaelum.slayautoplayer.core.FrameAnalyzer
import com.luciscaelum.slayautoplayer.core.SavedPhysics
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteOrder

/**
 * v0.50 — thin Android shell around the pure-Kotlin core (package ...core):
 *   frame (raw RGBA)  ->  FrameAnalyzer  ->  AutoPilot (state estimation + planner)  ->  tap gesture.
 * No Q-learning, no screenshots through the accessibility API, no per-pipe heuristics here.
 */
class GameAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val analyzer = FrameAnalyzer()
    private var pilot: AutoPilot? = null
    private var pilotW = 0
    private var pilotH = 0

    // run log
    private val logLock = Any()
    private val runLog = ArrayDeque<String>()
    private val pendingFlush = ArrayList<String>()
    private var logStart = System.currentTimeMillis()

    // statistics (frame thread only)
    private var lastPreviewMs = 0.0
    private var lastTraceMs = 0.0
    private var lastPerfMs = 0.0
    private var perfFrames = 0
    private var perfAnalyzeNs = 0L
    private var perfAgeMs = 0.0
    private var lastTapMs = 0.0
    @Volatile private var tapCancelled = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected = true
        screenStatus = "Служба подключена • включи захват экрана и панель"
        appendRunLog("SERVICE_CONNECTED version=${BuildConfigVersion.NAME}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* frames come from MediaProjection */ }

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

    override fun onDestroy() {
        if (instance === this) instance = null
        connected = false
        monitoring = false
        statPlaying = false
        flushLog()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ monitoring control

    private fun startMonitoringInternal() {
        synchronized(logLock) { runLog.clear(); pendingFlush.clear() }
        try { File(filesDir, RUN_LOG_FILE_NAME).delete() } catch (_: Exception) {}
        logStart = System.currentTimeMillis()
        autoTapCount = 0
        tapCancelled = 0
        statAttempt = 0; statPassed = 0; statLastResult = ""; statPlaying = false
        paused = false
        manual = false
        monitoring = true
        screenStatus = "Мониторинг включён • жду начала игры"
        appendRunLog("SESSION_START version=${BuildConfigVersion.NAME} tapStrokeMs=$TAP_STROKE_MS")
    }

    private fun stopMonitoring(reason: String) {
        if (monitoring) appendRunLog("SESSION_STOP reason=$reason taps=$autoTapCount cancelledGestures=$tapCancelled")
        monitoring = false
        paused = true
        screenStatus = reason
        flushLog()
    }

    // ------------------------------------------------------------------ frame pipeline (frame thread)

    private fun handleFrame(image: Image, tCapMs: Double, sw: Int, sh: Int) {
        val t0 = System.nanoTime()
        val plane = image.planes[0]
        val pixels = plane.buffer.order(ByteOrder.nativeOrder()).asIntBuffer()
        val stridePx = plane.rowStride / plane.pixelStride
        val cropTop = (sh * HEADER_CROP_FRACTION).toInt()
        val fieldH = sh - cropTop
        lastFrameWidth = sw
        lastFrameHeight = sh

        if (!monitoring) {
            val p = pilot
            if (p != null) { savePhysics(p); pilot = null }
            val now = t0 / 1e6
            if (now - lastPreviewMs < PREVIEW_INTERVAL_MS) return
            lastPreviewMs = now
            val scene = analyzer.analyze(pixels, stridePx, sw, sh, cropTop)
            publishScene(scene.birdX, scene.birdY, scene.pipes.firstOrNull()?.left, scene.pipes.firstOrNull()?.top, scene.pipes.firstOrNull()?.bottom)
            if (!paused || !manual) screenStatus = if (scene.active) "Игра видна • нажми АВТО" else "Жду игру (${scene.note})"
            return
        }

        var pl = pilot
        if (pl == null || pilotW != sw || pilotH != fieldH) {
            pl?.let { savePhysics(it) }
            val hit = loadHitScale(this)
            pl = AutoPilot(sw, fieldH, loadPhysics(), hit, hit)
            pilot = pl
            pilotW = sw
            pilotH = fieldH
            appendRunLog("PILOT_CREATED field=${sw}x$fieldH hitScale=${"%.2f".format(hit)} priors=${if (loadPhysics() != null) "SAVED" else "DEFAULT"}")
        }

        val scene = analyzer.analyze(pixels, stridePx, sw, sh, cropTop)
        val tAnalyzed = System.nanoTime()
        val tNowMs = tAnalyzed / 1e6
        val (decision, event) = pl.onScene(tCapMs, tNowMs, scene)

        analyzer.birdHintX = pl.birdXHint
        analyzer.birdHintW = pl.birdWHint
        statPlaying = scene.active
        statAttempt = pl.attempt
        statPassed = pl.passed
        statLatency = pl.latency
        if (event != null) {
            appendRunLog(event)
            if (event.startsWith("ATTEMPT_END")) {
                statLastResult = "${pl.passed} труб за ${"%.0f".format((tNowMs - pl.attemptStartMs) / 1000.0)} с"
                savePhysics(pl); flushLog()
            }
        }
        val first = scene.pipes.firstOrNull { it.right > scene.birdX }
        publishScene(scene.birdX, scene.birdY, first?.left, first?.top, first?.bottom)
        screenStatus = if (!scene.active) "Жду начала игры (${scene.note})"
        else "Играю: ${scene.note} • L=${pl.latency.toInt()}мс v=${"%.2f".format(pl.speed)}"

        if (decision != null) {
            if (decision.tap && !paused && !manual) {
                val ok = tap(sw, sh, cropTop)
                autoTapCount++
                lastTapMs = tNowMs
                lastTapStatus = "TAP ${decision.reason}${if (ok) "" else " ✗"}"
                appendRunLog("${decision.line} dispatch=${if (ok) "OK" else "FAILED"} g=${"%.5f".format(pl.g)} vf=${"%.3f".format(pl.vf)} L=${pl.latency.toInt()}")
            } else if (tNowMs - lastTraceMs >= TRACE_INTERVAL_MS || decision.reason == "VETO" || decision.reason == "OVERRIDE") {
                lastTraceMs = tNowMs
                appendRunLog(decision.line)
            }
        }

        // performance line every 2 s
        perfFrames++
        perfAnalyzeNs += tAnalyzed - t0
        perfAgeMs += tNowMs - tCapMs
        if (tNowMs - lastPerfMs >= 2000.0) {
            if (lastPerfMs > 0.0 && perfFrames > 0) {
                statFps = perfFrames * 1000.0 / (tNowMs - lastPerfMs)
                statAgeMs = perfAgeMs / perfFrames
                appendRunLog(
                    "PERF fps=${"%.1f".format(perfFrames * 1000.0 / (tNowMs - lastPerfMs))} " +
                        "analyzeMs=${"%.1f".format(perfAnalyzeNs / 1e6 / perfFrames)} ageMs=${"%.0f".format(perfAgeMs / perfFrames)} " +
                        "cancelledGestures=$tapCancelled"
                )
            }
            lastPerfMs = tNowMs
            perfFrames = 0; perfAnalyzeNs = 0L; perfAgeMs = 0.0
        }
    }

    private fun publishScene(bx: Double, by: Double, ox: Double?, gt: Double?, gb: Double?) {
        lastBirdX = bx.toInt()
        lastBirdY = by.toInt()
        lastObstacleX = ox?.toInt() ?: -1
        lastGapTop = gt?.toInt() ?: -1
        lastGapBottom = gb?.toInt() ?: -1
    }

    // ------------------------------------------------------------------ tap

    private val gestureCallback = object : GestureResultCallback() {
        override fun onCancelled(gestureDescription: GestureDescription?) { tapCancelled++ }
    }

    private fun tap(sw: Int, sh: Int, cropTop: Int): Boolean {
        val x = sw * TAP_X_FRACTION
        val y = cropTop + (sh - cropTop) * TAP_Y_FRACTION
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_STROKE_MS))
            .build()
        return try {
            // Called straight from the frame thread: posting to the main thread only adds latency.
            dispatchGesture(gesture, gestureCallback, null)
        } catch (e: Exception) {
            mainHandler.post { try { dispatchGesture(gesture, gestureCallback, null) } catch (_: Exception) {} }
            true
        }
    }

    // ------------------------------------------------------------------ physics persistence

    private fun savePhysics(p: AutoPilot) {
        val s = p.exportPhysics() ?: return
        try {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("gPerH", s.gPerH.toString())
                .putString("vfPerH", s.vfPerH.toString())
                .putString("latencyMs", s.latencyMs.toString())
                .putString("speedPerW", s.speedPerW.toString())
                .apply()
        } catch (_: Exception) {}
    }

    private fun loadPhysics(): SavedPhysics? {
        return try {
            val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val g = sp.getString("gPerH", null)?.toDouble() ?: return null
            val vf = sp.getString("vfPerH", null)?.toDouble() ?: return null
            val l = sp.getString("latencyMs", null)?.toDouble() ?: return null
            val s = sp.getString("speedPerW", null)?.toDouble() ?: return null
            if (g <= 0.0 || vf >= 0.0 || l !in 20.0..300.0 || s <= 0.0) null else SavedPhysics(g, vf, l, s)
        } catch (_: Exception) { null }
    }

    // ------------------------------------------------------------------ run log

    private fun appendRunLog(line: String) {
        val stamped = "${System.currentTimeMillis() - logStart} ms | $line"
        var flush = false
        synchronized(logLock) {
            runLog.addLast(stamped)
            while (runLog.size > MAX_RUN_LOG_LINES) runLog.removeFirst()
            pendingFlush.add(stamped)
            flush = pendingFlush.size >= LOG_FLUSH_BATCH
        }
        if (flush) flushLog()
    }

    private fun flushLog() {
        val batch: List<String> = synchronized(logLock) {
            if (pendingFlush.isEmpty()) null
            else ArrayList(pendingFlush).also { pendingFlush.clear() }
        } ?: return
        try {
            FileOutputStream(File(filesDir, RUN_LOG_FILE_NAME), true).use { out ->
                out.write((batch.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (_: Exception) {}
    }

    private fun exportRunLog(): String {
        flushLog()
        return synchronized(logLock) { runLog.joinToString("\n") }
    }

    // ------------------------------------------------------------------ public API (kept for the UI classes)

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
        @Volatile var autoTapCount = 0
        @Volatile var lastBirdX = -1
        @Volatile var lastBirdY = -1
        @Volatile var lastObstacleX = -1
        @Volatile var lastGapTop = -1
        @Volatile var lastGapBottom = -1
        @Volatile var lastTapStatus = "TAP —"

        /** True while the accessibility service is connected. */
        @Volatile var connected = false
            private set
        /** Set by MediaProjectionCaptureService while frames are flowing. */
        @Volatile var captureActive = false
        // live numbers for the overlay panel
        @Volatile var statPlaying = false
        @Volatile var statAttempt = 0
        @Volatile var statPassed = 0
        @Volatile var statFps = 0.0
        @Volatile var statAgeMs = 0.0
        @Volatile var statLatency = 0.0
        @Volatile var statLastResult = ""

        private const val SETTINGS = "bird_play_settings"
        const val DEFAULT_HIT_SCALE = 1.0

        fun loadHitScale(ctx: Context): Double = try {
            (ctx.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).getString("hitScale2", null)?.toDouble()
                ?: DEFAULT_HIT_SCALE).coerceIn(0.6, 1.6)
        } catch (_: Exception) { DEFAULT_HIT_SCALE }

        fun saveHitScale(ctx: Context, v: Double) {
            try { ctx.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE).edit().putString("hitScale2", v.toString()).apply() } catch (_: Exception) {}
        }

        private const val HEADER_CROP_FRACTION = 0.117
        private const val TAP_X_FRACTION = 0.78f
        private const val TAP_Y_FRACTION = 0.52f
        // Short press: the old 55 ms stroke made the gesture overlap the next tap and delayed its end.
        private const val TAP_STROKE_MS = 12L
        private const val PREVIEW_INTERVAL_MS = 400.0
        private const val TRACE_INTERVAL_MS = 120.0
        private const val MAX_RUN_LOG_LINES = 30000
        private const val LOG_FLUSH_BATCH = 64
        private const val RUN_LOG_FILE_NAME = "bird-play-session.txt"
        private const val PREFS = "bird_play_physics_v2"
        @Volatile private var instance: GameAccessibilityService? = null

        fun getRunLog(): String = instance?.exportRunLog() ?: "Служба Bird Play не подключена."

        fun getRunLog(context: Context): String {
            val active = instance
            if (active != null) return active.exportRunLog()
            val file = File(context.applicationContext.filesDir, RUN_LOG_FILE_NAME)
            return try {
                if (file.exists() && file.length() > 0L) file.readText(Charsets.UTF_8)
                else "Лог пока не создан. Включи автопилот и запусти игру."
            } catch (e: Exception) {
                "Не удалось прочитать журнал: " + e.javaClass.simpleName
            }
        }

        /** Called by MediaProjectionCaptureService for every captured frame (on its own thread). */
        fun processFrame(image: Image, captureMs: Double, screenW: Int, screenH: Int) {
            instance?.handleFrame(image, captureMs, screenW, screenH)
        }

        fun togglePause() {
            val service = instance ?: run { screenStatus = "Сначала включи службу специальных возможностей"; return }
            if (monitoring) service.stopMonitoring("Пауза") else service.startMonitoringInternal()
        }

        fun manualTakeover() {
            instance?.stopMonitoring("Ручное управление")
            manual = true
            paused = true
        }
    }
}

/** Single place for the human-readable version used in logs (keep in sync with build.gradle.kts). */
internal object BuildConfigVersion {
    const val NAME = "0.52.0"
}
