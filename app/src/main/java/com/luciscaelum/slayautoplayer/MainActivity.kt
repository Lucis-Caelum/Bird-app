package com.luciscaelum.slayautoplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val projectionRequest = 4107
    private val ui = Handler(Looper.getMainLooper())

    private class Step(val dot: View, val hint: TextView, val button: android.widget.Button)
    private lateinit var stepOverlay: Step
    private lateinit var stepAccess: Step
    private lateinit var stepCapture: Step
    private lateinit var startButton: android.widget.Button
    private lateinit var summary: TextView

    private val ticker = object : Runnable {
        override fun run() {
            refreshState()
            ui.postDelayed(this, 800)
        }
    }

    private fun dp(v: Int) = UiKit.dp(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.BG)
            setPadding(dp(18), dp(26), dp(18), dp(24))
        }

        root.addView(UiKit.text(this, "Bird Play", 30f, UiKit.TEXT, true))
        root.addView(UiKit.text(this, "Автопилот для игры в Telegram • v${BuildConfigVersion.NAME}", 13f, UiKit.TEXT_DIM).apply {
            setPadding(0, dp(2), 0, dp(14))
        })

        summary = UiKit.text(this, "", 14f, UiKit.TEXT)
        val summaryCard = UiKit.card(this).apply { addView(summary) }
        root.addView(summaryCard, lp(bottom = 12))

        // ---- setup steps
        root.addView(UiKit.text(this, "НАСТРОЙКА", 12f, UiKit.TEXT_DIM, true).apply { setPadding(dp(4), 0, 0, dp(6)) })
        stepOverlay = addStep(root, "Поверх других окон", "Нужно для плавающей панели") {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        stepAccess = addStep(root, "Служба специальных возможностей", "Нужна, чтобы делать тапы по экрану") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        stepCapture = addStep(root, "Захват экрана", "Разрешение нужно давать после каждого перезапуска") {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), projectionRequest)
        }

        startButton = UiKit.button(this, "Показать панель управления", true, 54) { togglePanel() }
        root.addView(startButton, lp(top = 6, bottom = 4))
        root.addView(UiKit.text(this, "Дальше: открой игру в Telegram и нажми «АВТО» на панели. Кнопка громкости «−» мгновенно останавливает автопилот.", 12.5f, UiKit.TEXT_DIM).apply {
            setPadding(dp(4), dp(4), dp(4), dp(14))
        })

        // ---- tuning
        root.addView(UiKit.text(this, "НАСТРОЙКИ", 12f, UiKit.TEXT_DIM, true).apply { setPadding(dp(4), 0, 0, dp(6)) })
        root.addView(hitboxCard(), lp(bottom = 12))

        root.addView(UiKit.button(this, "Журнал попыток", false) {
            startActivity(Intent(this, LogActivity::class.java))
        }, lp(bottom = 4))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(UiKit.BG)
            addView(root)
        })
        refreshState()
    }

    private fun lp(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top); bottomMargin = dp(bottom) }

    private fun addStep(root: LinearLayout, title: String, hint: String, onClick: () -> Unit): Step {
        val card = UiKit.card(this)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val dot = UiKit.dot(this, UiKit.BAD)
        row.addView(dot)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(UiKit.text(this, title, 15f, UiKit.TEXT, true))
        val hintView = UiKit.text(this, hint, 12f, UiKit.TEXT_DIM)
        col.addView(hintView)
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val button = UiKit.button(this, "Включить", false, 38) { onClick() }
        row.addView(button, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        })
        card.addView(row)
        root.addView(card, lp(bottom = 8))
        return Step(dot, hintView, button)
    }

    private fun hitboxCard(): LinearLayout {
        val card = UiKit.card(this)
        card.addView(UiKit.text(this, "Размер хитбокса птицы", 15f, UiKit.TEXT, true))
        val value = UiKit.text(this, "", 13f, UiKit.ACCENT)
        card.addView(UiKit.text(this, "Доля от реального спрайта (голова, ракета). Если задевает кристаллы — увеличь; если «не пролезает» в проход — уменьши. Смотри в журнале строку «nearEdge» у завершённых попыток.", 12f, UiKit.TEXT_DIM).apply {
            setPadding(0, dp(2), 0, dp(6))
        })
        card.addView(value)
        val bar = SeekBar(this).apply {
            max = 65 // 35%..100%
            progress = (GameAccessibilityService.loadHitScale(this@MainActivity) * 100 - 35).toInt().coerceIn(0, 65)
        }
        fun show() { value.text = "${35 + bar.progress}%" + if (bar.progress == 20) "  (по умолчанию)" else "" }
        show()
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { show() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                GameAccessibilityService.saveHitScale(this@MainActivity, (35 + bar.progress) / 100.0)
                Toast.makeText(this@MainActivity, "Применится при следующем запуске автопилота", Toast.LENGTH_SHORT).show()
            }
        })
        card.addView(bar)
        return card
    }

    private fun overlayOk() = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this)

    private fun togglePanel() {
        if (OverlayService.running) {
            stopService(Intent(this, OverlayService::class.java))
        } else {
            if (!overlayOk()) { Toast.makeText(this, "Сначала включи «Поверх других окон»", Toast.LENGTH_LONG).show(); return }
            try { startService(Intent(this, OverlayService::class.java)) }
            catch (e: Exception) { Toast.makeText(this, "Не удалось запустить панель: ${e.message}", Toast.LENGTH_LONG).show() }
        }
        ui.postDelayed({ refreshState() }, 300)
    }

    private fun setStep(step: Step, ok: Boolean, okText: String) {
        UiKit.setDot(step.dot, if (ok) UiKit.OK else UiKit.BAD)
        step.button.visibility = if (ok) View.GONE else View.VISIBLE
        if (ok) step.hint.text = okText
    }

    private fun refreshState() {
        val o = overlayOk()
        val a = GameAccessibilityService.connected
        val c = GameAccessibilityService.captureActive
        setStep(stepOverlay, o, "Включено")
        setStep(stepAccess, a, "Служба подключена")
        setStep(stepCapture, c, "Захват работает")
        val ready = o && a && c
        startButton.text = if (OverlayService.running) "Скрыть панель управления" else "Показать панель управления"
        startButton.alpha = if (ready || OverlayService.running) 1f else 0.5f
        summary.text = when {
            !o || !a || !c -> "Осталось шагов: ${listOf(o, a, c).count { !it }} из 3. Выполни их по порядку."
            OverlayService.running -> "Всё готово. Панель на экране — открой игру и нажми «АВТО»."
            else -> "Всё готово. Покажи панель и открой игру в Telegram."
        }
    }

    override fun onResume() {
        super.onResume()
        ui.post(ticker)
    }

    override fun onPause() {
        ui.removeCallbacks(ticker)
        super.onPause()
    }

    @Deprecated("MediaProjection consent result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == projectionRequest && resultCode == RESULT_OK && data != null) {
            val intent = Intent(this, MediaProjectionCaptureService::class.java).apply {
                putExtra(MediaProjectionCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(MediaProjectionCaptureService.EXTRA_RESULT_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } else if (requestCode == projectionRequest) {
            Toast.makeText(this, "Разрешение на захват экрана не выдано", Toast.LENGTH_LONG).show()
        }
    }
}
