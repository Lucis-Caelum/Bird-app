package com.luciscaelum.slayautoplayer

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Tiny shared UI helpers (the app has no XML layouts). */
object UiKit {
    const val BG = 0xFF0F131A.toInt()
    const val CARD = 0xFF1A212C.toInt()
    const val CARD_STROKE = 0xFF2A3342.toInt()
    const val TEXT = 0xFFEAF0F8.toInt()
    const val TEXT_DIM = 0xFF93A0B4.toInt()
    const val ACCENT = 0xFF4F9DFF.toInt()
    const val OK = 0xFF3DDC84.toInt()
    const val WARN = 0xFFFFB020.toInt()
    const val BAD = 0xFFFF5C5C.toInt()

    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(ctx: Context, fill: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(ctx, radiusDp).toFloat()
            if (strokeDp > 0) setStroke(dp(ctx, strokeDp), strokeColor)
        }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(ctx, CARD, 16, CARD_STROKE, 1)
        setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
    }

    fun text(ctx: Context, value: String, sizeSp: Float, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    /** Filled (primary) or outlined (secondary) button. */
    fun button(ctx: Context, label: String, primary: Boolean, heightDp: Int = 46, onClick: () -> Unit): Button =
        Button(ctx).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(if (primary) 0xFF06101F.toInt() else TEXT)
            background = if (primary) rounded(ctx, ACCENT, 12)
            else rounded(ctx, 0x00000000, 12, 0xFF3A4558.toInt(), 1)
            minHeight = dp(ctx, heightDp)
            minimumHeight = dp(ctx, heightDp)
            stateListAnimator = null
            gravity = Gravity.CENTER
            setOnClickListener { onClick() }
        }

    fun dot(ctx: Context, color: Int, sizeDp: Int = 12): View = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(ctx, sizeDp), dp(ctx, sizeDp)).apply { marginEnd = dp(ctx, 10) }
    }

    fun setDot(view: View, color: Int) {
        (view.background as? GradientDrawable)?.setColor(color)
    }
}
