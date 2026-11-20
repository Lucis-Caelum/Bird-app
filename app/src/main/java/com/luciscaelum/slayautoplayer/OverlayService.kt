package com.luciscaelum.slayautoplayer

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
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
import android.text.TextUtils

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var status: TextView? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refresh()
            if (panel != null) handler.postDelayed(this, 700)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

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
            setPadding(dp(8), dp(7), dp(8), dp(7))
            background = GradientDrawable().apply {
                setColor(0xB8222730.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0x99596273.toInt())
            }
        }

        status = TextView(this).apply {
            text = "⠿ BIRD PLAY\nИгровое поле: ожидание"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
            maxWidth = dp(168)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(3), dp(2), dp(3), dp(4))
            contentDescription = "Удерживай верхнюю область, чтобы переместить панель"
        }
        layout.addView(status)

        fun button(label: String, action: () -> Unit) {
            val item = Button(this).apply {
                text = label
                textSize = 10f
                isAllCaps = false
                minHeight = dp(32)
                minimumHeight = dp(32)
                setPadding(dp(4), 0, dp(4), 0)
                alpha = 0.82f
                setOnClickListener { action() }
            }
            layout.addView(item, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(5)
            })
        }

        button("СНИМОК ПОЛЯ") {
            GameAccessibilityService.requestScreenScan()
            refresh()
        }
        button("МОНИТОРИНГ / ПАУЗА") {
            GameAccessibilityService.togglePause()
            refresh()
        }
        button("РУЧНОЙ РЕЖИМ") {
            GameAccessibilityService.manualTakeover()
            refresh()
        }
        button("СТОП") {
            GameAccessibilityService.manualTakeover()
            stopSelf()
        }

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            dp(184),
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = dp(12)
        params.y = dp(110)

        status?.setOnTouchListener(object : View.OnTouchListener {
            private var downRawX = 0f
            private var downRawY = 0f
            private var startX = 0
            private var startY = 0

            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downRawX = event.rawX
                        downRawY = event.rawY
                        startX = params.x
                        startY = params.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = (startX - (event.rawX - downRawX)).toInt()
                        params.y = (startY + (event.rawY - downRawY)).toInt()
                        try { wm.updateViewLayout(layout, params) } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
                }
                return false
            }
        })

        try {
            wm.addView(layout, params)
            panel = layout
            windowParams = params
            handler.post(refreshTask)
        } catch (e: Exception) {
            Toast.makeText(this, "Bird Play: не удалось показать панель — ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun refresh() {
        val state = if (GameAccessibilityService.monitoring) "МОНИТОРИНГ" else "ПАУЗА"
        val detail = GameAccessibilityService.screenStatus.take(38)
        status?.text = "⠿ BIRD PLAY • $state\nПоле: ${GameAccessibilityService.lastFrameWidth}×${GameAccessibilityService.lastFrameHeight}\n$detail\nГромкость −: СТОП"
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        panel?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        panel = null
        windowParams = null
        super.onDestroy()
    }
}
