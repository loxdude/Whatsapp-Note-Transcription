package com.local.voicenotes.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

class GgufMetadataReaderTest {
    @Test fun readsArchitectureAndName() {
        val file = File.createTempFile("model", ".gguf")
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeInt(Integer.reverseBytes(0x46554747))
            out.writeInt(Integer.reverseBytes(3))
            out.writeLong(java.lang.Long.reverseBytes(0))
            out.writeLong(java.lang.Long.reverseBytes(2))
            writeString(out, "general.architecture")
            out.writeInt(Integer.reverseBytes(8))
            writeString(out, "qwen3-asr")
            writeString(out, "general.name")
            out.writeInt(Integer.reverseBytes(8))
            writeString(out, "Qwen 0.6B")
        }
        val metadata = GgufMetadataReader.read(file)
        assertEquals("qwen3-asr", metadata.architecture)
        assertEquals("Qwen 0.6B", metadata.name)
        file.delete()
    }

    private fun writeString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray()
        out.writeLong(java.lang.Long.reverseBytes(bytes.size.toLong()))
        out.write(bytes)
    }
}
