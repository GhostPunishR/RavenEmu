package com.ravenemu.android.player

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Captures d'écran du jeu, en PNG, dans Images/RavenEmu.
 *
 * L'image est celle que montre le lecteur, couleurs et corrections comprises,
 * agrandie [SCALE] fois sans lissage : 960 × 640 pour la GBA, 640 × 576 pour
 * la GB et la GBC. À sa taille native, une galerie l'agrandirait en la
 * lissant, et les pixels deviendraient flous.
 *
 * Depuis Android 10, l'image va dans la galerie sans aucune autorisation.
 * Sur Android 8 et 9, la galerie en demanderait une : l'image reste alors dans
 * le dossier Images propre à RavenEmu.
 */
internal object GbScreenshot {
    const val SCALE = 4
    private const val FOLDER = "RavenEmu"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss")

    /**
     * « Pokémon Émeraude 2026-10-24 22.10.05.png » : le nom du fichier du jeu,
     * sans son extension ni les caractères qu'un système de fichiers refuse.
     */
    fun fileName(game: String, at: LocalDateTime): String {
        val base = game.substringBeforeLast('.').ifBlank { game }
        val clean = base.map { if (it.code < 32 || it in "\\/:*?\"<>|") ' ' else it }
            .joinToString("").trim().take(80).ifEmpty { FOLDER }
        return "$clean ${at.format(STAMP)}.png"
    }

    /** Enregistre [frame] sans bloquer l'interface, puis dit où il se trouve. */
    fun capture(context: Context, scope: CoroutineScope, frame: ImageBitmap?, game: String) {
        // Copie privée : le lecteur réutilise ses images en rotation.
        val image = frame?.asAndroidBitmap()?.copy(Bitmap.Config.ARGB_8888, false) ?: return
        scope.launch {
            val message = try {
                val place = withContext(Dispatchers.IO) { save(context, image, game) }
                "Capture enregistrée dans $place"
            } catch (error: Exception) {
                Log.e("RavenEmuCapture", "Screenshot failed", error)
                "Capture impossible : ${error.message ?: "erreur inconnue"}"
            } finally {
                image.recycle()
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun save(context: Context, image: Bitmap, game: String): String {
        val scaled = Bitmap.createScaledBitmap(image, image.width * SCALE, image.height * SCALE, false)
        try {
            val name = fileName(game, LocalDateTime.now())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$FOLDER")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("galerie indisponible")
                try {
                    val written = resolver.openOutputStream(uri)?.use { output ->
                        scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
                    } ?: false
                    check(written) { "écriture refusée" }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (error: Exception) {
                    // Never leave half an image in the gallery.
                    resolver.delete(uri, null, null)
                    throw error
                }
                return "Images/$FOLDER"
            }
            val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: error("stockage indisponible")
            val folder = File(pictures, FOLDER).apply { mkdirs() }
            val written = File(folder, name).outputStream().use { output ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            check(written) { "écriture refusée" }
            return "le dossier Images de RavenEmu"
        } finally {
            if (scaled !== image) scaled.recycle()
        }
    }
}
