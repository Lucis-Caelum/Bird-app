package com.luciscaelum.slayautoplayer.core

import java.util.PriorityQueue
import java.util.Random
import kotlin.math.abs
import kotlin.math.max

/**
 * Offline simulator of the game + capture pipeline used to check the controller without a phone.
 * Pure Kotlin: compile together with the Kotlin files of the app's "core" package (see tools/simulator/README.md).
 */
class SimCfg(
    val H: Double = 2800.0,
    val W: Double = 1440.0,
    val g: Double = 0.0045,
    val vf: Double = -1.55,
    val L: Double = 110.0,
    val LJit: Double = 12.0,
    val speed: Double = 0.85,
    val spacing: Double = 1100.0,
    val pipeW: Double = 200.0,
    val gapH: Double = 780.0,
    val gapVar: Double = 60.0,
    val gapLo: Double = 0.30,
    val gapHi: Double = 0.70,
    val frame: Double = 16.7,
    val frameJit: Double = 2.0,
    val drop: Double = 0.04,
    val age: Double = 26.0,
    val ageJit: Double = 8.0,
    val noise: Double = 2.5,
    val outlier: Double = 0.01,
    val flicker: Double = 0.12,
    val gErr: Pair<Double, Double> = 0.6 to 1.6,
    val vfErr: Pair<Double, Double> = 0.7 to 1.4,
    val L0: Double = 90.0,
    val maxPipes: Int = 40
)

private class SPipe(var left: Double, val top: Double, val bottom: Double, val id: Int, var passed: Boolean = false)
private class Track { val tops = ArrayList<Double>(); val bots = ArrayList<Double>() }
private class Pend(val tDec: Double, val ts: Double, val y: Double, val obs: List<PipeObs>)

class SimResult(val passed: Int, val death: String, val taps: Int, val time: Double)

fun runSim(seed: Long, c: SimCfg): SimResult {
    val rng = Random(seed)
    fun uni(a: Double, b: Double) = a + (b - a) * rng.nextDouble()
    val H = c.H
    val W = c.W
    val bx = 0.26 * W
    val bhw = 50.0
    val bhh = 60.0
    var y = 0.45 * H
    var v = 0.0
    var t = 0.0
    val pipes = ArrayList<SPipe>()
    var nextId = 0
    fun spawn(x: Double) {
        val cc = uni(c.gapLo, c.gapHi) * H
        val gh = c.gapH + uni(-c.gapVar, c.gapVar)
        pipes.add(SPipe(x, cc - gh / 2, cc + gh / 2, nextId++))
    }
    spawn(W * 1.25)
    val phys = Physics(c.g * uni(c.gErr.first, c.gErr.second), c.vf * uni(c.vfErr.first, c.vfErr.second), c.L0)
    val tr = BirdTracker()
    val cal = Calibrator(phys)
    val ctl = Controller(W, H, bx - bhw, bx + bhw, bhh)
    val effects = ArrayList<Double>()
    val taps = ArrayList<Double>()
    val tracked = HashMap<Int, Track>()
    var passed = 0
    var death = "ALIVE"
    var nTaps = 0

    fun stepWorld(tTo: Double): Boolean {
        while (t < tTo) {
            t += 1.0
            if (effects.isNotEmpty() && effects[0] <= t) { v = c.vf; effects.removeAt(0) }
            v += c.g
            y += v
            for (p in pipes) p.left -= c.speed
            if (pipes.isNotEmpty() && pipes.last().left < W - c.spacing) spawn(W + 20)
            while (pipes.isNotEmpty() && pipes[0].left + c.pipeW < -50) pipes.removeAt(0)
            for (p in pipes) {
                if (!p.passed && p.left + c.pipeW < bx - bhw) { p.passed = true; passed++ }
                if (p.left <= bx + bhw && p.left + c.pipeW >= bx - bhw) {
                    if (y - bhh < p.top || y + bhh > p.bottom) {
                        death = if (y - bhh < p.top) "PIPE_TOP" else "PIPE_BOT"
                        return false
                    }
                }
            }
            if (y - bhh < 0) { death = "CEIL"; return false }
            if (y + bhh > H * 0.96) { death = "GROUND"; return false }
        }
        return true
    }

    val pend = PriorityQueue<Pend>(compareBy { it.tDec })
    var lastDec = 0.0
    var nextCap = c.frame
    while (passed < c.maxPipes && t < 120000) {
        val tDecNext = pend.peek()?.tDec ?: 1e18
        if (nextCap <= tDecNext) {
            if (!stepWorld(nextCap)) break
            val ts = t
            var snapY = y + rng.nextGaussian() * c.noise
            if (rng.nextDouble() < c.outlier) snapY += (if (rng.nextBoolean()) 1 else -1) * uni(8.0, 20.0)
            val snapP = ArrayList<PipeObs>()
            for (p in pipes) {
                if (p.left > W + 10) continue
                val tp = tracked.getOrPut(p.id) { Track() }
                val top = p.top + rng.nextGaussian() * 8
                var bot = p.bottom + rng.nextGaussian() * 8
                if (rng.nextDouble() < c.flicker) bot -= uni(60.0, 160.0)
                tp.tops.add(top); tp.bots.add(bot)
                while (tp.tops.size > 7) tp.tops.removeAt(0)
                while (tp.bots.size > 7) tp.bots.removeAt(0)
                val mt = medianOf(tp.tops)
                val mb = medianOf(tp.bots)
                val dev = ArrayList<Double>()
                for (x in tp.tops) dev.add(abs(x - mt))
                for (x in tp.bots) dev.add(abs(x - mb))
                val spread = 1.5 * medianOf(dev)
                val left = p.left + rng.nextGaussian() * 4
                snapP.add(PipeObs(left, left + c.pipeW, mt, mb, 1.0, spread))
            }
            val age = max(5.0, c.age + uni(-c.ageJit, c.ageJit))
            val td = max(lastDec + 1.0, ts + age)
            pend.add(Pend(td, ts, snapY, snapP))
            nextCap += c.frame + uni(-c.frameJit, c.frameJit)
            if (rng.nextDouble() < c.drop) nextCap += c.frame
            continue
        }
        val p = pend.poll()
        if (!stepWorld(p.tDec)) break
        lastDec = p.tDec
        val age = p.tDec - p.ts
        tr.add(p.ts, p.y)
        cal.update(tr, p.ts)
        val est = tr.estimate(phys.g) ?: continue
        val pending = ArrayList<Double>()
        for (i in max(0, taps.size - 4) until taps.size) {
            val te = taps[i] + phys.L
            if (te > p.ts - 55.0) pending.add(te - p.ts)
        }
        val lastDisp = if (taps.isNotEmpty()) taps.last() - p.ts else null
        val speed = c.speed * (1 + rng.nextGaussian() * 0.03)
        val vis = p.obs.filter { it.right > bx - bhw - 60 }
        val plan = ctl.decide(est[0], est[1], age, vis, speed, phys, pending, lastDisp)
        if (plan.tap) {
            taps.add(t)
            cal.noteTap(t)
            nTaps++
            effects.add(t + c.L + uni(-c.LJit, c.LJit))
            effects.sort()
        }
    }
    return SimResult(passed, death, nTaps, t)
}

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "renderseed") { val r = runRender(args[1].toLong(), args[2].toInt()); println("RESULT passed=${r.passed} death=${r.death}"); return }
    if (args.isNotEmpty() && args[0] == "render") { mainRender(args.drop(1).toTypedArray()); return }
    val n = if (args.isNotEmpty()) args[0].toInt() else 40
    val res = ArrayList<Int>()
    val deaths = HashMap<String, Int>()
    var full = 0
    var early = 0
    val t0 = System.currentTimeMillis()
    for (s in 0 until n) {
        val r = runSim(s.toLong(), SimCfg())
        res.add(r.passed)
        deaths.merge(r.death, 1, Int::plus)
        if (r.passed >= 40) full++
        if (r.passed < 5) early++
        println("seed $s: passed=${r.passed} death=${r.death} taps=${r.taps} t=${"%.1f".format(r.time / 1000)}s")
    }
    res.sort()
    println("median=${res[res.size / 2]} min=${res[0]} full=$full/$n early(<5)=$early deaths=$deaths  (${System.currentTimeMillis() - t0} ms)")
}
