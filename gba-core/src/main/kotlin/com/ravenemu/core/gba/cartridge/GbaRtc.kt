package com.ravenemu.core.gba.cartridge

import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Horloge temps réel Seiko **S-3511A**, logée dans certaines cartouches Game Boy
 * Advance (Pokémon Rubis, Saphir, Émeraude, la série Boktai…).
 *
 * Sans elle, ces jeux annoncent « la pile interne est épuisée » et désactivent
 * tout ce qui dépend du temps : cycle jour/nuit, pousse des baies, marées.
 *
 * ### Liaison
 * Le composant n'est pas mappé en mémoire : il se pilote **bit à bit** par trois
 * broches du port GPIO de la cartouche ([GbaGpio]).
 *
 * | Broche | Bit | Rôle |
 * |--------|-----|------|
 * | `SCK`  | 0   | horloge, cadencée par le jeu |
 * | `SIO`  | 1   | donnée, bidirectionnelle |
 * | `CS`   | 2   | sélection : le dialogue n'a lieu que pendant qu'elle est haute |
 *
 * Une transaction commence au front montant de `CS`, transporte un **octet de
 * commande** (poids fort en tête), puis les octets de données. Le composant
 * échantillonne l'entrée sur les **fronts montants** de `SCK` et présente sa
 * sortie sur les **fronts descendants**, de sorte que la donnée soit stable
 * quand le jeu relit la broche, horloge haute.
 *
 * ### Octet de commande
 * ```
 * bits 7-4 : 0110b, motif fixe
 * bits 3-1 : commande
 * bit  0   : 0 = écriture, 1 = lecture
 * ```
 * Les bibliothèques ne s'accordent pas sur le sens d'émission ; lorsque le motif
 * fixe n'apparaît pas, l'octet est relu à l'envers avant d'être rejeté.
 *
 * ### Limites documentées
 * - Les alarmes et les modes d'essai sont acceptés puis ignorés : aucun jeu
 *   connu ne s'en sert, et le composant reste silencieux, ce qui est le
 *   comportement observable attendu.
 * - Le format 12 heures n'est pas proposé : le registre d'état annonce le format
 *   24 heures, celui que réclament les jeux concernés.
 */
class GbaRtc(
    /**
     * Source de l'heure. Remplaçable pour figer le temps dans un test, ou pour
     * brancher une autre horloge que celle du système.
     */
    var clock: () -> LocalDateTime = { LocalDateTime.now() },
) {

    // --- Broches ---
    private var sck = 0
    private var sio = 0
    private var chipSelect = 0

    /** Niveau imposé à `SIO` par le composant quand c'est lui qui parle. */
    private var sioOut = 0

    // --- Transaction en cours ---
    private var phase = PHASE_IDLE
    private var shift = 0
    private var bitIndex = 0
    private var command = 0
    private var byteIndex = 0
    private var byteCount = 0

    /** Octets présentés au jeu (lecture) ou reçus de lui (écriture). */
    private val transfer = IntArray(7)

    /**
     * Registre d'état. Bit 6 = format 24 heures, bit 7 = coupure d'alimentation.
     *
     * C'est **ce bit 7 laissé à zéro** qui répond « la pile est bonne » : un jeu
     * qui le trouve à un considère l'horloge comme jamais réglée.
     */
    var status = STATUS_24_HOUR
        private set

    /**
     * Écart, en secondes, entre l'heure de l'hôte et celle que voit le jeu.
     *
     * L'horloge suit la machine plutôt que de compter ses propres cycles : c'est
     * ce qu'attend un joueur qui retrouve sa partie le lendemain. Régler l'heure
     * dans le jeu ne fait donc que déplacer cet écart.
     */
    var offsetSeconds: Long = 0
        private set

    /**
     * Niveau des quatre broches vu par le jeu : celles qu'il pilote lui renvoient
     * ce qu'il a écrit, les autres ce que le composant impose.
     */
    fun pinState(data: Int, direction: Int): Int {
        var result = data and direction
        if (direction and SIO_MASK == 0) result = result or (sioOut shl 1)
        return result and 0xF
    }

    /**
     * Applique l'état des broches écrit par le jeu. Seuls les bits déclarés en
     * **sortie** dans [direction] agissent ; les autres sont laissés au
     * composant.
     */
    fun write(data: Int, direction: Int) {
        val newSck = pin(data, direction, 0, sck)
        val newSio = pin(data, direction, 1, sio)
        val newCs = pin(data, direction, 2, chipSelect)

        when {
            // Composant désélectionné : toute transaction en cours est perdue.
            newCs == 0 -> phase = PHASE_IDLE
            // Front montant de CS : une transaction commence par sa commande.
            chipSelect == 0 -> {
                phase = PHASE_COMMAND
                shift = 0
                bitIndex = 0
            }
            sck == 0 && newSck == 1 -> onClockRise(newSio)
            sck == 1 && newSck == 0 -> onClockFall()
        }

        sck = newSck
        sio = newSio
        chipSelect = newCs
    }

    private fun pin(data: Int, direction: Int, bit: Int, previous: Int): Int =
        if (direction and (1 shl bit) != 0) (data ushr bit) and 1 else previous

    /** Front montant : le composant échantillonne ce que le jeu présente. */
    private fun onClockRise(bit: Int) {
        when (phase) {
            PHASE_COMMAND -> {
                // Poids fort en tête.
                shift = (shift shl 1) or bit
                if (++bitIndex == 8) decodeCommand()
            }
            PHASE_WRITING -> {
                // Octets de données : poids faible en tête.
                shift = shift or (bit shl bitIndex)
                if (++bitIndex == 8) {
                    transfer[byteIndex] = shift and 0xFF
                    shift = 0
                    bitIndex = 0
                    if (++byteIndex == byteCount) {
                        applyWrittenBytes()
                        phase = PHASE_IDLE
                    }
                }
            }
        }
    }

    /** Front descendant : le composant présente le bit suivant, poids faible en tête. */
    private fun onClockFall() {
        if (phase != PHASE_READING) return
        sioOut = (transfer[byteIndex] ushr bitIndex) and 1
        if (++bitIndex == 8) {
            bitIndex = 0
            if (++byteIndex >= byteCount) phase = PHASE_IDLE
        }
    }

    private fun decodeCommand() {
        var byte = shift and 0xFF
        // Motif fixe absent : la bibliothèque du jeu émet à l'envers.
        if (byte and 0xF0 != COMMAND_MARKER) byte = reverseBits(byte)
        command = (byte ushr 1) and 0x7
        val reading = byte and 1 != 0

        shift = 0
        bitIndex = 0
        byteIndex = 0
        transfer.fill(0)

        when (command) {
            CMD_RESET -> {
                status = STATUS_24_HOUR
                offsetSeconds = 0
                phase = PHASE_IDLE
            }
            CMD_STATUS -> {
                byteCount = 1
                if (reading) transfer[0] = status
                phase = if (reading) PHASE_READING else PHASE_WRITING
            }
            CMD_DATE_TIME -> {
                byteCount = 7
                if (reading) loadDateTime()
                phase = if (reading) PHASE_READING else PHASE_WRITING
            }
            CMD_TIME -> {
                byteCount = 3
                if (reading) loadTime()
                phase = if (reading) PHASE_READING else PHASE_WRITING
            }
            // Alarmes et modes d'essai : le composant reste muet (limite documentée).
            else -> phase = PHASE_IDLE
        }
    }

    /** Heure vue par le jeu : celle de l'hôte, décalée de [offsetSeconds]. */
    private fun gameTime(): LocalDateTime = clock().plusSeconds(offsetSeconds)

    private fun loadDateTime() {
        val t = gameTime()
        transfer[0] = toBcd(t.year % 100)
        transfer[1] = toBcd(t.monthValue)
        transfer[2] = toBcd(t.dayOfMonth)
        // Le composant numérote les jours de 0 à 6 ; `DayOfWeek` va de 1 (lundi)
        // à 7 (dimanche).
        transfer[3] = t.dayOfWeek.value % 7
        loadTimeInto(t, 4)
    }

    private fun loadTime() {
        loadTimeInto(gameTime(), 0)
    }

    /**
     * En format 24 heures, l'heure est un simple BCD de 0 à 23 : le bit 7 sert
     * d'indicateur après-midi et n'a de sens qu'en format 12 heures, que le
     * registre d'état n'annonce pas.
     */
    private fun loadTimeInto(t: LocalDateTime, at: Int) {
        transfer[at] = toBcd(t.hour)
        transfer[at + 1] = toBcd(t.minute)
        transfer[at + 2] = toBcd(t.second)
    }

    /** Le jeu vient de régler l'horloge : l'écart est recalculé en conséquence. */
    private fun applyWrittenBytes() {
        when (command) {
            CMD_STATUS -> {
                // Le format 24 heures est imposé, et l'indicateur de coupure
                // d'alimentation ne peut être que remis à zéro.
                status = STATUS_24_HOUR or (transfer[0] and WRITABLE_STATUS_BITS)
            }
            CMD_DATE_TIME -> {
                val date = runCatching {
                    LocalDate.of(
                        2000 + fromBcd(transfer[0]),
                        fromBcd(transfer[1]),
                        fromBcd(transfer[2]),
                    )
                }.getOrNull() ?: return
                setGameTime(date, 4)
            }
            CMD_TIME -> setGameTime(gameTime().toLocalDate(), 0)
        }
    }

    private fun setGameTime(date: LocalDate, at: Int) {
        val target = runCatching {
            date.atTime(
                fromBcd(transfer[at] and 0x7F),
                fromBcd(transfer[at + 1]),
                fromBcd(transfer[at + 2]),
            )
        }.getOrNull() ?: return
        offsetSeconds = java.time.Duration.between(clock(), target).seconds
    }

    // --- État instantané ---

    fun exportState(): IntArray = intArrayOf(
        sck, sio, chipSelect, sioOut,
        phase, shift, bitIndex, command, byteIndex, byteCount,
        status,
        (offsetSeconds ushr 32).toInt(), offsetSeconds.toInt(),
        transfer[0], transfer[1], transfer[2], transfer[3],
        transfer[4], transfer[5], transfer[6],
    )

    fun importState(data: IntArray) {
        sck = data[0]; sio = data[1]; chipSelect = data[2]; sioOut = data[3]
        phase = data[4]; shift = data[5]; bitIndex = data[6]
        command = data[7]; byteIndex = data[8]; byteCount = data[9]
        status = data[10]
        offsetSeconds = (data[11].toLong() shl 32) or (data[12].toLong() and 0xFFFF_FFFFL)
        for (i in 0 until 7) transfer[i] = data[13 + i]
    }

    fun reset() {
        sck = 0; sio = 0; chipSelect = 0; sioOut = 0
        phase = PHASE_IDLE
        shift = 0; bitIndex = 0; command = 0; byteIndex = 0; byteCount = 0
        status = STATUS_24_HOUR
        offsetSeconds = 0
        transfer.fill(0)
    }

    companion object {
        /** Nombre de mots produits par [exportState]. */
        const val STATE_WORDS = 20

        private const val PHASE_IDLE = 0
        private const val PHASE_COMMAND = 1
        private const val PHASE_READING = 2
        private const val PHASE_WRITING = 3

        private const val CMD_RESET = 0
        private const val CMD_STATUS = 1
        private const val CMD_DATE_TIME = 2
        private const val CMD_TIME = 3

        /** Motif fixe `0110b` dans les bits 7-4 de l'octet de commande. */
        private const val COMMAND_MARKER = 0x60

        private const val SIO_MASK = 0x2

        /** Format 24 heures : le seul proposé (limite documentée). */
        private const val STATUS_24_HOUR = 0x40

        /** Bits que le jeu peut réellement fixer dans le registre d'état. */
        private const val WRITABLE_STATUS_BITS = 0x0E

        private fun toBcd(value: Int): Int = ((value / 10) shl 4) or (value % 10)

        private fun fromBcd(value: Int): Int = ((value ushr 4) and 0xF) * 10 + (value and 0xF)

        private fun reverseBits(value: Int): Int {
            var result = 0
            for (i in 0 until 8) result = result or (((value ushr i) and 1) shl (7 - i))
            return result
        }
    }
}
