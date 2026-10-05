package com.ravenemu.renderer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.ravenemu.emulation.api.display.DisplayAdjustments
import com.ravenemu.emulation.api.render.FrameSwapChain

/**
 * Une découpe du tampon de la console, et l'endroit où elle se pose.
 *
 * [source] est en pixels du tampon produit par le moteur ; `null` désigne le
 * tampon entier. [destination] est en pixels de la vue, et l'image y est posée
 * telle quelle, sans ratio conservé ni échelle entière : quand un skin place
 * lui-même les écrans, c'est lui qui décide, et corriger son cadrage le
 * décalerait de l'image qu'il dessine autour.
 */
data class ScreenPlacement(val source: Rect?, val destination: Rect)

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
     * Zone réservée au jeu lorsqu'un panneau de skin externe occupe le bas.
     * `null` conserve strictement le calcul historique de la vue.
     */
    @Volatile
    var contentBounds: Rect? = null
        set(value) {
            field = value?.let(::Rect)
        }

    /**
     * Découpes du tampon et leurs emplacements, quand un skin place lui-même
     * les écrans de la console.
     *
     * Vide par défaut : le tampon est alors dessiné d'un bloc dans
     * [contentBounds]. Une Nintendo DS rend ses deux écrans empilés dans un
     * seul tampon, et un skin les sépare d'une charnière dessinée : sans cette
     * liste, l'image passerait par-dessus.
     */
    @Volatile
    var screenPlacements: List<ScreenPlacement> = emptyList()
        set(value) {
            field = value.map { ScreenPlacement(it.source?.let(::Rect), Rect(it.destination)) }
        }

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

    /**
     * Échange de trames entre le thread d'émulation et le thread de rendu.
     *
     * Le verrou ne sert qu'à l'échange de deux références : colorisation,
     * réglages, remplissage du bitmap et dessin ont lieu **hors verrou**, sur un
     * tampon que plus personne d'autre ne touche. Le thread d'émulation ne peut
     * donc jamais attendre le rendu d'une trame.
     */
    private val swapChain = FrameSwapChain(1)

    @Volatile
    private var surfaceAvailable = false

    private var renderThread: RenderThread? = null

    init {
        holder.addCallback(this)
    }

    /** Prépare la vue pour un framebuffer de [width] × [height] pixels. */
    fun configure(width: Int, height: Int) {
        frameWidth = width
        frameHeight = height
        swapChain.resize(width * height)
    }

    /**
     * Recopie une trame ARGB dans le tampon partagé et réveille le thread de
     * rendu. Retour immédiat : n'attend jamais le canvas ni le vsync.
     */
    fun presentFrame(frame: IntArray) {
        swapChain.publish(frame)
    }

    private fun computeDestination(canvasWidth: Int, canvasHeight: Int) {
        val bounds = contentBounds?.let(::Rect)
            ?: Rect(0, 0, canvasWidth, canvasHeight)
        if (frameWidth == 0 || frameHeight == 0 || !keepAspectRatio) {
            destRect.set(bounds)
            return
        }
        val scaleX = bounds.width().toFloat() / frameWidth
        val scaleY = bounds.height().toFloat() / frameHeight
        var scale = minOf(scaleX, scaleY)
        if (integerScaling) {
            scale = scale.toInt().coerceAtLeast(1).toFloat()
        }
        val width = (frameWidth * scale).toInt()
        val height = (frameHeight * scale).toInt()
        val left = bounds.left + (bounds.width() - width) / 2
        val top = if (
            contentBounds == null &&
            topAligned &&
            height + topInsetPx <= bounds.height()
        ) {
            bounds.top + topInsetPx
        } else {
            bounds.top + (bounds.height() - height) / 2
        }
        destRect.set(left, top, left + width, top + height)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAvailable = true
        swapChain.reopen()
        renderThread = RenderThread().also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceAvailable = true
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAvailable = false
        renderThread?.let { thread ->
            thread.running = false
            // Ferme la chaîne : c'est ce qui réveille une attente de trame et
            // permet au thread de rendu de sortir dans son délai.
            swapChain.close()
            thread.join(RENDER_THREAD_JOIN_MILLIS)
            if (thread.isAlive) {
                Log.w(TAG, "thread de rendu encore actif après la destruction de la surface")
            }
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
                // Seule cette ligne touche au verrou partagé, le temps d'un
                // échange de références. Tout ce qui suit est hors verrou.
                val source = swapChain.acquire(ACQUIRE_TIMEOUT_NANOS) ?: continue
                if (!running) break
                val width = frameWidth
                val height = frameHeight
                if (width <= 0 || height <= 0 || source.size < width * height) continue
                val bmp = ensureBitmap(width, height)
                val pixels = colorize(source, width * height)
                bmp.setPixels(pixels, 0, width, 0, 0, width, height)
                drawToSurface(bmp)
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
                val placements = screenPlacements
                if (placements.isEmpty()) {
                    computeDestination(canvas.width, canvas.height)
                    canvas.drawBitmap(bmp, null, destRect, paint)
                } else {
                    for (placement in placements) {
                        val source = placement.source
                        if (source == null) {
                            canvas.drawBitmap(bmp, null, placement.destination, paint)
                            continue
                        }
                        // Une découpe qui déborde du tampon est ramenée dedans
                        // plutôt que refusée : mieux vaut un écran un peu
                        // décalé qu'un écran noir. Celle qui n'en touche rien
                        // n'a rien à dessiner.
                        val clipped = Rect(source)
                        if (!clipped.intersect(0, 0, bmp.width, bmp.height)) continue
                        canvas.drawBitmap(bmp, clipped, placement.destination, paint)
                    }
                }
            } finally {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (_: Exception) {
                }
            }
        }
    }

    private companion object {
        const val TAG = "RavenEmuRenderer"

        /** Délai d'attente d'une trame avant de rendre la main à la boucle. */
        const val ACQUIRE_TIMEOUT_NANOS = 250_000_000L

        /** Délai laissé au thread de rendu pour sortir à la destruction. */
        const val RENDER_THREAD_JOIN_MILLIS = 500L
    }
}
