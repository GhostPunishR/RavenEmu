package com.ravenemu.romlibrary

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Les empreintes calculées au fil de l'eau sont celles de l'image entière.
 *
 * Ce n'est pas une variante tolérée : la bibliothèque range les jeux d'après ces
 * valeurs, et deux chemins qui n'en donneraient pas exactement les mêmes
 * feraient apparaître une même cartouche comme deux jeux différents selon
 * qu'elle a été lue d'une façon ou de l'autre.
 */
class FingerprintAccumulatorTest {

    private fun accumulate(data: ByteArray, chunks: List<Int>): Fingerprints {
        val accumulator = FingerprintAccumulator()
        var offset = 0
        for (size in chunks) {
            val length = minOf(size, data.size - offset)
            if (length <= 0) break
            accumulator.update(data.copyOfRange(offset, offset + length), length)
            offset += length
        }
        // Le reste, s'il en demeure : les découpages proposés ne couvrent pas
        // toujours toute l'image.
        if (offset < data.size) {
            val reste = data.copyOfRange(offset, data.size)
            accumulator.update(reste, reste.size)
            offset = data.size
        }
        assertEquals(data.size.toLong(), accumulator.bytesRead, "tout a été accumulé")
        return accumulator.finish()
    }

    @Test
    fun `le decoupage en blocs ne change aucune des trois empreintes`() {
        val data = Random(20260902).nextBytes(9_973)
        val attendu = Fingerprints.of(data)

        // Des découpages irréguliers, dont le pire : un octet à la fois.
        for (decoupage in listOf(
            listOf(data.size),
            listOf(1),
            listOf(4096),
            listOf(1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144, 233, 377, 610, 987),
            listOf(9_972, 1),
        )) {
            val obtenu = accumulate(data, List(data.size) { decoupage[it % decoupage.size] })
            assertEquals(attendu, obtenu, "découpage $decoupage")
        }
    }

    @Test
    fun `une image vide a les empreintes d'une image vide`() {
        assertEquals(Fingerprints.of(ByteArray(0)), FingerprintAccumulator().finish())
    }

    @Test
    fun `un bloc de longueur nulle ne change rien`() {
        val data = Random(7).nextBytes(100)
        val accumulator = FingerprintAccumulator()
        accumulator.update(ByteArray(0), 0)
        accumulator.update(data, data.size)
        accumulator.update(ByteArray(16), 0)
        assertEquals(Fingerprints.of(data), accumulator.finish())
        assertEquals(100L, accumulator.bytesRead)
    }

    @Test
    fun `seule la longueur annoncee compte, pas la taille du tampon`() {
        // Le tampon de lecture est réutilisé d'un bloc à l'autre : sa queue
        // porte encore les octets du bloc précédent, et les accumuler
        // ajouterait à l'image des octets qui n'y sont pas.
        val data = byteArrayOf(1, 2, 3)
        val tampon = byteArrayOf(1, 2, 3, 99, 99, 99, 99, 99)
        val accumulator = FingerprintAccumulator()
        accumulator.update(tampon, 3)
        assertEquals(Fingerprints.of(data), accumulator.finish())
    }
}
