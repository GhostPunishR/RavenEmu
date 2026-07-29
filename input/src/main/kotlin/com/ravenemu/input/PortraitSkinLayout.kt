package com.ravenemu.input

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView

/**
 * Composition portrait RavenEmu à trois couches :
 *
 * 1. coque vectorielle statique ;
 * 2. surface d'émulation inscrite dans l'ouverture native ;
 * 3. contrôles tactiles vectoriels et animés.
 *
 * La classe ne dépend pas du module renderer : l'application lui fournit la
 * vue de surface, ce qui évite un couplage inverse entre les modules.
 */
class PortraitSkinLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val shellView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_XY
        isClickable = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private var emulatorSurface: View? = null
    private var controlsView: TouchControlsView? = null
    private var layoutResult = SkinLayoutResult(
        skinRect = SkinPixelRect(0, 0, 0, 0),
        surfaceRect = SkinPixelRect(0, 0, 0, 0),
    )

    var skin: TouchSkin = TouchSkin.CLASSIC
        set(value) {
            if (field == value) return
            field = value
            controlsView?.skin = value
            updateShell()
            requestLayout()
        }

    /** Décalage réservé à l'encoche ; il ne modifie pas la géométrie interne. */
    var topInsetPx: Int = 0
        set(value) {
            val clamped = value.coerceAtLeast(0)
            if (field == clamped) return
            field = clamped
            requestLayout()
        }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = false
        clipToPadding = false
        addView(shellView, 0, generateDefaultLayoutParams())
        updateShell()
    }

    /**
     * Associe les deux couches dynamiques déclarées dans le layout de
     * l'application. Cette opération ne réordonne jamais la surface au-dessus
     * des contrôles.
     */
    fun bind(emulatorSurface: View, controls: TouchControlsView) {
        require(emulatorSurface.parent === this) {
            "EmulatorSurfaceView doit être un enfant direct de PortraitSkinLayout"
        }
        require(controls.parent === this) {
            "TouchControlsView doit être un enfant direct de PortraitSkinLayout"
        }
        this.emulatorSurface = emulatorSurface
        controlsView = controls
        controls.skin = skin
        shellView.bringToFront()
        emulatorSurface.bringToFront()
        controls.bringToFront()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val containerWidth = MeasureSpec.getSize(widthMeasureSpec)
        val containerHeight = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(containerWidth, containerHeight)

        val geometry = RavenSkinGeometries.forSkin(skin)
        layoutResult = if (geometry == null) {
            val full = SkinPixelRect(0, 0, containerWidth, containerHeight)
            SkinLayoutResult(full, full)
        } else {
            SkinLayoutCalculator.calculate(
                containerWidth = containerWidth,
                containerHeight = containerHeight,
                topInset = topInsetPx,
                geometry = geometry,
            )
        }

        val skinRect = layoutResult.skinRect
        shellView.measure(
            MeasureSpec.makeMeasureSpec(skinRect.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(skinRect.height, MeasureSpec.EXACTLY),
        )
        controlsView?.measure(
            MeasureSpec.makeMeasureSpec(skinRect.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(skinRect.height, MeasureSpec.EXACTLY),
        )
        val surfaceRect = layoutResult.surfaceRect
        emulatorSurface?.measure(
            MeasureSpec.makeMeasureSpec(surfaceRect.width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(surfaceRect.height, MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val skinRect = layoutResult.skinRect
        shellView.layout(skinRect.left, skinRect.top, skinRect.right, skinRect.bottom)
        controlsView?.layout(skinRect.left, skinRect.top, skinRect.right, skinRect.bottom)
        val surfaceRect = layoutResult.surfaceRect
        emulatorSurface?.layout(
            surfaceRect.left,
            surfaceRect.top,
            surfaceRect.right,
            surfaceRect.bottom,
        )
    }

    private fun updateShell() {
        val resource = when (skin) {
            TouchSkin.CLASSIC -> null
            TouchSkin.RAVEN_GB -> R.drawable.raven_skin_gb_background
            TouchSkin.RAVEN_GBA -> R.drawable.raven_skin_gba_background
        }
        if (resource == null) {
            shellView.setImageDrawable(null)
            shellView.visibility = View.GONE
        } else {
            shellView.setImageResource(resource)
            shellView.visibility = View.VISIBLE
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams =
        LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)

    override fun generateLayoutParams(attrs: AttributeSet): LayoutParams =
        LayoutParams(context, attrs)

    override fun generateLayoutParams(params: LayoutParams): LayoutParams =
        LayoutParams(params)

    override fun checkLayoutParams(params: LayoutParams): Boolean = true
}
