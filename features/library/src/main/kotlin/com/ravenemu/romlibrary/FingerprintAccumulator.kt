package com.ravenemu.romlibrary

import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * Calcule les empreintes d'une ROM **au fil de sa lecture**.
 *
 * ### Pourquoi au fil de l'eau
 *
 * `Fingerprints.of` demande l'image entière en mémoire. Pour une cartouche
 * Game Boy ou Game Boy Advance, qui pèse au plus trente-deux mégaoctets, cela
 * n'a aucune importance. Pour une cartouche Nintendo DS de deux cent
 * cinquante-six mégaoctets, cela demande à un téléphone de tenir un quart de
 * gigaoctet en mémoire **pour ranger un titre dans une liste**, et c'est ce qui
 * plafonnait la bibliothèque bien en dessous de ce que la console accepte.
 *
 * Les trois empreintes se calculent pourtant toutes les trois par passes
 * successives : aucune n'a besoin de revenir en arrière. Les accumuler bloc par
 * bloc rend le coût mémoire indépendant de la taille du fichier.
 *
 * ### Le même résultat, exactement
 *
 * Ce n'est pas une variante ni une approximation : pour une même suite d'octets,
 * les empreintes rendues ici sont **identiques** à celles de `Fingerprints.of`,
 * quel que soit le découpage en blocs. Une vérification l'éprouve en comparant
 * les deux chemins sur des découpages irréguliers, un octet à la fois compris.
 */
class FingerprintAccumulator {

    private val crc = CRC32()
    private val sha1 = MessageDigest.getInstance("SHA-1")
    private val sha256 = MessageDigest.getInstance("SHA-256")
    private var total = 0L

    /** Ajoute les [length] premiers octets de [buffer]. */
    fun update(buffer: ByteArray, length: Int = buffer.size) {
        require(length >= 0 && length <= buffer.size) { "Longueur hors du tampon" }
        if (length == 0) return
        crc.update(buffer, 0, length)
        sha1.update(buffer, 0, length)
        sha256.update(buffer, 0, length)
        total += length.toLong()
    }

    /** Nombre d'octets accumulés jusqu'ici. */
    val bytesRead: Long get() = total

    /**
     * Arrête le calcul et rend les trois empreintes.
     *
     * L'accumulateur n'est pas réutilisable ensuite : les condensats sont remis
     * à zéro par leur propre finalisation, et poursuivre donnerait l'empreinte
     * d'une suite qui n'a jamais existé.
     */
    fun finish(): Fingerprints = Fingerprints(
        crc32 = "%08X".format(crc.value),
        sha1 = sha1.digest().toHex(),
        sha256 = sha256.digest().toHex(),
    )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
