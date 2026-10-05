package com.ravenemu.storage

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Lecture bornée, y compris lorsque le fournisseur SAF ignore la taille du document. */
internal object BatterySaveReader {
    // Plus grand format produit : SRAM MBC6 + flash + zone cachée + pied RVM6.
    const val MAX_SIZE = 32 * 1024 + 1024 * 1024 + 256 + 8

    fun read(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_SIZE + 1 - output.size()))
            if (count < 0) return output.toByteArray()
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) return output.toByteArray()
                output.write(byte)
            } else {
                output.write(buffer, 0, count)
            }
            if (output.size() > MAX_SIZE) return null
        }
    }
}
