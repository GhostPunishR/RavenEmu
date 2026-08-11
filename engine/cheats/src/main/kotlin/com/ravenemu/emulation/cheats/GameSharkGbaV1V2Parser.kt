package com.ravenemu.emulation.cheats

enum class GameSharkGbaEncoding {
    RAW,
    ENCRYPTED,
}

enum class GameSharkGbaCommandType {
    WRITE_8,
    WRITE_16,
    WRITE_32,
    ROM_PATCH_16,
    IF_EQUAL_16_NEXT,
    IF_EQUAL_16_BLOCK,
    MASTER_HOOK,
    MASTER_GAME_ID,
}

data class GameSharkGbaWords(
    val left: Long,
    val right: Long,
)

data class GameSharkGbaDecodedLine(
    val normalized: String,
    val encoding: GameSharkGbaEncoding,
    val words: GameSharkGbaWords,
    val command: GameSharkGbaCommandType,
    val skippedLineCount: Int = 0,
)

/**
 * GameShark Advance v1/v2 (Action Replay v1/v2 en Europe).
 *
 * Une ligne contient deux mots de 32 bits. Sans préfixe, elle est toujours
 * interprétée comme le bloc chiffré saisi sur l'appareil. La représentation
 * déchiffrée de la spécification exige le préfixe explicite `RAW`, car les deux
 * formes ont la même longueur et ne peuvent pas être distinguées de manière
 * fiable. Les deux formes sont compilées vers les mêmes commandes GameShark.
 */
object GameSharkGbaV1V2Parser : CheatCodeParser {
    override val format: CheatFormat = CheatFormat.GAMESHARK_GBA_V1_V2

    override fun parse(raw: String): CheatCodeParseResult = when (val decoded = decodeLine(raw)) {
        is DecodeResult.Success -> CheatCodeParseResult.Success(
            CheatCode(format, decoded.line.normalized)
        )
        is DecodeResult.Failure -> CheatCodeParseResult.Failure(decoded.error)
    }

    /**
     * Rend un seul programme multiligne afin qu'une condition ne puisse jamais
     * consommer la première ligne du cheat suivant après aplatissement par l'UI.
     */
    override fun parseLines(raw: String): CheatCodeListParseResult {
        val sourceLines = raw.lineSequence()
            .mapIndexedNotNull { index, line ->
                line.takeIf { it.isNotBlank() }?.let { index + 1 to it }
            }
            .toList()
        if (sourceLines.isEmpty()) {
            return failure(1, CheatParseError.EMPTY)
        }
        if (sourceLines.size > MAX_LINES) {
            return failure(MAX_LINES + 1, CheatParseError.WRONG_LENGTH)
        }

        val decoded = ArrayList<Pair<Int, GameSharkGbaDecodedLine>>(sourceLines.size)
        for ((lineNumber, source) in sourceLines) {
            when (val result = decodeLine(source)) {
                is DecodeResult.Success -> decoded += lineNumber to result.line
                is DecodeResult.Failure -> return failure(lineNumber, result.error)
            }
        }
        for ((index, numbered) in decoded.withIndex()) {
            val line = numbered.second
            if (line.skippedLineCount > 0 && index + line.skippedLineCount >= decoded.size) {
                return failure(numbered.first, CheatParseError.INCOMPLETE_COMMAND)
            }
            if (line.skippedLineCount > 0) {
                val targets = decoded.subList(index + 1, index + line.skippedLineCount + 1)
                if (targets.any { (_, target) ->
                        target.command == GameSharkGbaCommandType.ROM_PATCH_16 ||
                            target.command == GameSharkGbaCommandType.MASTER_HOOK ||
                            target.command == GameSharkGbaCommandType.MASTER_GAME_ID
                    }
                ) {
                    return failure(numbered.first, CheatParseError.UNSUPPORTED_BANK_OR_COMMAND)
                }
            }
        }

        return CheatCodeListParseResult.Success(
            listOf(
                CheatCode(
                    format,
                    decoded.joinToString("\n") { it.second.normalized },
                )
            )
        )
    }

    fun decodeLine(raw: String): DecodeResult {
        if (raw.length > MAX_RAW_LINE_LENGTH) {
            return DecodeResult.Failure(CheatParseError.WRONG_LENGTH)
        }
        val trimmed = raw.trim()
        val explicitRaw = trimmed.length > RAW_PREFIX.length &&
            trimmed.startsWith(RAW_PREFIX, ignoreCase = true) &&
            (trimmed[RAW_PREFIX.length].isWhitespace() ||
                trimmed[RAW_PREFIX.length] == RAW_PREFIX_SEPARATOR)
        val payload = if (explicitRaw) {
            trimmed.substring(RAW_PREFIX.length + 1)
        } else {
            trimmed
        }
        val compact = buildString(payload.length) {
            payload.forEach { character ->
                if (!character.isWhitespace()) append(character.uppercaseChar())
            }
        }
        if (compact.isEmpty()) return DecodeResult.Failure(CheatParseError.EMPTY)
        if (compact.length != ENCODED_LENGTH) {
            return DecodeResult.Failure(CheatParseError.WRONG_LENGTH)
        }
        if (compact.any { it !in '0'..'9' && it !in 'A'..'F' }) {
            return DecodeResult.Failure(CheatParseError.NON_HEXADECIMAL_CHARACTER)
        }

        val wordsText = "${compact.substring(0, WORD_LENGTH)} ${compact.substring(WORD_LENGTH)}"
        val normalized = if (explicitRaw) "$RAW_PREFIX $wordsText" else wordsText
        val original = Words(
            compact.substring(0, WORD_LENGTH).toLong(16).toUInt(),
            compact.substring(WORD_LENGTH).toLong(16).toUInt(),
        )
        if (explicitRaw) {
            return when (val rawAttempt = interpret(
                original,
                normalized,
                GameSharkGbaEncoding.RAW,
            )) {
                is Interpretation.Success -> DecodeResult.Success(rawAttempt.line)
                is Interpretation.Failure -> DecodeResult.Failure(rawAttempt.error)
            }
        }

        val decrypted = decryptWords(original)
        val encryptedAttempt = interpret(
            decrypted,
            normalized,
            GameSharkGbaEncoding.ENCRYPTED,
        )
        if (encryptedAttempt is Interpretation.Success) {
            return DecodeResult.Success(encryptedAttempt.line)
        }
        return DecodeResult.Failure((encryptedAttempt as Interpretation.Failure).error)
    }

    /** Vecteur de déchiffrement public, utile aux tests de conformité. */
    fun decrypt(left: Long, right: Long): GameSharkGbaWords {
        val words = decryptWords(Words(left.toUInt(), right.toUInt()))
        return GameSharkGbaWords(words.left.toLong(), words.right.toLong())
    }

    private fun interpret(
        words: Words,
        normalized: String,
        encoding: GameSharkGbaEncoding,
    ): Interpretation {
        val left = words.left
        val right = words.right

        if (left == DEADFACE) {
            return Interpretation.Failure(CheatParseError.UNSUPPORTED_MASTER_CODE)
        }
        if (right == GAME_ID_MARKER) {
            return success(normalized, encoding, words, GameSharkGbaCommandType.MASTER_GAME_ID)
        }

        val type = (left shr 28).toInt()
        val address = left and ADDRESS_MASK
        return when (type) {
            0x0 -> writeCommand(
                normalized,
                encoding,
                words,
                address,
                right,
                width = 1,
                command = GameSharkGbaCommandType.WRITE_8,
            )
            0x1 -> writeCommand(
                normalized,
                encoding,
                words,
                address,
                right,
                width = 2,
                command = GameSharkGbaCommandType.WRITE_16,
            )
            0x2 -> writeCommand(
                normalized,
                encoding,
                words,
                address,
                right,
                width = 4,
                command = GameSharkGbaCommandType.WRITE_32,
            )
            0x3 -> Interpretation.Failure(
                CheatParseError.UNSUPPORTED_BANK_OR_COMMAND,
            )
            0x6 -> romPatch(normalized, encoding, words)
            0x8 -> Interpretation.Failure(
                CheatParseError.UNSUPPORTED_BANK_OR_COMMAND,
            )
            0xD -> conditionalNext(normalized, encoding, words, address)
            0xE -> conditionalBlock(normalized, encoding, words)
            0xF -> masterHook(normalized, encoding, words, address)
            else -> Interpretation.Failure(
                CheatParseError.UNSUPPORTED_BANK_OR_COMMAND,
            )
        }
    }

    private fun writeCommand(
        normalized: String,
        encoding: GameSharkGbaEncoding,
        words: Words,
        address: UInt,
        value: UInt,
        width: Int,
        command: GameSharkGbaCommandType,
    ): Interpretation {
        if ((width == 1 && value > 0xFFu) || (width == 2 && value > 0xFFFFu)) {
            return Interpretation.Failure(CheatParseError.VALUE_WIDTH_INVALID)
        }
        val addressError = writableAddressError(address, width)
        if (addressError != null) return Interpretation.Failure(addressError)
        return success(normalized, encoding, words, command)
    }

    private fun romPatch(
        normalized: String,
        encoding: GameSharkGbaEncoding,
        words: Words,
    ): Interpretation {
        if ((words.left and 0xFF00_0000u) != 0x6000_0000u) {
            return Interpretation.Failure(CheatParseError.UNSUPPORTED_BANK_OR_COMMAND)
        }
        val mode = (words.right shr 28).toInt()
        val reserved = words.right and 0x0FFF_0000u
        if (mode !in 0..1 || reserved != 0u) {
            return Interpretation.Failure(CheatParseError.UNSUPPORTED_BANK_OR_COMMAND)
        }
        return success(normalized, encoding, words, GameSharkGbaCommandType.ROM_PATCH_16)
    }

    private fun conditionalNext(
        normalized: String,
        encoding: GameSharkGbaEncoding,
        words: Words,
        address: UInt,
    ): Interpretation {
        if (words.right > 0xFFFFu) {
            return Interpretation.Failure(CheatParseError.VALUE_WIDTH_INVALID)
        }
        val addressError = readableAddressError(address, 2)
        if (addressError != null) return Interpretation.Failure(addressError)
        return success(
            normalized,
            encoding,
            words,
            GameSharkGbaCommandType.IF_EQUAL_16_NEXT,
            skippedLineCount = 1,
        )
    }

    private fun conditionalBlock(
        normalized: String,
        encoding: GameSharkGbaEncoding,
        words: Words,
    ): Interpretation {
        if ((words.left and 0xFF00_0000u) != 0xE000_0000u ||
            (words.right and 0xF000_0000u) != 0u
        ) {
            return Interpretation.Failure(CheatParseError.UNSUPPORTED_BANK_OR_COMMAND)
        }
        val count = ((words.left shr 16) and 0xFFu).toInt()
        if (count == 0) {
            return Interpretation.Failure(CheatParseError.INCOMPLETE_COMMAND)
        }
        val addressError = readableAddressError(words.right and ADDRESS_MASK, 2)
        if (addressError != null) return Interpretation.Failure(addressError)
        return success(
            normalized,
            encoding,
            words,
            GameSharkGbaCommandType.IF_EQUAL_16_BLOCK,
            skippedLineCount = count,
        )
    }

    private fun masterHook(
        normalized: String,
        encoding: GameSharkGbaEncoding,
        words: Words,
        address: UInt,
    ): Interpretation {
        if (address !in HOOK_START..HOOK_END || words.right !in MASTER_HOOK_VALUES) {
            return Interpretation.Failure(CheatParseError.UNSUPPORTED_MASTER_CODE)
        }
        return success(normalized, encoding, words, GameSharkGbaCommandType.MASTER_HOOK)
    }

    private fun writableAddressError(address: UInt, width: Int): CheatParseError? {
        val region = (address shr 24).toInt()
        val last = address.toLong() + width - 1L
        if (region !in WRITABLE_REGIONS || (last ushr 24).toInt() != region) {
            return CheatParseError.ADDRESS_OUT_OF_RANGE
        }
        if (address.toLong() % width != 0L) return CheatParseError.ADDRESS_MISALIGNED
        return null
    }

    private fun readableAddressError(address: UInt, width: Int): CheatParseError? {
        val region = (address shr 24).toInt()
        val last = address.toLong() + width - 1L
        if (region !in READABLE_REGIONS || (last ushr 24).toInt() != region) {
            return CheatParseError.ADDRESS_OUT_OF_RANGE
        }
        if (address.toLong() % width != 0L) return CheatParseError.ADDRESS_MISALIGNED
        return null
    }

    private fun decryptWords(source: Words): Words {
        var left = source.left
        var right = source.right
        for (round in 32 downTo 1) {
            val sum = DELTA * round.toUInt()
            right -= ((left shl 4) + SEED_2) xor
                (left + sum) xor
                ((left shr 5) + SEED_3)
            left -= ((right shl 4) + SEED_0) xor
                (right + sum) xor
                ((right shr 5) + SEED_1)
        }
        return Words(left, right)
    }

    private fun success(
        normalized: String,
        encoding: GameSharkGbaEncoding,
        words: Words,
        command: GameSharkGbaCommandType,
        skippedLineCount: Int = 0,
    ) = Interpretation.Success(
        GameSharkGbaDecodedLine(
            normalized = normalized,
            encoding = encoding,
            words = GameSharkGbaWords(words.left.toLong(), words.right.toLong()),
            command = command,
            skippedLineCount = skippedLineCount,
        )
    )

    private fun failure(line: Int, error: CheatParseError) =
        CheatCodeListParseResult.Failure(CheatLineFailure(line, error))

    sealed interface DecodeResult {
        data class Success(val line: GameSharkGbaDecodedLine) : DecodeResult
        data class Failure(val error: CheatParseError) : DecodeResult
    }

    private sealed interface Interpretation {
        data class Success(val line: GameSharkGbaDecodedLine) : Interpretation
        data class Failure(val error: CheatParseError) : Interpretation
    }

    private data class Words(val left: UInt, val right: UInt)

    private const val MAX_RAW_LINE_LENGTH = 128
    private const val MAX_LINES = 128
    private const val ENCODED_LENGTH = 16
    private const val WORD_LENGTH = 8
    private const val RAW_PREFIX = "RAW"
    private const val RAW_PREFIX_SEPARATOR = ':'

    private val ADDRESS_MASK = 0x0FFF_FFFFu
    private val DEADFACE = 0xDEAD_FACEu
    private val GAME_ID_MARKER = 0x001D_C0DEu
    private val HOOK_START = 0x0800_0000u
    private val HOOK_END = 0x09FF_FFFFu
    private val MASTER_HOOK_VALUES = setOf(
        0x0000_0001u,
        0x0000_0002u,
        0x0000_0003u,
        0x0000_0101u,
        0x0000_0102u,
        0x0000_0103u,
    )
    private val WRITABLE_REGIONS = setOf(0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0E, 0x0F)
    private val READABLE_REGIONS = (0x02..0x07).toSet()

    private val SEED_0 = 0x09F4_FBBDu
    private val SEED_1 = 0x9681_884Au
    private val SEED_2 = 0x3520_27E9u
    private val SEED_3 = 0xF3DE_E5A7u
    private val DELTA = 0x9E37_79B9u
}
