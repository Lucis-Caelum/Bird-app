package com.luciscaelum.slayautoplayer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors

class GameAccessibilityService : AccessibilityService() {
    private val screenshotExecutor = Executors.newSingleThreadExecutor()
    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        screenStatus = "Служба подключена"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun captureScreenInternal() {
        if (Build.VERSION.SDK_INT < 30) {
            screenStatus = "Снимок экрана требует Android 11 или новее"
            return
        }
        screenStatus = "Получаю снимок экрана…"
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            screenshotExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hardwareBuffer: HardwareBuffer = result.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(hardwareBuffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } finally {
                        hardwareBuffer.close()
                    }

                    if (bitmap == null) {
                        screenStatus = "Не удалось прочитать снимок экрана"
                        return
                    }

                    val image = InputImage.fromBitmap(bitmap, 0)
                    textRecognizer.process(image)
                        .addOnSuccessListener { visionText ->
                            val recognized = visionText.text
                                .replace("\\s+".toRegex(), " ")
                                .trim()
                            lastOcrText = recognized.take(120)
                            val scoreMatch = Regex("(?:score|сч[её]т)?\\s*[:#-]?\\s*(\\d{1,6})", RegexOption.IGNORE_CASE)
                                .find(recognized)
                            lastScoreCandidate = scoreMatch?.groupValues?.get(1) ?: "—"
                            screenStatus = if (recognized.isBlank()) {
                                "Снимок получен, текст не найден"
                            } else {
                                "Экран прочитан"
                            }
                            bitmap.recycle()
                        }
                        .addOnFailureListener {
                            screenStatus = "Снимок есть, но распознавание текста не удалось"
                            bitmap.recycle()
                        }
                }

                override fun onFailure(errorCode: Int) {
                    screenStatus = when (errorCode) {
                        1 -> "Снимок отклонён системой"
                        2 -> "Снимок экрана недоступен"
                        3 -> "Слишком часто: подожди секунду"
                        else -> "Ошибка снимка экрана ($errorCode)"
                    }
                }
            }
        )
    }

    fun tap(x: Float, y: Float, done: (() -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < 24 || paused || manual) return
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 45))
            .build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done?.invoke() }
        }, null)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        screenshotExecutor.shutdown()
        textRecognizer.close()
        super.onDestroy()
    }

    companion object {
        @Volatile var paused = true
            private set
        @Volatile var manual = true
            private set
        @Volatile var screenStatus = "Служба не подключена"
        @Volatile var lastOcrText = ""
        @Volatile var lastScoreCandidate = "—"
        @Volatile private var instance: GameAccessibilityService? = null

        fun togglePause() { paused = !paused }
        fun manualTakeover() { manual = true; paused = true }
        fun requestScreenScan() {
            val service = instance
            if (service == null) {
                screenStatus = "Сначала включи службу специальных возможностей"
            } else {
                service.captureScreenInternal()
            }
        }
    }
}
