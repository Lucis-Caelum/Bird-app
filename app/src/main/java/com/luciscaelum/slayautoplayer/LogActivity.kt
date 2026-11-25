package com.luciscaelum.slayautoplayer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
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
    private lateinit var attemptsText: TextView
    private var currentLog = ""

    private fun dp(value: Int) = UiKit.dp(this, value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(UiKit.BG)
            setPadding(dp(16), dp(22), dp(16), dp(14))
        }
        root.addView(UiKit.text(this, "Журнал попыток", 24f, UiKit.TEXT, true))
        root.addView(UiKit.text(this, "Сохрани или скопируй журнал после серии игр и пришли его разработчику для настройки.", 12.5f, UiKit.TEXT_DIM).apply {
            setPadding(0, dp(2), 0, dp(10))
        })

        val card = UiKit.card(this)
        summaryText = UiKit.text(this, "", 14f, UiKit.TEXT, true)
        attemptsText = UiKit.text(this, "", 12f, UiKit.TEXT_DIM).apply { setPadding(0, dp(4), 0, 0) }
        card.addView(summaryText)
        card.addView(attemptsText)
        root.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun action(label: String, primary: Boolean, run: () -> Unit) {
            actions.addView(UiKit.button(this, label, primary, 42, run),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        }
        action("Обновить", false) { refreshLog() }
        action("Скопировать", false) { copyLog() }
        action("Сохранить TXT", true) { beginSave() }
        root.addView(actions)

        logText = TextView(this).apply {
            textSize = 10.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFD7E0EC.toInt())
            background = UiKit.rounded(this@LogActivity, 0xFF0A0E14.toInt(), 12, UiKit.CARD_STROKE, 1)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { isFillViewport = true; addView(logText) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(10); bottomMargin = dp(8) })

        root.addView(UiKit.button(this, "Назад", false, 42) { finish() })
        setContentView(root)
        refreshLog()
    }

    private fun refreshLog() {
        currentLog = GameAccessibilityService.getRunLog(this)
        logText.text = if (currentLog.isBlank()) "Журнал пока пуст." else currentLog
        val started = Regex("ATTEMPT_START\\s+attempt=").findAll(currentLog).count()
        val results = Regex("ATTEMPT_END attempt=(\\d+) dur=([0-9.]+)s passed=(\\d+)").findAll(currentLog).toList()
        val best = results.maxOfOrNull { it.groupValues[3].toInt() } ?: 0
        val session = if (GameAccessibilityService.monitoring) "автопилот работает" else "автопилот остановлен"
        summaryText.text = "Попыток: $started  ·  лучший результат: $best труб"
        attemptsText.text = (if (results.isEmpty()) "Завершённых попыток пока нет" else
            "Последние: " + results.takeLast(6).joinToString("  ") { "#${it.groupValues[1]}→${it.groupValues[3]}" }) + "\n$session"
    }

    private fun copyLog() {
        refreshLog()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        // clipboard transactions are size-limited: copy the tail, which holds the latest attempts
        val tail = if (currentLog.length > 200_000) currentLog.takeLast(200_000) else currentLog
        cm.setPrimaryClip(ClipData.newPlainText("Bird Play log", tail))
        Toast.makeText(this, "Журнал скопирован", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Журнал сохранён", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка сохранения: " + e.javaClass.simpleName, Toast.LENGTH_LONG).show()
        }
    }
}
