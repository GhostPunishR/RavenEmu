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

    fun parseLines(format: CheatFormat, raw: String): CheatCodeListParseResult =
        requireNotNull(byFormat[format]) { "Aucun parseur pour ${format.name}" }.parseLines(raw)

    companion object {
        val DEFAULT = CheatParserRegistry(listOf(GameSharkGbParser, GameSharkGbaV1V2Parser))
    }
}
