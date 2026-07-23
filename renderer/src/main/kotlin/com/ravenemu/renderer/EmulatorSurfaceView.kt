package com.ravenemu.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.ravenemu.emulation.api.display.DisplayAdjustments
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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

    /**
     * Profil d'écran monochrome : quatre couleurs ARGB appliquées aux niveaux
     * `0..3` produits par le moteur (colors[0] = niveau 0 le plus clair). Si
     * `null`, le framebuffer est traité comme des couleurs ARGB directes.
     * Modifiable à chaud : le changement est visible dès la trame suivante,
     * sans toucher à l'émulation.
     */
    @Volatile
    var displayColors: IntArray? = null
        set(value) {
            field = value?.copyOf()
        }

    /**
     * Réglages d'affichage avancés (luminosité, contraste, correction LCD)
     * appliqués en post-traitement de la sortie ARGB, sans toucher à
     * l'émulation. Identité par défaut (aucun effet). Modifiable à chaud.
     */
    @Volatile
    var displayAdjustments: DisplayAdjustments = DisplayAdjustments()

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
    // rendu (consommateur), protégé par [frameLock].
    private val frameLock = ReentrantLock()
    private val frameReady = frameLock.newCondition()
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
        frameLock.withLock {
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
        frameLock.withLock {
            val target = latestFrame ?: return
            if (frame.size < target.size) return
            System.arraycopy(frame, 0, target, 0, target.size)
            frameDirty = true
            frameReady.signalAll()
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
            frameLock.withLock { frameReady.signalAll() }
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
        private var argbScratch = IntArray(0)
        private val palette = IntArray(4)

        /**
         * Convertit le framebuffer en couleurs ARGB prêtes à l'affichage puis y
         * applique les réglages d'affichage avancés.
         *
         * - Écran monochrome ([displayColors] non nul) : les niveaux `0..3` sont
         *   colorisés par le profil, dont les quatre couleurs reçoivent la
         *   luminosité/contraste (pas la correction LCD, qui viserait à corriger
         *   des couleurs brutes et non un profil déjà calibré).
         * - Sortie couleur ([displayColors] nul, Game Boy Color) : le
         *   framebuffer est déjà en ARGB ; chaque pixel reçoit tous les réglages
         *   actifs. Sans réglage, le tampon source est retourné tel quel.
         */
        private fun colorize(source: IntArray, pixelCount: Int): IntArray {
            val adjustments = displayAdjustments
            val colors = displayColors
            if (colors != null) {
                for (level in 0..3) palette[level] = adjustments.applyTone(colors[level])
                if (argbScratch.size < pixelCount) argbScratch = IntArray(pixelCount)
                val out = argbScratch
                for (i in 0 until pixelCount) {
                    val level = source[i]
                    out[i] = if (level in 0..3) palette[level] else palette[0]
                }
                return out
            }
            if (adjustments.isIdentity) return source
            if (argbScratch.size < pixelCount) argbScratch = IntArray(pixelCount)
            val out = argbScratch
            for (i in 0 until pixelCount) out[i] = adjustments.apply(source[i])
            return out
        }

        override fun run() {
            while (running) {
                var frameToDraw: Bitmap? = null
                frameLock.withLock {
                    while (running && !frameDirty) {
                        try {
                            frameReady.await(250, TimeUnit.MILLISECONDS)
                        } catch (_: InterruptedException) {
                            running = false
                        }
                    }
                    val source = latestFrame
                    if (running && source != null) {
                        frameDirty = false
                        val width = frameWidth
                        val height = frameHeight
                        val bmp = ensureBitmap(width, height)
                        val pixels = colorize(source, width * height)
                        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
                        frameToDraw = bmp
                    }
                }
                frameToDraw?.let(::drawToSurface)
            }
        }

        private fun ensureBitmap(width: Int, height: Int): Bitmap {
            val current = renderBitmap
            if (current == null || current.width != width || current.height != height) {
                return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    .also { renderBitmap = it }
            }
            return current
        }

        private fun drawToSurface(bmp: Bitmap) {
            if (!surfaceAvailable) return
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
