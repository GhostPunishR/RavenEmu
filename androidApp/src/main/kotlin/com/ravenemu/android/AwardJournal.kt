package com.ravenemu.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Durable journal of genuine SDK award requests, so an unlock earned offline
 * survives the app closing before the network comes back. Each request holds
 * an account token, so the file is always AES-GCM encrypted with a
 * non-exportable Android Keystore key, kept out of backups. Its own file and
 * key: never shared with RavenEmu 0.8.1's journal.
 */
internal class AwardJournal(context: Context) {
    data class Award(val account: String, val url: String, val post: String, val contentType: String)
    private val file = AtomicFile(File(context.noBackupFilesDir, "retroachievements.unsent-awards"))
    private val guard = AwardJournalGuard.forFile(file.baseFile.absolutePath)
    private val lease = guard.open()

    fun isCurrent(): Boolean = lease.access(false) { true }

    fun remember(entry: Award): Boolean = lease.access(false) {
        if (entry.account.isBlank() || !AwardPolicy.isSdkAward(entry.url, entry.post)) return@access false
        if (entry.contentType.length !in 1..128) return@access false
        val entries = read()
        val kept = AwardJournalEntries.remember(entries, entry, MAX_PENDING, ::keyOf) ?: return@access false
        if (kept != entries) write(kept)
        true
    }

    fun forAccount(account: String): List<Award> = lease.access(emptyList()) {
        AwardJournalEntries.latest(read().filter { it.account.equals(account, ignoreCase = true) }, ::keyOf)
    }

    fun acknowledge(entry: Award) = lease.access(Unit) {
        val entries = read()
        val kept = AwardJournalEntries.acknowledge(entries, entry, ::keyOf)
        if (kept != entries) write(kept)
    }

    private fun keyOf(award: Award) = AwardJournalEntries.key(award.account, award.post)

    /** Sign-out: revokes this and every other open client, then deletes. */
    fun clear() = guard.clear { file.delete() }

    private fun read(): List<Award> {
        val ciphertext = try {
            file.readFully()
        } catch (_: FileNotFoundException) {
            return emptyList()
        }
        require(ciphertext.size in 13..MAX_FILE_SIZE) { "Invalid encrypted award journal" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)))
        cipher.updateAAD(AAD)
        val json = String(cipher.doFinal(ciphertext, 12, ciphertext.size - 12), Charsets.UTF_8)
        val array = JSONArray(json)
        require(array.length() <= MAX_PENDING)
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            Award(item.getString("account"), item.getString("url"), item.getString("post"), item.getString("type"))
        }.onEach {
            require(it.account.isNotBlank() && AwardPolicy.isSdkAward(it.url, it.post))
        }
    }

    private fun write(entries: List<Award>) {
        require(entries.size <= MAX_PENDING)
        val json = JSONArray()
        entries.forEach { entry ->
            json.put(JSONObject().put("account", entry.account).put("url", entry.url)
                .put("post", entry.post).put("type", entry.contentType))
        }
        val plain = json.toString().toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            cipher.updateAAD(AAD)
            val data = cipher.iv + cipher.doFinal(plain)
            require(data.size <= MAX_FILE_SIZE)
            val output = file.startWrite()
            try {
                output.write(data)
                file.finishWrite(output)
            } catch (error: Exception) {
                file.failWrite(output)
                throw error
            }
        } finally {
            plain.fill(0)
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "RavenEmu.RetroAchievements.PendingAwards.v1"
        const val MAX_PENDING = 256
        const val MAX_FILE_SIZE = 3 * 1024 * 1024
        val AAD = "RavenEmu RavenEmu authenticated awards v1".toByteArray(Charsets.UTF_8)
    }
}
