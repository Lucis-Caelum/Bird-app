package com.luciscaelum.slayautoplayer

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var panel: LinearLayout? = null
    private var status: TextView? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (panel != null) return START_STICKY
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            setBackgroundColor(0xEE20242C.toInt())
        }
        status = TextView(this).apply {
            text = "BIRD PLAY • READY\\nСчёт: —"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
        }
        layout.addView(status)
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = label; textSize = 11f; setOnClickListener { action() } })
        }
        button("ПАУЗА / ПРОДОЛЖИТЬ") { GameAccessibilityService.togglePause(); refresh() }
        button("РУЧНОЙ РЕЖИМ") { GameAccessibilityService.manualTakeover(); refresh() }
        button("СТОП") { stopSelf() }
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        params.gravity = Gravity.TOP or Gravity.END
        params.x = 12; params.y = 120
        wm.addView(layout, params)
        panel = layout
        return START_STICKY
    }
    private fun refresh() {
        val state = if (GameAccessibilityService.paused) "PAUSE" else "READY"
        status?.text = "BIRD PLAY • $state\\nСчёт: —"
    }
    override fun onDestroy() {
        panel?.let { wm.removeView(it) }
        panel = null
        super.onDestroy()
    }
}
