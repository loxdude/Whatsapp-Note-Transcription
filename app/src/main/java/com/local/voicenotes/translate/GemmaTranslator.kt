package com.local.voicenotes.translate

import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Gemma 4 through LiteRT-LM. The engine is created lazily on the first translation (GPU, CPU
 * if the GPU cannot start) and kept until [release]. Every native call runs on one thread, so
 * [release] can never free the engine under a running conversation.
 */
class GemmaTranslator(
    private val cacheDir: File,
    /** Runs before the engine is created, e.g. to free the Arabic speech model's memory. */
    private val beforeLoad: () -> Unit = {}
) {
    private companion object {
        const val TAG = "VoiceNotesTranslate"
        /** After cancelProcess(), how long to wait for the native call to return before giving up on it. */
        const val CANCEL_GRACE_MILLIS = 30_000L
    }

    private val executor = Executors.newSingleThreadExecutor { Thread(it, "GemmaTranslate") }
    private val dispatcher = executor.asCoroutineDispatcher()

    // Touched only on the executor thread.
    private var engine: Engine? = null
    private var enginePath: String? = null

    /**
     * A translator into [language] bound to [model]; loading switches the engine if another
     * model was loaded.
     */
    fun forModel(model: File, language: String): TextTranslator = object : TextTranslator {
        override suspend fun load() { withContext(dispatcher) { ensureEngine(model) } }
        override suspend fun translate(text: String): String = withContext(dispatcher) {
            translateBlocking(ensureEngine(model), text, language)
        }
    }

    /** Frees the engine (and its GPU memory) once the current call returned. */
    suspend fun release() = withContext(dispatcher) { closeEngine() }

    /** Fire-and-forget [release] for callbacks that cannot suspend (trim memory). */
    fun releaseLater() { executor.execute { closeEngine() } }

    private fun closeEngine() {
        engine?.let { runCatching { it.close() }.onFailure { Log.w(TAG, "closing the engine failed", it) } }
        engine = null
        enginePath = null
    }

    private fun ensureEngine(model: File): Engine {
        val path = model.absolutePath
        engine?.takeIf { enginePath == path }?.let { return it }
        closeEngine()
        if (!model.isFile) throw IllegalStateException("The translation model is not installed.")
        beforeLoad()
        val started = System.nanoTime()
        val created = try {
            create(path, Backend.GPU()).also { Log.i(TAG, "engine on GPU") }
        } catch (error: Exception) {
            Log.w(TAG, "GPU engine failed; falling back to CPU", error)
            create(path, Backend.CPU()).also { Log.i(TAG, "engine on CPU") }
        }
        Log.i(TAG, "engine ready in ${(System.nanoTime() - started) / 1_000_000} ms")
        engine = created
        enginePath = path
        return created
    }

    private fun create(path: String, backend: Backend): Engine {
        val candidate = Engine(EngineConfig(modelPath = path, backend = backend, cacheDir = cacheDir.absolutePath))
        try {
            candidate.initialize()
        } catch (error: Throwable) {
            runCatching { candidate.close() }
            throw error
        }
        return candidate
    }

    /** One fresh conversation per chunk, greedy, no thinking. Honours coroutine cancellation. */
    private suspend fun translateBlocking(engine: Engine, text: String, language: String): String {
        val job = coroutineContext.job
        val conversation: Conversation = engine.createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
                thinkingConfig = ThinkingConfig(enableThinking = false),
                maxOutputToken = TranslationText.maxOutputTokens(text)
            )
        )
        val output = StringBuilder()
        val finished = CountDownLatch(1)
        var failure: Throwable? = null
        var cancelSent = false
        try {
            conversation.sendMessageAsync(TranslationText.prompt(text, language), object : MessageCallback {
                override fun onMessage(message: Message) { synchronized(output) { output.append(message.toString()) } }
                override fun onDone() { finished.countDown() }
                override fun onError(throwable: Throwable) { failure = throwable; finished.countDown() }
            })
            var waitedAfterCancel = 0L
            while (!finished.await(100, TimeUnit.MILLISECONDS)) {
                if (!job.isActive) {
                    if (!cancelSent) { conversation.cancelProcess(); cancelSent = true }
                    waitedAfterCancel += 100
                    if (waitedAfterCancel >= CANCEL_GRACE_MILLIS) {
                        // Closing a conversation that is still generating is unsafe; leave it to the engine.
                        Log.w(TAG, "generation did not stop after cancel; leaving the conversation open")
                        throw CancellationException("Translation cancelled.")
                    }
                }
            }
            conversation.close()
        } catch (error: Throwable) {
            if (error !is CancellationException) runCatching { conversation.close() }
            throw error
        }
        if (!job.isActive) throw CancellationException("Translation cancelled.")
        failure?.let { throw IllegalStateException("Translation failed: ${it.message ?: it.javaClass.simpleName}", it) }
        return synchronized(output) { output.toString() }.trim()
    }
}
