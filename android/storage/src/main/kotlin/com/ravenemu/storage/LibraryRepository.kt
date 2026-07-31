package com.ravenemu.storage

import android.content.Context
import android.net.Uri
import com.ravenemu.romlibrary.AnalysisResult
import com.ravenemu.romlibrary.RomAnalyzer
import com.ravenemu.romlibrary.RomIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestration de la bibliothèque : balayage des dossiers SAF, analyse des
 * fichiers nouveaux ou modifiés, retrait des fichiers disparus, persistance
 * de l'index. Toute l'E/S s'exécute hors du thread principal.
 *
 * L'état d'une ROM se déduit de la cartouche elle-même : aucune base
 * extérieure n'intervient, donc rien à importer ni à tenir à jour.
 */
class LibraryRepository(
    context: Context,
    /**
     * Analyseurs consultés pour chaque fichier balayé.
     *
     * Sans valeur par défaut : les construire ici obligerait ce module à nommer
     * les fournisseurs de console concrets, alors que la racine de composition
     * les connaît déjà. C'est elle qui les remet.
     */
    private val analyzers: List<RomAnalyzer>,
) {

    private val scanner = RomFileScanner(context)
    private val indexStore = RomIndexStore(context)

    fun loadIndex(): RomIndex = indexStore.load()

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
