package com.ravenemu.emulation.cheats

/** Point unique de résolution des parseurs, extensible sans logique Android. */
class CheatParserRegistry(parsers: Iterable<CheatCodeParser>) {
    private val parserList = parsers.toList()
    private val byFormat = parserList.associateBy(CheatCodeParser::format)

    init {
        require(byFormat.size == parserList.size) {
            "Plusieurs parseurs déclarent le même format"
        }
    }

    fun parse(format: CheatFormat, raw: String): CheatCodeParseResult =
        requireNotNull(byFormat[format]) { "Aucun parseur pour ${format.name}" }.parse(raw)

    /** Une ligne non vide de l'éditeur correspond à un code du même cheat. */
    fun parseLines(format: CheatFormat, raw: String): CheatCodeListParseResult {
        val lines = raw.lineSequence()
            .mapIndexedNotNull { index, line ->
                line.takeIf { it.isNotBlank() }?.let { index + 1 to it }
            }
            .toList()
        if (lines.isEmpty()) {
            return CheatCodeListParseResult.Failure(
                CheatLineFailure(1, CheatParseError.EMPTY)
            )
        }
        val parsed = ArrayList<CheatCode>(lines.size)
        for ((lineNumber, line) in lines) {
            when (val result = parse(format, line)) {
                is CheatCodeParseResult.Success -> parsed += result.code
                is CheatCodeParseResult.Failure -> {
                    return CheatCodeListParseResult.Failure(
                        CheatLineFailure(lineNumber, result.error)
                    )
                }
            }
        }
        return CheatCodeListParseResult.Success(parsed)
    }

    companion object {
        val DEFAULT = CheatParserRegistry(listOf(GameSharkGbParser))
    }
}
