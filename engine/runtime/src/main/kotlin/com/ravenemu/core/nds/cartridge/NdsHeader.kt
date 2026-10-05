package com.ravenemu.core.nds.cartridge

import com.ravenemu.emulation.api.RomLoadException

/** Modèle de console déclaré par l'octet de code unité de l'en-tête. */
enum class NdsUnitCode(val code: Int, val displayName: String) {
    /** Cartouche Nintendo DS. */
    NINTENDO_DS(0x00, "Nintendo DS"),

    /** Cartouche hybride, démarrable sur DS comme sur DSi. */
    NINTENDO_DS_AND_DSI(0x02, "Nintendo DS / DSi"),
    ;

    companion object {
        fun fromCode(code: Int): NdsUnitCode? = entries.firstOrNull { it.code == code }

        /**
         * Cartouche exclusivement DSi, hors du périmètre de ce cœur.
         *
         * Elle est nommée pour pouvoir être refusée avec sa raison plutôt que
         * confondue avec un octet inconnu : une DSi suppose un autre processeur,
         * une autre carte mémoire et des périphériques que RavenEmu ne fournit
         * pas, et démarrer à moitié serait pire qu'un refus net.
         */
        const val NINTENDO_DSI_ONLY = 0x03
    }
}

/**
 * En-tête d'une cartouche Nintendo DS, tel qu'il occupe les premiers octets de
 * la ROM.
 *
 * Cette lecture double celle du cœur natif, et c'est délibéré : la bibliothèque
 * indexe des fichiers sans jamais construire de moteur, et faire démarrer un
 * cœur pour lire un titre coûterait une allocation native par ROM parcourue. Le
 * prix de cette seconde lecture est qu'elle peut diverger de la première ; les
 * deux sont donc tenues au même refus et aux mêmes champs, et une vérification
 * du dépôt compare les constantes qu'elles partagent.
 *
 * Seuls sont retenus les champs dont la bibliothèque a besoin. Les blocs de code
 * processeur ne le sont pas : ils ne se lisent qu'à l'amorçage, et les décoder
 * ici en ferait des valeurs affichées que rien ne vérifie. Leur **cohérence**,
 * elle, est contrôlée, parce qu'un en-tête qui les place hors du fichier ne
 * décrit pas une cartouche.
 */
data class NdsHeader(
    /** Titre interne, sans les octets de remplissage. */
    val title: String,
    /** Code jeu à quatre caractères. */
    val gameCode: String,
    /** Code éditeur à deux caractères. */
    val makerCode: String,
    val unitCode: NdsUnitCode,
    /** Version de la ROM déclarée par l'en-tête. */
    val romVersion: Int,
    /**
     * Code de capacité de la puce. La taille annoncée vaut 128 Kio décalés de
     * cette valeur ; elle décrit la puce, pas la longueur du fichier.
     */
    val deviceCapacityCode: Int,
    /** Longueur utile annoncée par l'en-tête. */
    val totalUsedRomSize: Int,
    /** Somme de contrôle lue dans l'en-tête. */
    val headerCrc: Int,
    /** Somme recalculée sur les octets couverts. */
    val computedHeaderCrc: Int,
) {
    /**
     * `true` lorsque la somme de contrôle de l'en-tête concorde.
     *
     * Une divergence n'empêche pas le chargement : elle est rapportée, comme le
     * fait déjà l'en-tête Game Boy Advance pour sa propre somme. Une partie des
     * ROM amateur sont assemblées sans somme correcte alors qu'elles démarrent
     * sur console ; les refuser reviendrait à confondre « en-tête inhabituel »
     * et « fichier inexploitable ».
     */
    val headerChecksumValid: Boolean get() = headerCrc == computedHeaderCrc

    /** Taille annoncée par le code de capacité, en octets. */
    val declaredCapacityBytes: Long get() = (128L * 1024L) shl deviceCapacityCode

    companion object {
        /** Taille de l'en-tête décodée ici, en octets. */
        const val HEADER_SIZE = 0x200

        /** Étendue couverte par la somme de contrôle de l'en-tête. */
        const val CRC_COVERED_BYTES = 0x15E

        /** Emplacement de la somme de contrôle de l'en-tête. */
        const val HEADER_CRC_OFFSET = 0x15E

        /** Taille maximale d'une cartouche adressable, 4 Gio. */
        const val MAX_ROM_SIZE = 1L shl 32

        private fun byte(rom: ByteArray, offset: Int): Int = rom[offset].toInt() and 0xFF

        private fun readU16(rom: ByteArray, offset: Int): Int =
            byte(rom, offset) or (byte(rom, offset + 1) shl 8)

        private fun readU32(rom: ByteArray, offset: Int): Int =
            byte(rom, offset) or
                (byte(rom, offset + 1) shl 8) or
                (byte(rom, offset + 2) shl 16) or
                (byte(rom, offset + 3) shl 24)

        /**
         * Extrait un champ texte de longueur fixe.
         *
         * Tout ce qui n'est pas imprimable est **remplacé** plutôt que retiré :
         * ce texte finit dans une bibliothèque affichée à l'écran, et une ROM
         * abîmée ne doit pas pouvoir y glisser des caractères de contrôle. Le
         * remplacement laisse voir qu'il y avait quelque chose, là où un retrait
         * silencieux donnerait un titre plausible et faux.
         */
        private fun text(rom: ByteArray, offset: Int, length: Int): String {
            val value = StringBuilder(length)
            for (index in 0 until length) {
                val code = byte(rom, offset + index)
                if (code == 0) break
                value.append(if (code in 0x20..0x7E) code.toChar() else '?')
            }
            return value.toString().trimEnd(' ')
        }

        /**
         * CRC-16 des en-têtes de cartouche Nintendo DS.
         *
         * Variante réfléchie du polynôme `0x8005`, écrite sous sa forme inversée
         * `0xA001`, initialisée à `0xFFFF` et sans inversion finale.
         */
        fun crc16(data: ByteArray, length: Int): Int {
            var crc = 0xFFFF
            for (index in 0 until length) {
                crc = crc xor (data[index].toInt() and 0xFF)
                repeat(8) {
                    val carry = (crc and 1) != 0
                    crc = crc ushr 1
                    if (carry) crc = crc xor 0xA001
                }
            }
            return crc and 0xFFFF
        }

        /**
         * Décode et contrôle l'en-tête d'une image de cartouche.
         *
         * Ne sont refusés que les fichiers structurellement inexploitables :
         * trop courts pour porter un en-tête, portant un code unité que ce cœur
         * ne couvre pas, ou décrivant des blocs de code processeur qui sortent
         * du fichier ou recouvrent l'en-tête. Tout le reste est décodé et
         * rapporté, la somme de contrôle comprise.
         *
         * @param rom début de l'image, au moins [HEADER_SIZE] octets.
         * @param totalSizeBytes longueur du fichier entier. Elle est distincte
         *   de celle de [rom] parce que la bibliothèque décode l'en-tête sans
         *   charger l'image : une cartouche pèse jusqu'à un demi-gigaoctet, et
         *   contrôler que ses blocs de code tiennent dans le fichier ne doit pas
         *   demander de le tenir en mémoire. Les confondre ferait refuser toute
         *   cartouche lue par sa seule tête, ses blocs paraissant tous en sortir.
         *
         * @throws RomLoadException si l'image ne peut pas décrire une cartouche.
         */
        fun parse(rom: ByteArray, totalSizeBytes: Long = rom.size.toLong()): NdsHeader {
            if (rom.size < HEADER_SIZE) {
                throw RomLoadException(
                    "ROM Nintendo DS trop courte pour porter un en-tête : " +
                        "${rom.size} octets (< $HEADER_SIZE)"
                )
            }
            if (totalSizeBytes > MAX_ROM_SIZE) {
                throw RomLoadException("ROM Nintendo DS trop volumineuse")
            }

            val unit = byte(rom, 0x012)
            if (unit == NdsUnitCode.NINTENDO_DSI_ONLY) {
                throw RomLoadException("Cartouche exclusivement Nintendo DSi non prise en charge")
            }
            val unitCode = NdsUnitCode.fromCode(unit)
                ?: throw RomLoadException("Code unité Nintendo DS inconnu : $unit")

            requireCodeBlock(rom, totalSizeBytes, offsetAt = 0x020, sizeAt = 0x02C, name = "ARM9")
            requireCodeBlock(rom, totalSizeBytes, offsetAt = 0x030, sizeAt = 0x03C, name = "ARM7")

            return NdsHeader(
                title = text(rom, 0x000, 12),
                gameCode = text(rom, 0x00C, 4),
                makerCode = text(rom, 0x010, 2),
                unitCode = unitCode,
                romVersion = byte(rom, 0x01E),
                deviceCapacityCode = byte(rom, 0x014),
                totalUsedRomSize = readU32(rom, 0x080),
                headerCrc = readU16(rom, HEADER_CRC_OFFSET),
                computedHeaderCrc = crc16(rom, CRC_COVERED_BYTES),
            )
        }

        /**
         * Contrôle qu'un bloc de code tient dans le fichier sans recouvrir
         * l'en-tête.
         *
         * Les deux blocs sont la seule chose dont le démarrage dépende vraiment.
         * Les tailles et déplacements sont comparés en `Long` : lus sur trente-
         * deux bits non signés, ils dépassent l'entier signé de Kotlin, et une
         * comparaison signée prendrait un déplacement énorme pour un déplacement
         * négatif — c'est-à-dire pour un bloc qui tient.
         */
        private fun requireCodeBlock(
            rom: ByteArray,
            totalSizeBytes: Long,
            offsetAt: Int,
            sizeAt: Int,
            name: String,
        ) {
            val offset = readU32(rom, offsetAt).toLong() and 0xFFFF_FFFFL
            val size = readU32(rom, sizeAt).toLong() and 0xFFFF_FFFFL
            if (size == 0L) throw RomLoadException("Bloc de code $name vide")
            // La borne est la longueur du fichier, non celle du tampon reçu :
            // l'en-tête peut être décodé seul, et un bloc qui commence après lui
            // n'est pas pour autant hors du fichier.
            if (offset + size > totalSizeBytes) {
                throw RomLoadException("Bloc de code $name hors de la ROM")
            }
            if (offset < HEADER_SIZE) {
                throw RomLoadException("Bloc de code $name recouvrant l'en-tête")
            }
        }
    }
}
