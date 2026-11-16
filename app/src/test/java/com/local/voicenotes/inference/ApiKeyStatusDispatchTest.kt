package com.local.voicenotes.inference

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ApiKeyStatusDispatchTest {
    @Test fun blockedKeyReadDoesNotBlockTheUiDispatcherWhileLoadingStatus() = runBlocking {
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val prefs = object : ApiKeyPreferences {
            override fun getString(key: String): String? = when (key) {
                ApiKeyRepository.ENCRYPTED_KEY -> "synthetic-cipher"
                ApiKeyRepository.IV_KEY -> "synthetic-iv"
                else -> null
            }
            override fun edit(changes: Map<String, String?>) = Unit
        }
        val cipher = object : ApiKeyCipher {
            override fun encrypt(plainText: String) = "cipher" to "iv"
            override fun decrypt(cipherText: String, iv: String): String {
                readEntered.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS))
                return "synthetic-key"
            }
            override fun reset() = Unit
        }
        val store = MistralApiKeyStore(ApiKeyRepository(prefs, cipher))
        val reader = Executors.newSingleThreadExecutor()
        val ui = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val uiScope = CoroutineScope(SupervisorJob() + ui)
        val reading = reader.submit<MistralApiKeyResult> { store.read() }
        try {
            assertTrue(readEntered.await(2, TimeUnit.SECONDS))
            val statusEntered = CompletableDeferred<Unit>()
            val status = uiScope.async {
                statusEntered.complete(Unit)
                store.statusAsync()
            }
            withTimeout(2_000) { statusEntered.await() }
            // The shared key lock is still held. A heartbeat on this same UI dispatcher must run.
            val heartbeat = uiScope.async { true }
            assertTrue(withTimeout(1_000) { heartbeat.await() })
            assertFalse("status should still be waiting for the key transaction", status.isCompleted)
            releaseRead.countDown()
            assertEquals(MistralApiKeyStatus.CONFIGURED, withTimeout(2_000) { status.await() })
            assertTrue(reading.get(2, TimeUnit.SECONDS) is MistralApiKeyResult.Present)
        } finally {
            releaseRead.countDown()
            uiScope.cancel()
            ui.close()
            reader.shutdownNow()
            assertTrue(reader.awaitTermination(2, TimeUnit.SECONDS))
        }
    }
}
