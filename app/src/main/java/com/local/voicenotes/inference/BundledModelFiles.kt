package com.local.voicenotes.inference

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Size and SHA-256 of a model file shipped in the APK's assets. */
internal data class BundledModel(val assetName: String, val size: Long, val sha256: String)

/** The Parakeet Ultra graphs bundled in app/src/main/assets (litert-community/parakeet-ultra, CC-BY-4.0). */
internal object ParakeetUltraBundle {
    val decoder = BundledModel(
        "parakeet_ultra_decoder_i8.tflite", 17_866_768L,
        "0b54e2c39831519f81b3cc323c443fcd88f358d61f7c7285005f85371c18d1a0"
    )
    private val mels = mapOf(
        10 to BundledModel(
            "parakeet_ultra_mel_10s.tflite", 1_608_068L,
            "89cdd51d80f1fb829584b6d8d24e188a8f0c880b1aefae070245b71dc8977ccd"
        ),
        20 to BundledModel(
            "parakeet_ultra_mel_20s.tflite", 2_252_068L,
            "7684c1444fd4d1dfd6bf51c33d9548312fd2322ead7bcfd269ee3218f45ba28a"
        ),
        30 to BundledModel(
            "parakeet_ultra_mel_30s.tflite", 2_896_076L,
            "7deaca292363096375bcf1a64904dd1891ca9409ddba2e067a88e121803f10cc"
        )
    )

    fun mel(windowSeconds: Int): BundledModel =
        requireNotNull(mels[windowSeconds]) { "No bundled Parakeet Ultra mel graph for $windowSeconds s." }
}

/**
 * Fallback for LiteRT builds that cannot create a model from the APK's assets: copies a bundled
 * model once into [directory] (temp file, fsync, atomic rename) and only returns a file whose
 * size and SHA-256 match. An existing copy is re-hashed before it is reused, so a truncated or
 * corrupted copy is replaced instead of loaded.
 */
internal class BundledModelFiles(private val directory: File) {
    fun materialize(model: BundledModel, open: () -> InputStream, checkCancelled: () -> Unit = {}): File {
        val target = File(directory, model.assetName)
        if (target.isFile && target.length() == model.size &&
            target.inputStream().use { sha256(it, checkCancelled) } == model.sha256
        ) return target
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
        val temp = File(directory, "${model.assetName}.${System.nanoTime()}.tmp")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            open().use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        checkCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        copied += read
                    }
                    output.fd.sync()
                }
            }
            val hash = digest.digest().toHex()
            if (copied != model.size || hash != model.sha256) {
                throw IOException("Bundled model ${model.assetName} does not match its checksum.")
            }
            try {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return target
        } finally {
            temp.delete()
        }
    }

    private fun sha256(input: InputStream, checkCancelled: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(1 shl 16)
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
