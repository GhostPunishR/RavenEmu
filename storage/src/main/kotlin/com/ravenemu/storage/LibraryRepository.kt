package com.ravenemu.storage

import android.content.Context
import android.net.Uri
import com.ravenemu.romlibrary.AnalysisResult
import com.ravenemu.romlibrary.GameBoyRomAnalyzer
import com.ravenemu.romlibrary.GbaRomAnalyzer
import com.ravenemu.romlibrary.ReferenceDatabase
import com.ravenemu.romlibrary.RomAnalyzer
import com.ravenemu.romlibrary.RomIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestration de la bibliothèque : balayage des dossiers SAF, analyse des
 * fichiers nouveaux ou modifiés, retrait des fichiers disparus, persistance
 * de l'index. Toute l'E/S s'exécute hors du thread principal.
 *
 * La base de références sert à l'identification des ROM ; elle peut être
 * remplacée à chaud ([setReferenceDatabase]) après un import, puis appliquée
 * à l'index existant sans relire les fichiers ([reclassify]).
 */
class LibraryRepository(
    context: Context,
    referenceDatabase: ReferenceDatabase = ReferenceDatabase.empty(),
) {
    private val scanner = RomFileScanner(context)
    private val indexStore = RomIndexStore(context)

    private var database = referenceDatabase
    private var analyzers: List<RomAnalyzer> = buildAnalyzers(referenceDatabase)

    fun setReferenceDatabase(newDatabase: ReferenceDatabase) {
        database = newDatabase
        analyzers = buildAnalyzers(newDatabase)
    }

    private fun buildAnalyzers(db: ReferenceDatabase): List<RomAnalyzer> =
        listOf(GameBoyRomAnalyzer(db), GbaRomAnalyzer(db))

    fun loadIndex(): RomIndex = indexStore.load()

    /**
     * Recalcule le statut d'identification de chaque entrée selon la base
     * courante, sans relire les fichiers (les empreintes sont déjà connues).
     * Les choix utilisateur (statut forcé, pochette) sont préservés.
     */
    suspend fun reclassify(index: RomIndex): RomIndex = withContext(Dispatchers.IO) {
        val updated = index.copy(
            entries = index.entries.map { entry ->
                entry.copy(status = database.classify(entry.fingerprints, entry.title))
            }
        )
        indexStore.save(updated)
        updated
    }

    /**
     * Actualise l'index à partir des dossiers [romDirUris] et retourne le
     * nouvel index persisté. Les fichiers illisibles ou invalides sont
     * ignorés proprement.
     */
    suspend fun refresh(romDirUris: List<Uri>): RomIndex = withContext(Dispatchers.IO) {
        var index = indexStore.load()
        val extensions = analyzers.flatMap { it.console.romExtensions }.toSet()
        val scanned = scanner.scan(romDirUris, extensions)

        index = index.retainAll(scanned.map { it.uri.toString() }.toSet())

        for (file in scanned) {
            val uriString = file.uri.toString()
            if (!index.needsRefresh(uriString, file.sizeBytes, file.lastModified)) {
                continue
            }
            val analyzer = analyzers.firstOrNull { it.canAnalyze(file.name) } ?: continue
            if (file.sizeBytes > analyzer.maxRomSizeBytes) continue
            val data = try {
                scanner.readAll(file.uri, analyzer.maxRomSizeBytes)
            } catch (_: Exception) {
                null
            } ?: continue
            when (val result = analyzer.analyze(
                uri = uriString,
                fileName = file.name,
                lastModified = file.lastModified,
                data = data,
            )) {
                is AnalysisResult.Success -> {
                    // Conserve les choix utilisateur d'une version précédente.
                    val previous = index.byUri(uriString)
                    index = index.upsert(
                        result.entry.copy(
                            userStatusOverride = previous?.userStatusOverride,
                            coverUri = previous?.coverUri,
                        )
                    )
                }
                is AnalysisResult.Invalid -> Unit // fichier ignoré
            }
        }
        indexStore.save(index)
        index
    }

    /** Met à jour une entrée (pochette choisie, statut forcé…). */
    suspend fun update(index: RomIndex, entry: com.ravenemu.romlibrary.RomEntry): RomIndex =
        withContext(Dispatchers.IO) {
            val updated = index.upsert(entry)
            indexStore.save(updated)
            updated
        }

    /** Vide l'index (paramètre « nettoyage de l'index »). */
    suspend fun clear(): RomIndex = withContext(Dispatchers.IO) {
        indexStore.clear()
        RomIndex()
    }

    /** Lit le contenu d'une ROM indexée pour lancement en émulation. */
    suspend fun readRom(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            // Le fichier indexé a déjà été validé par son analyseur. Le
            // plafond global couvre néanmoins la taille maximale GBA.
            scanner.readAll(uri, analyzers.maxOf { it.maxRomSizeBytes })
        } catch (_: Exception) {
            null
        }
    }
}
