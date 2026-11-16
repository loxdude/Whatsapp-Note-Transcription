package com.local.voicenotes.audio

import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.sqrt

/** Fail-closed contract for the public synthetic pack, not for optional private notes. */
internal class SyntheticDecoderFixtures(private val dir: File) {
    companion object {
        val REQUIRED = setOf("tone.opus", "tone.wav", "tone.mp3", "reject-garbage.opus")
        const val EXPECTED_SAMPLES = 21_072
    }
    private val entries: JSONObject

    init {
        val manifestFile = File(dir, "manifest.json")
        check(manifestFile.isFile) { "Missing mandatory manifest.json; run generate_device_fixtures.py" }
        val manifest = JSONObject(manifestFile.readText())
        check(manifest.getInt("schema") == 1 && manifest.getInt("sample_rate") == 16_000)
        entries = manifest.getJSONObject("fixtures")
        check(entries.keys().asSequence().toSet() == REQUIRED) { "Missing/unknown mandatory fixture entries" }
        for (name in REQUIRED) {
            val entry = entries.getJSONObject(name)
            verifyHash(File(dir, name), entry.getString("sha256"))
            if (name.startsWith("reject-")) {
                check(entry.getBoolean("reject"))
                continue
            }
            check(entry.getInt("expected_samples") == EXPECTED_SAMPLES)
            check(entry.getString("reference") == "$name.ref.f32")
            verifyHash(File(dir, "$name.ref.f32"), entry.getString("reference_sha256"))
            val expectedRoute = if (name.endsWith(".opus")) "native-opus" else "android"
            check(entry.getString("route") == expectedRoute)
            val ref = reference(name)
            check(ref.size == EXPECTED_SAMPLES && ref.all { it.isFinite() })
            val rms = sqrt(ref.sumOf { it.toDouble() * it } / ref.size)
            check(rms > 0.05 && abs(rms - entry.getDouble("reference_rms")) < 0.000001) {
                "Invalid/silent reference: $name"
            }
        }
    }

    private fun verifyHash(file: File, expected: String) {
        check(file.isFile) { "Missing mandatory fixture/reference: ${file.name}" }
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        check(hash == expected) { "Fixture/reference hash mismatch: ${file.name}" }
    }

    private fun reference(name: String): FloatArray {
        val bytes = File(dir, entries.getJSONObject(name).getString("reference")).readBytes()
        check(bytes.size % 4 == 0)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.float }
    }

    fun checkPcm(name: String, pcm: FloatArray): JSONObject {
        val ref = reference(name)
        check(abs(pcm.size - EXPECTED_SAMPLES) <= 1) { "$name: ${pcm.size} samples; expected $EXPECTED_SAMPLES (+/-1 rounding)" }
        check(pcm.all { it.isFinite() }) { "$name: nonfinite PCM" }
        val n = minOf(ref.size, pcm.size)
        var actualPower = 0.0
        var referencePower = 0.0
        var errorPower = 0.0
        for (i in 0 until n) {
            actualPower += pcm[i].toDouble() * pcm[i]
            referencePower += ref[i].toDouble() * ref[i]
            val delta = pcm[i].toDouble() - ref[i]
            errorPower += delta * delta
        }
        val refRms = sqrt(referencePower / n)
        val rms = sqrt(actualPower / n)
        val ratio = rms / refRms
        val nrmse = sqrt(errorPower / n) / refRms
        check(refRms > 0.05 && ratio in 0.8..1.2 && nrmse <= 0.12) {
            "$name: signal mismatch (RMS ratio=$ratio, normalized RMSE=$nrmse)"
        }
        return JSONObject().put("rms", rms).put("referenceRms", refRms).put("rmsRatio", ratio).put("normalizedRmse", nrmse)
    }
}
