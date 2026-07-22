package com.ravenemu.romlibrary

import kotlinx.serialization.Serializable

/**
 * Statut d'identification d'une ROM. Une ROM n'est jamais présentée comme
 * officielle sans correspondance d'empreinte dans la base de références.
 */
enum class RomStatus(val displayName: String) {
    /** L'empreinte correspond à une entrée officielle de la base locale. */
    VERIFIED_OFFICIAL("Officielle vérifiée"),

    /** L'empreinte correspond à une entrée répertoriée comme modification. */
    KNOWN_HACK("ROM hack identifié"),

    /** En-tête plausible d'un jeu connu mais empreinte sans correspondance. */
    MODIFIED_OR_UNRECOGNIZED("Modifiée ou non reconnue"),

    /** Aucune correspondance fiable. */
    UNKNOWN("Inconnue"),

    /** Production amateur, identifiée par la base ou déclarée par l'utilisateur. */
    HOMEBREW("Homebrew"),
}

/** Nature d'une entrée de la base de références. */
enum class ReferenceKind { OFFICIAL, HACK, HOMEBREW }

/**
 * Entrée de la base locale d'identification : uniquement des métadonnées et
 * des empreintes — jamais de contenu de ROM.
 */
@Serializable
data class ReferenceEntry(
    val title: String,
    val kind: ReferenceKind,
    /** SHA-256 minuscule ; identifiant principal. */
    val sha256: String,
    val sha1: String? = null,
    val crc32: String? = null,
)

/**
 * Base de références locale. [classify] applique les règles de la
 * spécification : correspondance d'empreinte exigée pour tout statut
 * affirmatif, repli sur « Modifiée ou non reconnue » quand seul le titre
 * d'en-tête est reconnu, « Inconnue » sinon.
 */
class ReferenceDatabase(entries: List<ReferenceEntry>) {

    private val bySha256 = entries.associateBy { it.sha256.lowercase() }
    private val officialTitles = entries
        .filter { it.kind == ReferenceKind.OFFICIAL }
        .map { it.title.uppercase() }
        .toSet()

    val size: Int = bySha256.size

    fun find(fingerprints: Fingerprints): ReferenceEntry? =
        bySha256[fingerprints.sha256.lowercase()]

    fun classify(fingerprints: Fingerprints, headerTitle: String): RomStatus {
        val match = find(fingerprints)
        if (match != null) {
            return when (match.kind) {
                ReferenceKind.OFFICIAL -> RomStatus.VERIFIED_OFFICIAL
                ReferenceKind.HACK -> RomStatus.KNOWN_HACK
                ReferenceKind.HOMEBREW -> RomStatus.HOMEBREW
            }
        }
        val title = headerTitle.trim().uppercase()
        if (title.isNotEmpty() && officialTitles.contains(title)) {
            return RomStatus.MODIFIED_OR_UNRECOGNIZED
        }
        return RomStatus.UNKNOWN
    }

    companion object {
        /** Base vide : tout est « Inconnue » tant que rien n'est référencé. */
        fun empty(): ReferenceDatabase = ReferenceDatabase(emptyList())
    }
}
