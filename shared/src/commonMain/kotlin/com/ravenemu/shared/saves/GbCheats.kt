package com.ravenemu.shared.saves

/**
 * One cheat of a game's list, as the player entered it. [code] holds one or
 * more lines of the code on one line, separated by spaces: RavenCore reads
 * spaces, line breaks and '+' alike between them.
 */
data class GbCheat(val name: String, val code: String, val enabled: Boolean = true)

/**
 * A game's cheats, as RavenEmu PC writes them beside its saves: a line per
 * cheat, "1<TAB>name<TAB>code" (0 when it is off). The same file reads on
 * both.
 */
object GbCheatList {
    /** Line breaks, tabs and runs of spaces become one space; the ends are trimmed. */
    fun oneLine(text: String): String = text.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.joinToString(" ")

    /** Unreadable lines are skipped, and a cheat without code. */
    fun parse(text: String): List<GbCheat> = text.lineSequence().mapNotNull { raw ->
        val line = raw.removeSuffix("\r")
        val first = line.indexOf('\t')
        val second = if (first < 0) -1 else line.indexOf('\t', first + 1)
        if (second < 0) return@mapNotNull null
        val code = oneLine(line.substring(second + 1))
        if (code.isEmpty()) null else GbCheat(line.substring(first + 1, second), code, line.substring(0, first) == "1")
    }.toList()

    /** A name holds no tab or line break: they would split its line. */
    fun format(cheats: List<GbCheat>): String = buildString {
        for (cheat in cheats) {
            val name = cheat.name.map { if (it == '\t' || it == '\n' || it == '\r') ' ' else it }.joinToString("")
            append(if (cheat.enabled) '1' else '0').append('\t').append(name).append('\t')
                .append(oneLine(cheat.code)).append('\n')
        }
    }

    /** The codes RavenCore runs: the enabled ones, in order. */
    fun active(cheats: List<GbCheat>): List<String> = cheats.filter { it.enabled }.map { it.code }
}
