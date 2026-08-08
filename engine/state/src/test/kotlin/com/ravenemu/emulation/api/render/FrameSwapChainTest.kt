package com.ravenemu.emulation.api.render

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chaîne d'échange de trames : la brique qui sort la colorisation et le dessin
 * du verrou partagé.
 *
 * Elle est éprouvée ici **sans Android**, ce qui n'aurait pas été possible tant
 * que cette logique vivait dans la vue de rendu.
 */
class FrameSwapChainTest {

    private val size = 16

    private fun frameOf(value: Int) = IntArray(size) { value }

    @Test
    fun `une trame publiee est recuperee telle quelle`() {
        val chain = FrameSwapChain(size)
        assertTrue(chain.publish(frameOf(7)))
        val got = assertNotNull(chain.acquire(SECOND))
        assertTrue(got.all { it == 7 })
    }

    @Test
    fun `sans nouvelle trame la recuperation expire`() {
        val chain = FrameSwapChain(size)
        assertNull(chain.acquire(5_000_000L))
    }

    /** Une trame déjà consommée ne doit pas être rendue deux fois. */
    @Test
    fun `une trame n'est consommee qu'une fois`() {
        val chain = FrameSwapChain(size)
        chain.publish(frameOf(1))
        assertNotNull(chain.acquire(SECOND))
        assertNull(chain.acquire(5_000_000L))
    }

    /**
     * Le producteur plus rapide que le rendu écrase la trame en attente :
     * afficher la plus récente vaut mieux qu'accumuler du retard.
     */
    @Test
    fun `une publication rapide ecrase la trame en attente`() {
        val chain = FrameSwapChain(size)
        chain.publish(frameOf(1))
        chain.publish(frameOf(2))
        chain.publish(frameOf(3))

        val got = assertNotNull(chain.acquire(SECOND))
        assertTrue(got.all { it == 3 }, "la trame la plus récente doit primer")
        assertEquals(2L, chain.droppedFrames)
    }

    /**
     * Le point central : le tampon rendu au consommateur lui appartient jusqu'à
     * la récupération suivante. Le producteur peut publier sans le corrompre —
     * c'est ce qui autorise à coloriser et dessiner hors verrou.
     */
    @Test
    fun `le tampon consomme n'est pas corrompu par une publication`() {
        val chain = FrameSwapChain(size)
        chain.publish(frameOf(1))
        val held = assertNotNull(chain.acquire(SECOND))

        repeat(5) { chain.publish(frameOf(9)) }

        assertTrue(held.all { it == 1 }, "le tampon détenu doit rester intact")
    }

    /** Une trame plus courte que le tampon est refusée plutôt que tronquée. */
    @Test
    fun `une trame trop courte est refusee`() {
        val chain = FrameSwapChain(size)
        assertFalse(chain.publish(IntArray(size - 1)))
        assertNull(chain.acquire(5_000_000L))
    }

    @Test
    fun `un redimensionnement abandonne la trame en attente`() {
        val chain = FrameSwapChain(size)
        chain.publish(frameOf(4))
        chain.resize(size * 2)

        assertEquals(size * 2, chain.pixelCount)
        assertNull(chain.acquire(5_000_000L), "l'ancienne trame n'a plus la bonne taille")
        assertTrue(chain.publish(IntArray(size * 2) { 5 }))
        assertNotNull(chain.acquire(SECOND))
    }

    /** La fermeture réveille immédiatement un consommateur en attente. */
    @Test
    fun `la fermeture reveille le consommateur`() {
        val chain = FrameSwapChain(size)
        val woke = CountDownLatch(1)
        val result = AtomicBoolean(true)
        val reader = Thread {
            result.set(chain.acquire(10 * SECOND) != null)
            woke.countDown()
        }
        reader.start()
        Thread.sleep(30)
        chain.close()

        assertTrue(woke.await(5, TimeUnit.SECONDS), "la fermeture doit débloquer l'attente")
        assertFalse(result.get(), "une chaîne fermée ne rend aucune trame")
        reader.join(1000)
    }

    @Test
    fun `une chaine fermee refuse les publications puis se rouvre`() {
        val chain = FrameSwapChain(size)
        chain.close()
        assertFalse(chain.publish(frameOf(1)))

        chain.reopen()
        assertTrue(chain.publish(frameOf(2)))
        assertNotNull(chain.acquire(SECOND))
    }

    /**
     * Épreuve de concurrence : un producteur et un consommateur en parallèle.
     * Aucune trame ne doit être partiellement écrite — chaque tableau reçu doit
     * être uniforme, puisque le producteur n'en publie que d'uniformes.
     */
    @Test
    fun `producteur et consommateur concurrents ne melangent jamais deux trames`() {
        val chain = FrameSwapChain(size)
        val stop = AtomicBoolean(false)
        val torn = AtomicLong(0)
        val consumed = AtomicLong(0)

        val producer = Thread {
            var value = 1
            val frame = IntArray(size)
            while (!stop.get()) {
                frame.fill(value)
                chain.publish(frame)
                value++
            }
        }
        val consumer = Thread {
            while (!stop.get()) {
                val got = chain.acquire(5_000_000L) ?: continue
                val first = got[0]
                if (got.any { it != first }) torn.incrementAndGet()
                consumed.incrementAndGet()
            }
        }
        producer.start()
        consumer.start()
        Thread.sleep(300)
        stop.set(true)
        chain.close()
        producer.join(2000)
        consumer.join(2000)

        assertTrue(consumed.get() > 0, "le consommateur doit avoir vu des trames")
        assertEquals(0L, torn.get(), "aucune trame ne doit mélanger deux images")
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
