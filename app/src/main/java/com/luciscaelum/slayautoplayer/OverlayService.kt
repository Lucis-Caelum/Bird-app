package com.luciscaelum.slayautoplayer

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** Compact floating control panel. Drag it by the header; it stays out of the pipe lane. */
class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var stateDot: View? = null
    private var stateText: TextView? = null
    private var statsText: TextView? = null
    private var detailText: TextView? = null
    private var autoButton: Button? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refresh()
            if (panel != null) handler.postDelayed(this, 500)
        }
    }

    private fun dp(value: Int): Int = UiKit.dp(this, value)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (panel != null) return START_NOT_STICKY
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Bird Play: разреши отображение поверх окон", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
            background = UiKit.rounded(this@OverlayService, 0xDD141A24.toInt(), 14, 0xAA3A4558.toInt(), 1)
        }

        // header = drag handle
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(4))
        }
        stateDot = UiKit.dot(this, UiKit.WARN, 10)
        header.addView(stateDot)
        stateText = UiKit.text(this, "BIRD PLAY", 11f, UiKit.TEXT, true)
        header.addView(stateText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(UiKit.text(this, "⠿", 14f, UiKit.TEXT_DIM))
        layout.addView(header)

        statsText = UiKit.text(this, "—", 11f, UiKit.TEXT).apply { setPadding(dp(2), 0, dp(2), 0) }
        layout.addView(statsText)
        detailText = UiKit.text(this, "", 9.5f, UiKit.TEXT_DIM).apply {
            setPadding(dp(2), dp(1), dp(2), dp(4)); maxLines = 3
        }
        layout.addView(detailText)

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        autoButton = UiKit.button(this, "▶ АВТО", true, 36) {
            GameAccessibilityService.togglePause()
            refresh()
        }.apply { textSize = 12f }
        buttons.addView(autoButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.5f))
        buttons.addView(UiKit.button(this, "Лог", false, 36) {
            startActivity(Intent(this, LogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.apply { textSize = 12f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(4) })
        buttons.addView(UiKit.button(this, "✕", false, 36) {
            GameAccessibilityService.manualTakeover()
            stopService(Intent(this, MediaProjectionCaptureService::class.java))
            stopSelf()
        }.apply { textSize = 12f }, LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(4) })
        layout.addView(buttons)

        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            dp(176), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = dp(6)
        params.y = dp(60)

        header.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f; private var downY = 0f; private var sx = 0; private var sy = 0
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; sx = params.x; sy = params.y; return true }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (sx + (event.rawX - downX)).toInt()
                        params.y = (sy + (event.rawY - downY)).toInt()
                        try { wm.updateViewLayout(layout, params) } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }
        })

        try {
            wm.addView(layout, params)
            panel = layout
            running = true
            handler.post(refreshTask)
        } catch (e: Exception) {
            Toast.makeText(this, "Bird Play: не удалось показать панель — ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun refresh() {
        val g = GameAccessibilityService
        val monitoring = GameAccessibilityService.monitoring
        val playing = monitoring && g.statPlaying
        val dotColor = when {
            !GameAccessibilityService.connected || !GameAccessibilityService.captureActive -> UiKit.BAD
            playing -> UiKit.OK
            monitoring -> UiKit.WARN
            else -> 0xFF6B7788.toInt()
        }
        val label = when {
            !GameAccessibilityService.connected -> "НЕТ СЛУЖБЫ"
            !GameAccessibilityService.captureActive -> "НЕТ ЗАХВАТА"
            playing -> "ИГРАЮ"
            monitoring -> "ЖДУ ИГРУ"
            else -> "ПАУЗА"
        }
        stateDot?.let { UiKit.setDot(it, dotColor) }
        stateText?.text = "BIRD PLAY · $label"
        statsText?.text = "Попытка ${g.statAttempt} · труб ${g.statPassed} · тапов ${GameAccessibilityService.autoTapCount}"
        val last = if (g.statLastResult.isNotEmpty()) "Прошлая: ${g.statLastResult}\n" else ""
        detailText?.text = last + if (monitoring) "${"%.0f".format(g.statFps)} кадр/с · задержка ${g.statAgeMs.toInt()} мс · тап ${g.statLatency.toInt()} мс" else GameAccessibilityService.screenStatus.take(60)
        autoButton?.text = if (monitoring) "⏸ ПАУЗА" else "▶ АВТО"
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(refreshTask)
        panel?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        panel = null
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
            private set
    }
}
