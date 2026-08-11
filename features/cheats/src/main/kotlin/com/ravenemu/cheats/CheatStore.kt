package com.ravenemu.cheats

import com.ravenemu.emulation.cheats.CheatCodeListParseResult
import com.ravenemu.emulation.cheats.CheatDefinition
import com.ravenemu.emulation.cheats.CheatFormat
import com.ravenemu.emulation.cheats.CheatParserRegistry
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persistance privée des cheats, un document JSON par SHA-256 de ROM.
 *
 * Le dépôt ne dépend pas d'Android : l'application lui remet son répertoire
 * privé `files/cheats`, et les tests utilisent un dossier temporaire.
 */
class CheatStore(
    private val root: File,
    private val parsers: CheatParserRegistry = CheatParserRegistry.DEFAULT,
) {

    fun load(romSha256: String): List<CheatDefinition> {
        val file = fileFor(romSha256)
        if (!file.isFile || file.length() > MAX_FILE_SIZE_BYTES) return emptyList()
        return try {
            val document = json.decodeFromString<Document>(file.readText())
            if (document.version != DOCUMENT_VERSION || document.cheats.size > MAX_CHEATS) {
                return emptyList()
            }
            val definitions = document.cheats.mapNotNull(::decode)
            if (definitions.size != document.cheats.size) return emptyList()
            if (definitions.map(CheatDefinition::id).toSet().size != definitions.size) {
                return emptyList()
            }
            definitions
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Écriture atomique : l'ancien fichier reste intact si le remplacement échoue. */
    fun save(romSha256: String, cheats: List<CheatDefinition>): Boolean {
        if (cheats.size > MAX_CHEATS) return false
        val normalized = cheats.mapNotNull(::normalize)
        if (normalized.size != cheats.size) return false
        if (normalized.map(CheatDefinition::id).toSet().size != normalized.size) return false

        val destination = fileFor(romSha256)
        if (!root.isDirectory && !root.mkdirs()) return false
        val temporary = File(root, ".${destination.name}.tmp")
        return try {
            val document = Document(
                version = DOCUMENT_VERSION,
                cheats = normalized.map(::encode),
            )
            temporary.writeText(json.encodeToString(document))
            try {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(
                    temporary.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            true
        } catch (_: Exception) {
            temporary.delete()
            false
        }
    }

    private fun fileFor(romSha256: String): File {
        require(SHA256.matches(romSha256)) { "Empreinte SHA-256 de ROM invalide" }
        return File(root, "${romSha256.lowercase()}.json")
    }

    private fun decode(stored: StoredCheat): CheatDefinition? {
        val format = CheatFormat.fromStorageId(stored.format) ?: return null
        return normalize(
            CheatDefinition(
                id = stored.id,
                name = stored.name,
                codes = stored.codes,
                format = format,
                enabled = stored.enabled,
            )
        )
    }

    private fun normalize(definition: CheatDefinition): CheatDefinition? {
        val id = definition.id.trim()
        val name = definition.name.trim()
        if (id.isEmpty() || id.length > MAX_ID_LENGTH) return null
        if (name.isEmpty() || name.length > MAX_NAME_LENGTH) return null
        if (definition.codes.isEmpty() || definition.codes.size > MAX_CODES_PER_CHEAT) return null
        val parsed = parsers.parseLines(definition.format, definition.codes.joinToString("\n"))
        val codes = (parsed as? CheatCodeListParseResult.Success)
            ?.codes
            ?.map { it.normalized }
            ?: return null
        return definition.copy(id = id, name = name, codes = codes)
    }

    private fun encode(definition: CheatDefinition): StoredCheat = StoredCheat(
        id = definition.id,
        name = definition.name,
        codes = definition.codes,
        format = definition.format.storageId,
        enabled = definition.enabled,
    )

    @Serializable
    private data class Document(
        val version: Int,
        val cheats: List<StoredCheat>,
    )

    @Serializable
    private data class StoredCheat(
        val id: String,
        val name: String,
        val codes: List<String>,
        val format: Int,
        val enabled: Boolean,
    )

    companion object {
        private const val DOCUMENT_VERSION = 1
        private const val MAX_FILE_SIZE_BYTES = 1024L * 1024L
        private const val MAX_CHEATS = 512
        private const val MAX_CODES_PER_CHEAT = 128
        private const val MAX_ID_LENGTH = 128
        private const val MAX_NAME_LENGTH = 200
        private val SHA256 = Regex("[0-9a-fA-F]{64}")
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            prettyPrint = true
        }
    }
}
