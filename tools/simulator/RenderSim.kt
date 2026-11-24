package com.luciscaelum.slayautoplayer.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.PriorityQueue
import java.util.Random
import kotlin.math.max

/**
 * End-to-end check WITHOUT a phone: the game world is rendered into a raw RGBA buffer
 * (same colours the detector looks for), then FrameAnalyzer -> AutoPilot decide taps.
 * Half-resolution world (720x1400 field) to keep rendering cheap.
 */
private const val FW = 720
private const val FH = 1400
private const val HEADER = 150
private const val STRIDE = FW + 16 // padded rows like a real ImageReader

private const val C_BG = (255 shl 24) or (40 shl 16) or (25 shl 8) or 20           // dark
private const val C_BIRD = (255 shl 24) or (200 shl 16) or (90 shl 8) or 40        // R=40 G=90 B=200
private const val C_PIPE = (255 shl 24) or (150 shl 16) or (60 shl 8) or 140       // R=140 G=60 B=150
private const val C_SCORE = (255 shl 24) or (40 shl 16) or (100 shl 8) or 220      // R=220 G=100 B=40

private class RPipe(var left: Double, val top: Double, val bottom: Double, var passed: Boolean = false)
private class RPend(val tDec: Double, val ts: Double, val scene: FrameScene)

fun runRender(seed: Long, maxPipes: Int, noise: Boolean = true): SimResult {
    val rng = Random(seed)
    fun uni(a: Double, b: Double) = a + (b - a) * rng.nextDouble()
    val g = 0.00225
    val vf = -0.80
    val L = 105.0
    val speed = 0.425
    val pipeW = 100.0
    val bx = 0.26 * FW
    val bhw = 25.0
    val bhh = 30.0
    var y = 0.45 * FH
    var v = 0.0
    var t = 0.0
    val pipes = ArrayList<RPipe>()
    fun spawn(x: Double) {
        val c = uni(0.30, 0.70) * FH
        val gh = 390.0 + uni(-30.0, 30.0)
        pipes.add(RPipe(x, c - gh / 2, c + gh / 2))
    }
    spawn(FW * 1.25)
    val buf = ByteBuffer.allocateDirect(STRIDE * (FH + HEADER) * 4).order(ByteOrder.nativeOrder())
    val ib: IntBuffer = buf.asIntBuffer()
    val row = IntArray(STRIDE)
    val analyzer = FrameAnalyzer()
    // deliberately wrong priors: autopilot must learn
    val saved = SavedPhysics(
        gPerH = AutoPilot.DEFAULT_G_PER_H * uni(0.7, 1.4),
        vfPerH = AutoPilot.DEFAULT_VF_PER_H * uni(0.8, 1.2),
        latencyMs = 90.0,
        speedPerW = AutoPilot.DEFAULT_SPEED_PER_W * uni(0.8, 1.2)
    )
    val pilot = AutoPilot(FW, FH, saved)
    val effects = ArrayList<Double>()
    var passed = 0
    var death = "ALIVE"
    var nTaps = 0

    fun render() {
        for (r in 0 until FH + HEADER) {
            java.util.Arrays.fill(row, C_BG)
            if (r >= HEADER) {
                val fy = r - HEADER
                if (fy in (FH * 0.10).toInt() until (FH * 0.20).toInt()) for (x in 20 until 120) row[x] = C_SCORE
                for (p in pipes) {
                    if (fy < p.top || fy > p.bottom) {
                        val a = max(0, p.left.toInt()); val b = minOf(FW, (p.left + pipeW).toInt())
                        for (x in a until b) row[x] = C_PIPE
                    }
                }
                if (fy >= y - bhh && fy <= y + bhh) {
                    val a = (bx - bhw).toInt(); val b = (bx + bhw).toInt()
                    for (x in a until b) row[x] = C_BIRD
                }
            }
            ib.position(r * STRIDE)
            ib.put(row, 0, STRIDE)
        }
        ib.rewind()
    }

    fun step(tTo: Double): Boolean {
        while (t < tTo) {
            t += 1.0
            if (effects.isNotEmpty() && effects[0] <= t) { v = vf; effects.removeAt(0) }
            v += g; y += v
            for (p in pipes) p.left -= speed
            if (pipes.last().left < FW - 550.0) spawn(FW + 10.0)
            while (pipes.isNotEmpty() && pipes[0].left + pipeW < -30) pipes.removeAt(0)
            for (p in pipes) {
                if (!p.passed && p.left + pipeW < bx - bhw) { p.passed = true; passed++ }
                if (p.left <= bx + bhw && p.left + pipeW >= bx - bhw && (y - bhh < p.top || y + bhh > p.bottom)) {
                    death = if (y - bhh < p.top) "PIPE_TOP" else "PIPE_BOT"; return false
                }
            }
            if (y - bhh < 0) { death = "CEIL"; return false }
            if (y + bhh > FH * 0.96) { death = "GROUND"; return false }
        }
        return true
    }

    val pend = PriorityQueue<RPend>(compareBy { it.tDec })
    var lastDec = 0.0
    var next = 16.7
    while (passed < maxPipes && t < 90000) {
        val dn = pend.peek()?.tDec ?: 1e18
        if (next <= dn) {
            if (!step(next)) break
            render()
            val scene = analyzer.analyze(ib, STRIDE, FW, FH + HEADER, HEADER)
            val age = max(5.0, 26.0 + uni(-8.0, 8.0))
            pend.add(RPend(max(lastDec + 1.0, t + age), t, scene))
            next += 16.7 + (if (noise) uni(-2.0, 2.0) else 0.0)
            if (noise && rng.nextDouble() < 0.04) next += 16.7
            continue
        }
        val p = pend.poll()
        if (!step(p.tDec)) break
        lastDec = p.tDec
        val (d, ev) = pilot.onScene(p.ts, p.tDec, p.scene)
        if (System.getenv("BIRD_DEBUG") != null) {
            val sc = p.scene
            println("t=${t.toInt()} trueY=${y.toInt()} v=${"%.2f".format(v)} bird=${sc.birdFound}:${sc.birdX.toInt()},${sc.birdY.toInt()} pipes=" +
                sc.pipes.joinToString(";") { "${it.left.toInt()}-${it.right.toInt()}:${it.top.toInt()}..${it.bottom.toInt()}" } +
                " truth=" + pipes.joinToString(";") { "${it.left.toInt()}:${it.top.toInt()}..${it.bottom.toInt()}" } + " | " + (d?.line ?: "-") + (ev?.let { " EV:$it" } ?: ""))
        }
        if (d != null && d.tap) {
            nTaps++
            effects.add(t + L + uni(-10.0, 10.0)); effects.sort()
        }
    }
    return SimResult(passed, death, nTaps, t)
}

fun mainRender(args: Array<String>) {
    val n = if (args.isNotEmpty()) args[0].toInt() else 6
    val pipes = if (args.size > 1) args[1].toInt() else 20
    var full = 0
    for (s in 0 until n) {
        val t0 = System.currentTimeMillis()
        val r = runRender(s.toLong(), pipes)
        if (r.passed >= pipes) full++
        println("render seed $s: passed=${r.passed} death=${r.death} taps=${r.taps} t=${"%.1f".format(r.time / 1000)}s (${System.currentTimeMillis() - t0} ms)")
    }
    println("render: full=$full/$n")
}
