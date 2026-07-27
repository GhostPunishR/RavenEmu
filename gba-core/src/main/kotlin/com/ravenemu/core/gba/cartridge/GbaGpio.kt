package com.ravenemu.core.gba.cartridge

/**
 * Port **GPIO** de la cartouche : quatre broches exposées par le connecteur, que
 * le jeu pilote par trois registres logés dans l'espace ROM.
 *
 * | Adresse | Registre | Rôle |
 * |---------|----------|------|
 * | `0x0800_00C4` | données | niveau des quatre broches |
 * | `0x0800_00C6` | direction | 1 = le jeu pilote la broche, 0 = il l'écoute |
 * | `0x0800_00C8` | contrôle | bit 0 : les registres sont-ils relisibles |
 *
 * Ces adresses tombent au milieu de la ROM. Tant que le bit 0 du registre de
 * contrôle est à zéro, le port ne répond pas et les lectures traversent jusqu'aux
 * octets de la cartouche — comportement du matériel, et raison pour laquelle une
 * cartouche sans GPIO se lit sans surprise.
 *
 * Seule l'horloge temps réel [GbaRtc] est câblée derrière ce port. Les autres
 * périphériques qui l'empruntent (capteur solaire de Boktai, gyroscope de
 * WarioWare) ne sont pas pris en charge : ils réclament chacun un composant
 * distinct, et aucune des cartouches visées ici ne les utilise.
 */
class GbaGpio(val rtc: GbaRtc = GbaRtc()) {

    /** Dernier niveau écrit sur les broches par le jeu. */
    var data = 0
        private set

    /** Sens de chaque broche : bit à 1 = pilotée par le jeu. */
    var direction = 0
        private set

    /** `true` si le jeu a autorisé la relecture des registres. */
    var readable = false
        private set

    /** Lecture d'un des trois registres, à l'offset ROM [offset]. */
    fun read16(offset: Int): Int = when (offset) {
        DATA -> rtc.pinState(data, direction)
        DIRECTION -> direction
        CONTROL -> if (readable) 1 else 0
        else -> 0
    }

    /** Écriture d'un des trois registres, à l'offset ROM [offset]. */
    fun write16(offset: Int, value: Int) {
        when (offset) {
            DATA -> {
                data = value and 0xF
                rtc.write(data, direction)
            }
            DIRECTION -> {
                direction = value and 0xF
                rtc.write(data, direction)
            }
            CONTROL -> readable = value and 1 != 0
        }
    }

    fun exportState(): IntArray =
        intArrayOf(data, direction, if (readable) 1 else 0) + rtc.exportState()

    fun importState(state: IntArray) {
        data = state[0]
        direction = state[1]
        readable = state[2] != 0
        rtc.importState(state.copyOfRange(3, state.size))
    }

    fun reset() {
        data = 0
        direction = 0
        readable = false
        rtc.reset()
    }

    companion object {
        const val DATA = 0xC4
        const val DIRECTION = 0xC6
        const val CONTROL = 0xC8

        /** Première et dernière adresse ROM interceptées par le port. */
        const val FIRST_OFFSET = DATA
        const val LAST_OFFSET = CONTROL + 1

        /** Nombre de mots produits par [exportState]. */
        const val STATE_WORDS = 3 + GbaRtc.STATE_WORDS

        /** `true` si [offset] désigne un des trois registres du port. */
        fun covers(offset: Int): Boolean = offset in FIRST_OFFSET..LAST_OFFSET
    }
}
