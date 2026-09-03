package com.ravenemu.input

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import com.ravenemu.deltaskin.DeltaSkinButtonChanges
import com.ravenemu.deltaskin.DeltaSkinConsole
import com.ravenemu.deltaskin.DeltaSkinErrorCode
import com.ravenemu.deltaskin.DeltaSkinException
import com.ravenemu.deltaskin.DeltaSkinInputMapper
import com.ravenemu.deltaskin.DeltaSkinInputState
import com.ravenemu.deltaskin.DeltaSkinInsets
import com.ravenemu.deltaskin.DeltaSkinLayout
import com.ravenemu.deltaskin.DeltaSkinLayoutCalculator
import com.ravenemu.deltaskin.DeltaSkinMappedInput
import com.ravenemu.deltaskin.DeltaSkinRect
import com.ravenemu.deltaskin.DeltaSkinRepresentation
import com.ravenemu.deltaskin.DeltaSkinRepresentationKind
import com.ravenemu.deltaskin.DeltaSkinRepresentationPreference
import com.ravenemu.deltaskin.DeltaSkinRepresentationSelector
import com.ravenemu.deltaskin.DeltaSkinScreenPlacement
import com.ravenemu.deltaskin.DeltaSkinSize
import com.ravenemu.deltaskin.DeltaSkinTouchPoint
import com.ravenemu.deltaskin.DeltaSkinVisualTarget
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

data class DeltaSkinControllerAsset(
    val representation: DeltaSkinRepresentation,
    val pdfFile: File,
)

data class DeltaSkinControllerConfiguration(
    val skinSha256: String,
    val console: DeltaSkinConsole,
    val assets: Map<DeltaSkinRepresentationKind, DeltaSkinControllerAsset>,
    val preference: DeltaSkinRepresentationPreference,
    val nativeScreenSize: DeltaSkinSize,
    val hapticFeedback: Boolean,
    val visualFeedback: Boolean,
)

/**
 * Affiche le PDF tel quel et superpose seulement le retour de pression. Cette
 * vue ne dessine jamais de bouton RavenEmu.
 */
class DeltaSkinControllerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    interface Listener {
        fun onButtonChanges(changes: DeltaSkinButtonChanges)
        fun onMenu()
        /**
         * Le skin est prêt : le jeu occupe [gameArea].
         *
         * [screens] dit où chaque écran de la console se pose quand le skin le
         * décide lui-même, et reste vide sinon. Une Nintendo DS en a deux, que
         * le skin sépare d'une charnière dessinée : les poser d'un bloc dans
         * [gameArea] étalerait l'image par-dessus.
         */
        fun onSkinReady(gameArea: Rect, screens: List<DeltaSkinScreenPlacement>)
        fun onSkinLayoutChanged(gameArea: Rect, screens: List<DeltaSkinScreenPlacement>)
        fun onSkinError(code: DeltaSkinErrorCode)

        /**
         * Le stylet est posé sur la dalle, à cet endroit de la zone tactile.
         *
         * Rappelé à chaque déplacement tant que le doigt y reste : une console
         * n'a pas de notion de glissement, elle a un point de contact qui
         * change.
         */
        fun onTouchScreen(point: DeltaSkinTouchPoint)

        /** Le stylet est levé. */
        fun onTouchScreenReleased()
    }

    var listener: Listener? = null

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var renderJob: Job? = null
    private var configuration: DeltaSkinControllerConfiguration? = null
    private var safeInsets = DeltaSkinInsets()
    private var selectedKind: DeltaSkinRepresentationKind? = null
    private var selectedAsset: DeltaSkinControllerAsset? = null
    private var currentLayout: DeltaSkinLayout? = null
    private var mapper: DeltaSkinInputMapper? = null
    private var panelBitmap: Bitmap? = null
    private var generation = 0L
    private var ready = false
    private var activeVisuals: Set<DeltaSkinVisualTarget> = emptySet()
    private var fadingVisuals: Set<DeltaSkinVisualTarget> = emptySet()
    private var fadeStartedAtMillis = 0L
    private val pointerInputs = mutableMapOf<Int, DeltaSkinMappedInput>()

    /**
     * Le doigt qui tient la dalle, s'il y en a un.
     *
     * Une console n'a qu'un stylet : le premier doigt posé sur la zone la garde
     * jusqu'à ce qu'il la quitte, et un second doigt posé à côté ne la lui
     * prend pas. Sans cette règle, deux doigts feraient sauter le point de
     * contact de l'un à l'autre à chaque déplacement.
     */
    private var touchPointerId: Int? = null

    private val inputState = DeltaSkinInputState(
        onButtonsChanged = { changes -> listener?.onButtonChanges(changes) },
        onMenu = { listener?.onMenu() },
        onVisualsChanged = { visuals ->
            if (configuration?.visualFeedback == true) {
                val removed = activeVisuals - visuals
                if (removed.isNotEmpty()) {
                    fadingVisuals = removed
                    fadeStartedAtMillis = SystemClock.uptimeMillis()
                    postInvalidateOnAnimation()
                }
                activeVisuals = visuals
                fadingVisuals = fadingVisuals - visuals
            } else {
                activeVisuals = emptySet()
                fadingVisuals = emptySet()
            }
            invalidate()
        },
    )

    private val bitmapDestination = RectF()
    private val feedbackFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val feedbackStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }

    init {
        visibility = GONE
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun setConfiguration(value: DeltaSkinControllerConfiguration?) {
        releaseAll()
        generation++
        renderJob?.cancel()
        configuration = value
        panelBitmap = null
        selectedKind = null
        selectedAsset = null
        currentLayout = null
        mapper = null
        ready = false
        visibility = if (value == null) GONE else INVISIBLE
        if (value != null) updateLayoutAndRender()
    }

    fun setSafeInsets(insets: DeltaSkinInsets) {
        if (safeInsets == insets) return
        safeInsets = insets
        if (configuration != null) updateLayoutAndRender()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (configuration != null) updateLayoutAndRender()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val layout = currentLayout ?: return
        val bitmap = panelBitmap ?: return
        bitmapDestination.set(
            layout.panel.left.toFloat(),
            layout.panel.top.toFloat(),
            layout.panel.right.toFloat(),
            layout.panel.bottom.toFloat(),
        )
        canvas.drawBitmap(bitmap, null, bitmapDestination, null)
        activeVisuals.forEach { visual -> drawFeedback(canvas, visual, 1f) }
        if (fadingVisuals.isNotEmpty()) {
            val elapsed = SystemClock.uptimeMillis() - fadeStartedAtMillis
            val alpha = (1f - elapsed.toFloat() / FEEDBACK_FADE_MILLIS).coerceIn(0f, 1f)
            if (alpha > 0f) {
                fadingVisuals.forEach { visual -> drawFeedback(canvas, visual, alpha) }
                postInvalidateOnAnimation()
            } else {
                fadingVisuals = emptySet()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val hitMapper = mapper ?: return false
        if (!ready) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                updatePointer(
                    event.getPointerId(index),
                    event.getX(index).toDouble(),
                    event.getY(index).toDouble(),
                    hitMapper,
                )
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    updatePointer(
                        event.getPointerId(index),
                        event.getX(index).toDouble(),
                        event.getY(index).toDouble(),
                        hitMapper,
                    )
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP ->
                releasePointer(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        scope.cancel()
        super.onDetachedFromWindow()
    }

    private fun updatePointer(
        pointerId: Int,
        x: Double,
        y: Double,
        hitMapper: DeltaSkinInputMapper,
    ) {
        val input = hitMapper.inputAt(x, y)
        val previous = pointerInputs[pointerId] ?: DeltaSkinMappedInput()
        val newlyActive =
            (input.buttons - previous.buttons).isNotEmpty() ||
                (input.menu && !previous.menu)
        if (newlyActive && configuration?.hapticFeedback == true) {
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
        if (input.buttons.isEmpty() && !input.menu && input.visuals.isEmpty()) {
            pointerInputs.remove(pointerId)
        } else {
            pointerInputs[pointerId] = input
        }
        inputState.updatePointer(pointerId, input)
        updateTouchScreen(pointerId, input.touch)
    }

    /** Confie la dalle à ce doigt, la lui retire, ou laisse l'autre la tenir. */
    private fun updateTouchScreen(pointerId: Int, point: DeltaSkinTouchPoint?) {
        if (point == null) {
            if (touchPointerId == pointerId) releaseTouchScreen()
            return
        }
        if (touchPointerId != null && touchPointerId != pointerId) return
        touchPointerId = pointerId
        listener?.onTouchScreen(point)
    }

    private fun releaseTouchScreen() {
        if (touchPointerId == null) return
        touchPointerId = null
        listener?.onTouchScreenReleased()
    }

    private fun releasePointer(pointerId: Int) {
        pointerInputs.remove(pointerId)
        inputState.releasePointer(pointerId)
        if (touchPointerId == pointerId) releaseTouchScreen()
    }

    private fun releaseAll() {
        pointerInputs.clear()
        inputState.releaseAll()
        releaseTouchScreen()
        activeVisuals = emptySet()
        fadingVisuals = emptySet()
        invalidate()
    }

    private fun updateLayoutAndRender() {
        val config = configuration ?: return
        if (width <= 0 || height <= 0) return
        val availableWidth = width - safeInsets.left - safeInsets.right
        val availableHeight = height - safeInsets.top - safeInsets.bottom
        val kind = DeltaSkinRepresentationSelector.select(
            availableKinds = config.assets.keys,
            preference = config.preference,
            availableWidth = availableWidth,
            availableHeight = availableHeight,
        ) ?: return fail(DeltaSkinErrorCode.PORTRAIT_REPRESENTATION_MISSING)
        val asset = config.assets[kind]
            ?: return fail(DeltaSkinErrorCode.RESIZABLE_ASSET_MISSING)
        val layout = DeltaSkinLayoutCalculator.calculate(
            containerWidth = width.toDouble(),
            containerHeight = height.toDouble(),
            insets = safeInsets,
            mappingSize = asset.representation.mappingSize,
            nativeScreenSize = config.nativeScreenSize,
            declaredScreens = asset.representation.declaredScreens,
        ) ?: return fail(DeltaSkinErrorCode.INVALID_MAPPING_SIZE)

        val previousLayout = currentLayout
        currentLayout = layout
        mapper = DeltaSkinInputMapper(config.console, asset.representation, layout.panel)
        if (ready && previousLayout != layout) {
            listener?.onSkinLayoutChanged(layout.gameArea.toAndroidRect(), layout.screens)
        }

        val targetWidth = layout.panel.width.toInt().coerceAtLeast(1)
        val targetHeight = layout.panel.height.toInt().coerceAtLeast(1)
        if (
            selectedKind == kind &&
            selectedAsset == asset &&
            panelBitmap?.width == targetWidth &&
            panelBitmap?.height == targetHeight
        ) {
            if (!ready) becomeReady()
            invalidate()
            return
        }

        releaseAll()
        selectedKind = kind
        selectedAsset = asset
        panelBitmap = null
        ready = false
        visibility = INVISIBLE
        val token = ++generation
        renderJob?.cancel()
        renderJob = scope.launch {
            try {
                val rendered = DeltaSkinPdfRenderer.render(
                    file = asset.pdfFile,
                    skinSha256 = config.skinSha256,
                    kind = kind,
                    width = targetWidth,
                    height = targetHeight,
                    densityDpi = resources.displayMetrics.densityDpi,
                )
                if (token != generation || configuration !== config) return@launch
                panelBitmap = rendered
                becomeReady()
                invalidate()
            } catch (error: DeltaSkinException) {
                if (token == generation) fail(error.code)
            } catch (_: Exception) {
                if (token == generation) fail(DeltaSkinErrorCode.PDF_INVALID)
            }
        }
    }

    private fun becomeReady() {
        val layout = currentLayout ?: return
        ready = true
        visibility = VISIBLE
        listener?.onSkinReady(layout.gameArea.toAndroidRect(), layout.screens)
    }

    private fun fail(code: DeltaSkinErrorCode) {
        releaseAll()
        ready = false
        visibility = GONE
        listener?.onSkinError(code)
    }

    private fun DeltaSkinRect.toRectF(): RectF = RectF(
        left.toFloat(),
        top.toFloat(),
        right.toFloat(),
        bottom.toFloat(),
    )

    private fun DeltaSkinRect.toAndroidRect(): Rect = Rect(
        left.toInt(),
        top.toInt(),
        right.toInt(),
        bottom.toInt(),
    )

    private fun drawFeedback(
        canvas: Canvas,
        visual: DeltaSkinVisualTarget,
        alpha: Float,
    ) {
        feedbackFill.alpha = (48 * alpha).toInt()
        feedbackStroke.alpha = (90 * alpha).toInt()
        val rect = visual.rect.toRectF()
        canvas.drawRoundRect(rect, 4f, 4f, feedbackFill)
        canvas.drawRoundRect(rect, 4f, 4f, feedbackStroke)
    }

    private companion object {
        const val FEEDBACK_FADE_MILLIS = 90L
    }
}
