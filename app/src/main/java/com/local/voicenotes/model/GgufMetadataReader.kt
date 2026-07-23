package com.local.voicenotes.model

import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

data class GgufMetadata(val version: Int, val architecture: String, val name: String)

object GgufMetadataReader {
    private const val MAX_STRING = 16 * 1024 * 1024L
    private const val MAX_ARRAY = 10_000_000L

    fun read(file: File): GgufMetadata = RandomAccessFile(file, "r").use { input ->
        require(input.readIntLE() == 0x46554747) { "Not a GGUF file." }
        val version = input.readIntLE()
        require(version in 2..3) { "Unsupported GGUF version $version." }
        val tensorCount = input.readLongLE()
        val metadataCount = input.readLongLE()
        require(tensorCount >= 0 && metadataCount in 1..1_000_000) { "Invalid GGUF header." }
        var architecture = ""
        var name = file.nameWithoutExtension
        repeat(metadataCount.toInt()) {
            val key = input.readGgufString()
            val type = input.readIntLE()
            if (type == 8 && (key == "general.architecture" || key == "general.name")) {
                val value = input.readGgufString()
                if (key == "general.architecture") architecture = value else name = value
            } else {
                input.skipValue(type)
            }
        }
        require(architecture.isNotBlank()) { "GGUF has no architecture metadata." }
        GgufMetadata(version, architecture, name)
    }

    private fun RandomAccessFile.skipValue(type: Int) {
        when (type) {
            0, 1, 7 -> skipChecked(1)
            2, 3 -> skipChecked(2)
            4, 5, 6 -> skipChecked(4)
            10, 11, 12 -> skipChecked(8)
            8 -> readGgufString()
            9 -> {
                val elementType = readIntLE()
                val count = readLongLE()
                require(count in 0..MAX_ARRAY) { "Invalid GGUF array length." }
                repeat(count.toInt()) { skipValue(elementType) }
            }
            else -> error("Unsupported GGUF metadata type $type.")
        }
    }

    private fun RandomAccessFile.readGgufString(): String {
        val length = readLongLE()
        require(length in 0..MAX_STRING) { "Invalid GGUF string length." }
        val bytes = ByteArray(length.toInt())
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun RandomAccessFile.skipChecked(bytes: Long) {
        if (bytes < 0 || filePointer + bytes > length()) throw EOFException("Truncated GGUF metadata.")
        seek(filePointer + bytes)
    }

    private fun RandomAccessFile.readIntLE(): Int =
        Integer.reverseBytes(readInt())

    private fun RandomAccessFile.readLongLE(): Long =
        java.lang.Long.reverseBytes(readLong())
}

