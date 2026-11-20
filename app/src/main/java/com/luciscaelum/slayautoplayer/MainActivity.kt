package com.luciscaelum.slayautoplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(28), dp(22), dp(24))
        }

        content.addView(TextView(this).apply {
            text = "Bird Play 0.4.0"
            textSize = 26f
        })

        content.addView(TextView(this).apply {
            text = "Сначала настрой разрешения, затем запусти плавающую панель. Новая функция: «Считать экран» делает снимок игрового экрана и распознаёт видимый текст. Автопилот пока выключен — сначала проверяем распознавание."
            textSize = 15f
            setPadding(0, dp(10), 0, dp(8))
        })

        fun addButton(label: String, action: () -> Unit) {
            val button = Button(this).apply {
                text = label
                textSize = 14f
                isAllCaps = false
                setOnClickListener { action() }
            }
            content.addView(button, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(10)
            })
        }

        addButton("1. Разрешение поверх окон") {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Разрешение уже включено", Toast.LENGTH_SHORT).show()
            }
        }

        addButton("2. Включить службу специальных возможностей") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        addButton("3. Показать плавающую панель") {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Сначала включи разрешение «Поверх других окон»", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                try {
                    startService(Intent(this, OverlayService::class.java))
                    Toast.makeText(this, "Запускаю плавающую панель", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this, "Не удалось запустить панель: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        addButton("Остановить панель") {
            stopService(Intent(this, OverlayService::class.java))
            Toast.makeText(this, "Панель остановлена", Toast.LENGTH_SHORT).show()
        }

        val scroll = ScrollView(this).apply { addView(content) }
        setContentView(scroll)
    }
}
