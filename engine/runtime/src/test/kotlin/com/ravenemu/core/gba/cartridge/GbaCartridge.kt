package com.ravenemu.core.gba.cartridge

import com.ravenemu.core.gba.save.GbaSaveMemory
import com.ravenemu.core.gba.save.GbaSaveType
import com.ravenemu.emulation.api.RomLoadException

/**
 * Cartouche Game Boy Advance : conserve les octets de la ROM et son en-tête, et
 * fournit l'accès en lecture attendu par le bus.
 *
 * La ROM est mappée à partir de `0x0800_0000` sur trois zones miroir (états
 * d'attente WS0/WS1/WS2) ; le bus a déjà ramené l'offset dans `0x0..0x01FF_FFFF`
 * (32 Mio). Un accès au-delà de la taille réelle retourne `0` (pas de « open
 * bus » émulé dans le premier lot ; limite documentée).
 *
 * Outre la ROM, la cartouche porte deux composants facultatifs : sa mémoire de
 * sauvegarde (SRAM, Flash ou EEPROM) et son port GPIO, derrière lequel se trouve
 * l'horloge temps réel des jeux qui en embarquent une.
 */
class GbaCartridge private constructor(
    val rom: ByteArray,
    val header: GbaHeader,
    /** Type de mémoire de sauvegarde retenu (détecté ou imposé). */
    val saveType: GbaSaveType,
) {
    /** Mémoire de sauvegarde, ou `null` si la cartouche n'en déclare aucune. */
    val save: GbaSaveMemory? = GbaSaveMemory.create(saveType)

    /**
     * Port GPIO de la cartouche, ou `null` si elle n'en a pas.
     *
     * Il n'est créé que pour les cartouches qui embarquent réellement une horloge
     * temps réel : sans cela, les octets de ROM situés en `0x0800_00C4` seraient
     * détournés au profit d'un composant absent.
     */
    val gpio: GbaGpio? = if (hasRealTimeClock(rom)) GbaGpio() else null

    /** Lit un octet à l'offset ROM (déjà replié par le bus). */
    fun read8(offset: Int): Int =
        if (offset in rom.indices) rom[offset].toInt() and 0xFF else 0

    companion object {
        /** Taille maximale d'une ROM GBA : 32 Mio. */
        const val MAX_ROM_SIZE = 0x0200_0000

        /**
         * Signature de la bibliothèque Seiko livrée avec toutes les cartouches à
         * horloge (`SIIRTC_V001` chez Pokémon Rubis, Saphir et Émeraude).
         *
         * La chercher vaut mieux qu'une liste de codes de jeu : elle couvre les
         * révisions et les traductions sans qu'aucune table n'ait à être tenue à
         * jour, et son absence est une réponse tout aussi sûre.
         */
        private val RTC_SIGNATURE = "SIIRTC_V".toByteArray(Charsets.US_ASCII)

        /** `true` si la ROM embarque la bibliothèque d'horloge temps réel. */
        fun hasRealTimeClock(rom: ByteArray): Boolean {
            val first = RTC_SIGNATURE[0]
            val last = rom.size - RTC_SIGNATURE.size
            var i = 0
            while (i <= last) {
                if (rom[i] == first && matchesSignature(rom, i)) return true
                i++
            }
            return false
        }

        private fun matchesSignature(rom: ByteArray, at: Int): Boolean {
            for (j in 1 until RTC_SIGNATURE.size) {
                if (rom[at + j] != RTC_SIGNATURE[j]) return false
            }
            return true
        }

        /**
         * Crée une cartouche à partir des octets d'une ROM.
         *
         * @param forcedSaveType impose un type de sauvegarde au lieu de la
         *   détection automatique (réglage par jeu).
         * @throws RomLoadException si la ROM est trop courte, trop volumineuse
         *   ou dépourvue du marqueur GBA `0x96`.
         */
        fun create(rom: ByteArray, forcedSaveType: GbaSaveType? = null): GbaCartridge {
            if (rom.size > MAX_ROM_SIZE) {
                throw RomLoadException(
                    "ROM GBA trop volumineuse : ${rom.size} octets (> $MAX_ROM_SIZE)"
                )
            }
            val header = GbaHeader.parse(rom)
            if (!header.fixedMarkerValid) {
                throw RomLoadException(
                    "Marqueur GBA 0x96 absent : ce fichier n'est pas une ROM Game Boy Advance"
                )
            }
            return GbaCartridge(rom, header, forcedSaveType ?: GbaSaveType.detect(rom))
        }
    }
}
