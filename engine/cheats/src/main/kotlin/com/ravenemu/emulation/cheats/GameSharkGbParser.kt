package com.ravenemu.emulation.cheats

/** Opération décodée du format RAM GameShark GB/GBC. */
data class GameSharkGbRamWrite(
    val externalRamBank: Int,
    val value: Int,
    val address: Int,
    val normalized: String,
)

/**
 * Parseur original RavenEmu du format `AB CD EF GH`, où l'adresse est `GHEF`.
 * La plage et la banque sont volontairement identiques aux contrôles natifs.
 */
object GameSharkGbParser : CheatCodeParser {
    override val format: CheatFormat = CheatFormat.GAMESHARK_GB_GBC

    override fun parse(raw: String): CheatCodeParseResult = when (val decoded = decode(raw)) {
        is DecodeResult.Success -> CheatCodeParseResult.Success(
            CheatCode(format, decoded.write.normalized)
        )
        is DecodeResult.Failure -> CheatCodeParseResult.Failure(decoded.error)
    }

    fun decode(raw: String): DecodeResult {
        if (raw.length > MAX_RAW_LENGTH) {
            return DecodeResult.Failure(CheatParseError.WRONG_LENGTH)
        }
        val normalized = buildString(raw.length) {
            for (character in raw) {
                if (character == '-' || character.isWhitespace()) continue
                append(character.uppercaseChar())
            }
        }
        if (normalized.isEmpty()) return DecodeResult.Failure(CheatParseError.EMPTY)
        if (normalized.length != CODE_LENGTH) {
            return DecodeResult.Failure(CheatParseError.WRONG_LENGTH)
        }
        if (normalized.any { it.digitToIntOrNull(16) == null }) {
            return DecodeResult.Failure(CheatParseError.NON_HEXADECIMAL_CHARACTER)
        }

        val bank = normalized.substring(0, 2).toInt(16)
        if (bank !in MIN_EXTERNAL_RAM_BANK..MAX_EXTERNAL_RAM_BANK) {
            return DecodeResult.Failure(CheatParseError.UNSUPPORTED_BANK_OR_COMMAND)
        }
        val value = normalized.substring(2, 4).toInt(16)
        val address = normalized.substring(6, 8).toInt(16) shl 8 or
            normalized.substring(4, 6).toInt(16)
        if (address !in MIN_ADDRESS..MAX_ADDRESS) {
            return DecodeResult.Failure(CheatParseError.ADDRESS_OUT_OF_RANGE)
        }
        return DecodeResult.Success(
            GameSharkGbRamWrite(
                externalRamBank = bank,
                value = value,
                address = address,
                normalized = normalized,
            )
        )
    }

    sealed interface DecodeResult {
        data class Success(val write: GameSharkGbRamWrite) : DecodeResult
        data class Failure(val error: CheatParseError) : DecodeResult
    }

    const val CODE_LENGTH = 8
    const val MAX_RAW_LENGTH = 64
    const val MIN_EXTERNAL_RAM_BANK = 0x00
    const val MAX_EXTERNAL_RAM_BANK = 0x0F
    const val MIN_ADDRESS = 0xA000
    const val MAX_ADDRESS = 0xDFFF
}
