package com.luciscaelum.slayautoplayer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent

class GameAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    companion object {
        @Volatile var paused = true
            private set
        @Volatile var manual = true
            private set
        fun togglePause() { paused = !paused }
        fun manualTakeover() { manual = true; paused = true }
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
}
