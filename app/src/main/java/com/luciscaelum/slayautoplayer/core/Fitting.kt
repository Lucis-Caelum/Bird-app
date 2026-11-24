package com.luciscaelum.slayautoplayer.core

import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Small numeric helpers. Pure Kotlin, no Android dependencies.
 * Units everywhere in the core: pixels, milliseconds, y axis pointing DOWN.
 */

internal fun medianOf(values: List<Double>): Double {
    if (values.isEmpty()) return Double.NaN
    val s = values.sorted()
    val n = s.size
    return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
}

/**
 * Fits y = a + b*s + 0.5*g*s^2 with s = t - tref and a KNOWN gravity g.
 * Returns [a, b, rms] (a = position at tref, b = velocity at tref) or null.
 */
internal fun fitFixedG(
    ts: DoubleArray, ys: DoubleArray, from: Int, to: Int, g: Double, tref: Double
): DoubleArray? {
    val n = to - from
    if (n < 2) return null
    var ss = 0.0
    var sss = 0.0
    var sr = 0.0
    var ssr = 0.0
    for (i in from until to) {
        val s = ts[i] - tref
        val r = ys[i] - 0.5 * g * s * s
        ss += s
        sss += s * s
        sr += r
        ssr += s * r
    }
    val det = n * sss - ss * ss
    if (abs(det) < 1e-9) return null
    val a = (sr * sss - ss * ssr) / det
    val b = (n * ssr - ss * sr) / det
    var rss = 0.0
    for (i in from until to) {
        val s = ts[i] - tref
        val r = ys[i] - 0.5 * g * s * s
        val e = r - a - b * s
        rss += e * e
    }
    return doubleArrayOf(a, b, sqrt(rss / n))
}

/** Free quadratic fit y = a + b*s + c*s^2. Returns [a, b, c, rms] or null. */
internal fun fitQuadratic(
    ts: DoubleArray, ys: DoubleArray, from: Int, to: Int, tref: Double
): DoubleArray? {
    val n = to - from
    if (n < 4) return null
    var s1 = 0.0
    var s2 = 0.0
    var s3 = 0.0
    var s4 = 0.0
    var y0 = 0.0
    var y1 = 0.0
    var y2 = 0.0
    for (i in from until to) {
        val s = ts[i] - tref
        val y = ys[i]
        val q = s * s
        s1 += s
        s2 += q
        s3 += q * s
        s4 += q * q
        y0 += y
        y1 += s * y
        y2 += q * y
    }
    val m = arrayOf(
        doubleArrayOf(n.toDouble(), s1, s2, y0),
        doubleArrayOf(s1, s2, s3, y1),
        doubleArrayOf(s2, s3, s4, y2)
    )
    for (i in 0 until 3) {
        var p = i
        for (r in i + 1 until 3) if (abs(m[r][i]) > abs(m[p][i])) p = r
        if (abs(m[p][i]) < 1e-12) return null
        val tmp = m[i]
        m[i] = m[p]
        m[p] = tmp
        for (r in 0 until 3) {
            if (r == i) continue
            val f = m[r][i] / m[i][i]
            for (c in i until 4) m[r][c] -= f * m[i][c]
        }
    }
    val a = m[0][3] / m[0][0]
    val b = m[1][3] / m[1][1]
    val c = m[2][3] / m[2][2]
    var rss = 0.0
    for (i in from until to) {
        val s = ts[i] - tref
        val e = ys[i] - (a + b * s + c * s * s)
        rss += e * e
    }
    return doubleArrayOf(a, b, c, sqrt(rss / n))
}
