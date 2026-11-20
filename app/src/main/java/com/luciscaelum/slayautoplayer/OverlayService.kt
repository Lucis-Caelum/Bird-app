package com.luciscaelum.slayautoplayer

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var status: TextView? = null

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
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply {
                setColor(0xF2222730.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0xFF596273.toInt())
            }
        }

        status = TextView(this).apply {
            text = "BIRD PLAY  •  READY\nСчёт: —\nАвтопилот: выключен"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setPadding(dp(2), dp(2), dp(2), dp(8))
        }
        layout.addView(status)

        fun button(label: String, action: () -> Unit) {
            val item = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setOnClickListener { action() }
            }
            layout.addView(item, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
            })
        }

        button("ПАУЗА / ПРОДОЛЖИТЬ") {
            GameAccessibilityService.togglePause()
            refresh()
        }
        button("РУЧНОЙ РЕЖИМ") {
            GameAccessibilityService.manualTakeover()
            refresh()
        }
        button("СТОП") { stopSelf() }

        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.END
        params.x = dp(12)
        params.y = dp(110)

        try {
            wm.addView(layout, params)
            panel = layout
        } catch (e: Exception) {
            Toast.makeText(this, "Bird Play: не удалось показать панель — ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    private fun refresh() {
        val state = if (GameAccessibilityService.paused) "PAUSE" else "READY"
        status?.text = "BIRD PLAY  •  $state\nСчёт: —\nАвтопилот: выключен"
    }

    override fun onDestroy() {
        panel?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        panel = null
        super.onDestroy()
    }
}
