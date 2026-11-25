package com.luciscaelum.slayautoplayer.core

import kotlin.math.max
import kotlin.math.min

const val DT = 10.0
const val HORIZON = 820.0
const val MIN_TAP_GAP = 110.0
const val BIAS = 0.38

/** Extra safety (fraction of gap height) kept above the lower pipe: falling errors are the expensive ones. */
var BOT_EXTRA = 0.06

/** One observed pipe pair (px). top = bottom edge of the upper pipe, bottom = top edge of the lower pipe. */
class PipeObs(
    val left: Double,
    val right: Double,
    val top: Double,
    val bottom: Double,
    val conf: Double = 1.0,
    val spread: Double = 0.0
)

class Plan {
    var tap = false
    var reason = ""
    var scoreWait = 0.0
    var scoreTap = 0.0
}

/** Advance (y, v) by dur ms; flaps are absolute effect times on the same axis as t0. Returns [y, v]. */
internal fun advance(y0: Double, v0: Double, dur: Double, flaps: DoubleArray, nf: Int, g: Double, vf: Double, t0: Double): DoubleArray {
    var y = y0
    var v = v0
    var t = t0
    val end = t0 + dur
    // flaps is kept sorted by the callers
    for (i in 0 until nf) {
        val e = flaps[i]
        if (e <= t0 || e > end) continue
        val d = e - t
        y += v * d + 0.5 * g * d * d
        v = vf
        t = e
    }
    val d = end - t
    y += v * d + 0.5 * g * d * d
    v += g * d
    return doubleArrayOf(y, v)
}

/** Small sorted list of doubles without boxing. */
private class Flaps(cap: Int = 16) {
    var a = DoubleArray(cap)
    var n = 0
    fun copy(): Flaps {
        val f = Flaps(max(16, a.size)); System.arraycopy(a, 0, f.a, 0, n); f.n = n; return f
    }
    fun add(v: Double) {
        if (n == a.size) a = a.copyOf(n * 2)
        var i = n
        while (i > 0 && a[i - 1] > v) { a[i] = a[i - 1]; i-- }
        a[i] = v
        n++
    }
}

class Controller(
    private val W: Double,
    private val H: Double,
    private val birdLeft: Double,
    private val birdRight: Double,
    private val halfH: Double
) {
    private fun pipeMargin(p: PipeObs): Double = 0.06 * (p.bottom - p.top) + 8.0 + p.spread
    private fun botMargin(p: PipeObs): Double = pipeMargin(p) + BOT_EXTRA * (p.bottom - p.top)

    private class Constraint(val lo: Double, val hi: Double, val active: PipeObs?)

    private fun constraint(pipes: List<PipeObs>, speed: Double, tt: Double): Constraint {
        var lo = 0.03 * H + halfH
        var hi = H * 0.955 - halfH
        val xm = 12.0 + speed * 25.0
        val dx = speed * tt
        var active: PipeObs? = null
        for (p in pipes) {
            val r = p.right - dx
            val l = p.left - dx
            if (r < birdLeft - xm) continue
            if (active == null) active = p
            if (l <= birdRight + xm) {
                val m = pipeMargin(p)
                lo = max(lo, p.top + m + halfH)
                hi = min(hi, p.bottom - botMargin(p) - halfH)
            } else break
        }
        return Constraint(lo, hi, active)
    }

    private fun floorLine(pipes: List<PipeObs>, phys: Physics, c: Constraint): Double {
        val active = c.active
        val R = phys.vf * phys.vf / (2.0 * phys.g)
        val center: Double
        if (active == null) {
            center = 0.45 * H
        } else {
            val m = pipeMargin(active)
            val top = active.top + m + halfH
            val bot = active.bottom - botMargin(active) - halfH
            var nxt: PipeObs? = null
            var seen = false
            for (p in pipes) {
                if (seen) { nxt = p; break }
                if (p === active) seen = true
            }
            var w = 0.5
            if (nxt != null && bot > top) {
                val cA = 0.5 * (active.top + active.bottom)
                val cN = 0.5 * (nxt.top + nxt.bottom)
                val d = max(-1.0, min(1.0, (cN - cA) / (0.30 * H)))
                w = 0.5 + BIAS * d
            }
            center = top + w * (bot - top)
        }
        var F = center + 0.5 * R
        F = min(F, c.hi - 0.03 * R - 6.0)
        return F
    }

    /**
     * y0, v0: bird state at capture time t_s. age: ms between capture and now.
     * pipes: sorted by x, coordinates at capture time. speed: px/ms.
     * pending: effect times (relative to t_s) of taps already dispatched.
     * lastDispatch: dispatch time relative to t_s (negative) or null.
     */
    fun decide(
        y0: Double, v0In: Double, age: Double, pipes: List<PipeObs>, speed: Double,
        phys: Physics, pending: List<Double>, lastDispatch: Double?
    ): Plan {
        val g = phys.g
        val vf = phys.vf
        val L = phys.L
        var v0 = v0In
        val pend = Flaps()
        for (te in pending) {
            if (te <= 0) {
                if (te > -55.0) v0 = vf + g * (-te)
            } else pend.add(te)
        }
        val adv = advance(y0, v0, age, pend.a, pend.n, g, vf, 0.0)
        val y = adv[0]
        val v = adv[1]
        val flaps = Flaps()
        for (i in 0 until pend.n) if (pend.a[i] > age) flaps.add(pend.a[i] - age)
        val lastDisp = if (lastDispatch != null) lastDispatch - age else -1e9

        fun lineRule(yy: Double, vv: Double, t: Double, fl: Flaps, ld: Double): Boolean {
            if (t - ld < MIN_TAP_GAP) return false
            val e = advance(yy, vv, L, fl.a, fl.n, g, vf, t)
            val ch = constraint(pipes, speed, age + t + L)
            val F = floorLine(pipes, phys, ch)
            val ye = e[0]
            val ve = e[1]
            return if (ve >= 0) ye >= F else ye - ve * ve / (2.0 * g) >= F
        }

        fun simulate(takeNow: Boolean): Double {
            var yy = y
            var vv = v
            val fl = flaps.copy()
            var ld = lastDisp
            var t = 0.0
            var score = 0.0
            if (takeNow) {
                fl.add(L)
                ld = 0.0
            }
            while (t < HORIZON) {
                if (t > 0.0 && lineRule(yy, vv, t, fl, ld)) {
                    fl.add(t + L)
                    ld = t
                }
                val a = advance(yy, vv, DT, fl.a, fl.n, g, vf, t)
                yy = a[0]
                vv = a[1]
                t += DT
                val c = constraint(pipes, speed, age + t)
                if (yy < c.lo) score += (c.lo - yy) * (c.lo - yy)
                else if (yy > c.hi) score += (yy - c.hi) * (yy - c.hi)
            }
            return score
        }

        val plan = Plan()
        val canTap = (0.0 - lastDisp) >= MIN_TAP_GAP
        plan.scoreWait = simulate(false)
        plan.scoreTap = simulate(true)
        val policyTap = lineRule(y, v, 0.0, flaps, lastDisp)
        if (!canTap) {
            plan.reason = "COOLDOWN"
            return plan
        }
        if (policyTap) {
            if (plan.scoreWait + 300.0 < plan.scoreTap) {
                plan.reason = "VETO"
            } else {
                plan.tap = true
                plan.reason = "LINE"
            }
        } else {
            if (plan.scoreTap + 1500.0 < plan.scoreWait) {
                plan.tap = true
                plan.reason = "OVERRIDE"
            } else plan.reason = "WAIT"
        }
        return plan
    }
}
