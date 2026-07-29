package com.ravenemu.input

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** Rendu historique utilisé en paysage et comme solution de repli. */
internal class ClassicTouchSkinRenderer : TouchSkinRenderer {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val bounds = RectF()

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var density = 1f

    override fun onViewportChanged(
        width: Int,
        height: Int,
        density: Float,
        screenTopInsetPx: Int,
    ) {
        viewportWidth = width
        viewportHeight = height
        this.density = density
        strokePaint.strokeWidth = 1.5f * density
    }

    override fun draw(
        canvas: Canvas,
        layout: ControlLayout,
        editMode: Boolean,
        selectedElement: ControlId?,
        animationState: ControlAnimationState,
        drawBackground: Boolean,
    ) {
        for (index in layout.elements.indices) {
            val element = layout.elements[index]
            if (!element.visible && !editMode) continue
            val alpha = ((if (editMode) EDIT_ALPHA else element.opacity) * 255).toInt()
            val cx = ControlGeometry.centerX(element, viewportWidth)
            val cy = ControlGeometry.centerY(element, viewportHeight)
            val radius = ControlGeometry.radiusPx(element, density)
            val press = animationState.controlProgress(element.id)
            val pressedY = cy + PRESS_OFFSET_DP * density * press
            val scale = 1f - PRESS_SCALE_DELTA * press
            val saveCount = canvas.save()
            canvas.scale(scale, scale, cx, pressedY)
            fillPaint.color = Color.argb(alpha / 3, 255, 255, 255)
            strokePaint.color = Color.argb(alpha, 255, 255, 255)
            textPaint.color = Color.argb(alpha, 255, 255, 255)
            when (element.id) {
                ControlId.DPAD -> drawDpad(canvas, cx, pressedY, radius)
                ControlId.BUTTON_A -> drawRound(canvas, cx, pressedY, radius, "A")
                ControlId.BUTTON_B -> drawRound(canvas, cx, pressedY, radius, "B")
                ControlId.START -> drawPill(canvas, cx, pressedY, radius, "START")
                ControlId.SELECT -> drawPill(canvas, cx, pressedY, radius, "SELECT")
                ControlId.BUTTON_L -> drawPill(canvas, cx, pressedY, radius, "L")
                ControlId.BUTTON_R -> drawPill(canvas, cx, pressedY, radius, "R")
                ControlId.MENU -> drawPill(canvas, cx, pressedY, radius, "MENU")
            }
            canvas.restoreToCount(saveCount)
            if (editMode && selectedElement == element.id) {
                strokePaint.color = Color.rgb(155, 92, 255)
                canvas.drawCircle(cx, cy, radius * 1.2f, strokePaint)
            }
        }
    }

    private fun drawDpad(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val arm = radius / 3f
        bounds.set(cx - arm, cy - radius, cx + arm, cy + radius)
        canvas.drawRoundRect(bounds, arm / 2, arm / 2, fillPaint)
        canvas.drawRoundRect(bounds, arm / 2, arm / 2, strokePaint)
        bounds.set(cx - radius, cy - arm, cx + radius, cy + arm)
        canvas.drawRoundRect(bounds, arm / 2, arm / 2, fillPaint)
        canvas.drawRoundRect(bounds, arm / 2, arm / 2, strokePaint)
    }

    private fun drawRound(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        label: String,
    ) {
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, strokePaint)
        textPaint.textSize = radius * 0.9f
        canvas.drawText(label, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private fun drawPill(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        label: String,
    ) {
        bounds.set(
            cx - radius * PILL_HALF_WIDTH,
            cy - radius * PILL_HALF_HEIGHT,
            cx + radius * PILL_HALF_WIDTH,
            cy + radius * PILL_HALF_HEIGHT,
        )
        canvas.drawRoundRect(bounds, radius * PILL_HALF_HEIGHT, radius * PILL_HALF_HEIGHT, fillPaint)
        canvas.drawRoundRect(bounds, radius * PILL_HALF_HEIGHT, radius * PILL_HALF_HEIGHT, strokePaint)
        textPaint.textSize = radius * 0.52f
        canvas.drawText(label, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2f, textPaint)
    }

    private companion object {
        const val EDIT_ALPHA = 0.85f
        const val PRESS_SCALE_DELTA = 0.06f
        const val PRESS_OFFSET_DP = 2.5f
        const val PILL_HALF_WIDTH = 1.6f
        const val PILL_HALF_HEIGHT = 0.62f
    }
}
