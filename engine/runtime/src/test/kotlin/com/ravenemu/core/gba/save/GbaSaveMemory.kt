package com.ravenemu.core.gba.save

/** Mémoire de sauvegarde d'une cartouche : contenu brut et suivi des écritures. */
sealed class GbaSaveMemory(val type: GbaSaveType) {

    /** Contenu brut, sérialisé tel quel dans le fichier `.sav`. */
    val data: ByteArray = ByteArray(type.sizeBytes)

    /**
     * Numéro de génération : incrémenté à chaque écriture du jeu.
     *
     * Il identifie le contenu, ce qu'un simple drapeau ne sait pas faire : si
     * le jeu écrit pendant qu'une sauvegarde est en cours, la génération avance
     * et l'acquittement de l'ancienne est refusé.
     */
    var generation: Long = 0
        private set

    /** Dernière génération dont l'écriture a été confirmée. */
    private var savedGeneration: Long = 0

    /** `true` si la mémoire a changé depuis le dernier acquittement confirmé. */
    val dirty: Boolean get() = generation != savedGeneration

    /** À appeler à chaque écriture du jeu dans la mémoire de sauvegarde. */
    protected fun markWritten() {
        generation++
    }

    /** Remet le compteur au propre : contenu chargé depuis un `.sav` ou un état. */
    protected fun markClean() {
        savedGeneration = generation
    }

    /**
     * Acquitte l'écriture de [generation]. Sans effet si le jeu a écrit
     * depuis : les octets postérieurs à l'instantané doivent encore l'être.
     */
    fun acknowledgeSaved(generation: Long) {
        if (generation == this.generation) savedGeneration = generation
    }

    /** Restaure un contenu `.sav` ; les tailles différentes sont tronquées ou complétées. */
    fun import(saved: ByteArray) {
        val count = minOf(saved.size, data.size)
        saved.copyInto(data, 0, 0, count)
        if (count < data.size) data.fill(0, count, data.size)
        markClean()
    }

    fun export(): ByteArray = data.copyOf()

    /** Mémoire SRAM : accès direct par octet, sans protocole. */
    class Sram : GbaSaveMemory(GbaSaveType.SRAM) {
        fun read(address: Int): Int = data[address and (data.size - 1)].toInt() and 0xFF

        fun write(address: Int, value: Int) {
            data[address and (data.size - 1)] = (value and 0xFF).toByte()
            markWritten()
        }
    }

    /**
     * Mémoire Flash : les écritures passent par des **séquences de commandes**
     * (`0xAA` puis `0x55` sur des adresses fixes, suivies du code d'opération).
     * Les variantes 128 Kio exposent deux bancs de 64 Kio commutables.
     */
    class Flash(type: GbaSaveType) : GbaSaveMemory(type) {

        private var commandStep = 0
        private var idMode = false
        private var eraseArmed = false
        private var writeArmed = false
        private var bankSwitchArmed = false
        private var bank = 0

        init {
            // Une Flash vierge vaut 0xFF (état effacé).
            data.fill(0xFF.toByte())
        }

        private val manufacturerId = if (type == GbaSaveType.FLASH_128K) 0x62 else 0x32
        private val deviceId = if (type == GbaSaveType.FLASH_128K) 0x13 else 0x1B

        fun read(address: Int): Int {
            val offset = address and 0xFFFF
            if (idMode) {
                return when (offset) {
                    0 -> manufacturerId
                    1 -> deviceId
                    else -> 0
                }
            }
            return data[bank * BANK_SIZE + offset].toInt() and 0xFF
        }

        fun write(address: Int, value: Int) {
            val offset = address and 0xFFFF
            val byte = value and 0xFF

            if (writeArmed) {
                data[bank * BANK_SIZE + offset] = byte.toByte()
                markWritten()
                writeArmed = false
                return
            }
            if (bankSwitchArmed) {
                bank = byte and 0x1
                bankSwitchArmed = false
                return
            }
            when {
                commandStep == 0 && offset == 0x5555 && byte == 0xAA -> commandStep = 1
                commandStep == 1 && offset == 0x2AAA && byte == 0x55 -> commandStep = 2
                commandStep == 2 && offset == 0x5555 -> {
                    commandStep = 0
                    when (byte) {
                        0x90 -> idMode = true
                        0xF0 -> idMode = false
                        0x80 -> eraseArmed = true
                        0xA0 -> writeArmed = true
                        0xB0 -> if (this.type == GbaSaveType.FLASH_128K) bankSwitchArmed = true
                        0x10 -> if (eraseArmed) {
                            data.fill(0xFF.toByte())
                            markWritten()
                            eraseArmed = false
                        }
                    }
                }
                // Effacement d'un secteur de 4 Kio : 0x30 sur l'adresse visée.
                commandStep == 2 && byte == 0x30 && eraseArmed -> {
                    commandStep = 0
                    eraseArmed = false
                    val start = bank * BANK_SIZE + (offset and 0xF000)
                    data.fill(0xFF.toByte(), start, start + SECTOR_SIZE)
                    markWritten()
                }
                else -> commandStep = 0
            }
        }

        private companion object {
            const val BANK_SIZE = 64 * 1024
            const val SECTOR_SIZE = 4096
        }
    }

    /**
     * Mémoire EEPROM : protocole **série**, un bit par accès 16 bits, piloté en
     * pratique par DMA. Une requête commence par deux bits d'opération (`11`
     * pour lire, `10` pour écrire), suivis de l'adresse (6 ou 14 bits selon la
     * capacité) puis, en écriture, des 64 bits de données.
     *
     * La largeur d'adresse est déduite de la longueur du transfert DMA
     * ([hintTransferLength]), comme le font les jeux.
     */
    class Eeprom(type: GbaSaveType) : GbaSaveMemory(type) {

        private var addressBits = if (type == GbaSaveType.EEPROM_8K) 14 else 6

        private var state = State.IDLE
        private var bitBuffer = 0L
        private var bitCount = 0
        private var address = 0
        private var readShift = 0L
        private var readBitsSent = 0

        private enum class State { IDLE, COMMAND, WRITING, WRITE_STOP, READING }

        /**
         * Indique la longueur (en unités de 16 bits) du transfert DMA en cours :
         * 9 ou 73 bits désignent une EEPROM à adresse 6 bits, 17 ou 81 bits une
         * adresse 14 bits.
         */
        fun hintTransferLength(words: Int) {
            addressBits = when (words) {
                9, 73 -> 6
                17, 81 -> 14
                else -> return
            }
        }

        /** Lecture d'un bit (bit 0 du mot lu par le jeu). */
        fun read(): Int {
            if (state != State.READING) return 1
            // Quatre bits nuls précèdent les 64 bits de données.
            readBitsSent++
            if (readBitsSent <= 4) return 0
            val bitIndex = 64 - (readBitsSent - 4)
            val bit = ((readShift ushr bitIndex) and 1L).toInt()
            if (readBitsSent - 4 >= 64) {
                state = State.IDLE
                readBitsSent = 0
            }
            return bit
        }

        /** Écriture d'un bit (bit 0 du mot écrit par le jeu). */
        fun write(value: Int) {
            val bit = value and 1
            when (state) {
                State.IDLE -> {
                    bitBuffer = bit.toLong()
                    bitCount = 1
                    state = State.COMMAND
                }
                State.COMMAND -> {
                    bitBuffer = (bitBuffer shl 1) or bit.toLong()
                    bitCount++
                    if (bitCount == 2 + addressBits) beginTransfer()
                }
                State.WRITING -> {
                    bitBuffer = (bitBuffer shl 1) or bit.toLong()
                    bitCount++
                    if (bitCount == 64) {
                        commitWrite()
                        // Un dernier bit clôt la trame : il ne doit pas être pris
                        // pour le début d'une nouvelle commande.
                        state = State.WRITE_STOP
                    }
                }
                State.WRITE_STOP -> state = State.IDLE
                State.READING -> Unit // le bit d'arrêt clôt la requête de lecture
            }
        }

        private fun beginTransfer() {
            val command = (bitBuffer ushr addressBits).toInt() and 0x3
            address = (bitBuffer.toInt() and ((1 shl addressBits) - 1)) * 8
            if (command == 0x3) { // lecture
                readShift = readQuadWord(address)
                readBitsSent = 0
                state = State.READING
            } else {              // écriture
                bitBuffer = 0
                bitCount = 0
                state = State.WRITING
            }
        }

        private fun commitWrite() {
            val base = address and (data.size - 1)
            for (i in 0 until 8) {
                data[base + i] = ((bitBuffer ushr ((7 - i) * 8)) and 0xFF).toByte()
            }
            markWritten()
        }

        private fun readQuadWord(offset: Int): Long {
            val base = offset and (data.size - 1)
            var value = 0L
            for (i in 0 until 8) {
                value = (value shl 8) or (data[base + i].toLong() and 0xFF)
            }
            return value
        }
    }

    companion object {
        /** Crée la mémoire correspondant à [type], ou `null` si la cartouche n'en a pas. */
        fun create(type: GbaSaveType): GbaSaveMemory? = when (type) {
            GbaSaveType.NONE -> null
            GbaSaveType.SRAM -> Sram()
            GbaSaveType.FLASH_64K, GbaSaveType.FLASH_128K -> Flash(type)
            GbaSaveType.EEPROM_512, GbaSaveType.EEPROM_8K -> Eeprom(type)
        }
    }
}
