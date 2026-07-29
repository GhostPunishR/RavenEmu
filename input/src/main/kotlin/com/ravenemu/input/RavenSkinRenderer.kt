package com.ravenemu.input

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.ravenemu.emulation.api.EmulatorButton
import kotlin.math.max

/**
 * Base Canvas commune aux skins portrait RavenEmu.
 *
 * Le panneau, le motif de plume et tous les objets Paint/Path/RectF sont mis en
 * cache. Les boutons sont dessinés séparément à partir du [ControlLayout] :
 * aucun bitmap aplati ne couple le fond et les zones interactives.
 */
internal abstract class RavenSkinRenderer(
    private val definition: SkinDefinition,
) : TouchSkinRenderer {
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val panelEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val motifPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val innerShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val brandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.16f
    }
    private val bounds = RectF()
    private val featherPath = Path()

    private var viewportWidth = 0
    private var viewportHeight = 0
    private var density = 1f
    private var panelTop = 0f

    override fun onViewportChanged(
        width: Int,
        height: Int,
        density: Float,
        screenTopInsetPx: Int,
    ) {
        if (
            width == viewportWidth &&
            height == viewportHeight &&
            density == this.density &&
            panelTopInset == screenTopInsetPx
        ) {
            return
        }
        viewportWidth = width
        viewportHeight = height
        this.density = density
        panelTopInset = screenTopInsetPx
        if (width <= 0 || height <= 0) return

        val nativeScreenHeight = if (definition.screenAspectRatio > 0f) {
            width / definition.screenAspectRatio
        } else {
            0f
        }
        panelTop = (screenTopInsetPx + nativeScreenHeight + PANEL_GAP_DP * density)
            .coerceIn(0f, height.toFloat())
        panelPaint.shader = LinearGradient(
            0f,
            panelTop,
            0f,
            height.toFloat(),
            intArrayOf(
                Color.rgb(10, 10, 14),
                Color.rgb(20, 17, 27),
                Color.rgb(8, 8, 12),
            ),
            floatArrayOf(0f, 0.46f, 1f),
            Shader.TileMode.CLAMP,
        )
        panelEdgePaint.strokeWidth = 1.25f * density
        motifPaint.strokeWidth = 1.15f * density
        rimPaint.strokeWidth = 1.4f * density
        glowPaint.strokeWidth = 2.2f * density
        innerShadowPaint.strokeWidth = 2.1f * density
        brandPaint.textSize = 9.5f * density
        rebuildFeatherPath(width.toFloat(), height.toFloat())
    }

    override fun draw(
        canvas: Canvas,
        layout: ControlLayout,
        editMode: Boolean,
        selectedElement: ControlId?,
        animationState: ControlAnimationState,
        drawBackground: Boolean,
    ) {
        if (drawBackground) drawPanel(canvas)
        for (index in layout.elements.indices) {
            val element = layout.elements[index]
            if (!element.visible && !editMode) continue
            val alpha = (
                if (!element.visible) EDIT_HIDDEN_ALPHA
                else if (editMode) EDIT_ALPHA
                else element.opacity
                ).coerceIn(0f, 1f)
            val cx = ControlGeometry.centerX(element, viewportWidth)
            val cy = ControlGeometry.centerY(element, viewportHeight)
            val radius = ControlGeometry.radiusPx(element, density)
            when (element.id) {
                ControlId.DPAD ->
                    drawDpad(canvas, cx, cy, radius, alpha, animationState)
                ControlId.BUTTON_A ->
                    drawRoundButton(canvas, cx, cy, radius, "A", alpha, animationState)
                ControlId.BUTTON_B ->
                    drawRoundButton(canvas, cx, cy, radius, "B", alpha, animationState)
                ControlId.START ->
                    drawPillButton(canvas, element.id, cx, cy, radius, "START", alpha, animationState)
                ControlId.SELECT ->
                    drawPillButton(canvas, element.id, cx, cy, radius, "SELECT", alpha, animationState)
                ControlId.BUTTON_L ->
                    drawPillButton(canvas, element.id, cx, cy, radius, "L", alpha, animationState)
                ControlId.BUTTON_R ->
                    drawPillButton(canvas, element.id, cx, cy, radius, "R", alpha, animationState)
                ControlId.MENU ->
                    drawPillButton(canvas, element.id, cx, cy, radius, "MENU", alpha, animationState)
            }
            if (editMode && selectedElement == element.id) {
                drawSelection(canvas, cx, cy, radius, element.id)
            }
        }
    }

    private fun drawPanel(canvas: Canvas) {
        if (panelTop >= viewportHeight) return
        bounds.set(0f, panelTop, viewportWidth.toFloat(), viewportHeight.toFloat() + CORNER_DP * density)
        canvas.drawRoundRect(bounds, CORNER_DP * density, CORNER_DP * density, panelPaint)
        panelEdgePaint.color = withAlpha(definition.accentColor, 105)
        canvas.drawLine(0f, panelTop, viewportWidth.toFloat(), panelTop, panelEdgePaint)

        motifPaint.color = withAlpha(definition.accentColor, 38)
        canvas.drawPath(featherPath, motifPaint)
        brandPaint.color = withAlpha(Color.WHITE, 76)
        canvas.drawText(
            definition.brandLabel,
            viewportWidth * 0.5f,
            viewportHeight - 18f * density,
            brandPaint,
        )
    }

    private fun drawRoundButton(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        label: String,
        opacity: Float,
        animationState: ControlAnimationState,
    ) {
        val id = if (label == "A") ControlId.BUTTON_A else ControlId.BUTTON_B
        val press = animationState.controlProgress(id)
        val pressedY = cy + PRESS_OFFSET_DP * density * press
        val scale = 1f - PRESS_SCALE_DELTA * press
        val saveCount = canvas.save()
        canvas.scale(scale, scale, cx, pressedY)

        shadowPaint.color = withOpacity(Color.BLACK, opacity * (0.64f + press * 0.18f))
        canvas.drawCircle(cx, pressedY + (4f + press) * density, radius * 1.03f, shadowPaint)
        if (press > 0f) {
            glowPaint.color = withOpacity(definition.accentColor, opacity * press * 0.95f)
            glowPaint.strokeWidth = (2.2f + press * 1.8f) * density
            canvas.drawCircle(cx, pressedY, radius * 1.06f, glowPaint)
        }
        facePaint.color = withOpacity(definition.buttonColor, opacity)
        canvas.drawCircle(cx, pressedY, radius, facePaint)
        rimPaint.color = withOpacity(definition.accentColor, opacity * (0.72f + press * 0.28f))
        canvas.drawCircle(cx, pressedY, radius, rimPaint)
        innerShadowPaint.color = withOpacity(
            Color.BLACK,
            opacity * (0.36f + press * 0.28f),
        )
        canvas.drawCircle(cx, pressedY + density, radius * 0.88f, innerShadowPaint)

        textPaint.textSize = radius * 0.78f
        textPaint.color = withOpacity(Color.WHITE, opacity)
        drawCenteredText(canvas, label, cx, pressedY, textPaint)
        canvas.restoreToCount(saveCount)
    }

    private fun drawPillButton(
        canvas: Canvas,
        id: ControlId,
        cx: Float,
        cy: Float,
        radius: Float,
        label: String,
        opacity: Float,
        animationState: ControlAnimationState,
    ) {
        val press = animationState.controlProgress(id)
        val pressedY = cy + PRESS_OFFSET_DP * density * press
        val scale = 1f - PRESS_SCALE_DELTA * press
        val halfWidth = if (id == ControlId.MENU) radius * MENU_HALF_WIDTH else radius * PILL_HALF_WIDTH
        val halfHeight = radius * PILL_HALF_HEIGHT
        val corner = halfHeight
        val saveCount = canvas.save()
        canvas.scale(scale, scale, cx, pressedY)

        bounds.set(
            cx - halfWidth,
            pressedY - halfHeight + SHADOW_OFFSET_DP * density,
            cx + halfWidth,
            pressedY + halfHeight + SHADOW_OFFSET_DP * density,
        )
        shadowPaint.color = withOpacity(Color.BLACK, opacity * (0.62f + press * 0.18f))
        canvas.drawRoundRect(bounds, corner, corner, shadowPaint)

        bounds.set(cx - halfWidth, pressedY - halfHeight, cx + halfWidth, pressedY + halfHeight)
        if (press > 0f) {
            glowPaint.color = withOpacity(definition.accentColor, opacity * press)
            glowPaint.strokeWidth = (2.2f + press * 1.8f) * density
            canvas.drawRoundRect(bounds, corner, corner, glowPaint)
        }
        facePaint.color = withOpacity(definition.buttonColor, opacity)
        canvas.drawRoundRect(bounds, corner, corner, facePaint)
        rimPaint.color = withOpacity(definition.accentColor, opacity * (0.70f + press * 0.30f))
        canvas.drawRoundRect(bounds, corner, corner, rimPaint)

        bounds.inset(2.3f * density, 2f * density)
        innerShadowPaint.color = withOpacity(
            Color.BLACK,
            opacity * (0.34f + press * 0.30f),
        )
        canvas.drawRoundRect(bounds, max(0f, corner - 2f * density), max(0f, corner - 2f * density), innerShadowPaint)

        textPaint.textSize = radius * if (label.length > 2) 0.48f else 0.64f
        textPaint.color = withOpacity(Color.WHITE, opacity)
        drawCenteredText(canvas, label, cx, pressedY, textPaint)
        canvas.restoreToCount(saveCount)
    }

    private fun drawDpad(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        opacity: Float,
        animationState: ControlAnimationState,
    ) {
        val arm = radius * DPAD_ARM_RATIO
        shadowPaint.color = withOpacity(Color.BLACK, opacity * 0.68f)
        bounds.set(
            cx - arm,
            cy - radius + SHADOW_OFFSET_DP * density,
            cx + arm,
            cy + radius + SHADOW_OFFSET_DP * density,
        )
        canvas.drawRoundRect(bounds, DPAD_CORNER_DP * density, DPAD_CORNER_DP * density, shadowPaint)
        bounds.set(
            cx - radius,
            cy - arm + SHADOW_OFFSET_DP * density,
            cx + radius,
            cy + arm + SHADOW_OFFSET_DP * density,
        )
        canvas.drawRoundRect(bounds, DPAD_CORNER_DP * density, DPAD_CORNER_DP * density, shadowPaint)

        facePaint.color = withOpacity(definition.dpadColor, opacity)
        rimPaint.color = withOpacity(definition.accentColor, opacity * 0.68f)
        bounds.set(cx - arm, cy - radius, cx + arm, cy + radius)
        canvas.drawRoundRect(bounds, DPAD_CORNER_DP * density, DPAD_CORNER_DP * density, facePaint)
        canvas.drawRoundRect(bounds, DPAD_CORNER_DP * density, DPAD_CORNER_DP * density, rimPaint)
        bounds.set(cx - radius, cy - arm, cx + radius, cy + arm)
        canvas.drawRoundRect(bounds, DPAD_CORNER_DP * density, DPAD_CORNER_DP * density, facePaint)
        canvas.drawRoundRect(bounds, DPAD_CORNER_DP * density, DPAD_CORNER_DP * density, rimPaint)

        drawDpadDirection(
            canvas,
            EmulatorButton.UP,
            cx,
            cy,
            radius,
            arm,
            opacity,
            animationState.dpadProgress(EmulatorButton.UP),
        )
        drawDpadDirection(
            canvas,
            EmulatorButton.DOWN,
            cx,
            cy,
            radius,
            arm,
            opacity,
            animationState.dpadProgress(EmulatorButton.DOWN),
        )
        drawDpadDirection(
            canvas,
            EmulatorButton.LEFT,
            cx,
            cy,
            radius,
            arm,
            opacity,
            animationState.dpadProgress(EmulatorButton.LEFT),
        )
        drawDpadDirection(
            canvas,
            EmulatorButton.RIGHT,
            cx,
            cy,
            radius,
            arm,
            opacity,
            animationState.dpadProgress(EmulatorButton.RIGHT),
        )
        drawDpadGlyphs(canvas, cx, cy, radius, opacity)
    }

    private fun drawDpadDirection(
        canvas: Canvas,
        direction: EmulatorButton,
        cx: Float,
        cy: Float,
        radius: Float,
        arm: Float,
        opacity: Float,
        press: Float,
    ) {
        if (press <= 0f) return
        val centerX: Float
        val centerY: Float
        when (direction) {
            EmulatorButton.UP -> {
                bounds.set(cx - arm, cy - radius, cx + arm, cy)
                centerX = cx
                centerY = cy - radius * 0.5f
            }
            EmulatorButton.DOWN -> {
                bounds.set(cx - arm, cy, cx + arm, cy + radius)
                centerX = cx
                centerY = cy + radius * 0.5f
            }
            EmulatorButton.LEFT -> {
                bounds.set(cx - radius, cy - arm, cx, cy + arm)
                centerX = cx - radius * 0.5f
                centerY = cy
            }
            EmulatorButton.RIGHT -> {
                bounds.set(cx, cy - arm, cx + radius, cy + arm)
                centerX = cx + radius * 0.5f
                centerY = cy
            }
            else -> return
        }
        val pressedY = centerY + PRESS_OFFSET_DP * density * press
        val saveCount = canvas.save()
        canvas.translate(0f, pressedY - centerY)
        canvas.scale(1f - PRESS_SCALE_DELTA * press, 1f - PRESS_SCALE_DELTA * press, centerX, centerY)
        glowPaint.color = withOpacity(definition.accentColor, opacity * press)
        glowPaint.strokeWidth = (2f + press * 1.7f) * density
        canvas.drawRoundRect(bounds, DPAD_CORNER_DP * density, DPAD_CORNER_DP * density, glowPaint)
        facePaint.color = withOpacity(definition.pressedColor, opacity * (0.45f + press * 0.45f))
        canvas.drawRoundRect(bounds, DPAD_CORNER_DP * density, DPAD_CORNER_DP * density, facePaint)
        innerShadowPaint.color = withOpacity(Color.BLACK, opacity * press * 0.55f)
        canvas.drawRoundRect(bounds, DPAD_CORNER_DP * density, DPAD_CORNER_DP * density, innerShadowPaint)
        canvas.restoreToCount(saveCount)
    }

    private fun drawDpadGlyphs(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        opacity: Float,
    ) {
        motifPaint.color = withOpacity(definition.accentColor, opacity * 0.72f)
        motifPaint.strokeWidth = 1.4f * density
        val inset = radius * 0.56f
        val half = radius * 0.09f
        canvas.drawLine(cx - half, cy - inset + half, cx, cy - inset, motifPaint)
        canvas.drawLine(cx, cy - inset, cx + half, cy - inset + half, motifPaint)
        canvas.drawLine(cx - half, cy + inset - half, cx, cy + inset, motifPaint)
        canvas.drawLine(cx, cy + inset, cx + half, cy + inset - half, motifPaint)
        canvas.drawLine(cx - inset + half, cy - half, cx - inset, cy, motifPaint)
        canvas.drawLine(cx - inset, cy, cx - inset + half, cy + half, motifPaint)
        canvas.drawLine(cx + inset - half, cy - half, cx + inset, cy, motifPaint)
        canvas.drawLine(cx + inset, cy, cx + inset - half, cy + half, motifPaint)
    }

    private fun drawSelection(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        id: ControlId,
    ) {
        glowPaint.color = withAlpha(definition.accentColor, 255)
        glowPaint.strokeWidth = 2f * density
        if (id == ControlId.BUTTON_A || id == ControlId.BUTTON_B || id == ControlId.DPAD) {
            canvas.drawCircle(cx, cy, radius * 1.18f, glowPaint)
        } else {
            val halfWidth = if (id == ControlId.MENU) radius * MENU_HALF_WIDTH else radius * PILL_HALF_WIDTH
            val halfHeight = radius * PILL_HALF_HEIGHT
            bounds.set(
                cx - halfWidth - 4f * density,
                cy - halfHeight - 4f * density,
                cx + halfWidth + 4f * density,
                cy + halfHeight + 4f * density,
            )
            canvas.drawRoundRect(bounds, halfHeight, halfHeight, glowPaint)
        }
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        cx: Float,
        cy: Float,
        paint: Paint,
    ) {
        canvas.drawText(text, cx, cy - (paint.ascent() + paint.descent()) / 2f, paint)
    }

    private fun rebuildFeatherPath(width: Float, height: Float) {
        val cx = width * 0.5f
        val baseY = height - 54f * density
        val size = 42f * density
        featherPath.reset()
        featherPath.moveTo(cx, baseY + size * 0.45f)
        featherPath.cubicTo(
            cx - size * 0.15f,
            baseY + size * 0.08f,
            cx - size * 0.46f,
            baseY - size * 0.10f,
            cx - size * 0.30f,
            baseY - size * 0.50f,
        )
        featherPath.cubicTo(
            cx - size * 0.02f,
            baseY - size * 0.32f,
            cx + size * 0.36f,
            baseY - size * 0.22f,
            cx + size * 0.30f,
            baseY + size * 0.06f,
        )
        featherPath.cubicTo(
            cx + size * 0.23f,
            baseY + size * 0.30f,
            cx + size * 0.08f,
            baseY + size * 0.40f,
            cx,
            baseY + size * 0.45f,
        )
        featherPath.moveTo(cx - size * 0.23f, baseY - size * 0.36f)
        featherPath.lineTo(cx + size * 0.15f, baseY + size * 0.30f)
        featherPath.moveTo(cx - size * 0.16f, baseY - size * 0.12f)
        featherPath.lineTo(cx + size * 0.12f, baseY - size * 0.05f)
        featherPath.moveTo(cx - size * 0.05f, baseY + size * 0.08f)
        featherPath.lineTo(cx + size * 0.20f, baseY + size * 0.05f)
    }

    private fun withOpacity(color: Int, opacity: Float): Int =
        withAlpha(color, (opacity.coerceIn(0f, 1f) * 255).toInt())

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00ffffff) or (alpha.coerceIn(0, 255) shl 24)

    private var panelTopInset = Int.MIN_VALUE

    internal data class SkinDefinition(
        val screenAspectRatio: Float,
        val brandLabel: String,
        val accentColor: Int,
        val buttonColor: Int,
        val dpadColor: Int,
        val pressedColor: Int,
    )

    private companion object {
        const val EDIT_ALPHA = 0.96f
        const val EDIT_HIDDEN_ALPHA = 0.34f
        const val PRESS_SCALE_DELTA = 0.06f
        const val PRESS_OFFSET_DP = 2.5f
        const val SHADOW_OFFSET_DP = 3.5f
        const val PANEL_GAP_DP = 7f
        const val CORNER_DP = 24f
        const val PILL_HALF_WIDTH = 1.62f
        const val MENU_HALF_WIDTH = 1.78f
        const val PILL_HALF_HEIGHT = 0.62f
        const val DPAD_ARM_RATIO = 0.34f
        const val DPAD_CORNER_DP = 8f
    }
}

/** Skin portrait noir/anthracite et violet du cœur GB/GBC. */
internal class RavenGbSkinRenderer : RavenSkinRenderer(
    RavenSkinRenderer.SkinDefinition(
        screenAspectRatio = 160f / 144f,
        brandLabel = "RAVENEMU",
        accentColor = Color.rgb(151, 82, 255),
        buttonColor = Color.rgb(31, 28, 39),
        dpadColor = Color.rgb(25, 23, 31),
        pressedColor = Color.rgb(85, 48, 132),
    )
)

/** Skin portrait noir/anthracite et violet du cœur GBA. */
internal class RavenGbaSkinRenderer : RavenSkinRenderer(
    RavenSkinRenderer.SkinDefinition(
        screenAspectRatio = 240f / 160f,
        brandLabel = "RAVENEMU",
        accentColor = Color.rgb(166, 92, 255),
        buttonColor = Color.rgb(32, 29, 41),
        dpadColor = Color.rgb(24, 22, 30),
        pressedColor = Color.rgb(91, 50, 143),
    )
)
