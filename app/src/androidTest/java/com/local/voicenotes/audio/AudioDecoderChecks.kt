package com.local.voicenotes.audio

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Job
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Dependency-free on-device checks. See native/README.md for staging fixtures and running. */
class AudioDecoderChecks : Instrumentation() {
    private lateinit var arguments: Bundle
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            runBlocking {
                check(NativeOpus.available) { "Native library did not load" }
                val dir = File(targetContext.filesDir, arguments.getString("fixtures", "decoder-fixtures")).canonicalFile
                check(dir.toPath().startsWith(targetContext.filesDir.canonicalFile.toPath())) { "Fixtures must be app-private" }
                val synthetic = SyntheticDecoderFixtures(dir) // Mandatory even when private notes are supplied.
                val files = dir.listFiles()?.filter {
                    it.isFile && (it.extension.lowercase() in setOf("opus", "wav", "mp3") || it.name.startsWith("reject-"))
                }?.sortedBy { it.name }.orEmpty()
                val output = File(targetContext.cacheDir, "decoder-checks").apply { mkdirs() }
                val records = JSONArray()
                val decoder = AndroidAudioDecoder(targetContext.contentResolver)
                // Deterministically cancel after native parsing starts, not only between reads.
                val validOpus = files.first { it.extension == "opus" && !it.name.startsWith("reject-") }
                targetContext.contentResolver.openAssetFileDescriptor(Uri.fromFile(validOpus), "r")!!.use { fd ->
                    val job = Job()
                    var polls = 0
                    val status = IntArray(1)
                    val handle = NativeOpus.open(fd.parcelFileDescriptor.fd, fd.startOffset, fd.length,
                        NativeOpusControl { if (++polls >= 3) job.cancel(); !job.isActive }, status)
                    if (handle != 0L) NativeOpus.close(handle)
                    check(handle == 0L && status[0] == -10002 && job.isCancelled && polls >= 3) { "Cancellation during native open failed" }
                }
                for (file in files) {
                    val start = System.nanoTime()
                    if (file.name.startsWith("reject-")) {
                        val error = runCatching { decoder.decode(Uri.fromFile(file)) {} }.exceptionOrNull()
                        check(error != null) { "Expected rejection: ${file.name}" }
                        if (file.name.startsWith("reject-too-long")) check(error.message.orEmpty().contains("15-minute"))
                        if (file.name.startsWith("reject-work-limit")) check(error.message.orEmpty().contains("safety limits"))
                        records.put(JSONObject().put("file", file.name).put("rejected", true))
                        continue
                    }
                    var route = ""
                    val pcm = decoder.decodeWithRoute(Uri.fromFile(file), {}, { route = it })
                    val expectedRoute = if (file.extension == "opus") "native-opus" else "android"
                    check(route == expectedRoute) { "Wrong decoder for ${file.name}: $route (expected $expectedRoute)" }
                    check(pcm.isNotEmpty() && pcm.all { it.isFinite() }) { "Invalid PCM for ${file.name}" }
                    if (file.name == "large.wav") {
                        check(pcm.size == 14 * 60 * 16_000) { "Large WAV must decode all 14 minutes" }
                    }
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    if (arguments.getString("dumpPcm") == "true") {
                        File(output, file.name + ".f32").outputStream().buffered().use { out ->
                            val bytes = ByteBuffer.allocate(16384).order(ByteOrder.LITTLE_ENDIAN)
                            for (sample in pcm) {
                                if (bytes.remaining() < 4) { out.write(bytes.array(), 0, bytes.position()); bytes.clear() }
                                bytes.putFloat(sample)
                            }
                            out.write(bytes.array(), 0, bytes.position())
                        }
                    }
                    val record = JSONObject().put("file", file.name).put("samples", pcm.size).put("decodeMs", elapsed).put("route", route)
                    if (file.name in SyntheticDecoderFixtures.REQUIRED) record.put("signal", synthetic.checkPcm(file.name, pcm))
                    records.put(record)
                    sendStatus(0, Bundle().apply { putString("stream", "${file.name}: ${elapsed}ms, ${pcm.size} samples\n") })
                }
                checkProviderFixtures(dir, synthetic, decoder, records)
                File(output, "results.json").writeText(records.toString(2))
                result.putString("stream", "PASS: ${files.size} decoder fixtures; report cache/decoder-checks/results.json\n")
            }
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun nativePcm(uri: Uri, offsetProvider: Boolean = false): FloatArray {
        val samples = ArrayList<Float>()
        targetContext.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { asset ->
            if (offsetProvider) {
                check(asset.startOffset == DecoderFixtureProvider.PREFIX && asset.length > 0)
            }
            val fd = asset.fileDescriptor
            Os.lseek(fd, 23, OsConstants.SEEK_SET)
            fun unchanged() { check(Os.lseek(fd, 0, OsConstants.SEEK_CUR) == 23L) { "JNI changed provider fd position" } }
            val status = IntArray(1)
            val handle = NativeOpus.open(asset.parcelFileDescriptor.fd, asset.startOffset, asset.length, NativeOpusControl { false }, status)
            check(handle != 0L) { "Provider/native open failed: ${status[0]}" }
            try {
                unchanged()
                check(NativeOpus.total(handle) == 63_216L)
                val chunk = FloatArray(5760)
                while (true) {
                    val n = NativeOpus.read(handle, chunk)
                    unchanged()
                    check(n in 0..chunk.size) { "Provider/native read failed: $n" }
                    if (n == 0) break
                    for (i in 0 until n) samples.add(chunk[i])
                    check(samples.size <= 63_216) { "Unexpected extra provider PCM" }
                }
            } finally {
                NativeOpus.close(handle)
                unchanged()
                check(fd.valid()) { "JNI closed caller's provider fd" }
            }
        }
        check(samples.size == 63_216 && samples.all { it.isFinite() })
        return samples.toFloatArray()
    }

    private suspend fun checkProviderFixtures(
        dir: File, synthetic: SyntheticDecoderFixtures, decoder: AndroidAudioDecoder, records: JSONArray
    ) {
        val resolver = targetContext.contentResolver
        val staged = resolver.call(DecoderFixtureProvider.BASE, "stage", null, Bundle().apply {
            putByteArray("opus", File(dir, "tone.opus").readBytes())
        })
        check(staged?.getBoolean("staged") == true) { "Test APK provider is unavailable" }
        val baseline = nativePcm(Uri.fromFile(File(dir, "tone.opus")))
        val fromProvider = nativePcm(DecoderFixtureProvider.OFFSET, offsetProvider = true)
        check(fromProvider.contentEquals(baseline)) { "Subfile offset/length changed native PCM" }
        var route = ""
        val pcm = decoder.decodeWithRoute(DecoderFixtureProvider.OFFSET, {}, { route = it })
        check(route == "native-opus")
        records.put(JSONObject().put("provider", "offset").put("route", route)
            .put("samples", pcm.size).put("fdPositionPreserved", true).put("signal", synthetic.checkPcm("tone.opus", pcm)))

        // A real nonseekable Binder-delivered PFD, not just a synthetic invalid fd.
        val asset = resolver.openAssetFileDescriptor(DecoderFixtureProvider.PIPE, "r")!!
        val pipeFd = asset.fileDescriptor
        asset.use {
            check(asset.startOffset == 0L && asset.length == -1L)
            val seekError = runCatching { Os.lseek(pipeFd, 0, OsConstants.SEEK_CUR) }.exceptionOrNull()
            check(seekError is ErrnoException && seekError.errno == OsConstants.ESPIPE)
            val status = IntArray(1)
            val handle = NativeOpus.open(asset.parcelFileDescriptor.fd, 0, -1, NativeOpusControl { false }, status)
            if (handle != 0L) NativeOpus.close(handle)
            check(handle == 0L && status[0] != -10001 && status[0] != -10002) { "Pipe must request platform fallback, not a safety/cancellation error" }
            check(pipeFd.valid()) { "Native refusal closed caller's pipe" }
        }
        check(!pipeFd.valid()) { "Provider pipe did not close" }
        check(resolver.call(DecoderFixtureProvider.BASE, "awaitIdle", null, null)?.getBoolean("idle") == true) { "Pipe producer leaked/blocked after native refusal" }

        route = ""
        // Android MediaExtractor commonly cannot seek an Ogg pipe. Success is
        // permitted but NOT promised: the route and cleanup are the assertions.
        val attempt = runCatching { decoder.decodeWithRoute(DecoderFixtureProvider.PIPE, {}, { route = it }) }
        check(route == "android") { "Pipe did not attempt the Android fallback" }
        check(attempt.exceptionOrNull() !is CancellationException)
        val pipeRecord = JSONObject().put("provider", "pipe").put("nativeRefused", true).put("route", route)
        attempt.fold(
            onSuccess = { pipeRecord.put("samples", it.size).put("signal", synthetic.checkPcm("tone.opus", it)) },
            onFailure = { pipeRecord.put("platformError", it.javaClass.name).put("message", it.message) }
        )
        check(resolver.call(DecoderFixtureProvider.BASE, "awaitIdle", null, null)?.getBoolean("idle") == true) { "Pipe producer leaked/blocked after fallback" }
        records.put(pipeRecord.put("producerIdle", true))
    }
}
