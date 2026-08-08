package com.ravenemu.app.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import com.ravenemu.romlibrary.RomEntry
import com.ravenemu.storage.CoverResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chargement des pochettes de la bibliothèque : hors du fil principal, avec
 * cache, et à la taille de la vignette.
 *
 * L'adaptateur faisait tout le travail lui-même, à chaque vignette entrant à
 * l'écran et sur le fil qui doit dessiner :
 *
 * - la résolution de la pochette interroge le fournisseur de documents jusqu'à
 *   huit fois par entrée (quatre extensions, dans le dossier de la ROM puis
 *   dans celui des pochettes), chaque appel étant un aller-retour entre
 *   processus ;
 * - le décodage se faisait à la résolution d'origine, soit plusieurs
 *   mégaoctets alloués pour quelques centaines de pixels affichés ;
 * - la jaquette de repli était redessinée à chaque fois.
 *
 * Rien n'était mémorisé : une vignette qui repassait à l'écran refaisait tout.
 * Avec une dizaine de jeux cela ne se voyait pas ; avec une centaine, le
 * défilement faisait des entrées-sorties en continu sur le fil d'affichage.
 *
 * Ici, chaque résultat est mémorisé et le travail se fait en arrière-plan. Les
 * deux caches sont distincts : une pochette absente est une information utile
 * qu'il faut retenir, sans quoi on repaierait les huit requêtes à chaque fois.
 */
class CoverLoader(
    private val context: Context,
    private val resolver: CoverResolver,
    private val scope: CoroutineScope,
    private val coversDirectory: () -> Uri?,
) {

    /**
     * Pochette prête à afficher : l'image, et la couleur qu'elle prête à son
     * titre. Les deux voyagent ensemble parce qu'elles se calculent ensemble —
     * extraire la teinte plus tard supposerait de relire les pixels.
     */
    class Cover(val bitmap: Bitmap, val accent: Int)

    /**
     * Pochettes prêtes, plafonnées à un huitième de la mémoire accordée au
     * processus — assez pour couvrir plusieurs écrans de défilement, assez peu
     * pour ne jamais provoquer de manque de mémoire.
     */
    private val pretes = object : LruCache<String, Cover>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt().coerceAtLeast(4 * 1024)
    ) {
        override fun sizeOf(key: String, value: Cover): Int = value.bitmap.byteCount / 1024
    }

    /**
     * Pochettes déjà cherchées. `null` en valeur signifie « cherchée, aucune
     * trouvée » : c'est justement ce qu'il ne faut pas rechercher.
     */
    private val resolus = HashMap<String, Uri?>()

    /**
     * Clé d'une vignette.
     *
     * Elle inclut la pochette choisie et le nom affiché : changer l'une ou
     * l'autre doit produire une image différente, sans quoi le cache
     * ressortirait l'ancienne. La taille en fait partie parce qu'une même
     * pochette n'est pas décodée pareil en grille et en liste.
     */
    fun key(entry: RomEntry, largeurPx: Int, hauteurPx: Int): String =
        "${entry.uri}|${entry.coverUri.orEmpty()}|${entry.displayName}|${largeurPx}x$hauteurPx"

    /** Pochette déjà prête, à poser sans attendre. `null` s'il faut la charger. */
    fun cached(key: String): Cover? = pretes.get(key)

    /**
     * Charge la pochette et rend la main sur le fil principal.
     *
     * L'appelant garde le [Job] pour l'annuler quand la vignette est recyclée :
     * sans cela, un défilement rapide laisse une file de décodages dont les
     * résultats arriveraient dans les mauvaises vignettes.
     */
    fun load(
        entry: RomEntry,
        key: String,
        largeurPx: Int,
        hauteurPx: Int,
        onReady: (Cover) -> Unit,
    ): Job = scope.launch {
        val cover = withContext(Dispatchers.IO) { decode(entry, key, largeurPx, hauteurPx) }
        onReady(cover)
    }

    private fun decode(entry: RomEntry, key: String, largeurPx: Int, hauteurPx: Int): Cover {
        pretes.get(key)?.let { return it }

        val uri = synchronized(resolus) {
            if (resolus.containsKey(entry.uri)) {
                resolus[entry.uri]
            } else {
                resolver.resolve(entry, null, coversDirectory()).also { resolus[entry.uri] = it }
            }
        }

        val image = uri?.let { decodeSampled(it, largeurPx, hauteurPx) }
            ?: CoverArtGenerator.generate(entry.displayName)
        val cover = Cover(image, accentDe(image))
        pretes.put(key, cover)
        return cover
    }

    /**
     * Décode à la taille utile : une première passe lit l'en-tête sans
     * allouer, la seconde décode réduit.
     */
    private fun decodeSampled(uri: Uri, largeurPx: Int, hauteurPx: Int): Bitmap? = try {
        val mesure = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, mesure)
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = CoverSampling.sampleSize(
                mesure.outWidth,
                mesure.outHeight,
                largeurPx,
                hauteurPx,
            )
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    } catch (_: Exception) {
        // Pochette illisible ou permission perdue : la jaquette générée prend
        // le relais. L'échec ne doit pas vider la bibliothèque de son contenu.
        null
    }

    /**
     * Teinte de la pochette, lue sur une réduction et non sur l'image entière :
     * la couleur dominante ne se joue pas au pixel près, et lire un million de
     * pixels par vignette coûterait ce que le sous-échantillonnage vient
     * d'économiser.
     */
    private fun accentDe(image: Bitmap): Int {
        val cote = ECHANTILLON_COTE
        val miniature = try {
            Bitmap.createScaledBitmap(image, cote, cote, true)
        } catch (_: Exception) {
            // Image de dimensions dégénérées : le titre gardera la teinte
            // neutre, ce qui reste lisible.
            return CoverAccent.DEFAUT
        }
        val pixels = IntArray(cote * cote)
        miniature.getPixels(pixels, 0, cote, 0, 0, cote, cote)
        // `createScaledBitmap` peut rendre l'image d'origine quand la taille
        // demandée est déjà la bonne : la recycler détruirait la pochette.
        if (miniature !== image) miniature.recycle()
        return CoverAccent.extract(pixels, cote, cote)
    }

    /**
     * Oublie ce qui concerne une entrée, après un renommage ou un changement
     * de pochette. Le cache des pochettes prêtes se purge de lui-même par
     * éviction ; ce qui compte est de rechercher la pochette à nouveau.
     */
    fun invalidate(entry: RomEntry) {
        synchronized(resolus) { resolus.remove(entry.uri) }
    }

    private companion object {
        /** Côté de la réduction sur laquelle la teinte est lue. */
        const val ECHANTILLON_COTE = 32
    }
}
