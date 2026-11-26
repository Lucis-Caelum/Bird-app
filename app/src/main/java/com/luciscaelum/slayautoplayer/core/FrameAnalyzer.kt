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
    private var cand = BooleanArray(0)
    private var vertical = BooleanArray(0)
    private var birdCols = IntArray(0)
    private var birdRows = IntArray(0)

    /** Usual horizontal position / width of the bird (set by the caller once known); <0 = unknown. */
    @Volatile var birdHintX = -1.0
    @Volatile var birdHintW = 0.0

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
            colPurple = IntArray(sampleW)
            cand = BooleanArray(sampleW); birdCols = IntArray(sampleW)
        }
        if (vertical.size < sampleH) { vertical = BooleanArray(sampleH); birdRows = IntArray(sampleH) }
        java.util.Arrays.fill(colPurple, 0, sampleW, 0)
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
                }
            }
        }

        // bird = the biggest connected cluster of "bird" pixels (so bonus crystals / potions that are
        // bluish do not inflate the box); when the bird's usual x is known, clusters far from it are ignored
        var birdFound = false
        var birdX = -1.0
        var birdY = -1.0
        var birdW = 0.0
        var birdH = 0.0
        if (birdPixels >= 24) {
            var bestC0 = -1; var bestC1 = -1; var bestCount = 0
            var c0 = -1; var count = 0; var gapCols = 0
            for (sx in 0..sampleW) {
                val on = sx < sampleW && birdCols[sx] >= 2
                if (on) {
                    if (c0 < 0) c0 = sx
                    count += birdCols[sx]; gapCols = 0
                    if (sx == sampleW - 1) gapCols = 99
                }
                if (c0 >= 0 && (!on)) {
                    gapCols++
                    if (gapCols > 2 || sx >= sampleW - 1) {
                        val c1 = sx - gapCols + 1 - 1
                        val cx = (c0 + c1) * step / 2.0
                        val okHint = birdHintX < 0 || kotlin.math.abs(cx - birdHintX) <= max(0.9 * birdHintW, 90.0)
                        if (okHint && count > bestCount) { bestCount = count; bestC0 = c0; bestC1 = c1 }
                        c0 = -1; count = 0; gapCols = 0
                    }
                }
            }
            if (bestC0 >= 0) {
                java.util.Arrays.fill(birdRows, 0, sampleH, 0)
                for (sy in 0 until sampleH) {
                    val yy = sy * step
                    if (yy < birdY0 || yy >= birdY1) continue
                    val rowBase = (top + yy) * stridePx
                    for (sx in bestC0..bestC1) {
                        val c = px.get(rowBase + sx * step)
                        val r = c and 255
                        val g = (c ushr 8) and 255
                        val b = (c ushr 16) and 255
                        if (b > 55 && b > r * 1.18 && b > g * 1.04 && r < 145 && g < 170) birdRows[sy]++
                    }
                }
                var bestR0 = -1; var bestR1 = -1; var bestRCount = 0
                var r0 = -1; var rc = 0; var gapRows = 0
                for (sy in 0..sampleH) {
                    val on = sy < sampleH && birdRows[sy] >= 2
                    if (on) { if (r0 < 0) r0 = sy; rc += birdRows[sy]; gapRows = 0 }
                    else if (r0 >= 0) {
                        gapRows++
                        if (gapRows > 3 || sy >= sampleH) {
                            val r1 = sy - gapRows
                            if (rc > bestRCount) { bestRCount = rc; bestR0 = r0; bestR1 = r1 }
                            r0 = -1; rc = 0; gapRows = 0
                        }
                    }
                }
                if (bestR0 >= 0) {
                    birdW = ((bestC1 - bestC0) * step).toDouble()
                    birdH = ((bestR1 - bestR0) * step).toDouble()
                    if (birdW >= 12 && birdH >= 18) {
                        birdFound = true
                        birdX = (bestC0 + bestC1) * step / 2.0
                        birdY = (bestR0 + bestR1) * step / 2.0
                    }
                }
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

        val pipes = ArrayList<RawPipe>(groups.size)
        for (grp in groups) {
            // caps are as wide as the stone body: only a tiny safety margin for the sampling grid
            val left = max(0, grp[0] - step)
            val right = min(w, grp[1] + step)
            val gap = findGap(px, stridePx, top, h, sampleH, step, grp[0], grp[1], w) ?: continue
            pipes.add(RawPipe(left.toDouble(), right.toDouble(), gap[0].toDouble(), gap[1].toDouble()))
        }
        return FrameScene(
            true, birdFound, birdX, birdY, birdW, birdH, pipes,
            "птица=${if (birdFound) "${birdX.toInt()},${birdY.toInt()}" else "нет"} труб=${pipes.size}"
        )
    }

    private var tip = BooleanArray(0)

    /**
     * Opening (top, bottom) inside one pipe column group, or null when it cannot be confirmed.
     * 1) stone body rows (purple, >=3 of 9 samples) give a first gap;
     * 2) the red crystal tip that hangs into the gap is added ONLY while it stays connected to the body
     *    (<=3 empty sampled rows) - so a floating bonus crystal or a potion in the gap is not a wall.
     */
    private fun findGap(
        px: IntBuffer, stridePx: Int, top: Int, h: Int, sampleH: Int, step: Int,
        gx0: Int, gx1: Int, w: Int
    ): IntArray? {
        val xs = IntArray(9) { i -> (gx0 + (gx1 - gx0) * (0.02 + i * 0.12)).toInt().coerceIn(0, w - 1) }
        if (tip.size < sampleH) tip = BooleanArray(sampleH)
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
                else if (isCrystal(r, g, b)) crystal++
            }
            vertical[sy] = purple >= 3
            tip[sy] = crystal >= 1 && purple < 3
        }
        // runs of stone rows, merging texture breaks (moss, cracks)
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
        var bi = -1
        for (i in 0 until runs.size - 1) {
            val gap = runs[i + 1][0] - runs[i][1]
            if (gap > best) { best = gap; bi = i }
        }
        if (bi < 0) return null
        var gt = runs[bi][1]
        var gb = runs[bi + 1][0]
        val stoneB = gb
        // crystal tip hanging down from the upper stone
        var i = gt / step
        var last = i - 1
        var miss = 0
        val limDown = gb / step
        while (i < limDown) {
            if (tip[i]) { last = i; miss = 0 } else if (++miss > 3) break
            i++
        }
        gt = max(gt, (last + 1) * step)
        // crystal tip growing up from the lower stone
        i = gb / step - 1
        last = i + 1
        miss = 0
        val limUp = gt / step
        while (i >= limUp) {
            if (tip[i]) { last = i; miss = 0 } else if (++miss > 3) break
            i--
        }
        gb = min(gb, last * step)
        // The crystals have a FIXED size in the game (measured on a real screenshot): tip of the upper pipe hangs
        // 8.5% of the field below its stone body, the lower one reaches 5.8% above its stone. Dark/animated crystals
        // are sometimes missed by colour, so never let the opening be wider than the geometry allows.
        // The crystals have a FIXED size in the game (measured on a real screenshot). Dark or animated crystals are
        // sometimes missed by colour, which made the opening look 700-800 px instead of ~540. So:
        // 1) the lower tip can never be lower than 5.8% of the field above the lower stone body;
        // 2) the opening has a constant height (22% of the field): if it comes out wider, the upper tip was missed.
        gb = min(gb, stoneB - (TIP_DOWN * h).toInt())
        if (gb - gt > GAP_WIDE * h) gt = gb - (GAP_EXPECT * h).toInt()
        val gap = gb - gt
        if (gap < h * 0.08 || gap > h * 0.62) return null
        return intArrayOf(gt, gb)
    }

    private val TIP_DOWN = 0.058
    private val GAP_EXPECT = 0.222
    private val GAP_WIDE = 0.236

    private fun isPurple(r: Int, g: Int, b: Int) =
        r in 48..190 && b in 48..195 && g < 115 && r > g * 1.18 && b > g * 1.18

    // Crystals are saturated red -> orange -> pale orange: (150,8,20) (203,22,5) (232,85,34) (252,158,97)
    private fun isCrystal(r: Int, g: Int, b: Int) = r >= 130 && r > g * 1.35 && r > b * 1.5
}
