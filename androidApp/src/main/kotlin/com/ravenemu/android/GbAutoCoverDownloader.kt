package com.ravenemu.android

import android.graphics.Bitmap
import android.util.Log
import com.ravenemu.shared.GamePlatform
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.text.Normalizer
import java.util.Locale

/**
 * Optional online artwork for ROMs already added to the GB/GBC/GBA library.
 * Source: Libretro's public Named_Boxarts index of each game's console
 * ("Nintendo - Game Boy", "- Game Boy Color" or "- Game Boy Advance").
 *
 * Only the public directory index and selected PNG are fetched. ROM content,
 * hashes, SRAM and state files are never uploaded. All I/O is called from IO.
 *
 * Disabled unless the user turns it on in the settings: the requests tell a
 * third-party server which games are in the library.
 */
internal class GbAutoCoverDownloader {
    private val cachedNames = mutableMapOf<GamePlatform, List<BoxartRecord>>()

    /**
     * [found] holds the artwork downloaded during this pass. [unmatched] lists
     * the ROMs the index has no entry for: the caller remembers them, so a
     * game libretro does not know is not looked up again at every launch,
     * which used to download the whole index (up to 2 MB) each time. A failed
     * download is not reported there: it may succeed on the next pass.
     */
    data class Pass(val found: Map<String, Bitmap>, val unmatched: Set<String>)

    fun fillMissing(
        entries: List<StoredGbRom>,
        store: GbCoverStore,
        skippedIds: Set<String>,
    ): Pass {
        val missing = entries.filter { item ->
            item.id !in skippedIds && store.read(item.id) == null
        }.take(MAX_IMAGES_PER_PASS)
        if (missing.isEmpty()) return Pass(emptyMap(), emptySet())

        val artwork = mutableMapOf<String, Bitmap>()
        val unmatched = mutableSetOf<String>()
        for (item in missing) {
            // Only the index of a console present in the library is fetched.
            val folder = indexUrl(item.platform)
            val match = GbBoxartMatcher.bestMatch(item.filename, getNames(item.platform))
            if (match == null) {
                unmatched += item.id
                continue
            }
            try {
                val response = download(folder + match.encodedHref, MAX_IMAGE_BYTES)
                val bitmap = store.saveDownloadedIfMissing(item.id, response)
                if (bitmap != null) artwork[item.id] = bitmap
            } catch (e: Exception) {
                Log.w(TAG, "Unable to download boxart for ${item.id.take(12)}", e)
            }
        }
        return Pass(artwork, unmatched)
    }

    private fun getNames(platform: GamePlatform): List<BoxartRecord> {
        cachedNames[platform]?.let { return it }
        val index = download(indexUrl(platform), MAX_INDEX_BYTES).toString(Charsets.UTF_8)
        val names = GbBoxartMatcher.parseIndex(index)
        require(names.isNotEmpty()) { "Catalogue de jaquettes indisponible." }
        cachedNames[platform] = names
        return names
    }

    private fun indexUrl(platform: GamePlatform): String = when (platform) {
        GamePlatform.GB -> GB_INDEX_URL
        GamePlatform.GBC -> GBC_INDEX_URL
        GamePlatform.GBA -> GBA_INDEX_URL
    }

    private fun download(address: String, maximum: Int): ByteArray {
        val url = URL(address)
        require(url.protocol == "https" && url.host == "thumbnails.libretro.com") {
            "Source de jaquettes non autorisée."
        }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 7000
            connection.readTimeout = 12000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "RavenEmu/0.1")
            connection.setRequestProperty("Accept", "image/png,text/html")
            require(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Service de jaquettes indisponible (${connection.responseCode})."
            }
            return connection.inputStream.use { input -> readBounded(input, maximum) }
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var count = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            require(read <= maximum - count) { "Téléchargement trop volumineux." }
            output.write(buffer, 0, read)
            count += read
        }
        require(count > 0) { "Réponse vide." }
        return output.toByteArray()
    }

    private companion object {
        const val TAG = "RavenEmuCover"
        const val GB_INDEX_URL =
            "https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy/Named_Boxarts/"
        const val GBC_INDEX_URL =
            "https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy%20Color/Named_Boxarts/"
        const val GBA_INDEX_URL =
            "https://thumbnails.libretro.com/Nintendo%20-%20Game%20Boy%20Advance/Named_Boxarts/"
        const val MAX_INDEX_BYTES = 2 * 1024 * 1024
        const val MAX_IMAGE_BYTES = 3 * 1024 * 1024
        const val MAX_IMAGES_PER_PASS = 30
    }
}

internal data class BoxartRecord(val title: String, val encodedHref: String)

/** Conservative matching: same full title, and same country if supplied. */
internal object GbBoxartMatcher {
    private val hrefRegex = Regex("""href=["']([^"'<>]+?\.png)["']""", RegexOption.IGNORE_CASE)
    private val regionRegex = Regex(
        """\b(France|USA|Europe|World|Japan|Germany|Spain|Italy|Australia|Korea|Brazil)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val annotations = Regex("""\s*\([^)]*\)""")
    private val extension = Regex("""\.(gb|gbc|gba|png)$""", RegexOption.IGNORE_CASE)
    private val accents = Regex("""\p{M}+""")
    private val separators = Regex("""[^a-z0-9]+""")
    private val space = Regex("""\s+""")

    fun parseIndex(html: String): List<BoxartRecord> = hrefRegex.findAll(html)
        .mapNotNull { match ->
            val href = match.groupValues[1].replace("&amp;", "&")
            // Never follow parent paths, nested paths, absolute links or
            // query strings supplied by a remote directory listing.
            if (href.startsWith(".") || href.contains('/') || href.contains('\\') ||
                href.contains('?') || href.contains('#')) return@mapNotNull null
            val decoded = try {
                URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")
            } catch (_: Exception) { return@mapNotNull null }
            if (!decoded.endsWith(".png", ignoreCase = true) ||
                decoded.contains('/') || decoded.contains('\\')) return@mapNotNull null
            BoxartRecord(decoded.dropLast(4), href)
        }
        .toList()

    fun bestMatch(filename: String, entries: List<BoxartRecord>): BoxartRecord? {
        val base = normalizedTitle(filename)
        if (base.isEmpty()) return null
        val wantedRegions = regions(filename)
        return entries.asSequence()
            .filter { normalizedTitle(it.title) == base }
            .mapNotNull { entry ->
                val foundRegions = regions(entry.title)
                // Do not silently replace a French cover with a USA/Japan cover.
                if (wantedRegions.isNotEmpty() &&
                    foundRegions.intersect(wantedRegions).isEmpty()) return@mapNotNull null
                if (wantedRegions.isEmpty() && "japan" in foundRegions) return@mapNotNull null
                val priority = when {
                    wantedRegions.isNotEmpty() -> 100
                    "world" in foundRegions -> 90
                    "europe" in foundRegions -> 80
                    "usa" in foundRegions -> 70
                    else -> 30
                }
                val extraTags = annotations.findAll(entry.title).count()
                (priority - extraTags) to entry
            }
            .maxByOrNull { it.first }
            ?.second
    }

    private fun regions(text: String): Set<String> =
        regionRegex.findAll(text.substringBeforeLast('.', text))
            .map { it.value.lowercase(Locale.ROOT) }.toSet()

    private fun normalizedTitle(text: String): String {
        val stripped = extension.replace(text, "")
        val withoutMetadata = annotations.replace(stripped, " ")
        val decomposed = Normalizer.normalize(withoutMetadata, Normalizer.Form.NFD)
        val ascii = accents.replace(decomposed, "").lowercase(Locale.ROOT)
        return space.replace(separators.replace(ascii, " "), " ").trim()
    }
}
