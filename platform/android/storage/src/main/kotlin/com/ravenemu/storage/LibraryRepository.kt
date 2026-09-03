package com.ravenemu.storage

import android.content.Context
import android.net.Uri
import com.ravenemu.romlibrary.AnalysisResult
import com.ravenemu.romlibrary.LibraryRefresh
import com.ravenemu.romlibrary.RejectedRom
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
     * Actualise l'index à partir des dossiers [romDirUris].
     *
     * Rapporte l'index persisté **et** les fichiers écartés en chemin. Ces
     * refus étaient auparavant silencieux : un fichier rangé au bon endroit,
     * avec la bonne extension, disparaissait de la bibliothèque sans que rien
     * ne dise lequel ni pourquoi. Trois chemins l'écartaient, et chacun a
     * maintenant sa raison.
     */
    suspend fun refresh(romDirUris: List<Uri>): LibraryRefresh = withContext(Dispatchers.IO) {
        var index = indexStore.load()
        val extensions = analyzers.flatMap { it.console.romExtensions }.toSet()
        val scanned = scanner.scan(romDirUris, extensions)
        val rejected = mutableListOf<RejectedRom>()

        index = index.retainAll(scanned.map { it.uri.toString() }.toSet())

        for (file in scanned) {
            val uriString = file.uri.toString()
            if (!index.needsRefresh(uriString, file.sizeBytes, file.lastModified)) {
                continue
            }
            // L'extension du fichier a mené jusqu'ici, donc un analyseur existe.
            // S'il manque, c'est un défaut de câblage et non un fichier fautif.
            val analyzer = analyzers.firstOrNull { it.canAnalyze(file.name) } ?: continue
            if (file.sizeBytes > analyzer.maxIndexableBytes) {
                rejected += RejectedRom(
                    file.name,
                    "trop volumineuse pour être indexée : " +
                        "${file.sizeBytes / (1024L * 1024L)} Mio, " +
                        "maximum ${analyzer.maxIndexableBytes / (1024L * 1024L)} Mio",
                )
                continue
            }
            val analysis = try {
                analyse(analyzer, file, uriString)
            } catch (_: Exception) {
                null
            }
            if (analysis == null) {
                rejected += RejectedRom(file.name, "fichier illisible")
                continue
            }
            when (val result = analysis) {
                is AnalysisResult.Success -> {
                    // Conserve les choix utilisateur d'une version précédente.
                    val previous = index.byUri(uriString)
                    index = index.upsert(
                        result.entry.copy(
                            coverUri = previous?.coverUri,
                        )
                    )
                }
                is AnalysisResult.Invalid -> rejected += RejectedRom(file.name, result.reason)
            }
        }
        indexStore.save(index)
        LibraryRefresh(index, rejected)
    }

    /**
     * Analyse un fichier par le chemin que son analyseur réclame.
     *
     * Un analyseur qui se contente de l'en-tête fait défiler le fichier sans
     * jamais le tenir entier en mémoire ; les autres reçoivent l'image, parce
     * qu'ils y cherchent des choses qui peuvent être n'importe où — la
     * signature de sauvegarde d'une cartouche Game Boy Advance, par exemple.
     *
     * Rend `null` quand le document est illisible ou déborde son plafond.
     */
    private companion object {
        /**
         * Ce qu'une lecture en mémoire Java s'autorise, 128 Mio.
         *
         * Le tas d'une application Android est plafonné bien en dessous de la
         * mémoire de l'appareil, et un tableau plus grand ne rend pas une
         * erreur : il lève une `OutOfMemoryError`, qui n'est pas une exception
         * et traverse les rattrapages ordinaires.
         */
        const val JAVA_HEAP_CEILING = 0x0800_0000
    }

    private fun analyse(
        analyzer: RomAnalyzer,
        file: ScannedFile,
        uriString: String,
    ): AnalysisResult? {
        if (analyzer.headerBytes > 0) {
            val streamed = scanner.readStreamed(
                file.uri,
                analyzer.headerBytes,
                analyzer.maxIndexableBytes,
            ) ?: return null
            return analyzer.analyzeHeader(
                uri = uriString,
                fileName = file.name,
                lastModified = file.lastModified,
                header = streamed.header,
                sizeBytes = streamed.sizeBytes,
                fingerprints = streamed.fingerprints,
            )
        }
        val data = scanner.readAll(file.uri, analyzer.maxRomSizeBytes) ?: return null
        return analyzer.analyze(
            uri = uriString,
            fileName = file.name,
            lastModified = file.lastModified,
            data = data,
        )
    }

    /** Met à jour une entrée (pochette choisie, statut forcé…). */
    suspend fun update(index: RomIndex, entry: com.ravenemu.romlibrary.RomEntry): RomIndex =
        withContext(Dispatchers.IO) {
            val updated = index.upsert(entry)
            indexStore.save(updated)
            updated
        }

    /**
     * Retire une entrée de l'index.
     *
     * Seul l'index est modifié : le fichier de ROM, la sauvegarde de cartouche
     * et les états instantanés restent sur le disque. Un nouveau balayage
     * retrouvera l'entrée — c'est un masquage, pas une suppression, et
     * l'interface doit le dire.
     */
    suspend fun remove(index: RomIndex, uri: String): RomIndex =
        withContext(Dispatchers.IO) {
            val updated = index.remove(uri)
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
            // Le fichier indexé a déjà été validé par son analyseur. Le plafond
            // appliqué ici n'est pas celui des moteurs : c'est ce que le tas
            // Java peut tenir. Les cartouches qui le dépassent passent par
            // `loadRomFromDescriptor`, qui ne l'emprunte pas ; ce chemin-ci
            // reste celui des consoles dont les cartouches sont petites, et
            // lui laisser un demi-gigaoctet ne ferait qu'échanger un refus
            // clair contre un manque de mémoire.
            scanner.readAll(uri, minOf(analyzers.maxOf { it.maxRomSizeBytes }, JAVA_HEAP_CEILING))
        } catch (_: Exception) {
            null
        }
    }
}
