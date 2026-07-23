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
import com.ravenemu.emulation.api.EmulatorButton
import kotlin.math.abs

/**
 * Superposition tactile dessinée localement (croix, A/B, Start/Select, menu),
 * multi-touch, avec zone tactile élargie et diagonales sur la croix.
 *
 * Deux modes :
 * - jeu : les pressions sont transmises via [listener] ;
 * - édition ([editMode]) : glisser pour déplacer un élément, les
 *   changements sont publiés via [onLayoutChanged] en coordonnées relatives.
 */
class TouchControlsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Réception des pressions de boutons logiques. */
    interface Listener {
        fun onButton(button: EmulatorButton, pressed: Boolean)
        fun onMenu()
    }

    var listener: Listener? = null

    /** Publication de la disposition modifiée en mode édition. */
    var onLayoutChanged: ((ControlLayout) -> Unit)? = null

    var layoutSpec: ControlLayout = ControlLayout.defaultPortrait()
        set(value) {
            field = value
            invalidate()
        }

    var editMode: Boolean = false
        set(value) {
            field = value
            releaseAll()
            invalidate()
        }

    /** Élément sélectionné dans l'éditeur (pour taille/opacité externes). */
    var selectedElement: ControlId? = null
        private set

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val bounds = RectF()

    /** Boutons actuellement enfoncés, par identifiant de pointeur. */
    private val pointerButtons = mutableMapOf<Int, MutableSet<EmulatorButton>>()

    /** Pointeur en cours de glissement en mode édition. */
    private var dragPointer = -1
    private var dragElement: ControlId? = null

    private fun baseSizePx(id: ControlId): Float {
        val density = resources.displayMetrics.density
        return when (id) {
            ControlId.DPAD -> 76f * density
            ControlId.BUTTON_A, ControlId.BUTTON_B -> 34f * density
            ControlId.START, ControlId.SELECT -> 26f * density
            ControlId.BUTTON_L, ControlId.BUTTON_R -> 28f * density
            ControlId.MENU -> 22f * density
        }
    }

    private fun elementRadius(element: ControlElement): Float =
        baseSizePx(element.id) * element.scale

    private fun elementCenterX(element: ControlElement): Float = element.centerX * width

    private fun elementCenterY(element: ControlElement): Float = element.centerY * height

    // ---- Dessin ----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (element in layoutSpec.elements) {
            if (!element.visible && !editMode) continue
            val alpha = ((if (editMode) 0.85f else element.opacity) * 255).toInt()
            val cx = elementCenterX(element)
            val cy = elementCenterY(element)
            val radius = elementRadius(element)
            fillPaint.color = Color.argb(alpha / 3, 255, 255, 255)
            strokePaint.color = Color.argb(alpha, 255, 255, 255)
            textPaint.color = Color.argb(alpha, 255, 255, 255)
            when (element.id) {
                ControlId.DPAD -> drawDpad(canvas, cx, cy, radius)
                ControlId.BUTTON_A -> drawRound(canvas, cx, cy, radius, "A")
                ControlId.BUTTON_B -> drawRound(canvas, cx, cy, radius, "B")
                ControlId.START -> drawPill(canvas, cx, cy, radius, "START")
                ControlId.SELECT -> drawPill(canvas, cx, cy, radius, "SELECT")
                ControlId.BUTTON_L -> drawPill(canvas, cx, cy, radius, "L")
                ControlId.BUTTON_R -> drawPill(canvas, cx, cy, radius, "R")
                ControlId.MENU -> drawRound(canvas, cx, cy, radius, "≡")
            }
            if (editMode && selectedElement == element.id) {
                strokePaint.color = Color.argb(255, 120, 200, 255)
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

    private fun drawRound(canvas: Canvas, cx: Float, cy: Float, radius: Float, label: String) {
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, strokePaint)
        textPaint.textSize = radius * 0.9f
        canvas.drawText(label, cx, cy + radius * 0.32f, textPaint)
    }

    private fun drawPill(canvas: Canvas, cx: Float, cy: Float, radius: Float, label: String) {
        bounds.set(cx - radius * 1.6f, cy - radius * 0.6f, cx + radius * 1.6f, cy + radius * 0.6f)
        canvas.drawRoundRect(bounds, radius * 0.6f, radius * 0.6f, fillPaint)
        canvas.drawRoundRect(bounds, radius * 0.6f, radius * 0.6f, strokePaint)
        textPaint.textSize = radius * 0.55f
        canvas.drawText(label, cx, cy + radius * 0.2f, textPaint)
    }

    // ---- Tactile ----

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editMode) return handleEditTouch(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                updatePointer(event.getPointerId(index), x, y)
                if (menuAt(x, y)) listener?.onMenu()
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    updatePointer(event.getPointerId(index), event.getX(index), event.getY(index))
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                releasePointer(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    /** Réévalue les boutons couverts par un pointeur (glissement compris). */
    private fun updatePointer(pointerId: Int, x: Float, y: Float) {
        val newButtons = buttonsAt(x, y)
        val previous = pointerButtons[pointerId] ?: mutableSetOf()
        if (newButtons == previous) return
        for (button in previous - newButtons) listener?.onButton(button, false)
        var anyNew = false
        for (button in newButtons - previous) {
            listener?.onButton(button, true)
            anyNew = true
        }
        if (anyNew && layoutSpec.hapticFeedback) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (newButtons.isEmpty()) {
            pointerButtons.remove(pointerId)
        } else {
            pointerButtons[pointerId] = newButtons
        }
    }

    private fun releasePointer(pointerId: Int) {
        pointerButtons.remove(pointerId)?.forEach { listener?.onButton(it, false) }
    }

    private fun releaseAll() {
        for ((_, buttons) in pointerButtons) {
            buttons.forEach { listener?.onButton(it, false) }
        }
        pointerButtons.clear()
        dragPointer = -1
        dragElement = null
    }

    /** Boutons logiques couverts par le point ([x], [y]). */
    private fun buttonsAt(x: Float, y: Float): MutableSet<EmulatorButton> {
        val result = mutableSetOf<EmulatorButton>()
        for (element in layoutSpec.elements) {
            if (!element.visible) continue
            val cx = elementCenterX(element)
            val cy = elementCenterY(element)
            val reach = elementRadius(element) * element.touchScale
            val dx = x - cx
            val dy = y - cy
            when (element.id) {
                ControlId.DPAD -> {
                    if (abs(dx) > reach || abs(dy) > reach) continue
                    // Zone morte centrale, diagonales autorisées.
                    val dead = reach * 0.18f
                    if (dx < -dead) result += EmulatorButton.LEFT
                    if (dx > dead) result += EmulatorButton.RIGHT
                    if (dy < -dead) result += EmulatorButton.UP
                    if (dy > dead) result += EmulatorButton.DOWN
                }
                ControlId.BUTTON_A ->
                    if (dx * dx + dy * dy <= reach * reach) result += EmulatorButton.A
                ControlId.BUTTON_B ->
                    if (dx * dx + dy * dy <= reach * reach) result += EmulatorButton.B
                ControlId.START ->
                    if (abs(dx) <= reach * 1.6f && abs(dy) <= reach) {
                        result += EmulatorButton.START
                    }
                ControlId.SELECT ->
                    if (abs(dx) <= reach * 1.6f && abs(dy) <= reach) {
                        result += EmulatorButton.SELECT
                    }
                ControlId.BUTTON_L ->
                    if (abs(dx) <= reach * 1.6f && abs(dy) <= reach) result += EmulatorButton.L
                ControlId.BUTTON_R ->
                    if (abs(dx) <= reach * 1.6f && abs(dy) <= reach) result += EmulatorButton.R
                ControlId.MENU -> Unit // géré séparément
            }
        }
        return result
    }

    private fun menuAt(x: Float, y: Float): Boolean {
        val element = layoutSpec.element(ControlId.MENU) ?: return false
        if (!element.visible) return false
        val dx = x - elementCenterX(element)
        val dy = y - elementCenterY(element)
        val reach = elementRadius(element) * element.touchScale
        return dx * dx + dy * dy <= reach * reach
    }

    // ---- Édition ----

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
                val element = dragElement?.let { layoutSpec.element(it) } ?: return true
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
        for (element in layoutSpec.elements) {
            val dx = x - elementCenterX(element)
            val dy = y - elementCenterY(element)
            val distance = dx * dx + dy * dy
            val reach = elementRadius(element) * 1.4f
            if (distance <= reach * reach && distance < bestDistance) {
                bestDistance = distance
                best = element.id
            }
        }
        return best
    }

    /** Applique un réglage à l'élément sélectionné (curseurs de l'éditeur). */
    fun adjustSelected(scale: Float? = null, opacity: Float? = null, visible: Boolean? = null) {
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

}
