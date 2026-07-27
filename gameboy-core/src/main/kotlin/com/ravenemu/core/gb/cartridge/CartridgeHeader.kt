package com.ravenemu.core.gb.cartridge

import com.ravenemu.emulation.api.RomLoadException

/** Contrôleur mémoire déclaré par l'en-tête de la cartouche. */
enum class MbcType(val displayName: String) {
    NONE("Sans MBC"),
    MBC1("MBC1"),
    MBC2("MBC2"),
    MBC3("MBC3"),
    MBC5("MBC5"),
    UNSUPPORTED("Non pris en charge"),
}

/** Région déclarée par l'octet 0x014A de l'en-tête. */
enum class RomRegion(val displayName: String) {
    JAPAN("Japon"),
    INTERNATIONAL("International"),
    UNKNOWN("Inconnue"),
}

/**
 * En-tête de cartouche Game Boy (plage 0x0100–0x014F de la ROM), décodé
 * d'après la documentation matérielle publique. Le parsing est tolérant :
 * seuls une taille minimale et des champs de taille cohérents sont exigés,
 * la validité des checksums est exposée sans être bloquante.
 */
data class CartridgeHeader(
    /** Titre ASCII nettoyé (peut être vide sur certaines cartouches). */
    val title: String,
    /** Octet type de cartouche (0x0147) brut. */
    val cartridgeTypeCode: Int,
    /** Contrôleur mémoire correspondant au type. */
    val mbcType: MbcType,
    /** `true` si le type de cartouche déclare de la RAM externe. */
    val hasRam: Boolean,
    /** `true` si le type de cartouche déclare une pile de sauvegarde. */
    val hasBattery: Boolean,
    /** `true` si le type de cartouche déclare une horloge temps réel. */
    val hasRtc: Boolean,
    /** Taille ROM déclarée en octets. */
    val romSizeBytes: Int,
    /** Taille RAM cartouche déclarée en octets (512 pour MBC2). */
    val ramSizeBytes: Int,
    /** Région déclarée. */
    val region: RomRegion,
    /** Octet 0x0143 : 0x80 = compatible GBC, 0xC0 = GBC uniquement. */
    val cgbFlag: Int,
    /** `true` si la cartouche annonce des fonctions Game Boy Color. */
    val supportsCgb: Boolean,
    /** `true` si la cartouche exige une Game Boy Color. */
    val requiresCgb: Boolean,
    /** Checksum d'en-tête déclaré (0x014D). */
    val headerChecksum: Int,
    /** `true` si le checksum d'en-tête recalculé correspond. */
    val headerChecksumValid: Boolean,
    /** Checksum global déclaré (0x014E–0x014F). */
    val globalChecksum: Int,
    /** `true` si le checksum global recalculé correspond. */
    val globalChecksumValid: Boolean,
) {
    companion object {
        /** Premier octet du code fabricant, quand la cartouche en porte un. */
        private const val MANUFACTURER_CODE_START = 0x013F
        private const val MANUFACTURER_CODE_SIZE = 4

        /**
         * `true` si les quatre octets précédant l'indicateur couleur forment un
         * code fabricant plutôt que la fin du titre.
         *
         * Un tel code est toujours fait de quatre majuscules ou chiffres — le
         * dernier désignant la région, comme le `F` de `APSF` sur une cartouche
         * française. Un titre qui occuperait cette zone contiendrait presque
         * toujours autre chose : une espace, une ponctuation, une minuscule.
         * Le test se trompe donc au pire sur un titre de quinze caractères
         * entièrement en majuscules et sans séparateur, cas qu'aucune cartouche
         * connue ne présente.
         */
        private fun hasManufacturerCode(rom: ByteArray): Boolean {
            for (i in 0 until MANUFACTURER_CODE_SIZE) {
                val c = rom[MANUFACTURER_CODE_START + i].toInt() and 0xFF
                val alphanumeric = c in 0x41..0x5A || c in 0x30..0x39
                if (!alphanumeric) return false
            }
            return true
        }

        const val HEADER_END = 0x0150
        const val MIN_ROM_SIZE = 0x8000
        const val MAX_ROM_SIZE = 8 * 1024 * 1024

        /**
         * Décode l'en-tête d'une ROM.
         * @throws RomLoadException si la ROM est trop petite ou trop grande.
         */
        fun parse(rom: ByteArray): CartridgeHeader {
            if (rom.size < MIN_ROM_SIZE) {
                throw RomLoadException(
                    "ROM trop petite (${rom.size} octets, minimum $MIN_ROM_SIZE)"
                )
            }
            if (rom.size > MAX_ROM_SIZE) {
                throw RomLoadException(
                    "ROM trop grande (${rom.size} octets, maximum $MAX_ROM_SIZE)"
                )
            }

            val cgbFlag = rom[0x0143].toInt() and 0xFF
            val supportsColor = cgbFlag == 0x80 || cgbFlag == 0xC0
            // La zone titre a rétréci au fil des générations de cartouches :
            // 16 caractères à l'origine, puis 15 quand 0x0143 est devenu
            // l'indicateur couleur, puis 11 quand les quatre octets suivants
            // ont accueilli le code fabricant. Rien dans l'en-tête ne dit
            // laquelle des deux dernières s'applique, d'où le test ci-dessous.
            val titleEnd = when {
                !supportsColor -> 0x0144
                hasManufacturerCode(rom) -> MANUFACTURER_CODE_START
                else -> 0x0143
            }
            val title = buildString {
                for (i in 0x0134 until titleEnd) {
                    val c = rom[i].toInt() and 0xFF
                    if (c == 0) break
                    if (c in 0x20..0x7E) append(c.toChar())
                }
            }.trim()

            val typeCode = rom[0x0147].toInt() and 0xFF
            val romSizeCode = rom[0x0148].toInt() and 0xFF
            val ramSizeCode = rom[0x0149].toInt() and 0xFF

            val mbc = when (typeCode) {
                0x00, 0x08, 0x09 -> MbcType.NONE
                0x01, 0x02, 0x03 -> MbcType.MBC1
                0x05, 0x06 -> MbcType.MBC2
                0x0F, 0x10, 0x11, 0x12, 0x13 -> MbcType.MBC3
                0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E -> MbcType.MBC5
                else -> MbcType.UNSUPPORTED
            }
            val hasRam = typeCode in intArrayOf(
                0x02, 0x03, 0x05, 0x06, 0x08, 0x09, 0x10, 0x12, 0x13, 0x1A,
                0x1B, 0x1D, 0x1E,
            )
            val hasBattery = typeCode in intArrayOf(
                0x03, 0x06, 0x09, 0x0F, 0x10, 0x13, 0x1B, 0x1E,
            )
            val hasRtc = typeCode == 0x0F || typeCode == 0x10

            val romSize = when {
                romSizeCode <= 0x08 -> MIN_ROM_SIZE shl romSizeCode
                romSizeCode == 0x52 -> 72 * Cartridge.ROM_BANK_SIZE
                romSizeCode == 0x53 -> 80 * Cartridge.ROM_BANK_SIZE
                romSizeCode == 0x54 -> 96 * Cartridge.ROM_BANK_SIZE
                else -> 0
            }
            val ramSize = when {
                mbc == MbcType.MBC2 -> 512
                else -> when (ramSizeCode) {
                    0x00 -> 0
                    0x01 -> 2 * 1024
                    0x02 -> 8 * 1024
                    0x03 -> 32 * 1024
                    0x04 -> 128 * 1024
                    0x05 -> 64 * 1024
                    else -> 0
                }
            }

            val region = when (rom[0x014A].toInt() and 0xFF) {
                0x00 -> RomRegion.JAPAN
                0x01 -> RomRegion.INTERNATIONAL
                else -> RomRegion.UNKNOWN
            }

            val declaredHeaderChecksum = rom[0x014D].toInt() and 0xFF
            var computed = 0
            for (i in 0x0134..0x014C) {
                computed = (computed - (rom[i].toInt() and 0xFF) - 1) and 0xFF
            }

            val declaredGlobal =
                ((rom[0x014E].toInt() and 0xFF) shl 8) or (rom[0x014F].toInt() and 0xFF)
            var globalSum = 0
            for (i in rom.indices) {
                if (i != 0x014E && i != 0x014F) globalSum += rom[i].toInt() and 0xFF
            }
            globalSum = globalSum and 0xFFFF

            return CartridgeHeader(
                title = title,
                cartridgeTypeCode = typeCode,
                mbcType = mbc,
                hasRam = hasRam || mbc == MbcType.MBC2,
                hasBattery = hasBattery,
                hasRtc = hasRtc,
                romSizeBytes = romSize,
                ramSizeBytes = if (hasRam || mbc == MbcType.MBC2) ramSize else 0,
                region = region,
                cgbFlag = cgbFlag,
                supportsCgb = cgbFlag == 0x80 || cgbFlag == 0xC0,
                requiresCgb = cgbFlag == 0xC0,
                headerChecksum = declaredHeaderChecksum,
                headerChecksumValid = computed == declaredHeaderChecksum,
                globalChecksum = declaredGlobal,
                globalChecksumValid = globalSum == declaredGlobal,
            )
        }
    }
}
