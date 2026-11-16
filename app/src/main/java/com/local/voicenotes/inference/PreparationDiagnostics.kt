package com.local.voicenotes.inference

internal object PreparationDiagnostics {
    /** User-facing error for a failed CPU session preparation. */
    fun message(modelName: String, error: Throwable): String {
        val reason = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
        return "CPU preparation failed for $modelName. $reason"
    }
}
