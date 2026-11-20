package com.example.meetingtranscript.data.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only encrypted refresh tokens are persisted. Access tokens and passwords are never stored. */
class KeystoreRefreshTokenStore(context: Context) : RefreshTokenStore {
    private val preferences = context.getSharedPreferences("secure_auth", Context.MODE_PRIVATE)
    private val alias = "omnichat_refresh_key"

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    override fun read(serverId: String): String? {
        val saved = preferences.getString(serverId, null) ?: return null
        return try {
            val parts = saved.split(":", limit = 2)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            cipher.updateAAD(serverId.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (error: Exception) {
            clear(serverId)
            throw AuthApiException("SECURE_STORAGE_INVALID")
        }
    }

    override fun write(serverId: String, token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(serverId.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        val value = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP)
        check(preferences.edit().putString(serverId, value).commit()) { "Token storage failed" }
    }

    override fun clear(serverId: String) { check(preferences.edit().remove(serverId).commit()) }
}
