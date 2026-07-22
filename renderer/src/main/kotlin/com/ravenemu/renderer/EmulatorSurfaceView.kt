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
 * Le rendu est **découplé** du thread d'émulation : [presentFrame] se contente
 * de recopier les pixels dans un tampon partagé (opération brève, sans verrou
 * de canvas), et un thread de rendu dédié dessine sur la surface à sa propre
 * cadence, en se calant sur le vsync côté écran. Ainsi le thread d'émulation
 * n'est jamais bloqué par l'affichage : sa cadence reste pilotée par l'audio,
 * ce qui évite les sous-alimentations audio (craquements) lorsqu'une image
 * tarde à être présentée.
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

    @Volatile
    private var frameWidth = 0

    @Volatile
    private var frameHeight = 0

    private val destRect = Rect()
    private val paint = Paint().apply {
        isFilterBitmap = false // nearest-neighbor
        isAntiAlias = false
    }

    // Tampon partagé entre le thread d'émulation (producteur) et le thread de
    // rendu (consommateur). L'accès est protégé par [frameLock].
    private val frameLock = Any()
    private var latestFrame: IntArray? = null
    private var frameDirty = false

    @Volatile
    private var surfaceAvailable = false

    private var renderThread: RenderThread? = null

    init {
        holder.addCallback(this)
    }

    /** Prépare la vue pour un framebuffer de [width] × [height] pixels. */
    fun configure(width: Int, height: Int) {
        synchronized(frameLock) {
            frameWidth = width
            frameHeight = height
            latestFrame = IntArray(width * height)
            frameDirty = false
        }
    }

    /**
     * Recopie une trame ARGB dans le tampon partagé et réveille le thread de
     * rendu. Retour immédiat : n'attend jamais le canvas ni le vsync.
     */
    fun presentFrame(frame: IntArray) {
        synchronized(frameLock) {
            val target = latestFrame ?: return
            if (frame.size < target.size) return
            System.arraycopy(frame, 0, target, 0, target.size)
            frameDirty = true
            frameLock.notifyAll()
        }
    }

    private fun computeDestination(canvasWidth: Int, canvasHeight: Int) {
        if (frameWidth == 0 || frameHeight == 0 || !keepAspectRatio) {
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
        renderThread = RenderThread().also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceAvailable = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAvailable = false
        renderThread?.let { thread ->
            thread.running = false
            synchronized(frameLock) { frameLock.notifyAll() }
            thread.join(500)
        }
        renderThread = null
    }

    /**
     * Thread de rendu : attend une trame fraîche, la dessine dans son propre
     * bitmap, puis la présente. Le `unlockCanvasAndPost` se cale sur le vsync
     * ici, sans impacter le thread d'émulation.
     */
    private inner class RenderThread : Thread("RavenEmu-Render") {
        @Volatile
        var running = true

        private var renderBitmap: Bitmap? = null

        override fun run() {
            while (running) {
                val width: Int
                val height: Int
                synchronized(frameLock) {
                    while (running && !frameDirty) {
                        try {
                            frameLock.wait(250)
                        } catch (_: InterruptedException) {
                            return
                        }
                    }
                    if (!running) return
                    frameDirty = false
                    width = frameWidth
                    height = frameHeight
                    val source = latestFrame ?: continue
                    val bmp = renderBitmap.let {
                        if (it == null || it.width != width || it.height != height) {
                            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                                .also { created -> renderBitmap = created }
                        } else {
                            it
                        }
                    }
                    bmp.setPixels(source, 0, width, 0, 0, width, height)
                }
                drawToSurface()
            }
        }

        private fun drawToSurface() {
            if (!surfaceAvailable) return
            val bmp = renderBitmap ?: return
            val canvas = try {
                holder.lockCanvas()
            } catch (_: Exception) {
                null
            } ?: return
            try {
                canvas.drawColor(Color.BLACK)
                computeDestination(canvas.width, canvas.height)
                canvas.drawBitmap(bmp, null, destRect, paint)
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {
                }
            }
        }
    }
}
