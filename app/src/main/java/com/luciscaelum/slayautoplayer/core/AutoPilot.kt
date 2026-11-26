package com.luciscaelum.slayautoplayer.core

import kotlin.math.max
import kotlin.math.min

/** Physics normalised by field size so it survives a change of screen / resolution. */
class SavedPhysics(val gPerH: Double, val vfPerH: Double, val latencyMs: Double, val speedPerW: Double)

class Decision(
    val tap: Boolean,
    val reason: String,
    val birdY: Double,
    val birdV: Double,
    val line: String
)

/**
 * Everything between "scene seen in a frame" and "tap or not". Pure Kotlin, no Android classes:
 * the same code runs on the phone and in tools/simulator.
 *
 * Time is always in milliseconds on ONE monotonic axis: [tCap] = when the frame was captured,
 * [tNow] = when the decision is made (tNow - tCap = pipeline delay that the controller compensates).
 */
class AutoPilot(
    private val fieldW: Int,
    private val fieldH: Int,
    saved: SavedPhysics?,
    /** Hit box = detected bird box * these factors (the detector sees only the blue hoodie, not the rocket). */
    private val hitScaleW: Double = DEFAULT_HIT_SCALE_W,
    private val hitScaleH: Double = DEFAULT_HIT_SCALE_H
) {
    private val W = fieldW.toDouble()
    private val H = fieldH.toDouble()

    private val phys = Physics(
        g0 = saved?.gPerH?.times(H) ?: DEFAULT_G_PER_H * H,
        vf0 = saved?.vfPerH?.times(H) ?: DEFAULT_VF_PER_H * H,
        L0 = saved?.latencyMs ?: DEFAULT_L
    )
    private val bird = BirdTracker()
    private val cal = Calibrator(phys)
    private val pipes = PipeTracker(W, saved?.speedPerW?.times(W) ?: DEFAULT_SPEED_PER_W * W)

    private val taps = ArrayList<Double>() // dispatch times of the current attempt
    private val birdXs = ArrayList<Double>()
    private val birdWs = ArrayList<Double>()
    private val birdHs = ArrayList<Double>()
    private var lastBirdSeen = -1e18
    private var wasActive = false

    var attempt = 0
        private set
    var attemptStartMs = 0.0
        private set
    var attemptTaps = 0
        private set
    val passed: Int get() = pipes.passedCount
    val g: Double get() = phys.g
    val vf: Double get() = phys.vf
    val latency: Double get() = phys.L
    val speed: Double get() = pipes.speed
    /** Usual bird position/width for FrameAnalyzer's hint (NaN until known). */
    val birdXHint: Double get() = if (birdXs.size >= 3) medianOf(birdXs) else -1.0
    val birdWHint: Double get() = if (birdWs.size >= 3) medianOf(birdWs) else 0.0

    // closest approach to a pipe edge during the last 2 s, to explain deaths in the log
    private class Margin(val t: Double, val v: Double, val side: String, val y: Double, val gt: Double, val gb: Double)
    private val margins = ArrayList<Margin>()

    fun exportPhysics(): SavedPhysics? {
        if (phys.flapSampleCount < 1 || phys.gSampleCount < 1) return null
        return SavedPhysics(phys.g / H, phys.vf / H, phys.L, pipes.speed / W)
    }

    /** Call when the game scene disappears (game over / menu). Returns a one-line summary or null. */
    private fun endAttempt(tNow: Double): String {
        val dur = (tNow - attemptStartMs) / 1000.0
        var worst: Margin? = null
        for (m in margins) if (worst == null || m.v < worst.v) worst = m
        val why = if (worst == null) "none" else
            "${worst.side} margin=${worst.v.toInt()}px y=${worst.y.toInt()} gap=${worst.gt.toInt()}..${worst.gb.toInt()} ${(tNow - worst.t).toInt()}ms_before_end"
        return "ATTEMPT_END attempt=$attempt dur=${"%.1f".format(dur)}s passed=${pipes.passedCount} taps=$attemptTaps nearEdge=[$why] " +
            "g=${"%.5f".format(phys.g)} vf=${"%.3f".format(phys.vf)} L=${phys.L.toInt()} speed=${"%.3f".format(pipes.speed)}"
    }

    /** Returns (decision or null, event line or null). */
    fun onScene(tCap: Double, tNow: Double, scene: FrameScene): Pair<Decision?, String?> {
        var event: String? = null
        if (!scene.active) {
            if (wasActive) event = endAttempt(tNow)
            wasActive = false
            return null to event
        }
        if (!wasActive) {
            wasActive = true
            attempt++
            attemptStartMs = tNow
            attemptTaps = 0
            bird.reset()
            pipes.reset()
            cal.resetAttempt()
            taps.clear()
            birdXs.clear(); birdWs.clear(); birdHs.clear(); margins.clear()
            event = "ATTEMPT_START attempt=$attempt g=${"%.5f".format(phys.g)} vf=${"%.3f".format(phys.vf)} " +
                "L=${phys.L.toInt()} speed=${"%.3f".format(pipes.speed)} field=${fieldW}x$fieldH"
        }
        if (scene.birdFound) {
            lastBirdSeen = tCap
            push(birdXs, scene.birdX)
            // remember only "normal" sprite sizes: a shrunk bird must not shrink the hit box permanently
            if (birdWs.size < 5 || scene.birdH >= 0.72 * medianOf(birdHs)) {
                push(birdWs, scene.birdW); push(birdHs, scene.birdH)
            }
            val bhNow = if (birdHs.size >= 3) medianOf(birdHs) else scene.birdH
            bird.add(tCap, scene.birdY + SPRITE_DY * bhNow)
        }
        val bxm = medianOf(birdXs)
        val bwm = medianOf(birdWs)
        val passX = if (bxm.isNaN() || bwm.isNaN()) -1e9 else bxm - 0.5 * bwm
        val obs = pipes.update(tCap, scene.pipes, passX)
        if (!scene.birdFound || tCap - lastBirdSeen > 250.0 || birdXs.size < 3) return null to event

        cal.update(bird, tCap)
        val est = bird.estimate(phys.g) ?: return null to event
        val ye = est[0]
        val ve = est[1]

        val bx = medianOf(birdXs)
        val bw = max(medianOf(birdWs), 0.03 * W)
        val bh = max(medianOf(birdHs), 0.03 * H)
        // The detector sees only the blue hoodie; the real sprite (head, rocket, flame) is bigger and offset.
        val halfW = SPRITE_HALF_W * bw * hitScaleW
        val halfH = SPRITE_HALF_H * bh * hitScaleH
        val bxs = bx + SPRITE_DX * bw
        val ctl = Controller(W, H, bxs - halfW, bxs + halfW, halfH)
        // margin to the pipe the bird is level with (negative = touching)
        for (p in obs) {
            if (p.left <= bxs + halfW && p.right >= bxs - halfW) {
                val mt = (ye - halfH) - p.top
                val mb = p.bottom - (ye + halfH)
                margins.add(if (mt < mb) Margin(tCap, mt, "TOP", ye, p.top, p.bottom) else Margin(tCap, mb, "BOTTOM", ye, p.top, p.bottom))
                break
            }
        }
        while (margins.isNotEmpty() && tCap - margins[0].t > 2000.0) margins.removeAt(0)

        val pending = ArrayList<Double>(4)
        for (i in max(0, taps.size - 4) until taps.size) {
            val te = taps[i] + phys.L
            if (te > tCap - 55.0) pending.add(te - tCap)
        }
        val lastDisp = if (taps.isNotEmpty()) taps.last() - tCap else null
        val age = min(250.0, max(3.0, tNow - tCap))
        val vis = obs.filter { it.right > bxs - halfW - 60.0 }
        val plan = ctl.decide(ye, ve, age, vis, pipes.speed, phys, pending, lastDisp)
        if (plan.tap) {
            taps.add(tNow)
            cal.noteTap(tNow)
            attemptTaps++
        }
        val line = "${if (plan.tap) "TAP" else "..."} ${plan.reason} y=${ye.toInt()} v=${"%.2f".format(ve)} age=${age.toInt()} " +
            "pipes=${vis.size} sw=${plan.scoreWait.toInt()} st=${plan.scoreTap.toInt()}"
        return Decision(plan.tap, plan.reason, ye, ve, line) to event
    }

    private fun push(list: ArrayList<Double>, v: Double) {
        list.add(v)
        while (list.size > 15) list.removeAt(0)
    }

    companion object {
        // Ballpark values from the v0.43 logs (field height ~2300 px, flap speed ~ -1.9 px/ms).
        // They are only priors: the calibrator refines them online and the result is persisted.
        const val DEFAULT_G_PER_H = 5.0e-6
        const val DEFAULT_VF_PER_H = -7.5e-4
        const val DEFAULT_L = 90.0
        const val DEFAULT_HIT_SCALE_W = 0.55
        const val DEFAULT_HIT_SCALE_H = 0.55
        // Real sprite vs detected hoodie box (measured on a screenshot, units of hoodie box size):
        // hair is 1.04*bh above the hoodie centre, shoes 0.68*bh below, flame 1.46*bw left, nose 1.02*bw right.
        const val SPRITE_DY = -0.18
        const val SPRITE_HALF_H = 0.86
        const val SPRITE_DX = -0.22
        const val SPRITE_HALF_W = 1.24
        const val DEFAULT_SPEED_PER_W = 0.0006
    }
}
