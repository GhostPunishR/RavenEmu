package com.ravenemu.input

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.ravenemu.emulation.api.EmulatorButton

/**
 * Contrôle RavenEmu composé de calques vectoriels indépendants.
 *
 * Le fond de coque ne contient jamais la face interactive : chaque instance
 * possède son asset normal, son halo et son renfort d'ombre interne. Les
 * transformations sont appliquées directement aux vues, sans `Animator`.
 */
internal open class RavenControlAssetView(
    context: Context,
    val controlId: ControlId,
    private val style: Style,
) : ViewGroup(context) {

    private val glow = image(glowResource(style))
    private val face = image(faceResource(controlId))
    private val pressed = image(pressedResource(style))
    private val label: TextView? = labelFor(controlId)?.let(::createLabel)

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = false
        isFocusable = false
        contentDescription = labelFor(controlId) ?: controlId.name
        addView(glow)
        addView(face)
        addView(pressed)
        label?.let(::addView)
    }

    open fun applyVisualState(
        element: ControlElement,
        animationState: ControlAnimationState,
        density: Float,
        editMode: Boolean,
    ) {
        val progress = animationState.controlProgress(controlId)
        alpha = when {
            !element.visible -> EDIT_HIDDEN_ALPHA
            editMode -> EDIT_ALPHA
            else -> element.opacity
        }
        val scale = 1f - PRESS_SCALE_DELTA * progress
        scaleX = scale
        scaleY = scale
        translationY = PRESS_OFFSET_DP * density * progress
        face.alpha = 1f - FACE_DIM_ON_PRESS * progress
        glow.alpha = progress * GLOW_MAX_ALPHA
        pressed.alpha = progress * PRESSED_MAX_ALPHA
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val measuredHeight = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
        val childWidthSpec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val childHeightSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        glow.measure(childWidthSpec, childHeightSpec)
        face.measure(childWidthSpec, childHeightSpec)
        pressed.measure(childWidthSpec, childHeightSpec)
        label?.let {
            it.setTextSize(TypedValue.COMPLEX_UNIT_PX, labelSizePx(measuredWidth, measuredHeight))
            it.measure(childWidthSpec, childHeightSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        glow.layout(0, 0, width, height)
        face.layout(0, 0, width, height)
        pressed.layout(0, 0, width, height)
        label?.layout(0, 0, width, height)
    }

    private fun image(resource: Int): ImageView = ImageView(context).apply {
        setImageResource(resource)
        scaleType = ImageView.ScaleType.FIT_XY
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun createLabel(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(ACCENT_TEXT)
        gravity = when (style) {
            Style.CAPTION, Style.MENU -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            else -> Gravity.CENTER
        }
        includeFontPadding = false
        letterSpacing = when (style) {
            Style.CAPTION, Style.MENU -> 0.08f
            else -> 0.03f
        }
        typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
        setShadowLayer(5f, 0f, 1f, SHADOW_TEXT)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun labelSizePx(width: Int, height: Int): Float = when (style) {
        Style.ROUND -> width * 0.36f
        Style.SHOULDER -> height * 0.37f
        Style.CAPTION -> width * 0.14f
        Style.MENU -> width * 0.13f
        Style.DPAD -> 0f
    }

    internal enum class Style { DPAD, ROUND, CAPTION, MENU, SHOULDER }

    private companion object {
        const val PRESS_SCALE_DELTA = 0.06f
        const val PRESS_OFFSET_DP = 2.5f
        const val FACE_DIM_ON_PRESS = 0.10f
        const val GLOW_MAX_ALPHA = 0.92f
        const val PRESSED_MAX_ALPHA = 0.78f
        const val EDIT_ALPHA = 0.96f
        const val EDIT_HIDDEN_ALPHA = 0.34f
        val ACCENT_TEXT = Color.rgb(170, 104, 255)
        val SHADOW_TEXT = Color.argb(230, 32, 7, 56)

        fun labelFor(id: ControlId): String? = when (id) {
            ControlId.DPAD -> null
            ControlId.BUTTON_A -> "A"
            ControlId.BUTTON_B -> "B"
            ControlId.START -> "START"
            ControlId.SELECT -> "SELECT"
            ControlId.MENU -> "MENU"
            ControlId.BUTTON_L -> "L"
            ControlId.BUTTON_R -> "R"
        }

        fun faceResource(id: ControlId): Int = when (id) {
            ControlId.DPAD -> R.drawable.raven_control_dpad
            ControlId.BUTTON_A -> R.drawable.raven_control_button_a
            ControlId.BUTTON_B -> R.drawable.raven_control_button_b
            ControlId.START -> R.drawable.raven_control_start
            ControlId.SELECT -> R.drawable.raven_control_select
            ControlId.MENU -> R.drawable.raven_control_menu
            ControlId.BUTTON_L -> R.drawable.raven_control_button_l
            ControlId.BUTTON_R -> R.drawable.raven_control_button_r
        }

        fun glowResource(style: Style): Int = when (style) {
            Style.DPAD -> R.drawable.raven_control_glow_dpad
            Style.ROUND -> R.drawable.raven_control_glow_round
            Style.MENU -> R.drawable.raven_control_glow_menu
            Style.CAPTION, Style.SHOULDER -> R.drawable.raven_control_glow_pill
        }

        fun pressedResource(style: Style): Int = when (style) {
            Style.DPAD -> R.drawable.raven_control_pressed_dpad
            Style.ROUND -> R.drawable.raven_control_pressed_round
            Style.MENU -> R.drawable.raven_control_pressed_menu
            Style.CAPTION, Style.SHOULDER -> R.drawable.raven_control_pressed_pill
        }
    }
}

/** D-pad à quatre halos indépendants : deux sont visibles lors d'une diagonale. */
internal class RavenDpadAssetView(context: Context) :
    RavenControlAssetView(context, ControlId.DPAD, Style.DPAD) {

    private val up = directionOverlay(rotationDegrees = 0f)
    private val rightDirection = directionOverlay(rotationDegrees = 90f)
    private val down = directionOverlay(rotationDegrees = 180f)
    private val leftDirection = directionOverlay(rotationDegrees = 270f)

    init {
        addView(up)
        addView(rightDirection)
        addView(down)
        addView(leftDirection)
    }

    override fun applyVisualState(
        element: ControlElement,
        animationState: ControlAnimationState,
        density: Float,
        editMode: Boolean,
    ) {
        super.applyVisualState(element, animationState, density, editMode)
        up.alpha = animationState.dpadProgress(EmulatorButton.UP)
        rightDirection.alpha = animationState.dpadProgress(EmulatorButton.RIGHT)
        down.alpha = animationState.dpadProgress(EmulatorButton.DOWN)
        leftDirection.alpha = animationState.dpadProgress(EmulatorButton.LEFT)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val widthSpec = MeasureSpec.makeMeasureSpec(measuredWidth, MeasureSpec.EXACTLY)
        val heightSpec = MeasureSpec.makeMeasureSpec(measuredHeight, MeasureSpec.EXACTLY)
        up.measure(widthSpec, heightSpec)
        rightDirection.measure(widthSpec, heightSpec)
        down.measure(widthSpec, heightSpec)
        leftDirection.measure(widthSpec, heightSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val width = right - left
        val height = bottom - top
        up.layout(0, 0, width, height)
        rightDirection.layout(0, 0, width, height)
        down.layout(0, 0, width, height)
        leftDirection.layout(0, 0, width, height)
    }

    private fun directionOverlay(rotationDegrees: Float): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.raven_control_dpad_direction)
        scaleType = ImageView.ScaleType.FIT_XY
        rotation = rotationDegrees
        alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }
}

internal object RavenControlViewFactory {
    fun create(context: Context, id: ControlId): RavenControlAssetView = when (id) {
        ControlId.DPAD -> RavenDpadAssetView(context)
        ControlId.BUTTON_A, ControlId.BUTTON_B ->
            RavenControlAssetView(context, id, RavenControlAssetView.Style.ROUND)
        ControlId.SELECT, ControlId.START ->
            RavenControlAssetView(context, id, RavenControlAssetView.Style.CAPTION)
        ControlId.MENU ->
            RavenControlAssetView(context, id, RavenControlAssetView.Style.MENU)
        ControlId.BUTTON_L, ControlId.BUTTON_R ->
            RavenControlAssetView(context, id, RavenControlAssetView.Style.SHOULDER)
    }
}
