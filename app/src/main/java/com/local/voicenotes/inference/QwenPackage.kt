package com.local.voicenotes.inference

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.InflaterInputStream

/** Bounded reader for the published LiteRT-LM 1.x section table. */
internal object QwenPackage {
    fun extract(file: File, directory: File) {
        val complete = File(directory, "complete")
        if (complete.isFile && complete.readText() == file.length().toString() &&
            listOf("tf_lite_audio_encoder_hw.tflite", "tf_lite_embedder.tflite", "tf_lite_prefill_decode.tflite", "tokenizer.json").all { File(directory,it).isFile }) return
        directory.mkdirs()
        complete.delete()
        RandomAccessFile(file, "r").use { source ->
            val bytes = ByteArray(minOf(source.length(), 1024L * 1024).toInt())
            source.readFully(bytes)
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(String(bytes, 0, 8) == "LITERTLM" && b.getInt(8) == 1) { "Unsupported LiteRT-LM package version" }
            fun valid(p: Int, n: Int = 4): Int { require(p >= 0 && n >= 0 && p.toLong()+n <= bytes.size); return p }
            fun indirect(p: Int): Int = valid(p + b.getInt(valid(p)))
            fun field(p: Int, i: Int): Int {
                val vt = valid(p - b.getInt(valid(p)), 4)
                val length = b.getShort(vt).toInt() and 65535
                if (4+2*i >= length) return 0
                val off = b.getShort(valid(vt+4+2*i,2)).toInt() and 65535
                return if (off == 0) 0 else valid(p+off)
            }
            fun string(p: Int): String {
                val start = indirect(p)
                val length = b.getInt(start)
                valid(start+4,length)
                return String(bytes,start+4,length,Charsets.UTF_8)
            }
            val root = indirect(32)
            val sections = indirect(field(root,1))
            val vector = indirect(field(sections,0))
            val count = b.getInt(vector)
            require(count in 1..32)
            val expected = setOf("tf_lite_audio_encoder_hw", "tf_lite_embedder", "tf_lite_prefill_decode")
            val found = mutableSetOf<String>()
            var tokenizerFound = false
            for (i in 0 until count) {
                val section = indirect(valid(vector+4+4*i))
                val begin = b.getLong(valid(field(section,1),8))
                val end = b.getLong(valid(field(section,2),8))
                require(begin >= bytes.size.coerceAtMost(16384) && end > begin && end <= source.length()) { "Invalid package section range" }
                val kind = b.get(valid(field(section,3),1)).toInt()
                var name = ""
                val itemField = field(section,0)
                if (itemField != 0) {
                    val items = indirect(itemField)
                    val itemCount = b.getInt(items)
                    require(itemCount in 0..64)
                    for (j in 0 until itemCount) {
                        val item = indirect(valid(items+4+4*j))
                        if (string(field(item,0)) == "model_type") {
                            val value = indirect(field(item,2))
                            name = string(field(value,0))
                        }
                    }
                }
                if (kind == 3 && name in expected) {
                    val target = File(directory,"$name.tflite")
                    source.seek(begin)
                    target.outputStream().use { output ->
                        val buffer = ByteArray(1024*1024)
                        var remaining = end-begin
                        while (remaining > 0) {
                            val length = minOf(remaining,buffer.size.toLong()).toInt()
                            source.readFully(buffer,0,length)
                            output.write(buffer,0,length)
                            remaining -= length
                        }
                    }
                    found.add(name)
                } else if (kind == 6) {
                    require(end-begin in 9..16L*1024*1024)
                    source.seek(begin)
                    val raw = ByteArray((end-begin).toInt()).also(source::readFully)
                    val expanded = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).long
                    require(expanded in 1..32L*1024*1024)
                    InflaterInputStream(raw.inputStream().apply { skip(8) }).use { input ->
                        val output = File(directory,"tokenizer.json")
                        output.outputStream().use { sink ->
                            val buffer = ByteArray(65536)
                            var written = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                written += n
                                require(written <= expanded)
                                sink.write(buffer,0,n)
                            }
                            require(written == expanded)
                        }
                    }
                    tokenizerFound = true
                }
            }
            require(found == expected && tokenizerFound) { "Missing Qwen encoder, cached decoder, embedder or tokenizer" }
            complete.writeText(file.length().toString())
        }
    }
}
