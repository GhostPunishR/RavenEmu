package com.ravenemu.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.ravenemu.emulation.api.EmulatorButton

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

    /** Rendu sélectionné indépendamment de la disposition sérialisée. */
    var skin: TouchSkin = TouchSkin.RAVEN_GB
        set(value) {
            if (field == value) return
            field = value
            animationState.reset()
            configureRenderer()
            invalidate()
        }

    /** Marge haute de l'écran de jeu (encoche/caméra), pour finir le skin dessous. */
    var screenTopInsetPx: Int = 0
        set(value) {
            val clamped = value.coerceAtLeast(0)
            if (field == clamped) return
            field = clamped
            configureRenderer()
            invalidate()
        }

    /**
     * Masque le panneau opaque lorsque l'image de jeu est volontairement
     * étirée plein écran ; les boutons restent rendus individuellement.
     */
    var skinPanelVisible: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

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

    private val classicRenderer = ClassicTouchSkinRenderer()
    private val ravenGbRenderer = RavenGbSkinRenderer()
    private val ravenGbaRenderer = RavenGbaSkinRenderer()
    private val animationState = ControlAnimationState()
    private val inputState = TouchInputState(
        onButtonChanged = { button, pressed ->
            // Envoi logique synchrone : l'animation n'est mise à jour qu'après.
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

    /** Pointeur en cours de glissement en mode édition. */
    private var dragPointer = -1
    private var dragElement: ControlId? = null

    private fun elementRadius(element: ControlElement): Float =
        ControlGeometry.radiusPx(element, resources.displayMetrics.density)

    private fun elementCenterX(element: ControlElement): Float =
        ControlGeometry.centerX(element, width)

    private fun elementCenterY(element: ControlElement): Float =
        ControlGeometry.centerY(element, height)

    // ---- Dessin ----

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        configureRenderer()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val needsNextFrame = animationState.advance(System.nanoTime())
        activeRenderer().draw(
            canvas,
            layoutSpec,
            editMode,
            selectedElement,
            animationState,
            skinPanelVisible,
        )
        if (needsNextFrame) postInvalidateOnAnimation()
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
                val hitMask = hitMaskAt(x, y)
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

    private fun releaseAll() {
        inputState.releaseAll()
        dragPointer = -1
        dragElement = null
    }

    private fun hitMaskAt(x: Float, y: Float): Int = ControlHitTester.hitMask(
        layout = layoutSpec,
        x = x,
        y = y,
        width = width,
        height = height,
        density = resources.displayMetrics.density,
    )

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

    override fun onDetachedFromWindow() {
        releaseAll()
        super.onDetachedFromWindow()
    }

    private fun activeRenderer(): TouchSkinRenderer = when (skin) {
        TouchSkin.CLASSIC -> classicRenderer
        TouchSkin.RAVEN_GB -> ravenGbRenderer
        TouchSkin.RAVEN_GBA -> ravenGbaRenderer
    }

    private fun configureRenderer() {
        activeRenderer().onViewportChanged(
            width = width,
            height = height,
            density = resources.displayMetrics.density,
            screenTopInsetPx = screenTopInsetPx,
        )
    }

    private fun performControlHapticFeedback() {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }
}
