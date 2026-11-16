package com.local.voicenotes.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MistralApiKeyStoreTest {
    private class FakePrefs : ApiKeyPreferences {
        val values = mutableMapOf<String, String>()
        var failWrites = false
        override fun getString(key: String): String? = values[key]
        override fun edit(changes: Map<String, String?>) {
            if (failWrites) throw IllegalStateException("disk full")
            changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
        }
    }

    /** Reversible "encryption" bound to a key generation, like a Keystore key. */
    private class FakeCipher : ApiKeyCipher {
        var generation = 1
        var failEncrypt = false
        var resets = 0
        override fun encrypt(plainText: String): Pair<String, String> {
            if (failEncrypt) throw IllegalStateException("keystore unavailable")
            return plainText.reversed() to "iv-$generation"
        }
        override fun decrypt(cipherText: String, iv: String): String {
            if (iv != "iv-$generation") throw javax.crypto.AEADBadTagException("tag mismatch")
            return cipherText.reversed()
        }
        override fun reset() { resets++; generation++ }
    }

    private val prefs = FakePrefs()
    private val cipher = FakeCipher()
    private val repo = ApiKeyRepository(prefs, cipher)
    private val store = MistralApiKeyStore(repo)

    @Test fun absentWhenNothingStored() {
        assertEquals(MistralApiKeyResult.Absent, repo.read())
        assertEquals(MistralApiKeyStatus.ABSENT, repo.status())
        assertFalse(store.hasKey())
        assertNull(store.get())
    }

    @Test fun roundTripTrimsAndEncrypts() {
        assertTrue(store.set("  sk-123  ").isSuccess)
        assertEquals(MistralApiKeyResult.Present("sk-123"), store.read())
        assertFalse(prefs.values.values.any { it == "sk-123" })
        assertTrue(store.hasKey())
        assertEquals("sk-123", store.get())
    }

    @Test fun presentToStringDoesNotLeakKey() {
        assertFalse(MistralApiKeyResult.Present("sk-secret").toString().contains("sk-secret"))
    }

    @Test fun undecryptableKeyIsClearedAndReportedUnreadableUntilReentered() {
        store.set("sk-123")
        cipher.generation++ // Keystore key lost/rotated, e.g. after a backup restore.

        assertEquals(MistralApiKeyStatus.CONFIGURED, store.status())
        assertEquals(MistralApiKeyResult.Unreadable, store.read())
        assertNull(prefs.values[ApiKeyRepository.ENCRYPTED_KEY])
        assertNull(prefs.values[ApiKeyRepository.IV_KEY])
        assertEquals(1, cipher.resets)
        // Sticky until the user enters a new key, so the UI can keep explaining why.
        assertEquals(MistralApiKeyResult.Unreadable, store.read())
        assertEquals(MistralApiKeyStatus.UNREADABLE, store.status())
        assertFalse(store.hasKey())

        assertTrue(store.set("sk-new").isSuccess)
        assertEquals(MistralApiKeyResult.Present("sk-new"), store.read())
        assertEquals(MistralApiKeyStatus.CONFIGURED, store.status())
    }

    @Test fun halfWrittenEntryIsUnreadable() {
        prefs.values[ApiKeyRepository.ENCRYPTED_KEY] = "abc"
        assertEquals(MistralApiKeyResult.Unreadable, repo.read())
        assertTrue(prefs.values.keys == setOf(ApiKeyRepository.UNREADABLE_FLAG))
    }

    @Test fun clearingRemovesEverythingIncludingUnreadableFlag() {
        prefs.values[ApiKeyRepository.UNREADABLE_FLAG] = "1"
        prefs.values[ApiKeyRepository.LEGACY_KEY] = "old"
        assertTrue(store.set("   ").isSuccess)
        assertTrue(prefs.values.isEmpty())
        assertEquals(MistralApiKeyResult.Absent, store.read())
    }

    @Test fun legacyPlainTextKeyIsMigrated() {
        prefs.values[ApiKeyRepository.LEGACY_KEY] = " sk-legacy "
        assertEquals(MistralApiKeyStatus.CONFIGURED, repo.status())
        assertEquals(MistralApiKeyResult.Present("sk-legacy"), repo.read())
        assertNull(prefs.values[ApiKeyRepository.LEGACY_KEY])
        assertEquals(MistralApiKeyResult.Present("sk-legacy"), repo.read())
    }

    @Test fun legacyKeyKeptWhenMigrationFails() {
        prefs.values[ApiKeyRepository.LEGACY_KEY] = "sk-legacy"
        cipher.failEncrypt = true
        assertEquals(MistralApiKeyResult.Present("sk-legacy"), repo.read())
        assertEquals("sk-legacy", prefs.values[ApiKeyRepository.LEGACY_KEY])
    }

    @Test fun setFailuresAreReturnedNotThrown() {
        cipher.failEncrypt = true
        assertEquals("keystore unavailable", store.set("sk-1").exceptionOrNull()?.message)
        cipher.failEncrypt = false
        prefs.failWrites = true
        assertTrue(store.set("sk-1").isFailure)
        assertTrue(store.set("").isFailure)
    }

    @Test fun resolveUsesStoredKeyAndMapsFailuresToMessages() {
        assertEquals("sk", resolveMistralApiKey(null, false) { MistralApiKeyResult.Present("sk") })
        assertResolveFails(MistralApiKeyResult.NOT_CONFIGURED_MESSAGE) { MistralApiKeyResult.Absent }
        assertResolveFails(MistralApiKeyResult.UNREADABLE_MESSAGE) { MistralApiKeyResult.Unreadable }
    }

    @Test fun envOverrideOnlyInDebuggableBuilds() {
        assertEquals("env", resolveMistralApiKey(" env ", true) { MistralApiKeyResult.Absent })
        assertEquals("stored", resolveMistralApiKey("env", false) { MistralApiKeyResult.Present("stored") })
        assertEquals("stored", resolveMistralApiKey("  ", true) { MistralApiKeyResult.Present("stored") })
        assertResolveFails(MistralApiKeyResult.NOT_CONFIGURED_MESSAGE, env = "env", allowEnv = false) {
            MistralApiKeyResult.Absent
        }
    }

    @Test fun envOverrideDoesNotTouchStoredKey() {
        var reads = 0
        resolveMistralApiKey("env", true) { reads++; MistralApiKeyResult.Absent }
        assertEquals(0, reads)
    }

    private fun assertResolveFails(
        message: String,
        env: String? = null,
        allowEnv: Boolean = false,
        stored: () -> MistralApiKeyResult
    ) {
        try {
            resolveMistralApiKey(env, allowEnv, stored)
            fail("expected failure")
        } catch (e: IllegalStateException) {
            assertEquals(message, e.message)
        }
    }
}
