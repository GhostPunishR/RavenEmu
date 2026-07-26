package com.ravenemu.core.gba.bios

import com.ravenemu.core.gba.memory.GbaBus

/**
 * Routines de décompression du BIOS Game Boy Advance, réimplémentées pour
 * RavenEmu à partir de la description publique des formats.
 *
 * Les jeux stockent la quasi-totalité de leurs graphismes compressés et les
 * déploient en VRAM par ces appels : sans eux, l'écran reste noir.
 *
 * Chaque flux commence par un en-tête de quatre octets : un octet de type
 * (`0x10` LZ77, `0x20` Huffman, `0x30` RLE, `0x81`/`0x82` filtres différentiels)
 * suivi de la taille décompressée sur 24 bits.
 *
 * Les variantes « Vram » écrivent par **demi-mots** : la VRAM, la palette et
 * l'OAM n'acceptent pas les écritures d'octet isolé. Le résultat est donc
 * assemblé en mémoire puis recopié dans la largeur voulue.
 */
internal object BiosDecompression {

    /** Taille décompressée annoncée par l'en-tête (24 bits de poids fort). */
    private fun decompressedSize(header: Int): Int = (header ushr 8) and 0xFF_FFFF

    private fun read8(bus: GbaBus, address: Int): Int = bus.read8(address)

    /**
     * Recopie [data] à partir de [dest], par demi-mots si [halfwords] est vrai
     * (destinations VRAM, palette et OAM).
     */
    private fun writeOut(bus: GbaBus, dest: Int, data: ByteArray, halfwords: Boolean) {
        if (halfwords) {
            var i = 0
            while (i + 1 < data.size) {
                val value = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
                bus.write16(dest + i, value)
                i += 2
            }
            // Octet final isolé : complété à zéro pour rester sur un demi-mot.
            if (i < data.size) bus.write16(dest + i, data[i].toInt() and 0xFF)
        } else {
            for (i in data.indices) bus.write8(dest + i, data[i].toInt() and 0xFF)
        }
    }

    /**
     * `LZ77UnCompWram` / `LZ77UnCompVram` : fenêtre glissante de 4 Kio. Un octet
     * de drapeaux précède huit unités ; un drapeau à 1 introduit une référence
     * (longueur et distance), un drapeau à 0 un octet littéral.
     */
    fun lz77(bus: GbaBus, source: Int, dest: Int, toVram: Boolean) {
        val header = bus.read32(source)
        val size = decompressedSize(header)
        if (size <= 0 || size > MAX_OUTPUT) return
        val out = ByteArray(size)
        var src = source + 4
        var written = 0

        while (written < size) {
            val flags = read8(bus, src++)
            for (bit in 7 downTo 0) {
                if (written >= size) break
                if ((flags ushr bit) and 1 == 0) {
                    out[written++] = read8(bus, src++).toByte()
                    continue
                }
                val b0 = read8(bus, src++)
                val b1 = read8(bus, src++)
                val length = (b0 ushr 4) + 3
                val distance = (((b0 and 0xF) shl 8) or b1) + 1
                var from = written - distance
                if (from < 0) return // flux incohérent : on abandonne sans écrire
                repeat(length) {
                    if (written < size) out[written++] = out[from++]
                }
            }
        }
        writeOut(bus, dest, out, toVram)
    }

    /**
     * `RLUnCompWram` / `RLUnCompVram` : un octet de drapeau indique soit une
     * répétition (bit 7 à 1), soit une suite d'octets littéraux.
     */
    fun runLength(bus: GbaBus, source: Int, dest: Int, toVram: Boolean) {
        val header = bus.read32(source)
        val size = decompressedSize(header)
        if (size <= 0 || size > MAX_OUTPUT) return
        val out = ByteArray(size)
        var src = source + 4
        var written = 0

        while (written < size) {
            val flag = read8(bus, src++)
            if (flag and 0x80 != 0) {
                val length = (flag and 0x7F) + 3
                val value = read8(bus, src++).toByte()
                repeat(length) { if (written < size) out[written++] = value }
            } else {
                val length = (flag and 0x7F) + 1
                repeat(length) {
                    if (written < size) out[written++] = read8(bus, src++).toByte()
                }
            }
        }
        writeOut(bus, dest, out, toVram)
    }

    /**
     * `HuffUnComp` : arbre binaire suivi d'un flux de bits lu par mots de
     * 32 bits. Chaque symbole fait 4 ou 8 bits selon l'en-tête.
     */
    fun huffman(bus: GbaBus, source: Int, dest: Int) {
        val header = bus.read32(source)
        val size = decompressedSize(header)
        val symbolBits = header and 0xF
        if (size <= 0 || size > MAX_OUTPUT) return
        if (symbolBits != 4 && symbolBits != 8) return

        // La table Huffman commence à l'octet de taille lui-même : c'est sur
        // cette base, et en adresses absolues, que se calculent les offsets des
        // enfants. Aligner un offset relatif à la racine donnerait un décalage
        // d'un octet et ferait dérailler tout le parcours de l'arbre.
        val treeStart = source + 4
        val treeBytes = (read8(bus, treeStart) + 1) * 2
        val rootAddress = treeStart + 1
        val treeEnd = treeStart + treeBytes
        var streamAddress = treeEnd

        val out = ByteArray(size)
        var written = 0
        var nibbleHigh = false
        var pending = 0

        var nodeAddress = rootAddress
        var word = 0
        var bitsLeft = 0
        // Un arbre incohérent pourrait ne jamais atteindre de feuille : on borne
        // le nombre de bits consommés pour toujours terminer.
        var budget = size.toLong() * 8 * MAX_BITS_PER_SYMBOL + 64

        while (written < size) {
            if (budget-- <= 0) return // flux invalide : rien n'est écrit
            if (bitsLeft == 0) {
                word = bus.read32(streamAddress)
                streamAddress += 4
                bitsLeft = 32
            }
            val bit = (word ushr 31) and 1
            word = word shl 1
            bitsLeft--

            val nodeValue = read8(bus, nodeAddress)
            val offset = nodeValue and 0x3F
            val nextAddress = (nodeAddress and 1.inv()) + (offset + 1) * 2 + bit
            val isLeaf = if (bit == 0) nodeValue and 0x80 != 0 else nodeValue and 0x40 != 0

            // Un enfant hors de la table signale un flux corrompu.
            if (nextAddress < rootAddress || nextAddress >= treeEnd) return

            if (!isLeaf) {
                nodeAddress = nextAddress
                continue
            }
            val symbol = read8(bus, nextAddress)
            nodeAddress = rootAddress
            if (symbolBits == 8) {
                out[written++] = symbol.toByte()
            } else {
                // Deux symboles de 4 bits par octet, poids faible en premier.
                if (!nibbleHigh) {
                    pending = symbol and 0xF
                    nibbleHigh = true
                } else {
                    out[written++] = (pending or ((symbol and 0xF) shl 4)).toByte()
                    nibbleHigh = false
                }
            }
        }
        writeOut(bus, dest, out, halfwords = true)
    }

    /**
     * `Diff8bitUnFilter` / `Diff16bitUnFilter` : chaque élément du flux est un
     * écart par rapport au précédent.
     */
    fun differentialUnfilter(bus: GbaBus, source: Int, dest: Int, wide: Boolean, toVram: Boolean) {
        val header = bus.read32(source)
        val size = decompressedSize(header)
        if (size <= 0 || size > MAX_OUTPUT) return
        val out = ByteArray(size)
        var src = source + 4

        if (wide) {
            var previous = 0
            var i = 0
            while (i + 1 < size) {
                val delta = read8(bus, src) or (read8(bus, src + 1) shl 8)
                src += 2
                previous = (previous + delta) and 0xFFFF
                out[i] = (previous and 0xFF).toByte()
                out[i + 1] = ((previous ushr 8) and 0xFF).toByte()
                i += 2
            }
        } else {
            var previous = 0
            for (i in 0 until size) {
                previous = (previous + read8(bus, src++)) and 0xFF
                out[i] = previous.toByte()
            }
        }
        writeOut(bus, dest, out, toVram)
    }

    /**
     * `BitUnPack` : élargit des éléments de 1, 2, 4 ou 8 bits vers 8, 16 ou
     * 32 bits, avec décalage constant optionnel.
     */
    fun bitUnPack(bus: GbaBus, source: Int, dest: Int, paramAddress: Int) {
        val sourceLength = bus.read16(paramAddress)
        val sourceWidth = read8(bus, paramAddress + 2)
        val destWidth = read8(bus, paramAddress + 3)
        val offsetWord = bus.read32(paramAddress + 4)
        val dataOffset = offsetWord and 0x7FFF_FFFF
        val offsetZero = offsetWord and 0x8000_0000.toInt() != 0
        if (sourceWidth == 0 || destWidth == 0 || sourceLength <= 0) return

        var buffer = 0L
        var bitsInBuffer = 0
        var destAddress = dest
        val mask = (1 shl sourceWidth) - 1

        for (i in 0 until sourceLength) {
            val byte = read8(bus, source + i)
            var consumed = 0
            while (consumed < 8) {
                val value = (byte ushr consumed) and mask
                consumed += sourceWidth
                val expanded = if (value == 0 && !offsetZero) 0L else (value + dataOffset).toLong()
                buffer = buffer or (expanded shl bitsInBuffer)
                bitsInBuffer += destWidth
                if (bitsInBuffer >= 32) {
                    bus.write32(destAddress, buffer.toInt())
                    destAddress += 4
                    buffer = 0
                    bitsInBuffer = 0
                }
            }
        }
        if (bitsInBuffer > 0) bus.write32(destAddress, buffer.toInt())
    }

    /** Garde-fou : refuse un flux annonçant une taille aberrante. */
    private const val MAX_OUTPUT = 1 shl 20

    /** Profondeur d'arbre Huffman tolérée avant de conclure à un flux corrompu. */
    private const val MAX_BITS_PER_SYMBOL = 32
}
