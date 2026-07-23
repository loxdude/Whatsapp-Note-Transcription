package com.local.voicenotes.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.inference.NativeParakeetBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

// Versioned deliberately: the prior registry used a different model format.
private val Context.modelDataStore by preferencesDataStore("models_v2")

class ModelRepository(private val context: Context) {
    private val modelsKey = stringPreferencesKey("imported_models")
    private val selectedKey = stringPreferencesKey("selected_model")

    suspend fun models(): List<ImportedModel> = context.modelDataStore.data.map { prefs ->
        decodeModels(prefs[modelsKey].orEmpty())
    }.first().filter { File(it.path).isFile }

    suspend fun selectedModelId(): String? = context.modelDataStore.data.map { it[selectedKey] }.first()

    suspend fun select(id: String) = context.modelDataStore.edit { it[selectedKey] = id }

    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): ImportedModel = withContext(Dispatchers.IO) {
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val partial = File(modelsDir, "import-${System.nanoTime()}.partial")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            var sourceLength = -1L
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                sourceLength = descriptor.statSize
                require(sourceLength >= 300L * 1024L * 1024L) {
                    "This is not a complete Parakeet model. Select the 416 MB ggml-parakeet .bin file itself."
                }
                require(context.filesDir.usableSpace > sourceLength + 256L * 1024L * 1024L) {
                    "Not enough free space. Keep at least 256 MB free after importing the model."
                }
                FileInputStream(descriptor.fileDescriptor).use { input -> FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var copied = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count
                        onProgress((copied.toFloat() / sourceLength).coerceIn(0f, 1f))
                    }
                    output.fd.sync()
                } }
            } ?: error("Cannot open the selected model.")
            require(partial.length() == sourceLength) { "Model import was incomplete." }

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = File(modelsDir, "$hash.bin")
            if (!destination.exists()) require(partial.renameTo(destination)) { "Could not finalize model import." }
            else partial.delete()

            val enabled = NativeParakeetBridge.isSupportedModel(destination.absolutePath)
            val sourceName = context.contentResolver.query(
                uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: uri.lastPathSegment
                ?: "Parakeet model"
            val note = if (enabled) {
                "Ready: whisper.cpp Parakeet, fully offline"
            } else {
                "Not a supported whisper.cpp Parakeet ggml .bin model."
            }
            val model = ImportedModel(
                id = hash,
                displayName = sourceName.takeIf { it.contains("parakeet", ignoreCase = true) }
                    ?.removeSuffix(".bin")
                    ?: "Parakeet TDT 0.6B v3",
                path = destination.absolutePath,
                sizeBytes = destination.length(),
                architecture = "parakeet",
                backend = "parakeet",
                enabled = enabled,
                note = note
            )
            save((models().filterNot { it.id == hash } + model).sortedByDescending { it.enabled })
            if (enabled) select(hash)
            onProgress(1f)
            model
        } catch (t: Throwable) {
            partial.delete()
            throw t
        }
    }

    private suspend fun save(models: List<ImportedModel>) = context.modelDataStore.edit {
        it[modelsKey] = JSONArray().apply {
            models.forEach { model ->
                put(JSONObject().apply {
                    put("id", model.id); put("name", model.displayName); put("path", model.path)
                    put("size", model.sizeBytes); put("architecture", model.architecture)
                    put("backend", model.backend); put("enabled", model.enabled); put("note", model.note)
                })
            }
        }.toString()
    }

    private fun decodeModels(value: String): List<ImportedModel> = runCatching {
        val array = if (value.isBlank()) JSONArray() else JSONArray(value)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ImportedModel(
                item.getString("id"), item.getString("name"), item.getString("path"), item.getLong("size"),
                item.getString("architecture"), item.optString("backend"), item.optBoolean("enabled"),
                item.optString("note")
            )
        }
    }.getOrDefault(emptyList())
}
