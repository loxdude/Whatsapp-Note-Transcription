package com.local.voicenotes.inference

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.local.voicenotes.concurrent.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Result of reading the stored Mistral API key. */
sealed interface MistralApiKeyResult {
    data class Present(val key: String) : MistralApiKeyResult {
        override fun toString(): String = "Present(key=***)"
    }

    data object Absent : MistralApiKeyResult

    /**
     * A key was stored but could not be decrypted (e.g. the Keystore key was lost after a
     * backup restore). The undecryptable ciphertext has been removed.
     */
    data object Unreadable : MistralApiKeyResult

    companion object {
        const val UNREADABLE_MESSAGE = "Saved API key could not be decrypted; enter it again."
        const val NOT_CONFIGURED_MESSAGE = "Mistral API key not configured."
    }
}

/** Key status for UI that does not require decrypting the key. */
enum class MistralApiKeyStatus { CONFIGURED, ABSENT, UNREADABLE }

/**
 * Picks the key for a request. The `MISTRAL_API_KEY` environment override is honoured
 * only for debuggable builds; release builds always use the stored key.
 * Throws [IllegalStateException] with a user-facing message when no key is usable.
 */
internal fun resolveMistralApiKey(
    envKey: String?,
    allowEnvOverride: Boolean,
    stored: () -> MistralApiKeyResult
): String {
    if (allowEnvOverride) envKey?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    return when (val result = stored()) {
        is MistralApiKeyResult.Present -> result.key
        MistralApiKeyResult.Absent -> throw IllegalStateException(MistralApiKeyResult.NOT_CONFIGURED_MESSAGE)
        MistralApiKeyResult.Unreadable -> throw IllegalStateException(MistralApiKeyResult.UNREADABLE_MESSAGE)
    }
}

/** Minimal persistent key/value seam over SharedPreferences. */
internal interface ApiKeyPreferences {
    fun getString(key: String): String?

    /** Applies all changes in one edit; a `null` value removes the entry. */
    fun edit(changes: Map<String, String?>)
}

/** Encryption seam: Android Keystore AES-GCM in production, fakes in JVM tests. */
internal interface ApiKeyCipher {
    /** Returns ciphertext and IV (both opaque strings). */
    fun encrypt(plainText: String): Pair<String, String>
    fun decrypt(cipherText: String, iv: String): String

    /** Drops the underlying key so the next [encrypt] creates a fresh one. */
    fun reset()
}

/** Platform-independent API key logic: migration, sealed read results, safe writes. */
internal class ApiKeyRepository(
    private val prefs: ApiKeyPreferences,
    private val cipher: ApiKeyCipher
) {
    companion object {
        const val LEGACY_KEY = "mistral_api_key"
        const val ENCRYPTED_KEY = "mistral_api_key_encrypted"
        const val IV_KEY = "mistral_api_key_iv"

        /** Set when a stored key turned out undecryptable; cleared by the next [set]. */
        const val UNREADABLE_FLAG = "mistral_api_key_unreadable"

        // All stores use the same preference entries and Keystore alias. Protect the
        // whole read/migrate/reset/write transaction, not just one repository instance.
        private val processLock = Any()
    }

    fun read(): MistralApiKeyResult = synchronized(processLock) {
        val encrypted = prefs.getString(ENCRYPTED_KEY)
        val iv = prefs.getString(IV_KEY)
        if (encrypted != null || iv != null) {
            val plain = if (encrypted != null && iv != null) {
                runCatchingCancellable { cipher.decrypt(encrypted, iv) }.getOrNull()?.trim()
            } else {
                null // Half-written entry: cannot be decrypted either.
            }
            if (!plain.isNullOrEmpty()) return MistralApiKeyResult.Present(plain)
            markUnreadable()
            return MistralApiKeyResult.Unreadable
        }

        // One-time migration of installations that stored the key as plain text. If the
        // encrypted write fails, the legacy value stays so the key is not lost.
        val legacy = prefs.getString(LEGACY_KEY)?.trim()
        if (!legacy.isNullOrEmpty()) {
            set(legacy)
            return MistralApiKeyResult.Present(legacy)
        }
        if (prefs.getString(UNREADABLE_FLAG) != null) return MistralApiKeyResult.Unreadable
        return MistralApiKeyResult.Absent
    }

    /**
     * Status without decrypting. A stored key that has not been read yet reports
     * CONFIGURED; once a read finds it undecryptable it reports UNREADABLE until [set].
     */
    fun status(): MistralApiKeyStatus = synchronized(processLock) {
        when {
            prefs.getString(ENCRYPTED_KEY) != null || !prefs.getString(LEGACY_KEY).isNullOrBlank() ->
                MistralApiKeyStatus.CONFIGURED
            prefs.getString(UNREADABLE_FLAG) != null -> MistralApiKeyStatus.UNREADABLE
            else -> MistralApiKeyStatus.ABSENT
        }
    }

    /** Stores [value] encrypted; blank clears the key. Cipher/storage errors are returned. */
    fun set(value: String): Result<Unit> = synchronized(processLock) {
        runCatchingCancellable {
            val key = value.trim()
            if (key.isEmpty()) {
                prefs.edit(mapOf(LEGACY_KEY to null, ENCRYPTED_KEY to null, IV_KEY to null, UNREADABLE_FLAG to null))
            } else {
                val (encrypted, iv) = cipher.encrypt(key)
                prefs.edit(mapOf(ENCRYPTED_KEY to encrypted, IV_KEY to iv, LEGACY_KEY to null, UNREADABLE_FLAG to null))
            }
        }
    }

    private fun markUnreadable() {
        runCatchingCancellable {
            prefs.edit(mapOf(ENCRYPTED_KEY to null, IV_KEY to null, UNREADABLE_FLAG to "1"))
        }
        runCatchingCancellable { cipher.reset() }
    }
}

/** Stores the Mistral key encrypted with an app-private Android Keystore key. */
class MistralApiKeyStore internal constructor(private val repository: ApiKeyRepository) {
    constructor(context: Context) : this(
        ApiKeyRepository(
            SharedPreferencesApiKeyPreferences(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)),
            AndroidKeystoreApiKeyCipher()
        )
    )

    companion object {
        private const val PREFS_NAME = "app_prefs"
    }

    /**
     * Reads the key. On [MistralApiKeyResult.Unreadable] the undecryptable ciphertext has
     * already been cleared; show [MistralApiKeyResult.UNREADABLE_MESSAGE] and ask again.
     */
    fun read(): MistralApiKeyResult = repository.read()

    /** Plaintext key or null (absent or unreadable). Prefer [read] to distinguish the two. */
    fun get(): String? = (read() as? MistralApiKeyResult.Present)?.key

    /** Does not decrypt, but may wait for a concurrent Keystore transaction; use [statusAsync] from UI. */
    fun status(): MistralApiKeyStatus = repository.status()

    suspend fun statusAsync(): MistralApiKeyStatus = withContext(Dispatchers.IO) { status() }

    fun hasKey(): Boolean = status() == MistralApiKeyStatus.CONFIGURED

    /** Saves (or, when blank, clears) the key. Failures are returned, never thrown. */
    fun set(value: String): Result<Unit> = repository.set(value)
}

private class SharedPreferencesApiKeyPreferences(private val prefs: SharedPreferences) : ApiKeyPreferences {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun edit(changes: Map<String, String?>) {
        val editor = prefs.edit()
        changes.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
        editor.apply()
    }
}

private class AndroidKeystoreApiKeyCipher : ApiKeyCipher {
    private companion object {
        const val KEY_ALIAS = "mistral_api_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEYSTORE = "AndroidKeyStore"
    }

    override fun encrypt(plainText: String): Pair<String, String> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plainText.encodeToByteArray())
        return Base64.encodeToString(encrypted, Base64.NO_WRAP) to Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }

    override fun decrypt(cipherText: String, iv: String): String {
        val key = existingKey() ?: throw IllegalStateException("Keystore key missing")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return cipher.doFinal(Base64.decode(cipherText, Base64.NO_WRAP)).decodeToString()
    }

    override fun reset() {
        keyStore().deleteEntry(KEY_ALIAS)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(KEY_ALIAS, null) as? SecretKey

    private fun secretKey(): SecretKey {
        existingKey()?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply {
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
