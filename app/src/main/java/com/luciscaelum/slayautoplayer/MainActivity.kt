package com.luciscaelum.slayautoplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 24)
        }
        root.addView(TextView(this).apply {
            text = "SLAY AutoPlayer 0.1\n\nПервый тестовый APK: плавающая панель, управление и проверка захвата экрана. Автопилот пока выключен — сначала нужна калибровка под фактическую картинку игры."
            textSize = 18f
        })
        fun addButton(label: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = label; setOnClickListener { action() } },
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        addButton("1. Разрешить отображение поверх окон") {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        addButton("2. Включить службу управления") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton("3. Показать плавающую панель") {
            startService(Intent(this, OverlayService::class.java))
        }
        addButton("Остановить панель") { stopService(Intent(this, OverlayService::class.java)) }
        setContentView(root)
    }
}
