package com.ravenemu.romlibrary

import java.security.MessageDigest
import java.util.zip.CRC32
import kotlinx.serialization.Serializable

/**
 * Empreintes d'une ROM. La classification de la bibliothèque repose sur ces
 * valeurs, jamais sur le seul en-tête ou le nom de fichier.
 */
@Serializable
data class Fingerprints(
    /** CRC32 en hexadécimal majuscule sur 8 caractères. */
    val crc32: String,
    /** SHA-1 en hexadécimal minuscule. */
    val sha1: String,
    /** SHA-256 en hexadécimal minuscule. */
    val sha256: String,
) {
    companion object {
        fun of(data: ByteArray): Fingerprints {
            val crc = CRC32().apply { update(data) }
            return Fingerprints(
                crc32 = "%08X".format(crc.value),
                sha1 = MessageDigest.getInstance("SHA-1").digest(data).toHex(),
                sha256 = MessageDigest.getInstance("SHA-256").digest(data).toHex(),
            )
        }

        private fun ByteArray.toHex(): String =
            joinToString("") { "%02x".format(it) }
    }
}
