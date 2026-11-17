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
        var transientDecryptFailure = false
        var resets = 0
        override fun encrypt(plainText: String): Pair<String, String> {
            if (failEncrypt) throw IllegalStateException("keystore unavailable")
            return plainText.reversed() to "iv-$generation"
        }
        override fun decrypt(cipherText: String, iv: String): String {
            if (transientDecryptFailure) throw java.security.ProviderException("keystore busy")
            if (iv != "iv-$generation") throw javax.crypto.AEADBadTagException("tag mismatch")
            return cipherText.reversed()
        }
        override fun reset() { resets++; generation++ }
    }

    private val prefs = FakePrefs()
    private val cipher = FakeCipher()
    private val repo = ApiKeyRepository(prefs, cipher)
    private val store = MistralApiKeyStore(repo)

    @Test fun backendConstructorAcceptsGraphsSharedStore() {
        assertTrue("backend must accept a Context and the graph's shared MistralApiKeyStore",
            MistralTranscriptionBackend::class.java.constructors.any {
                it.parameterTypes.contentEquals(arrayOf(android.content.Context::class.java, MistralApiKeyStore::class.java))
            })
    }

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

    @Test fun transientDecryptFailureKeepsTheKeyAndReportsUnavailable() {
        store.set("sk-123")
        val before = prefs.values.toMap()
        cipher.transientDecryptFailure = true
        assertEquals(MistralApiKeyResult.Unavailable, store.read())
        assertEquals(before, prefs.values)
        assertEquals(0, cipher.resets)
        assertEquals(MistralApiKeyStatus.CONFIGURED, store.status())
        assertResolveFails(MistralApiKeyResult.UNAVAILABLE_MESSAGE) { MistralApiKeyResult.Unavailable }

        cipher.transientDecryptFailure = false
        assertEquals(MistralApiKeyResult.Present("sk-123"), store.read())
    }

    @Test fun keystoreKeyIsOnlyResetWhenTheUnreadableEntryWasCleared() {
        store.set("sk-123")
        cipher.generation++
        prefs.failWrites = true
        assertEquals(MistralApiKeyResult.Unreadable, store.read())
        assertEquals(0, cipher.resets)
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
        assertEquals(MistralApiKeyStatus.CONFIGURED_UNENCRYPTED, repo.status())
        assertEquals(MistralApiKeyResult.Present("sk-legacy"), repo.read())
        assertNull(prefs.values[ApiKeyRepository.LEGACY_KEY])
        assertEquals(MistralApiKeyResult.Present("sk-legacy"), repo.read())
    }

    @Test fun legacyKeyKeptWhenMigrationFails() {
        prefs.values[ApiKeyRepository.LEGACY_KEY] = "sk-legacy"
        cipher.failEncrypt = true
        assertEquals(MistralApiKeyResult.Present("sk-legacy"), repo.read())
        assertEquals("sk-legacy", prefs.values[ApiKeyRepository.LEGACY_KEY])
        // The UI must not claim encryption while the plain-text value is still stored.
        assertEquals(MistralApiKeyStatus.CONFIGURED_UNENCRYPTED, repo.status())
        assertTrue(store.hasKey())

        cipher.failEncrypt = false // The next read retries the migration.
        assertEquals(MistralApiKeyResult.Present("sk-legacy"), repo.read())
        assertNull(prefs.values[ApiKeyRepository.LEGACY_KEY])
        assertEquals(MistralApiKeyStatus.CONFIGURED, repo.status())
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

    @Test fun twoStoresCannotReadMixedCiphertextAndIvOrEraseNewKey() {
        assertTwoStoreIsolation(pauseDuringDecrypt = false)
    }

    @Test fun unreadableResetCannotRaceWithAnotherStoresNewKey() {
        assertTwoStoreIsolation(pauseDuringDecrypt = true)
    }

    private fun assertTwoStoreIsolation(pauseDuringDecrypt: Boolean) {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val writerStarted = java.util.concurrent.CountDownLatch(1)
        val writerDone = java.util.concurrent.CountDownLatch(1)
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val values = java.util.concurrent.ConcurrentHashMap<String, String>()
        val resets = java.util.concurrent.atomic.AtomicInteger()
        fun pause() {
            if (armed.compareAndSet(true, false)) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
        }
        val sharedPrefs = object : ApiKeyPreferences {
            override fun getString(key: String): String? {
                val value = synchronized(this) { values[key] }
                if (!pauseDuringDecrypt && key == ApiKeyRepository.ENCRYPTED_KEY) pause()
                return value
            }
            @Synchronized override fun edit(changes: Map<String, String?>) {
                changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
            }
        }
        val sharedCipher = object : ApiKeyCipher {
            override fun encrypt(plainText: String) = "cipher-$plainText" to "iv-$plainText"
            override fun decrypt(cipherText: String, iv: String): String {
                if (pauseDuringDecrypt) pause()
                val plain = cipherText.removePrefix("cipher-")
                if (iv != "iv-$plain" || (pauseDuringDecrypt && plain == "old-synthetic")) {
                    throw javax.crypto.AEADBadTagException("synthetic tag mismatch")
                }
                return plain
            }
            override fun reset() { resets.incrementAndGet() }
        }
        // Separate repositories, as constructed by two Context-based store clients today.
        val reader = MistralApiKeyStore(ApiKeyRepository(sharedPrefs, sharedCipher))
        val writer = MistralApiKeyStore(ApiKeyRepository(sharedPrefs, sharedCipher))
        reader.set("old-synthetic").getOrThrow()
        armed.set(true)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val reading = pool.submit<MistralApiKeyResult> { reader.read() }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val writing = pool.submit {
                writerStarted.countDown()
                try { writer.set("new-synthetic").getOrThrow() } finally { writerDone.countDown() }
            }
            assertTrue(writerStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val wroteWhileReadPaused = writerDone.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)
            release.countDown()
            val result = reading.get(5, java.util.concurrent.TimeUnit.SECONDS)
            writing.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertFalse("second store wrote during another store's read/reset transaction", wroteWhileReadPaused)
            assertEquals(if (pauseDuringDecrypt) MistralApiKeyResult.Unreadable else
                MistralApiKeyResult.Present("old-synthetic"), result)
            assertEquals("cipher-new-synthetic", values[ApiKeyRepository.ENCRYPTED_KEY])
            assertEquals("iv-new-synthetic", values[ApiKeyRepository.IV_KEY])
            assertFalse(values.containsKey(ApiKeyRepository.UNREADABLE_FLAG))
            assertEquals(if (pauseDuringDecrypt) 1 else 0, resets.get())
            assertEquals(MistralApiKeyResult.Present("new-synthetic"), writer.read())
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
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
