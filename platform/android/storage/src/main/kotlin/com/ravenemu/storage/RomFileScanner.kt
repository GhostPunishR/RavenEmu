package com.ravenemu.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.ravenemu.romlibrary.FingerprintAccumulator
import com.ravenemu.romlibrary.Fingerprints
import java.io.ByteArrayOutputStream

/** Fichier ROM candidat découvert dans un dossier choisi par l'utilisateur. */
data class ScannedFile(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long,
    /** URI du dossier parent, utilisé pour chercher une pochette voisine. */
    val parentUri: Uri?,
)

/**
 * Parcourt récursivement les dossiers accordés via le Storage Access
 * Framework à la recherche de fichiers ROM. Aucune permission globale de
 * stockage n'est requise : seuls les arbres choisis par l'utilisateur sont
 * lus.
 */
class RomFileScanner(private val context: Context) {

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 8 * 1024
    }

    /**
     * Liste les fichiers dont l'extension figure dans [extensions]
     * (minuscules, sans point) sous chacun des arbres [treeUris].
     */
    fun scan(treeUris: List<Uri>, extensions: Set<String>): List<ScannedFile> {
        val results = mutableListOf<ScannedFile>()
        for (treeUri in treeUris) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: continue
            scanDirectory(root, extensions, results)
        }
        return results
    }

    private fun scanDirectory(
        directory: DocumentFile,
        extensions: Set<String>,
        results: MutableList<ScannedFile>,
    ) {
        for (child in directory.listFiles()) {
            if (child.isDirectory) {
                scanDirectory(child, extensions, results)
                continue
            }
            // Ce qui n'est pas un dossier est un document, et c'est l'extension
            // qui décide de la suite. `isFile` n'est volontairement pas
            // consulté : il rend faux dès que le fournisseur de documents
            // n'annonce **aucun** type pour le fichier, ce qui arrive pour les
            // extensions qu'Android ne connaît pas. Une ROM devenait alors
            // invisible sans être ni dossier ni fichier, et rien ne le signalait.
            val name = child.name ?: continue
            val extension = name.substringAfterLast('.', "").lowercase()
            if (extension in extensions) {
                results += ScannedFile(
                    uri = child.uri,
                    name = name,
                    sizeBytes = child.length(),
                    lastModified = child.lastModified(),
                    parentUri = directory.uri,
                )
            }
        }
    }

    /**
     * Ce qu'une lecture en flux rapporte d'un document : sa tête, sa longueur,
     * et ses empreintes, sans jamais le tenir entier en mémoire.
     */
    data class StreamedRom(
        val header: ByteArray,
        val sizeBytes: Long,
        val fingerprints: Fingerprints,
    ) {
        // `ByteArray` compare par identité : deux têtes identiques seraient
        // déclarées différentes, et l'égalité par défaut d'une classe de données
        // mentirait en silence. Les empreintes, elles, disent déjà tout.
        override fun equals(other: Any?): Boolean =
            other is StreamedRom &&
                sizeBytes == other.sizeBytes &&
                fingerprints == other.fingerprints &&
                header.contentEquals(other.header)

        override fun hashCode(): Int =
            (header.contentHashCode() * 31 + sizeBytes.hashCode()) * 31 + fingerprints.hashCode()
    }

    /**
     * Fait défiler un document pour en tirer sa tête et ses empreintes.
     *
     * Le coût mémoire ne dépend pas de la taille du fichier : un tampon de huit
     * kilooctets et les [headerBytes] premiers octets, rien de plus. C'est ce
     * qui permet d'indexer une cartouche d'un demi-gigaoctet sur un téléphone.
     *
     * Rend `null` pour un document illisible, ou plus long que [maxBytes] : la
     * borne est vérifiée **pendant** la lecture, un fournisseur de documents
     * pouvant annoncer une longueur et en servir une autre.
     */
    fun readStreamed(uri: Uri, headerBytes: Int, maxBytes: Long): StreamedRom? {
        require(headerBytes >= 0) { "En-tête de longueur négative" }
        require(maxBytes >= 0) { "Plafond de lecture négatif" }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            val header = ByteArray(headerBytes)
            val accumulator = FingerprintAccumulator()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (total > maxBytes - count) return@use null
                // La tête se remplit au fil des premiers blocs : elle peut être
                // à cheval sur deux lectures, et rien ne garantit qu'un flux
                // rende huit kilooctets du premier coup.
                if (total < headerBytes) {
                    val wanted = minOf(count.toLong(), headerBytes - total).toInt()
                    buffer.copyInto(header, total.toInt(), 0, wanted)
                }
                accumulator.update(buffer, count)
                total += count.toLong()
            }
            if (total < headerBytes) return@use null
            StreamedRom(header, total, accumulator.finish())
        }
    }

    /**
     * Lit le contenu complet d'un document, avec plafond de taille pour
     * écarter les fichiers aberrants avant analyse.
     */
    fun readAll(uri: Uri, maxBytes: Int): ByteArray? {
        require(maxBytes >= 0) { "Plafond de lecture négatif" }
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                if (total > maxBytes - count) return@use null
                output.write(buffer, 0, count)
                total += count
            }
            output.toByteArray()
        }
    }
}
