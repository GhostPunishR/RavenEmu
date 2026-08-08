package com.ravenemu.emulation.api.render

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Échange de trames entre le thread d'émulation (producteur) et le thread de
 * rendu (consommateur), à **trois tampons**.
 *
 * Chaque tampon appartient à un seul rôle à la fois : le producteur écrit dans
 * le sien, le consommateur lit le sien, et le troisième sert de boîte aux
 * lettres entre les deux. Publier ou récupérer une trame se réduit alors à
 * l'échange de deux références.
 *
 * C'est tout l'intérêt : le verrou n'est tenu que le temps de cet échange.
 * Colorisation, réglages d'affichage, remplissage du bitmap et dessin se font
 * **hors verrou**, sur un tampon que plus personne d'autre ne touche. Le thread
 * d'émulation ne peut donc jamais attendre le rendu d'une trame — seulement la
 * poignée de nanosecondes d'un échange de pointeurs.
 *
 * Une trame n'est publiée qu'une fois entièrement copiée : le consommateur ne
 * voit jamais d'image à moitié écrite.
 *
 * Le producteur qui va plus vite que le rendu écrase simplement la trame en
 * attente. C'est voulu : afficher la plus récente vaut mieux qu'accumuler du
 * retard.
 */
class FrameSwapChain(pixelCount: Int) {

    private val lock = ReentrantLock()
    private val ready = lock.newCondition()

    private var producer = IntArray(pixelCount)
    private var pending = IntArray(pixelCount)
    private var consumer = IntArray(pixelCount)

    private var hasPending = false
    private var closed = false

    /** Taille courante d'un tampon, en pixels. */
    var pixelCount: Int = pixelCount
        private set

    /** Trames publiées qui n'ont jamais été affichées (diagnostic). */
    var droppedFrames: Long = 0
        private set

    /**
     * Publie [frame]. Appelé par le producteur.
     *
     * La copie se fait **hors verrou**, dans le tampon dont le producteur est
     * seul propriétaire ; seul l'échange final est protégé. Retourne `false` si
     * la chaîne est fermée, si la trame est trop courte, ou si un
     * redimensionnement est survenu pendant la copie — auquel cas le contenu
     * copié ne correspond plus et doit être abandonné.
     */
    fun publish(frame: IntArray): Boolean {
        val target = lock.withLock { if (closed) null else producer } ?: return false
        if (frame.size < target.size) return false
        System.arraycopy(frame, 0, target, 0, target.size)
        lock.withLock {
            // Un redimensionnement pendant la copie a remplacé les tampons :
            // ce qu'on vient d'écrire n'a plus la bonne taille.
            if (closed || producer !== target) return false
            if (hasPending) droppedFrames++
            val swap = pending
            pending = producer
            producer = swap
            hasPending = true
            ready.signalAll()
        }
        return true
    }

    /**
     * Récupère la trame la plus récente, ou `null` après [timeoutNanos] sans
     * rien de neuf (ou si la chaîne est fermée). Appelé par le consommateur.
     *
     * Le tableau retourné sort de la chaîne jusqu'au prochain appel : il peut
     * être lu tranquillement hors verrou.
     */
    fun acquire(timeoutNanos: Long): IntArray? = lock.withLock {
        var remaining = timeoutNanos
        while (!closed && !hasPending && remaining > 0) {
            remaining = ready.awaitNanos(remaining)
        }
        if (closed || !hasPending) return null
        val swap = consumer
        consumer = pending
        pending = swap
        hasPending = false
        consumer
    }

    /**
     * Change la taille des trames. Les tampons sont réalloués et la trame en
     * attente abandonnée : elle n'a plus les bonnes dimensions.
     */
    fun resize(pixelCount: Int) = lock.withLock {
        if (pixelCount == this.pixelCount) return@withLock
        this.pixelCount = pixelCount
        producer = IntArray(pixelCount)
        pending = IntArray(pixelCount)
        consumer = IntArray(pixelCount)
        hasPending = false
    }

    /** Ferme la chaîne et réveille un consommateur en attente. */
    fun close() = lock.withLock {
        closed = true
        ready.signalAll()
    }

    /** Rouvre une chaîne fermée (surface recréée). */
    fun reopen() = lock.withLock {
        closed = false
        hasPending = false
    }
}
