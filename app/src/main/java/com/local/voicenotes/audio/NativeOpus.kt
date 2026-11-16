package com.local.voicenotes.audio

internal class NativeOpusControl(private val cancelled: () -> Boolean) {
    fun isCancelled(): Boolean = cancelled()
}

/** JNI is deliberately narrow: one owned decoder per synchronous call, never shared across threads. */
internal object NativeOpus {
    val available: Boolean by lazy {
        try { System.loadLibrary("voicenotes_opus"); true }
        catch (_: LinkageError) { false }
        catch (_: SecurityException) { false }
    }

    external fun open(fd: Int, offset: Long, length: Long, control: NativeOpusControl, status: IntArray): Long
    external fun total(handle: Long): Long
    external fun read(handle: Long, pcm: FloatArray): Int
    external fun close(handle: Long)
}
