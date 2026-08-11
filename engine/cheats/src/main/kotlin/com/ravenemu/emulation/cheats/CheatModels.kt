package com.ravenemu.emulation.cheats

/** Formats de cheats connus du frontend, avec identifiant persistant figé. */
enum class CheatFormat(
    val storageId: Int,
    val displayName: String,
) {
    /** Patch RAM GameShark huit chiffres documenté pour Game Boy / Color. */
    GAMESHARK_GB_GBC(0, "GameShark GB/GBC"),

    /** GameShark Advance original, compatible avec les appareils v1/v2. */
    GAMESHARK_GBA_V1_V2(1, "GameShark GBA v1/v2"),
    ;

    companion object {
        fun fromStorageId(storageId: Int): CheatFormat? =
            entries.firstOrNull { it.storageId == storageId }
    }
}

/**
 * Bloc normalisé prêt pour le cœur qui annonce [format]. Les formats à
 * commandes liées peuvent conserver plusieurs lignes dans le même bloc.
 */
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
    ADDRESS_MISALIGNED,
    VALUE_WIDTH_INVALID,
    INCOMPLETE_COMMAND,
    UNSUPPORTED_MASTER_CODE,
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

    /**
     * Parse un cheat complet. Le comportement par défaut conserve le modèle
     * historique GB/GBC où chaque ligne est indépendante. Un format dont les
     * commandes portent sur les lignes suivantes peut redéfinir cette méthode
     * et rendre un seul [CheatCode] multiligne, ce qui préserve les limites de
     * chaque définition jusqu'au cœur natif.
     */
    fun parseLines(raw: String): CheatCodeListParseResult {
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
            when (val result = parse(line)) {
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
}
