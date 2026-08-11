package com.ravenemu.emulation.cheats

/** Formats de cheats connus du frontend, avec identifiant persistant figé. */
enum class CheatFormat(
    val storageId: Int,
    val displayName: String,
) {
    /** Patch RAM GameShark huit chiffres documenté pour Game Boy / Color. */
    GAMESHARK_GB_GBC(0, "GameShark GB/GBC"),
    ;

    companion object {
        fun fromStorageId(storageId: Int): CheatFormat? =
            entries.firstOrNull { it.storageId == storageId }
    }
}

/** Une ligne normalisée, prête à être envoyée au cœur qui annonce [format]. */
data class CheatCode(
    val format: CheatFormat,
    val normalized: String,
)

/**
 * Cheat nommé par le joueur. Plusieurs [codes] forment une seule option dans
 * l'interface et sont activés ou désactivés atomiquement.
 */
data class CheatDefinition(
    val id: String,
    val name: String,
    val codes: List<String>,
    val format: CheatFormat,
    val enabled: Boolean,
)

/** Capability immuable annoncée par un cœur après chargement de la ROM. */
data class CheatSupport(val formats: Set<CheatFormat>) {
    fun supports(format: CheatFormat): Boolean = format in formats
}

/**
 * Capability séparée d'EmulatorCore : un cœur sans cheats ne porte aucune
 * méthode factice. Toute mutation doit être appelée depuis le thread de la
 * session d'émulation.
 */
interface CheatCapableCore {
    val cheatSupport: CheatSupport

    /** Remplace en une opération la liste complète des lignes actives. */
    fun replaceActiveCheats(codes: List<CheatCode>)
}

enum class CheatParseError {
    EMPTY,
    WRONG_LENGTH,
    NON_HEXADECIMAL_CHARACTER,
    UNSUPPORTED_BANK_OR_COMMAND,
    ADDRESS_OUT_OF_RANGE,
}

sealed interface CheatCodeParseResult {
    data class Success(val code: CheatCode) : CheatCodeParseResult
    data class Failure(val error: CheatParseError) : CheatCodeParseResult
}

data class CheatLineFailure(
    val lineNumber: Int,
    val error: CheatParseError,
)

sealed interface CheatCodeListParseResult {
    data class Success(val codes: List<CheatCode>) : CheatCodeListParseResult
    data class Failure(val failure: CheatLineFailure) : CheatCodeListParseResult
}

interface CheatCodeParser {
    val format: CheatFormat
    fun parse(raw: String): CheatCodeParseResult
}
