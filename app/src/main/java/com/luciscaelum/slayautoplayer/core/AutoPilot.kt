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
class AutoPilot(private val fieldW: Int, private val fieldH: Int, saved: SavedPhysics?) {
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

    fun exportPhysics(): SavedPhysics? {
        if (phys.flapSampleCount < 2 || phys.gSampleCount < 3) return null
        return SavedPhysics(phys.g / H, phys.vf / H, phys.L, pipes.speed / W)
    }

    /** Call when the game scene disappears (game over / menu). Returns a one-line summary or null. */
    private fun endAttempt(tNow: Double): String {
        val dur = (tNow - attemptStartMs) / 1000.0
        return "ATTEMPT_END attempt=$attempt dur=${"%.1f".format(dur)}s passed=${pipes.passedCount} taps=$attemptTaps " +
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
            birdXs.clear(); birdWs.clear(); birdHs.clear()
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
            bird.add(tCap, scene.birdY)
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
        val ctl = Controller(W, H, bx - 0.5 * bw, bx + 0.5 * bw, 0.5 * bh)

        val pending = ArrayList<Double>(4)
        for (i in max(0, taps.size - 4) until taps.size) {
            val te = taps[i] + phys.L
            if (te > tCap - 55.0) pending.add(te - tCap)
        }
        val lastDisp = if (taps.isNotEmpty()) taps.last() - tCap else null
        val age = min(250.0, max(3.0, tNow - tCap))
        val vis = obs.filter { it.right > bx - 0.5 * bw - 60.0 }
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
        const val DEFAULT_G_PER_H = 1.6e-6
        const val DEFAULT_VF_PER_H = -7.5e-4
        const val DEFAULT_L = 90.0
        const val DEFAULT_SPEED_PER_W = 0.0006
    }
}
