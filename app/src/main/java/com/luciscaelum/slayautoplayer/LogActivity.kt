package com.luciscaelum.slayautoplayer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogActivity : Activity() {
    private val saveRequest = 4208
    private lateinit var logText: TextView
    private lateinit var summaryText: TextView
    private var currentLog = ""

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(16))
        }

        root.addView(TextView(this).apply {
            text = "Журнал игровых попыток"
            textSize = 23f
            setTextColor(0xFF18202A.toInt())
        })

        root.addView(TextView(this).apply {
            text = "Подробная диагностика текущей сессии. Обнови журнал после серии игр и сохрани его как TXT в выбранную папку телефона."
            textSize = 14f
            setTextColor(0xFF4B5563.toInt())
            setPadding(0, dp(7), 0, dp(9))
        })

        summaryText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF245B8F.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(summaryText)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun actionButton(label: String, action: () -> Unit) {
            val button = Button(this).apply {
                text = label
                isAllCaps = false
                textSize = 12f
                setOnClickListener { action() }
            }
            actions.addView(button, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            })
        }
        actionButton("Обновить") { refreshLog() }
        actionButton("Сохранить TXT") { beginSave() }
        root.addView(actions)

        logText = TextView(this).apply {
            textSize = 10.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFE7EDF5.toInt())
            setBackgroundColor(0xFF18212B.toInt())
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextIsSelectable(true)
        }
        val logScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(logText)
        }
        root.addView(logScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(8) })

        root.addView(Button(this).apply {
            text = "Назад"
            isAllCaps = false
            setOnClickListener { finish() }
        })

        setContentView(root)
        refreshLog()
    }

    private fun refreshLog() {
        currentLog = GameAccessibilityService.getRunLog(this)
        logText.text = if (currentLog.isBlank()) "Журнал пока пуст." else currentLog
        val started = Regex("ATTEMPT_START\\s+attempt=").findAll(currentLog).count()
        val ended = Regex("ATTEMPT_END\\s+attempt=").findAll(currentLog).count()
        val session = if (GameAccessibilityService.monitoring) "мониторинг активен" else "мониторинг остановлен"
        summaryText.text = "Попыток начато: $started  •  завершено/прервано: $ended  •  $session"
    }

    private fun beginSave() {
        refreshLog()
        if (currentLog.isBlank() || currentLog.startsWith("Лог пока")) {
            Toast.makeText(this, "Пока нечего сохранять", Toast.LENGTH_SHORT).show()
            return
        }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "Bird-Play-session-$stamp.txt")
        }
        startActivityForResult(intent, saveRequest)
    }

    @Deprecated("Use Activity Result APIs")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != saveRequest || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val output = contentResolver.openOutputStream(uri, "wt")
                ?: throw IllegalStateException("Не удалось открыть файл для записи")
            output.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(currentLog) }
            Toast.makeText(this, "Журнал сохранён в выбранное место", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка сохранения: " + e.javaClass.simpleName, Toast.LENGTH_LONG).show()
        }
    }
}
