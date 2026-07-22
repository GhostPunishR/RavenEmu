package com.ravenemu.storage

import android.content.Context
import android.net.Uri
import com.ravenemu.romlibrary.NoIntroDatParser
import com.ravenemu.romlibrary.ReferenceDatabase
import com.ravenemu.romlibrary.ReferenceDataset
import com.ravenemu.romlibrary.ReferenceImportException
import com.ravenemu.romlibrary.ReferenceKind
import java.io.File

/** Résultat d'un import de base d'empreintes. */
sealed class ImportResult {
    data class Success(val entryCount: Int) : ImportResult()
    data class Failure(val reason: String) : ImportResult()
}

/**
 * Base d'empreintes de référence locale : fusionne un jeu de données embarqué
 * (`assets/references.json`, semence facultative) avec les bases importées par
 * l'utilisateur (fichiers DAT No-Intro ou datasets JSON) stockées dans
 * l'espace privé. La base ne contient que des métadonnées et des empreintes,
 * jamais de ROM.
 */
class ReferenceDatabaseStore(private val context: Context) {

    private fun importDir(): File =
        File(context.filesDir, "references").apply { mkdirs() }

    /** Construit la base à partir de la semence embarquée et des imports. */
    fun load(): ReferenceDatabase {
        val datasets = buildList {
            bundledSeed()?.let(::add)
            importDir().listFiles { f -> f.extension == "json" }?.sortedBy { it.name }?.forEach { file ->
                ReferenceDataset.fromJson(runCatching { file.readText() }.getOrDefault(""))
                    ?.let(::add)
            }
        }
        return ReferenceDataset.merge(datasets)
    }

    private fun bundledSeed(): ReferenceDataset? = try {
        context.assets.open("references.json").bufferedReader().use {
            ReferenceDataset.fromJson(it.readText())
        }
    } catch (_: Exception) {
        null // aucune semence embarquée : la base démarre vide
    }

    /**
     * Importe une base depuis un document choisi par l'utilisateur. Détecte le
     * format (DAT XML No-Intro ou dataset JSON) et enregistre un dataset
     * normalisé dans l'espace privé.
     */
    fun import(uri: Uri, kind: ReferenceKind = ReferenceKind.OFFICIAL): ImportResult {
        val content = try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                if (bytes.size > MAX_IMPORT_SIZE) {
                    return ImportResult.Failure("Fichier trop volumineux")
                }
                String(bytes, Charsets.UTF_8)
            } ?: return ImportResult.Failure("Fichier illisible")
        } catch (_: Exception) {
            return ImportResult.Failure("Fichier illisible")
        }

        val dataset = try {
            parseContent(content, kind)
        } catch (e: ReferenceImportException) {
            return ImportResult.Failure(e.message ?: "Format non reconnu")
        }
        if (dataset.entries.isEmpty()) {
            return ImportResult.Failure("Aucune entrée exploitable")
        }

        return try {
            val target = File(importDir(), "import-${System.currentTimeMillis()}.json")
            target.writeText(dataset.toJson())
            ImportResult.Success(dataset.entries.size)
        } catch (_: Exception) {
            ImportResult.Failure("Enregistrement impossible")
        }
    }

    private fun parseContent(content: String, kind: ReferenceKind): ReferenceDataset {
        val trimmed = content.trimStart()
        return if (trimmed.startsWith("<")) {
            ReferenceDataset(source = "import DAT", entries = NoIntroDatParser.parse(content, kind))
        } else {
            ReferenceDataset.fromJson(content)
                ?: throw ReferenceImportException("JSON de dataset invalide")
        }
    }

    /** Supprime toutes les bases importées (la semence embarquée subsiste). */
    fun clearImports() {
        importDir().listFiles()?.forEach { it.delete() }
    }

    companion object {
        /** 32 Mio : un DAT complet reste bien en deçà. */
        const val MAX_IMPORT_SIZE = 32 * 1024 * 1024
    }
}
