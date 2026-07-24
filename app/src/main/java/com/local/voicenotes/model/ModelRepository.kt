package com.local.voicenotes.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.local.voicenotes.domain.ImportedModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private val Context.modelDataStore by preferencesDataStore("litert_models")

/**
 * Stores a persistent Storage Access Framework URI rather than copying a 1.2 GB
 * model into app-private storage. This keeps one model copy on the phone and
 * lets LiteRT memory-map it directly.
 */
class ModelRepository(private val context: Context) {
    private val modelsKey = stringPreferencesKey("imported_models")
    private val selectedKey = stringPreferencesKey("selected_model")

    suspend fun models(): List<ImportedModel> = context.modelDataStore.data.map { prefs ->
        decodeModels(prefs[modelsKey].orEmpty())
    }.first().filter(::isReadable).filterNot { it.architecture == "qwen3-asr-0.6b" }

    suspend fun selectedModelId(): String? = context.modelDataStore.data.map { it[selectedKey] }.first()

    suspend fun select(id: String) = context.modelDataStore.edit { it[selectedKey] = id }

    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): ImportedModel = withContext(Dispatchers.IO) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val resolver = context.contentResolver
        val metadata = resolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            require(cursor.moveToFirst()) { "Cannot inspect the selected model." }
            cursor.getString(0).orEmpty() to cursor.getLong(1)
        }
        val displayName = metadata?.first ?: uri.lastPathSegment.orEmpty()
        val sourceLength = metadata?.second
            ?: resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            ?: -1L

        require(sourceLength > 0) { "The selected model has no readable size." }
        require(displayName.endsWith(".tflite", ignoreCase = true)) {
            "Choose a .tflite model from the LiteRT Parakeet collection."
        }
        val header = resolver.openInputStream(uri)?.use { input ->
            ByteArray(8).also { require(input.read(it) == it.size) { "The model header is truncated." } }
        } ?: error("Cannot read the selected model.")
        require(header.copyOfRange(4, 8).contentEquals("TFL3".encodeToByteArray())) {
            "This is not a valid LiteRT/TFLite flatbuffer."
        }

        val lowerName = displayName.lowercase()
        val isParakeet = "parakeet" in lowerName && ("tdt" in lowerName || "0.6b_v3" in lowerName)
        val isStateful = "stateful" in lowerName
        val isSm8650 = "sm8650" in lowerName
        val enabled = isParakeet && isStateful
        val note = when {
            !isParakeet -> "Not recognized as a Parakeet TDT v3 export"
            !isStateful -> "Stateless 5 s export; select the stateful model for WhatsApp notes"
            isSm8650 -> "Ready · stateful · Snapdragon 8 Gen 3 NPU"
            else -> "Ready · stateful generic model (SM8650 export is preferred)"
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest("${uri}|$sourceLength".encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        val model = ImportedModel(
            id = hash,
            displayName = displayName,
            path = uri.toString(),
            sizeBytes = sourceLength,
            architecture = "parakeet-tdt-0.6b-v3",
            backend = if (isSm8650) "litert-qnn-sm8650" else "litert-qnn",
            enabled = enabled,
            note = note
        )
        val updated = (models().filterNot { it.id == hash } + model).sortedByDescending { it.enabled }
        save(updated)
        if (enabled) select(hash)
        onProgress(1f)
        model
    }

    private suspend fun save(models: List<ImportedModel>) = context.modelDataStore.edit {
        it[modelsKey] = JSONArray().apply {
            models.forEach { model ->
                put(JSONObject().apply {
                    put("id", model.id)
                    put("name", model.displayName)
                    put("path", model.path)
                    put("size", model.sizeBytes)
                    put("architecture", model.architecture)
                    put("backend", model.backend)
                    put("enabled", model.enabled)
                    put("note", model.note)
                })
            }
        }.toString()
    }

    private fun decodeModels(value: String): List<ImportedModel> = runCatching {
        val array = if (value.isBlank()) JSONArray() else JSONArray(value)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ImportedModel(
                item.getString("id"),
                item.getString("name"),
                item.getString("path"),
                item.getLong("size"),
                item.getString("architecture"),
                item.optString("backend"),
                item.optBoolean("enabled"),
                item.optString("note")
            )
        }
    }.getOrDefault(emptyList())

    private fun isReadable(model: ImportedModel): Boolean = runCatching {
        val uri = Uri.parse(model.path)
        if (uri.scheme == "content") {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length >= 0 } == true
        } else {
            File(model.path).isFile
        }
    }.getOrDefault(false)
}
