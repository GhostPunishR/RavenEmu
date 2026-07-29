package com.ravenemu.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import com.ravenemu.emulation.api.EmulatorButton
import kotlin.math.max

/**
 * Couche interactive du skin.
 *
 * En portrait RavenEmu, chaque commande est une vue vectorielle distincte. La
 * classe conserve uniquement l'entrée, les animations, le multitouch et
 * l'éditeur. Le rendu Canvas historique reste limité au mode paysage CLASSIC.
 */
class TouchControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    interface Listener {
        fun onButton(button: EmulatorButton, pressed: Boolean)
        fun onMenu()
    }

    var listener: Listener? = null
    var onLayoutChanged: ((ControlLayout) -> Unit)? = null

    var skin: TouchSkin = TouchSkin.RAVEN_GB
        set(value) {
            if (field == value) return
            field = value
            animationState.reset()
            updateControlMode()
            requestLayout()
            invalidate()
        }

    var layoutSpec: ControlLayout = ControlLayout.defaultPortrait()
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var editMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            releaseAll()
            requestLayout()
            invalidate()
        }

    var selectedElement: ControlId? = null
        private set

    private val classicRenderer = ClassicTouchSkinRenderer()
    private val animationState = ControlAnimationState()
    private val controlViews = arrayOfNulls<RavenControlAssetView>(ControlId.entries.size)
    private val selectionBounds = RectF()
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(171, 103, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val inputState = TouchInputState(
        onButtonChanged = { button, pressed ->
            // L'entrée logique reste synchrone et précède toute animation.
            listener?.onButton(button, pressed)
            if (animationState.setButtonPressed(button, pressed)) {
                animationState.advance(System.nanoTime())
                postInvalidateOnAnimation()
            }
        },
        onMenuVisualChanged = { pressed ->
            if (animationState.setMenuPressed(pressed)) {
                animationState.advance(System.nanoTime())
                postInvalidateOnAnimation()
            }
        },
    )

    private var dragPointer = -1
    private var dragElement: ControlId? = null

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
        ensureRavenControlViews()
        updateControlMode()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        classicRenderer.onViewportChanged(
            width = width,
            height = height,
            density = resources.displayMetrics.density,
            screenTopInsetPx = 0,
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        val measuredHeight = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(measuredWidth, measuredHeight)
        if (skin == TouchSkin.CLASSIC) return

        val density = resources.displayMetrics.density
        for (id in ControlId.entries) {
            val element = layoutSpec.element(id) ?: continue
            val child = controlViews[id.ordinal] ?: continue
            val childWidth = ControlGeometry.widthPx(element, skin, measuredWidth, density)
                .toInt().coerceAtLeast(1)
            val childHeight = ControlGeometry.heightPx(element, skin, measuredHeight, density)
                .toInt().coerceAtLeast(1)
            child.measure(
                MeasureSpec.makeMeasureSpec(childWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(childHeight, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (skin == TouchSkin.CLASSIC) return
        for (id in ControlId.entries) {
            val element = layoutSpec.element(id) ?: continue
            val child = controlViews[id.ordinal] ?: continue
            val shouldShow = element.visible || editMode
            child.visibility = if (shouldShow) View.VISIBLE else View.GONE
            if (!shouldShow) continue
            val centerX = ControlGeometry.centerX(element, width).toInt()
            val centerY = ControlGeometry.centerY(element, height).toInt()
            val childLeft = centerX - child.measuredWidth / 2
            val childTop = centerY - child.measuredHeight / 2
            child.layout(
                childLeft,
                childTop,
                childLeft + child.measuredWidth,
                childTop + child.measuredHeight,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val needsNextFrame = animationState.advance(System.nanoTime())
        if (skin == TouchSkin.CLASSIC) {
            classicRenderer.draw(
                canvas = canvas,
                layout = layoutSpec,
                editMode = editMode,
                selectedElement = selectedElement,
                animationState = animationState,
                drawBackground = false,
            )
        } else {
            applyVectorVisualState()
        }
        if (needsNextFrame) postInvalidateOnAnimation()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (skin != TouchSkin.CLASSIC && editMode) drawEditorSelection(canvas)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return handleEditTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val hitMask = hitMaskAt(event.getX(index), event.getY(index))
                val newPress = inputState.updatePointer(event.getPointerId(index), hitMask)
                if (newPress && layoutSpec.hapticFeedback) performControlHapticFeedback()
                if (ControlHitTester.hasMenu(hitMask)) listener?.onMenu()
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val newPress = inputState.updatePointer(
                        event.getPointerId(index),
                        hitMaskAt(event.getX(index), event.getY(index)),
                    )
                    if (newPress && layoutSpec.hapticFeedback) performControlHapticFeedback()
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                inputState.releasePointer(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun hitMaskAt(x: Float, y: Float): Int = ControlHitTester.hitMask(
        layout = layoutSpec,
        x = x,
        y = y,
        width = width,
        height = height,
        density = resources.displayMetrics.density,
        skin = skin,
    )

    private fun handleEditTouch(event: MotionEvent): Boolean {
        if (layoutSpec.locked) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = elementAt(event.x, event.y)
                selectedElement = hit
                if (hit != null) {
                    dragPointer = event.getPointerId(0)
                    dragElement = hit
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val element = dragElement?.let(layoutSpec::element) ?: return true
                val index = event.findPointerIndex(dragPointer)
                if (index < 0) return true
                val updated = element.copy(
                    centerX = event.getX(index) / width,
                    centerY = event.getY(index) / height,
                ).clamped()
                layoutSpec = layoutSpec.with(updated)
                onLayoutChanged?.invoke(layoutSpec)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragPointer = -1
                dragElement = null
            }
        }
        return true
    }

    private fun elementAt(x: Float, y: Float): ControlId? {
        var best: ControlId? = null
        var bestDistance = Float.MAX_VALUE
        val density = resources.displayMetrics.density
        for (element in layoutSpec.elements) {
            val halfWidth = ControlGeometry.widthPx(element, skin, width, density) * 0.7f
            val halfHeight = ControlGeometry.heightPx(element, skin, height, density) * 0.7f
            if (halfWidth <= 0f || halfHeight <= 0f) continue
            val normalizedX = (x - ControlGeometry.centerX(element, width)) / halfWidth
            val normalizedY = (y - ControlGeometry.centerY(element, height)) / halfHeight
            val distance = normalizedX * normalizedX + normalizedY * normalizedY
            if (distance <= 1f && distance < bestDistance) {
                bestDistance = distance
                best = element.id
            }
        }
        return best
    }

    fun adjustSelected(
        scale: Float? = null,
        opacity: Float? = null,
        visible: Boolean? = null,
    ) {
        val id = selectedElement ?: return
        val element = layoutSpec.element(id) ?: return
        val updated = element.copy(
            scale = scale ?: element.scale,
            opacity = opacity ?: element.opacity,
            visible = visible ?: element.visible,
        ).clamped()
        layoutSpec = layoutSpec.with(updated)
        onLayoutChanged?.invoke(layoutSpec)
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    private fun applyVectorVisualState() {
        val density = resources.displayMetrics.density
        for (id in ControlId.entries) {
            val element = layoutSpec.element(id) ?: continue
            controlViews[id.ordinal]?.applyVisualState(
                element = element,
                animationState = animationState,
                density = density,
                editMode = editMode,
            )
        }
    }

    private fun drawEditorSelection(canvas: Canvas) {
        val id = selectedElement ?: return
        val child = controlViews[id.ordinal] ?: return
        if (child.visibility != View.VISIBLE) return
        val padding = 5f * resources.displayMetrics.density
        selectionBounds.set(
            child.left - padding,
            child.top - padding,
            child.right + padding,
            child.bottom + padding,
        )
        val radius = max(10f * resources.displayMetrics.density, child.height * 0.18f)
        canvas.drawRoundRect(selectionBounds, radius, radius, selectionPaint)
    }

    private fun ensureRavenControlViews() {
        for (id in ControlId.entries) {
            if (controlViews[id.ordinal] != null) continue
            val view = RavenControlViewFactory.create(context, id)
            controlViews[id.ordinal] = view
            addView(view, generateDefaultLayoutParams())
        }
    }

    private fun updateControlMode() {
        val vectorMode = skin != TouchSkin.CLASSIC
        for (child in controlViews) {
            child?.visibility = if (vectorMode) View.VISIBLE else View.GONE
        }
    }

    private fun releaseAll() {
        inputState.releaseAll()
        dragPointer = -1
        dragElement = null
    }

    private fun performControlHapticFeedback() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(params: LayoutParams): LayoutParams =
        LayoutParams(params)

    override fun checkLayoutParams(params: LayoutParams): Boolean = true
}
