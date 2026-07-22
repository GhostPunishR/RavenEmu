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
 *
 * Au moins une empreinte doit être renseignée. Les formats de bases répandus
 * (No-Intro) fournissent CRC32 + SHA-1 ; d'autres ajoutent le SHA-256. La
 * correspondance retient la plus forte empreinte disponible.
 */
@Serializable
data class ReferenceEntry(
    val title: String,
    val kind: ReferenceKind,
    val sha256: String? = null,
    val sha1: String? = null,
    val crc32: String? = null,
    val sizeBytes: Long? = null,
) {
    init {
        require(sha256 != null || sha1 != null || crc32 != null) {
            "Une entrée de référence doit contenir au moins une empreinte"
        }
    }

    /** Empreintes normalisées (SHA en minuscules, CRC32 en majuscules). */
    fun normalized(): ReferenceEntry = copy(
        sha256 = sha256?.lowercase(),
        sha1 = sha1?.lowercase(),
        crc32 = crc32?.uppercase(),
    )
}

/**
 * Base de références locale. [classify] applique les règles de la
 * spécification : correspondance d'empreinte exigée pour tout statut
 * affirmatif, repli sur « Modifiée ou non reconnue » quand seul le titre
 * d'en-tête est reconnu, « Inconnue » sinon. La recherche compare le
 * SHA-256, puis le SHA-1, puis le CRC32.
 */
class ReferenceDatabase(entries: List<ReferenceEntry>) {

    private val bySha256 = HashMap<String, ReferenceEntry>()
    private val bySha1 = HashMap<String, ReferenceEntry>()
    private val byCrc32 = HashMap<String, ReferenceEntry>()
    private val officialTitles = HashSet<String>()

    init {
        for (raw in entries) {
            val entry = raw.normalized()
            entry.sha256?.let { bySha256.putIfAbsent(it, entry) }
            entry.sha1?.let { bySha1.putIfAbsent(it, entry) }
            entry.crc32?.let { byCrc32.putIfAbsent(it, entry) }
            if (entry.kind == ReferenceKind.OFFICIAL) {
                officialTitles.add(entry.title.trim().uppercase())
            }
        }
    }

    /** Nombre d'entrées distinctes (par empreinte la plus forte). */
    val size: Int = entries.map { it.normalized() }
        .map { it.sha256 ?: it.sha1 ?: it.crc32 }
        .toSet().size

    /** Vrai si la base ne contient aucune référence. */
    val isEmpty: Boolean get() = bySha256.isEmpty() && bySha1.isEmpty() && byCrc32.isEmpty()

    fun find(fingerprints: Fingerprints): ReferenceEntry? =
        bySha256[fingerprints.sha256.lowercase()]
            ?: bySha1[fingerprints.sha1.lowercase()]
            ?: byCrc32[fingerprints.crc32.uppercase()]

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
