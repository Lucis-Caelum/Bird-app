package com.luciscaelum.slayautoplayer.core

import java.nio.IntBuffer
import kotlin.math.max
import kotlin.math.min

/** Result of analysing one captured frame. All coordinates are in "field" pixels (header cropped). */
class FrameScene(
    val active: Boolean,
    val birdFound: Boolean = false,
    val birdX: Double = -1.0,
    val birdY: Double = -1.0,
    val birdW: Double = 0.0,
    val birdH: Double = 0.0,
    val pipes: List<RawPipe> = emptyList(),
    val note: String = ""
)

/**
 * Pixel classification straight from the raw RGBA ImageReader buffer (no Bitmap copies).
 * The colour rules are the ones that already worked in earlier versions (score badge, blue bird,
 * purple pipe body, orange crystal caps), but every visible pipe is returned, not only the nearest.
 *
 * Pixel layout: little-endian Int of bytes R,G,B,A  ->  r = c&255, g = c>>8&255, b = c>>16&255.
 */
class FrameAnalyzer {
    private var colPurple = IntArray(0)
    private var colCrystal = IntArray(0)
    private var cand = BooleanArray(0)
    private var vertical = BooleanArray(0)
    private var birdCols = IntArray(0)
    private var birdRows = IntArray(0)

    fun analyze(px: IntBuffer, stridePx: Int, w: Int, frameH: Int, cropTop: Int): FrameScene {
        val top = cropTop.coerceIn(0, frameH - 1)
        val h = frameH - top
        if (h <= 8 || w <= 8) return FrameScene(false, note = "пустое поле")
        val step = 3
        val sampleW = (w + step - 1) / step
        val sampleH = (h + step - 1) / step

        // 1) score badge: exists only during active gameplay
        var scoreOrange = 0
        val sy0 = (h * 0.08).toInt()
        val sy1 = (h * 0.24).toInt()
        val sx1 = (w * 0.27).toInt()
        var y = sy0
        while (y < sy1) {
            val rowBase = (top + y) * stridePx
            var x = 0
            while (x < sx1) {
                val c = px.get(rowBase + x)
                val r = c and 255
                val g = (c ushr 8) and 255
                val b = (c ushr 16) and 255
                if (r > 165 && g in 25..145 && b < 85 && r > g * 1.35) scoreOrange++
                x += 2
            }
            y += 2
        }
        if (scoreOrange <= 90) return FrameScene(false, note = "меню / отсчёт")

        if (colPurple.size < sampleW) {
            colPurple = IntArray(sampleW); colCrystal = IntArray(sampleW)
            cand = BooleanArray(sampleW); birdCols = IntArray(sampleW)
        }
        if (vertical.size < sampleH) { vertical = BooleanArray(sampleH); birdRows = IntArray(sampleH) }
        java.util.Arrays.fill(colPurple, 0, sampleW, 0)
        java.util.Arrays.fill(colCrystal, 0, sampleW, 0)
        java.util.Arrays.fill(birdCols, 0, sampleW, 0)
        java.util.Arrays.fill(birdRows, 0, sampleH, 0)

        val birdX0 = (w * 0.07).toInt()
        val birdX1 = (w * 0.46).toInt()
        val birdY0 = (h * 0.12).toInt()
        val birdY1 = (h * 0.97).toInt()
        val pipeX0 = (w * 0.12).toInt()
        var birdPixels = 0

        // 2) one sampled pass: bird mask + pipe column occupancy
        for (sy in 0 until sampleH) {
            val yy = sy * step
            val rowBase = (top + yy) * stridePx
            val birdRow = yy in birdY0 until birdY1
            for (sx in 0 until sampleW) {
                val xx = sx * step
                val scanBird = birdRow && xx >= birdX0 && xx < birdX1
                val scanPipe = xx >= pipeX0
                if (!scanBird && !scanPipe) continue
                val c = px.get(rowBase + xx)
                val r = c and 255
                val g = (c ushr 8) and 255
                val b = (c ushr 16) and 255
                if (scanBird && b > 55 && b > r * 1.18 && b > g * 1.04 && r < 145 && g < 170) {
                    birdCols[sx]++
                    birdRows[sy]++
                    birdPixels++
                }
                if (scanPipe) {
                    if (isPurple(r, g, b)) colPurple[sx]++
                    if (isCrystal(r, g, b)) colCrystal[sx]++
                }
            }
        }

        // bird bbox from column/row histograms (needs >=2 samples, ignores lone stray pixels)
        var bx0 = -1; var bx1 = -1; var by0 = -1; var by1 = -1
        if (birdPixels >= 24) {
            for (sx in 0 until sampleW) if (birdCols[sx] >= 2) { if (bx0 < 0) bx0 = sx; bx1 = sx }
            for (sy in 0 until sampleH) if (birdRows[sy] >= 2) { if (by0 < 0) by0 = sy; by1 = sy }
        }
        var birdFound = false
        var birdX = -1.0
        var birdY = -1.0
        var birdW = 0.0
        var birdH = 0.0
        if (bx0 >= 0 && by0 >= 0) {
            birdW = ((bx1 - bx0) * step).toDouble()
            birdH = ((by1 - by0) * step).toDouble()
            if (birdW >= 12 && birdH >= 18) {
                birdFound = true
                birdX = (bx0 + bx1) * step / 2.0
                birdY = (by0 + by1) * step / 2.0
            }
        }

        // 3) pipe column groups
        val colThreshold = max(10, (sampleH * 0.12).toInt())
        for (i in 0 until sampleW) cand[i] = colPurple[i] >= colThreshold
        for (i in 1 until sampleW - 1) if (!cand[i] && cand[i - 1] && cand[i + 1]) cand[i] = true
        val minPipeW = (w * 0.11).toInt().coerceAtLeast(1)
        val groups = ArrayList<IntArray>(4)
        var start = -1
        for (i in 0..sampleW) {
            val on = i < sampleW && cand[i]
            if (on) {
                if (start < 0) start = i
            } else if (start >= 0) {
                if ((i - start) * step >= minPipeW) groups.add(intArrayOf(start * step, min(i * step, w)))
                start = -1
            }
        }

        val maxExt = (w * 0.065).toInt().coerceAtLeast(step)
        val weak = max(2, (sampleH * 0.0035).toInt())
        val pipes = ArrayList<RawPipe>(groups.size)
        for (grp in groups) {
            // extend over crystal caps
            var left = grp[0]
            var ext = 0
            while (left - step >= 0 && ext < maxExt) {
                val idx = ((left - step) / step).coerceIn(0, sampleW - 1)
                if (colPurple[idx] + colCrystal[idx] < weak) break
                left -= step; ext += step
            }
            var right = grp[1]
            ext = 0
            while (right < w && ext < maxExt) {
                val idx = (right / step).coerceIn(0, sampleW - 1)
                if (colPurple[idx] + colCrystal[idx] < weak) break
                right = min(right + step, w); ext += step
            }
            val gap = findGap(px, stridePx, top, h, sampleH, step, grp[0], grp[1], w) ?: continue
            pipes.add(RawPipe(left.toDouble(), right.toDouble(), gap[0].toDouble(), gap[1].toDouble()))
        }
        return FrameScene(
            true, birdFound, birdX, birdY, birdW, birdH, pipes,
            "птица=${if (birdFound) "${birdX.toInt()},${birdY.toInt()}" else "нет"} труб=${pipes.size}"
        )
    }

    /** Opening (top, bottom) inside one pipe column group, or null when it cannot be confirmed. */
    private fun findGap(
        px: IntBuffer, stridePx: Int, top: Int, h: Int, sampleH: Int, step: Int,
        gx0: Int, gx1: Int, w: Int
    ): IntArray? {
        val xs = IntArray(9) { i -> (gx0 + (gx1 - gx0) * (0.02 + i * 0.12)).toInt().coerceIn(0, w - 1) }
        for (sy in 0 until sampleH) {
            val rowBase = (top + sy * step) * stridePx
            var purple = 0
            var crystal = 0
            for (x in xs) {
                val c = px.get(rowBase + x)
                val r = c and 255
                val g = (c ushr 8) and 255
                val b = (c ushr 16) and 255
                if (isPurple(r, g, b)) purple++
                if (isCrystal(r, g, b)) crystal++
            }
            vertical[sy] = purple >= 3 || crystal >= 3
        }
        // runs of wall rows, merging texture breaks
        val minRun = h * 0.025
        val mergeTol = max(step * 2, (h * 0.025).toInt())
        val runs = ArrayList<IntArray>(4)
        var rs = -1
        for (i in 0..sampleH) {
            val on = i < sampleH && vertical[i]
            if (on) {
                if (rs < 0) rs = i
            } else if (rs >= 0) {
                if ((i - rs) * step >= minRun) {
                    val a = rs * step
                    val b = min(i * step, h)
                    val prev = runs.lastOrNull()
                    if (prev != null && a - prev[1] <= mergeTol) prev[1] = b else runs.add(intArrayOf(a, b))
                }
                rs = -1
            }
        }
        if (runs.size < 2) return null
        var best = 0
        var gt = -1
        var gb = -1
        for (i in 0 until runs.size - 1) {
            val gap = runs[i + 1][0] - runs[i][1]
            if (gap > best) { best = gap; gt = runs[i][1]; gb = runs[i + 1][0] }
        }
        if (best < h * 0.08 || best > h * 0.62) return null
        return intArrayOf(gt, gb)
    }

    private fun isPurple(r: Int, g: Int, b: Int) =
        r in 48..190 && b in 48..195 && g < 115 && r > g * 1.18 && b > g * 1.18

    private fun isCrystal(r: Int, g: Int, b: Int) =
        r >= 125 && g in 28..190 && b <= 150 && r > g * 1.12 && r > b * 1.18
}
