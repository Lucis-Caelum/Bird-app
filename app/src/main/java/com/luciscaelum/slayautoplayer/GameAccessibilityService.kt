package com.luciscaelum.slayautoplayer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import kotlin.math.max
import kotlin.math.min
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class GameAccessibilityService : AccessibilityService() {
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanInFlight = AtomicBoolean(false)
    private var previousBirdY = -1
    private var previousBirdAt = 0L
    private var lastAutoTapAt = 0L
    private var lastSceneSeenAt = 0L
    private val monitorTask = object : Runnable {
        override fun run() {
            if (monitoring && !paused && !manual) captureScreenInternal()
            if (monitoring) mainHandler.postDelayed(this, 160)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        screenStatus = "Служба подключена"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { if (monitoring && !paused && !manual && !scanInFlight.get()) { mainHandler.removeCallbacks(monitorTask); mainHandler.post(monitorTask) } }
    override fun onInterrupt() { screenStatus = "Служба временно прервана; ожидаю продолжения" }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN &&
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && monitoring) {
            stopMonitoring("СТОП: кнопка громкости −")
            Toast.makeText(applicationContext, "Bird Play: мониторинг остановлен", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onKeyEvent(event)
    }

    private fun captureScreenInternal() {
        if (Build.VERSION.SDK_INT < 30) {
            screenStatus = "Снимок экрана требует Android 11+"
            return
        }
        if (!scanInFlight.compareAndSet(false, true)) return
        screenStatus = "Считываю игровое поле…"
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer: HardwareBuffer = result.hardwareBuffer
                    val full = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally { buffer.close() }

                    if (full == null) {
                        screenStatus = "Не удалось прочитать снимок"
                        scanInFlight.set(false)
                        return
                    }

                    // Telegram status bar and Mini App header occupy the top ~11.7%.
                    // Keep the complete playfield below them for visual game analysis.
                    val top = (full.height * 0.117f).toInt().coerceIn(1, full.height - 1)
                    val screenW = full.width
                    val screenH = full.height
                    val field = Bitmap.createBitmap(full, 0, top, screenW, screenH - top)
                    full.recycle()

                    val detection = detectPlayfield(field)
                    handleAutoControl(detection, field.width, top, screenW, screenH)
                    lastFrameWidth = field.width
                    lastFrameHeight = field.height
                    lastBirdX = detection.birdX
                    lastBirdY = detection.birdY
                    lastObstacleX = detection.obstacleX
                    lastGapTop = detection.gapTop
                    lastGapBottom = detection.gapBottom
                    field.recycle()
                    scanInFlight.set(false)
                }

                override fun onFailure(errorCode: Int) {
                    screenStatus = when (errorCode) {
                        1 -> "Снимок отклонён системой"
                        2 -> "Снимок недоступен"
                        3 -> "Слишком частые запросы"
                        else -> "Ошибка снимка ($errorCode)"
                    }
                    scanInFlight.set(false)
                }
            })
        } catch (e: Exception) {
            screenStatus = "Ошибка снимка: " + e.javaClass.simpleName
            scanInFlight.set(false)
        }
    }

    private data class Detection(val birdX:Int=-1,val birdY:Int=-1,val obstacleX:Int=-1,val gapTop:Int=-1,val gapBottom:Int=-1,val message:String)
    private fun detectPlayfield(bitmap: Bitmap): Detection {
        val w=bitmap.width; val h=bitmap.height; val step=3
        val purple=IntArray(w)
        var minBirdX=w; var maxBirdX=0; var minBirdY=h; var maxBirdY=0; var bluePixels=0
        for(y in 0 until h step step) for(x in 0 until w step step) {
            val c=bitmap.getPixel(x,y); val r=(c ushr 16) and 255; val gg=(c ushr 8) and 255; val bl=c and 255
            if(x>w*0.10f && r in 48..190 && bl in 48..195 && gg<115 && r>gg*1.18 && bl>gg*1.18) purple[x]++
            if(x in (w*0.08f).toInt()..(w*0.46f).toInt() && y in (h*0.16f).toInt()..(h*0.90f).toInt() && bl>55 && bl>r*1.18 && bl>gg*1.04 && r<145 && gg<170) {
                minBirdX=min(minBirdX,x); maxBirdX=max(maxBirdX,x); minBirdY=min(minBirdY,y); maxBirdY=max(maxBirdY,y); bluePixels++
            }
        }
        val birdFound=bluePixels>=24 && maxBirdX-minBirdX>=12 && maxBirdY-minBirdY>=18
        val birdX=if(birdFound)(minBirdX+maxBirdX)/2 else -1
        val birdY=if(birdFound)(minBirdY+maxBirdY)/2 else -1
        val searchFrom=if(birdFound)(birdX+w*0.08f).toInt().coerceAtMost(w-1) else (w*0.20f).toInt()
        val threshold=(h*0.025f).toInt().coerceAtLeast(5)
        val groups=mutableListOf<Pair<Int,Int>>(); var groupStart=-1
        for(x in searchFrom until w) {
            if(purple[x]>=threshold) { if(groupStart<0) groupStart=x }
            else if(groupStart>=0) { if(x-groupStart>=w*0.018f) groups.add(groupStart to x); groupStart=-1 }
        }
        if(groupStart>=0 && w-groupStart>=w*0.018f) groups.add(groupStart to w)
        if(groups.isEmpty()) return Detection(if(birdFound) birdX else -1, if(birdFound) birdY else -1, message=if(birdFound) "ИГРА: персонаж y=$birdY • ожидаю появления труб" else "Жду активную игру и препятствия")
        val nearest=groups.first()
        val pipeX=(nearest.first+nearest.second)/2
        val sampleX=((nearest.first+nearest.second)/2).coerceIn(0,w-1)
        val occupied=BooleanArray(h)
        for(y in 0 until h step step) {
            var n=0
            for(x in max(0,sampleX-3)..min(w-1,sampleX+3)) {
                val c=bitmap.getPixel(x,y); val r=(c ushr 16) and 255; val gg=(c ushr 8) and 255; val bl=c and 255
                if(r in 48..190 && bl in 48..195 && gg<115 && r>gg*1.18 && bl>gg*1.18) n++
            }
            occupied[y]=n>=2
        }
        val runs=mutableListOf<Pair<Int,Int>>(); var run=-1
        for(y in 0 until h step step) { if(occupied[y]) { if(run<0) run=y } else if(run>=0) { if(y-run>=h*0.035f) runs.add(run to y); run=-1 } }
        if(run>=0 && h-run>=h*0.035f) runs.add(run to h)
        if(runs.size<2) return Detection(if(birdFound) birdX else -1,if(birdFound) birdY else -1,pipeX,message="Труба x=$pipeX найдена; ищу вторую часть и просвет")
        var top=-1; var bottom=-1; var largest=0
        for(i in 0 until runs.size-1) { val gap=runs[i+1].first-runs[i].second; if(gap>largest) { largest=gap; top=runs[i].second; bottom=runs[i+1].first } }
        if(largest<h*0.08f || largest>h*0.62f) return Detection(message="Проверяю препятствие x=$pipeX; просвет пока не подтверждён")
        if(!birdFound) return Detection(obstacleX=pipeX,gapTop=top,gapBottom=bottom,message="Трубы найдены x=$pipeX; ищу персонажа")
        return Detection(birdX,birdY,pipeX,top,bottom,"ИГРА: птица y=$birdY • труба x=$pipeX • проход $top..$bottom")
    }

    private fun handleAutoControl(d:Detection,fieldWidth:Int,cropTop:Int,screenWidth:Int,screenHeight:Int) {
        if(!monitoring || paused || manual) return
        val now=SystemClock.elapsedRealtime()
        val birdVisible = d.birdX >= 0 && d.birdY >= 0
        if (!birdVisible) {
            previousBirdY = -1
            previousBirdAt = 0L
            screenStatus = d.message
            return
        }
        val hasGap = d.obstacleX > d.birdX && d.gapTop >= 0 && d.gapBottom > d.gapTop
        lastSceneSeenAt = now
        val velocity=if(previousBirdY>=0 && previousBirdAt>0L) (d.birdY-previousBirdY).toFloat()/(now-previousBirdAt).coerceAtLeast(1L) else 0f
        previousBirdY=d.birdY; previousBirdAt=now
        val sx=resources.displayMetrics.widthPixels.toFloat()/screenWidth.coerceAtLeast(1)
        val sy=resources.displayMetrics.heightPixels.toFloat()/screenHeight.coerceAtLeast(1)
        val tx=(screenWidth*0.76f*sx).coerceIn(1f,resources.displayMetrics.widthPixels-1f)
        val ty=((cropTop+(screenHeight-cropTop)*0.52f)*sy).coerceIn(1f,resources.displayMetrics.heightPixels-1f)
        if (!hasGap) {
            val stabiliseLine=(screenHeight-cropTop)*0.52f
            if(d.birdY>stabiliseLine && now-lastAutoTapAt>=300L) {
                lastAutoTapAt=now
                tap(tx,ty)
                screenStatus="ПОДДЕРЖКА ДО ТРУБ • персонаж y=${d.birdY}"
            } else screenStatus="ПЕРСОНАЖ y=${d.birdY} • жду первых труб"
            return
        }
        val center=(d.gapTop+d.gapBottom)/2f
        val gap=(d.gapBottom-d.gapTop).toFloat()
        val distance=d.obstacleX-d.birdX
        val approach=distance in (fieldWidth*0.04f).toInt()..(fieldWidth*0.72f).toInt()
        val predicted=d.birdY+velocity*115f
        val target=center-gap*0.06f
        if(approach && predicted>target+gap*0.08f && now-lastAutoTapAt>=260L) {
            lastAutoTapAt=now
            tap(tx,ty)
            screenStatus="АВТОКЛИК • птица y=${d.birdY} • проход ${d.gapTop}..${d.gapBottom}"
        } else screenStatus="ИГРА • птица y=${d.birdY} • труба x=${d.obstacleX} • проход ${d.gapTop}..${d.gapBottom}"
    }

    private fun startMonitoringInternal() {
        if (instance == null) {
            screenStatus = "Включи службу специальных возможностей"
            return
        }
        manual = false
        paused = false
        monitoring = true
        previousBirdY = -1
        previousBirdAt = 0L
        lastAutoTapAt = 0L
        lastSceneSeenAt = 0L
        screenStatus = "АВТОПИЛОТ ВКЛЮЧЁН • жду игровую сцену • громкость − = СТОП"
        mainHandler.removeCallbacks(monitorTask)
        mainHandler.post(monitorTask)
    }

    private fun stopMonitoring(reason: String = "Мониторинг остановлен") {
        monitoring = false
        paused = true
        manual = true
        mainHandler.removeCallbacks(monitorTask)
        screenStatus = reason
    }

    fun tap(x: Float, y: Float, done: (() -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < 24 || paused || manual || !monitoring) return
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 45))
            .build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                screenStatus = "КАСАНИЕ ДОСТАВЛЕНО"
                done?.invoke()
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                screenStatus = "ANDROID ОТМЕНИЛ КАСАНИЕ"
            }
        }, null)
        if (!accepted) screenStatus = "ANDROID НЕ ПРИНЯЛ КАСАНИЕ"
    }

    override fun onDestroy() {
        stopMonitoring("Служба отключена")
        if (instance === this) instance = null
        screenshotExecutor.shutdown()
        super.onDestroy()
    }

    companion object {
        @Volatile var paused = true
            private set
        @Volatile var manual = true
            private set
        @Volatile var monitoring = false
            private set
        @Volatile var screenStatus = "Служба не подключена"
        @Volatile var lastFrameWidth = 0
        @Volatile var lastFrameHeight = 0
        @Volatile var lastBirdX = -1
        @Volatile var lastBirdY = -1
        @Volatile var lastObstacleX = -1
        @Volatile var lastGapTop = -1
        @Volatile var lastGapBottom = -1
        @Volatile private var instance: GameAccessibilityService? = null

        fun togglePause() {
            val service = instance ?: return
            if (monitoring) service.stopMonitoring("Пауза")
            else service.startMonitoringInternal()
        }
        fun manualTakeover() {
            instance?.stopMonitoring("Ручное управление")
            manual = true
            paused = true
        }
        fun requestScreenScan() {
            val service = instance
            if (service == null) screenStatus = "Сначала включи службу специальных возможностей"
            else service.captureScreenInternal()
        }
    }
}
