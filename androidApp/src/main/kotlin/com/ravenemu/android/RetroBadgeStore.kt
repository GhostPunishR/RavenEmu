package com.ravenemu.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * RavenEmu 0.8.1's public RA badge/avatar loading policy, limited to the RA image host.
 * Cache files are derived from the URL hash, not the username or badge title.
 */
internal class RetroBadgeStore(context: Context) {
    private val applicationContext = context.applicationContext
    private val directory = File(context.cacheDir, "retroachievements-images")

    fun load(rawUrl: String): Bitmap? {
        val url = sanitize(rawUrl) ?: return null
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val cacheFile = File(directory, "$hash.img")
        val bytes = if (cacheFile.exists() && cacheFile.length() in 1..MAX_BYTES.toLong()) {
            cacheFile.readBytes()
        } else {
            val downloaded = download(url) ?: return null
            runCatching {
                if (directory.isDirectory || directory.mkdirs()) {
                    val temporary = File(directory, "$hash.tmp")
                    temporary.writeBytes(downloaded)
                    if (!temporary.renameTo(cacheFile)) temporary.delete()
                }
            }
            downloaded
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth !in 1..2048 || opts.outHeight !in 1..2048) return null
        var sample = 1
        while (opts.outWidth / sample > 144 || opts.outHeight / sample > 144) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun download(url: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpsURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 6_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("User-Agent", RetroUserAgent.of(applicationContext))
            if (connection.responseCode != 200) return null
            // No Content-Type gate: an image host may label badges with a
            // generic type, and that silently dropped them. The decoder below
            // is the real check: anything that is not an image yields null.
            connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    if (read > MAX_BYTES - out.size()) return null
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val MAX_BYTES = 768 * 1024
        private const val IMAGE_HOST = "media.retroachievements.org"

        /**
         * Accept only the RetroAchievements image host, over HTTPS, never a
         * redirect: the one host besides the API that PRIVACY.md names.
         */
        fun sanitize(raw: String?): String? {
            val value = raw?.trim().orEmpty()
            if (value.isBlank() || value.length > 512) return null
            val uri = runCatching { URI(value) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() != "https" || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null ||
                (uri.port != -1 && uri.port != 443)) return null
            if (uri.host?.lowercase() != IMAGE_HOST) return null
            if (!uri.rawPath.orEmpty().startsWith("/") ||
                uri.rawPath.orEmpty().contains("..")) return null
            return uri.toASCIIString()
        }
    }
}
