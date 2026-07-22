package com.ravenemu.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * Affichage du framebuffer produit par un moteur d'émulation.
 *
 * [presentFrame] est appelée depuis le thread d'émulation avec un tableau
 * ARGB ; la vue copie les pixels dans un [Bitmap] réutilisé et le dessine sur
 * la surface avec l'échelle demandée. Aucune allocation par trame.
 *
 * Modes d'échelle :
 * - ratio natif conservé (défaut), filtrage nearest-neighbor ;
 * - mise à l'échelle entière (pixels parfaits) ;
 * - étirement plein écran si l'utilisateur l'active explicitement.
 */
class EmulatorSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    /** Conserver le ratio natif de la console (sinon étirement). */
    @Volatile
    var keepAspectRatio: Boolean = true

    /** N'utiliser que des facteurs d'échelle entiers. */
    @Volatile
    var integerScaling: Boolean = false

    /**
     * Ancrer l'image en haut de la surface plutôt que de la centrer
     * verticalement. Utilisé en portrait pour libérer le bas de l'écran au
     * profit des commandes tactiles.
     */
    @Volatile
    var topAligned: Boolean = false

    /** Marge haute en pixels (encoche / zone caméra) quand [topAligned]. */
    @Volatile
    var topInsetPx: Int = 0

    private var frameWidth = 0
    private var frameHeight = 0
    private var bitmap: Bitmap? = null
    private val destRect = Rect()
    private val paint = Paint().apply {
        isFilterBitmap = false // nearest-neighbor
        isAntiAlias = false
    }

    @Volatile
    private var surfaceAvailable = false

    init {
        holder.addCallback(this)
    }

    /** Prépare la vue pour un framebuffer de [width] × [height] pixels. */
    fun configure(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Copie et affiche une trame ARGB. Sans effet si la surface n'est pas
     * prête, ce qui rend l'appel sûr pendant les transitions de cycle de vie.
     */
    fun presentFrame(frame: IntArray) {
        val bmp = bitmap ?: return
        if (!surfaceAvailable) return
        bmp.setPixels(frame, 0, frameWidth, 0, 0, frameWidth, frameHeight)
        val canvas = holder.lockCanvas() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            computeDestination(canvas.width, canvas.height)
            canvas.drawBitmap(bmp, null, destRect, paint)
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }

    private fun computeDestination(canvasWidth: Int, canvasHeight: Int) {
        if (frameWidth == 0 || frameHeight == 0) {
            destRect.set(0, 0, canvasWidth, canvasHeight)
            return
        }
        if (!keepAspectRatio) {
            destRect.set(0, 0, canvasWidth, canvasHeight)
            return
        }
        val scaleX = canvasWidth.toFloat() / frameWidth
        val scaleY = canvasHeight.toFloat() / frameHeight
        var scale = minOf(scaleX, scaleY)
        if (integerScaling) {
            scale = scale.toInt().coerceAtLeast(1).toFloat()
        }
        val width = (frameWidth * scale).toInt()
        val height = (frameHeight * scale).toInt()
        val left = (canvasWidth - width) / 2
        val top = if (topAligned && height + topInsetPx <= canvasHeight) {
            topInsetPx
        } else {
            (canvasHeight - height) / 2
        }
        destRect.set(left, top, left + width, top + height)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAvailable = true
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceAvailable = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAvailable = false
    }
}
