package com.local.voicenotes.audio

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Bundle
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
                val dir = File(targetContext.filesDir, arguments.getString("fixtures", "decoder-fixtures"))
                val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
                check(files.isNotEmpty()) { "Stage fixtures in app-private files/decoder-fixtures first" }
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
                    records.put(JSONObject().put("file", file.name).put("samples", pcm.size).put("decodeMs", elapsed).put("route", route))
                    sendStatus(0, Bundle().apply { putString("stream", "${file.name}: ${elapsed}ms, ${pcm.size} samples\n") })
                }
                File(output, "results.json").writeText(records.toString(2))
                result.putString("stream", "PASS: ${files.size} decoder fixtures; report cache/decoder-checks/results.json\n")
            }
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("stream", "FAIL: ${error.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, result)
        }
    }
}
