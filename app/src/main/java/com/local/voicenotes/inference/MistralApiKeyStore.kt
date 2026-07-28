package com.local.voicenotes.inference

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the Mistral key encrypted with an app-private Android Keystore key. */
class MistralApiKeyStore(context: Context) {
    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val LEGACY_KEY = "mistral_api_key"
        private const val ENCRYPTED_KEY = "mistral_api_key_encrypted"
        private const val IV_KEY = "mistral_api_key_iv"
        private const val KEY_ALIAS = "mistral_api_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun get(): String? {
        val encrypted = prefs.getString(ENCRYPTED_KEY, null)
        val iv = prefs.getString(IV_KEY, null)
        if (encrypted != null && iv != null) return decrypt(encrypted, iv)

        // One-time migration of installations that stored the key as plain text.
        val legacy = prefs.getString(LEGACY_KEY, null)?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        set(legacy)
        return legacy
    }

    fun set(value: String) {
        val key = value.trim()
        if (key.isEmpty()) {
            prefs.edit().remove(LEGACY_KEY).remove(ENCRYPTED_KEY).remove(IV_KEY).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(key.encodeToByteArray())
        prefs.edit()
            .putString(ENCRYPTED_KEY, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(IV_KEY, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .remove(LEGACY_KEY)
            .apply()
    }

    private fun decrypt(encrypted: String, iv: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).decodeToString()
    }.getOrNull()

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }
}
