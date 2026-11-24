package com.luciscaelum.slayautoplayer.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A pipe pair detected in ONE frame (field coordinates, px). */
class RawPipe(val left: Double, val right: Double, val top: Double, val bottom: Double)

/**
 * Keeps a separate identity for every visible pipe pair and filters its geometry over time.
 * - top/bottom = temporal median of the last observations (kills 1-frame detector flicker)
 * - spread = robust scatter of those observations, used by the planner as an extra safety margin
 * - speed = world scroll speed (px/ms) estimated from the motion of many pipes
 */
class PipeTracker(private val fieldW: Double, priorSpeed: Double) {
    private class Track(var id: Int) {
        val t = ArrayList<Double>()
        val left = ArrayList<Double>()
        val width = ArrayList<Double>()
        val top = ArrayList<Double>()
        val bot = ArrayList<Double>()
        var lastT = 0.0
        var firstT = 0.0
        fun add(tm: Double, l: Double, w: Double, tp: Double, bt: Double) {
            if (t.isEmpty()) firstT = tm
            t.add(tm); left.add(l); width.add(w); top.add(tp); bot.add(bt)
            while (t.size > 7) { t.removeAt(0); left.removeAt(0); width.removeAt(0); top.removeAt(0); bot.removeAt(0) }
            lastT = tm
        }
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private val speedSamples = ArrayList<Double>()
    var speed: Double = priorSpeed
        private set
    val trackCount: Int get() = tracks.size
    /** Pipes that left the screen behind the bird since the last reset(). */
    var passedCount = 0
        private set

    fun reset() {
        tracks.clear()
        passedCount = 0
    }

    /** Returns the filtered pipes sorted by x, with coordinates as of time [tm]. */
    fun update(tm: Double, dets: List<RawPipe>, passX: Double = -1e9): List<PipeObs> {
        val sorted = dets.sortedBy { it.left }
        val used = BooleanArray(tracks.size)
        val tol = max(48.0, 0.06 * fieldW)
        val matched = arrayOfNulls<Track>(sorted.size)
        for ((i, d) in sorted.withIndex()) {
            var best = -1
            var bestD = tol
            for ((k, tk) in tracks.withIndex()) {
                if (used[k]) continue
                val pred = tk.left.last() - speed * (tm - tk.lastT)
                val dd = abs(pred - d.left)
                if (dd < bestD) { bestD = dd; best = k }
            }
            if (best >= 0) { used[best] = true; matched[i] = tracks[best] }
        }
        for ((i, d) in sorted.withIndex()) {
            val tk = matched[i] ?: Track(nextId++).also { tracks.add(it) }
            tk.add(tm, d.left, d.right - d.left, d.top, d.bottom)
        }
        // forget tracks that disappeared
        val it = tracks.iterator()
        while (it.hasNext()) {
            val tk = it.next()
            if (tm - tk.lastT > 450.0) {
                if (tk.left.last() + tk.width.last() < passX) passedCount++
                it.remove()
            }
        }
        updateSpeed()

        val out = ArrayList<PipeObs>()
        for (tk in tracks) {
            if (tm - tk.lastT > 120.0) continue // not seen in the current frame
            val n = tk.t.size
            val lefts = ArrayList<Double>(n)
            for (i in 0 until n) lefts.add(tk.left[i] - speed * (tm - tk.t[i]))
            val l = medianOf(lefts)
            val w = medianOf(tk.width)
            val mt = medianOf(tk.top)
            val mb = medianOf(tk.bot)
            val dev = ArrayList<Double>(2 * n)
            for (x in tk.top) dev.add(abs(x - mt))
            for (x in tk.bot) dev.add(abs(x - mb))
            val spread = 1.5 * medianOf(dev)
            val conf = min(1.0, n / 3.0)
            out.add(PipeObs(l, l + w, mt, mb, conf, spread))
        }
        out.sortBy { it.left }
        return out
    }

    private fun updateSpeed() {
        for (tk in tracks) {
            val n = tk.t.size
            if (n < 5 || tk.t[n - 1] - tk.t[0] < 250.0) continue
            // least squares slope of left over time
            var st = 0.0; var sl = 0.0
            for (i in 0 until n) { st += tk.t[i]; sl += tk.left[i] }
            val mt = st / n; val ml = sl / n
            var num = 0.0; var den = 0.0
            for (i in 0 until n) { num += (tk.t[i] - mt) * (tk.left[i] - ml); den += (tk.t[i] - mt) * (tk.t[i] - mt) }
            if (den < 1.0) continue
            val s = -num / den
            if (s in 0.0002 * fieldW..0.0025 * fieldW) {
                speedSamples.add(s)
                while (speedSamples.size > 25) speedSamples.removeAt(0)
            }
        }
        if (speedSamples.size >= 3) speed = medianOf(speedSamples)
    }
}
