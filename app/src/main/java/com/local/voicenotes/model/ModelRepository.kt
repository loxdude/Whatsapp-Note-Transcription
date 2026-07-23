package com.local.voicenotes.model

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.inference.NativeQwenBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

private val Context.modelDataStore by preferencesDataStore("models")

class ModelRepository(private val context: Context) {
    private val modelsKey = stringPreferencesKey("imported_models")
    private val selectedKey = stringPreferencesKey("selected_model")

    suspend fun models(): List<ImportedModel> = context.modelDataStore.data.map { prefs ->
        decodeModels(prefs[modelsKey].orEmpty())
    }.first().filter { File(it.path).isFile }

    suspend fun selectedModelId(): String? = context.modelDataStore.data.map { it[selectedKey] }.first()

    suspend fun select(id: String) = context.modelDataStore.edit { it[selectedKey] = id }

    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): ImportedModel = withContext(Dispatchers.IO) {
        val sourceLength = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            ?: error("Cannot inspect the selected model.")
        require(sourceLength > 0) { "The selected model has no readable size." }
        val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
        val usable = context.filesDir.usableSpace
        require(usable > sourceLength + 256L * 1024L * 1024L) {
            "Not enough free space. Keep at least 256 MB free after importing the model."
        }
        val partial = File(modelsDir, "import-${System.nanoTime()}.partial")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(partial).use { output ->
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
                }
            } ?: error("Cannot read the selected model.")
            require(partial.length() == sourceLength) { "Model import was incomplete." }
            val metadata = GgufMetadataReader.read(partial)
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val destination = File(modelsDir, "$hash.gguf")
            if (!destination.exists()) require(partial.renameTo(destination)) { "Could not finalize model import." }
            else partial.delete()
            val backend = runCatching { NativeQwenBridge.detectBackend(destination.absolutePath) }.getOrDefault("")
            val enabled = backend == "qwen3"
            val isProjector = metadata.name.contains("mmproj", true) || destination.length() < 300_000_000L
            val note = when {
                enabled -> "Ready for fully offline transcription"
                isProjector -> "Audio projector detected; paired llama.cpp models are not supported by the pinned runtime"
                backend.isNotBlank() -> "Detected backend '$backend' is experimental and disabled"
                else -> "This GGUF layout is not supported by the pinned Qwen runtime"
            }
            val model = ImportedModel(
                id = hash,
                displayName = metadata.name.ifBlank { "Qwen3-ASR model" },
                path = destination.absolutePath,
                sizeBytes = destination.length(),
                architecture = metadata.architecture,
                backend = backend,
                enabled = enabled,
                note = note
            )
            val updated = (models().filterNot { it.id == hash } + model).sortedByDescending { it.enabled }
            save(updated)
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
