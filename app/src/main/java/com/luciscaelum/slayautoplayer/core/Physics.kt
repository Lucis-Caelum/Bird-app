package com.luciscaelum.slayautoplayer.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Model: y'' = g (g > 0, y axis points down); a flap sets v = vf (vf < 0) after tap latency L.
 * Units: px, ms. g is px/ms^2, vf px/ms, L ms.
 */
class Physics(val g0: Double, val vf0: Double, val L0: Double) {
    var g = g0
        private set
    var vf = vf0
        private set
    var L = L0
        private set

    private val gSamples = ArrayList<Double>()
    private val vfSamples = ArrayList<Double>()
    private val lSamples = ArrayList<Double>()

    val gSampleCount: Int get() = gSamples.size
    val flapSampleCount: Int get() = lSamples.size

    fun addG(v: Double) {
        if (!(v >= 0.35 * g0 && v <= 3.0 * g0)) return
        gSamples.add(v)
        while (gSamples.size > 15) gSamples.removeAt(0)
        g = medianOf(gSamples)
    }

    fun addFlap(vfNew: Double, lNew: Double) {
        if (-vfNew >= 0.4 * abs(vf0) && -vfNew <= 2.5 * abs(vf0)) {
            vfSamples.add(vfNew)
            while (vfSamples.size > 9) vfSamples.removeAt(0)
            vf = medianOf(vfSamples)
        }
        if (lNew in 25.0..280.0) {
            lSamples.add(lNew)
            while (lSamples.size > 9) lSamples.removeAt(0)
            L = medianOf(lSamples)
        }
    }
}

const val RING_MS = 900.0

/** Ring buffer of bird centre observations (time ms, y px). */
class BirdTracker {
    var t = DoubleArray(256)
        private set
    var y = DoubleArray(256)
        private set
    var n = 0
        private set

    fun reset() {
        n = 0
    }

    fun add(tm: Double, ym: Double) {
        if (n > 0 && tm <= t[n - 1]) return // keep strictly increasing time
        if (n == t.size) {
            t = t.copyOf(n * 2)
            y = y.copyOf(n * 2)
        }
        t[n] = tm
        y[n] = ym
        n++
        var drop = 0
        while (drop < n - 1 && tm - t[drop] > RING_MS) drop++
        if (drop > 0) {
            System.arraycopy(t, drop, t, 0, n - drop)
            System.arraycopy(y, drop, y, 0, n - drop)
            n -= drop
        }
    }

    /** Returns [y, v, rms, windowSize] at the time of the last sample, or null. */
    fun estimate(g: Double): DoubleArray? {
        if (n == 0) return null
        val tl = t[n - 1]
        if (n == 1) return doubleArrayOf(y[0], 0.0, 0.0, 1.0)
        for (w in intArrayOf(12, 9, 7, 5, 3)) {
            if (w > n) continue
            val f = fitFixedG(t, y, n - w, n, g, tl)
            if (f != null && (f[2] <= 5.0 || w == 3)) return doubleArrayOf(f[0], f[1], f[2], w.toDouble())
        }
        val dt = max(1.0, t[n - 1] - t[n - 2])
        return doubleArrayOf(y[n - 1], (y[n - 1] - y[n - 2]) / dt - 0.5 * g * dt, 0.0, 2.0)
    }
}

/** Learns g, vf and tap latency L from recorded history. Never gates control. */
class Calibrator(private val phys: Physics) {
    companion object {
        const val SETTLE_MS = 330.0
    }

    private val allTaps = ArrayList<Double>()
    private var lastCalTd = -1e18
    private var lastRunT = -1e18

    fun resetAttempt() {
        allTaps.clear()
        lastCalTd = -1e18
    }

    fun noteTap(td: Double) {
        allTaps.add(td)
        while (allTaps.size > 16) allTaps.removeAt(0)
    }

    fun update(tr: BirdTracker, tnow: Double) {
        gravity(tr)
        if (tnow - lastRunT >= 60.0) {
            lastRunT = tnow
            flaps(tr, tnow)
        }
    }

    private fun gravity(tr: BirdTracker) {
        val n = tr.n
        if (n < 10) return
        for (w in intArrayOf(n, 20, 14, 10)) {
            if (w > n) continue
            val from = n - w
            val t0 = tr.t[from]
            val t1 = tr.t[n - 1]
            if (t1 - t0 < 170.0) continue
            var tapped = false
            for (td in allTaps) if (td >= t0 - 320.0 && td <= t1) {
                tapped = true
                break
            }
            if (tapped) continue
            val q = fitQuadratic(tr.t, tr.y, from, n, t1)
            if (q != null && q[3] <= 4.5) {
                phys.addG(2.0 * q[2])
                return
            }
        }
    }

    /** Residual sum of squares of y(t) = y0 + v0*(min(t,te1)-tw) + h(t). */
    private fun eval(ts: DoubleArray, ys: DoubleArray, cnt: Int, tw: Double, g: Double, flaps: DoubleArray, nf: Int, vf: Double): Double {
        val te1 = if (nf > 0) flaps[0] else 1e18
        var yy = 0.0
        var vv = 0.0
        var prev = tw
        var k = 0
        var s11 = 0.0
        var s12 = 0.0
        var s22 = 0.0
        var r1 = 0.0
        var r2 = 0.0
        val b2s = DoubleArray(cnt)
        val rs = DoubleArray(cnt)
        for (i in 0 until cnt) {
            val t = ts[i]
            while (k < nf && flaps[k] <= t) {
                val d = flaps[k] - prev
                yy += vv * d + 0.5 * g * d * d
                vv = vf
                prev = flaps[k]
                k++
            }
            val d = t - prev
            val h = yy + vv * d + 0.5 * g * d * d
            val b2 = min(t, te1) - tw
            val r = ys[i] - h
            s11 += 1.0
            s12 += b2
            s22 += b2 * b2
            r1 += r
            r2 += b2 * r
            b2s[i] = b2
            rs[i] = r
        }
        val det = s11 * s22 - s12 * s12
        if (abs(det) < 1e-6) return 1e18
        val y0 = (r1 * s22 - s12 * r2) / det
        val v0 = (s11 * r2 - s12 * r1) / det
        var rss = 0.0
        for (i in 0 until cnt) {
            val e = rs[i] - y0 - v0 * b2s[i]
            rss += e * e
        }
        return rss
    }

    private fun flaps(tr: BirdTracker, tnow: Double) {
        val g = phys.g
        var td0 = Double.NaN
        var td1 = Double.NaN
        for (td in allTaps) {
            if (td > lastCalTd && td <= tnow - SETTLE_MS) {
                if (td0.isNaN()) td0 = td
                td1 = td
            }
        }
        if (td0.isNaN()) return
        val tw = td0 - 230.0
        val tend = min(tnow, td1 + SETTLE_MS)
        val ts = DoubleArray(tr.n)
        val ys = DoubleArray(tr.n)
        var cnt = 0
        for (i in 0 until tr.n) {
            val t = tr.t[i]
            if (t in tw..tend) {
                ts[cnt] = t
                ys[cnt] = tr.y[i]
                cnt++
            }
        }
        if (cnt < 14 || ts[0] > td0 - 120.0) {
            lastCalTd = td1
            return
        }
        val taps = allTaps.filter { it >= tw - 290.0 && it <= tend }
        val fl = DoubleArray(taps.size)

        fun cost(L: Double, vf: Double, gg: Double): Double {
            var nf = 0
            for (td in taps) if (td + L > tw) fl[nf++] = td + L
            java.util.Arrays.sort(fl, 0, nf)
            return eval(ts, ys, cnt, tw, gg, fl, nf, vf)
        }

        var L = phys.L
        var vf = phys.vf
        var G = g
        var best = cost(L, vf, G)
        for (rnd in 0 until 2) {
            for (k in doubleArrayOf(0.5, 0.62, 0.78, 0.9, 1.1, 1.28, 1.6, 2.0)) {
                val c = cost(L, vf, G * k)
                if (c < best) { best = c; G *= k }
            }
            var l = 30
            while (l < 295) {
                val c = cost(l.toDouble(), vf, G)
                if (c < best) { best = c; L = l.toDouble() }
                l += 5
            }
            for (k in -6 until 15) {
                val v2 = min(phys.vf0 * (1.0 + 0.12 * k), -0.3)
                val c = cost(L, v2, G)
                if (c < best) { best = c; vf = v2 }
            }
        }
        for (it in 0 until 2) {
            for (dl in doubleArrayOf(-4.0, -2.0, -1.0, 1.0, 2.0, 4.0)) {
                val c = cost(L + dl, vf, G)
                if (c < best) { best = c; L += dl }
            }
            for (f in doubleArrayOf(0.94, 0.97, 1.03, 1.06)) {
                val c = cost(L, vf * f, G)
                if (c < best) { best = c; vf *= f }
            }
            for (f in doubleArrayOf(0.9, 0.96, 1.04, 1.1)) {
                val c = cost(L, vf, G * f)
                if (c < best) { best = c; G *= f }
            }
        }
        val rms = sqrt(best / cnt)
        lastCalTd = td1
        if (rms > 9.0) return
        val worse = min(cost(L - 30.0, vf, G), cost(L + 30.0, vf, G))
        if (worse < best * 1.35 + 20.0 * cnt) return
        phys.addFlap(vf, L)
        if (phys.gSampleCount < 3) phys.addG(G)
    }
}
