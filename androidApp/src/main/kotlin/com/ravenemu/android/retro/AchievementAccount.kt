// Ported from RavenEmu 0.8.1: encrypted token-only RA account storage, isolated for RavenEmu.
package com.ravenemu.android.retro

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Token only; encrypted with an Android Keystore key and excluded from backups. */
class AchievementAccount(context: Context) {
    /**
     * [avatarUrl] n'est pas un secret : il est conservé avec le reste pour que
     * la carte de profil montre le portrait sans attendre le réseau. Un fichier
     * écrit avant son arrivée se relit sans lui.
     */
    data class Credentials(
        val username: String,
        val token: String,
        val displayName: String = username,
        val avatarUrl: String = "",
    )
    private val file = AtomicFile(File(context.noBackupFilesDir, "retroachievements.account"))

    fun read(): Credentials? = runCatching {
        val bytes = file.readFully()
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
        val username = json.getString("username")
        Credentials(username, json.getString("token"), json.optString("displayName").ifBlank { username },
            json.optString("avatarUrl"))
    }.getOrNull()

    fun save(username: String, token: String, displayName: String = username, avatarUrl: String = "") {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val plain = JSONObject().put("username", username).put("token", token)
            .put("displayName", displayName.ifBlank { username }).put("avatarUrl", avatarUrl)
            .toString().toByteArray(Charsets.UTF_8)
        val encrypted = cipher.iv + cipher.doFinal(plain)
        plain.fill(0)
        val stream = file.startWrite()
        try {
            stream.write(encrypted)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    fun clear() = file.delete()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build())
        }.generateKey()
    }

    private companion object { const val KEY = "RavenEmu.RetroAchievements.v1" }
}

