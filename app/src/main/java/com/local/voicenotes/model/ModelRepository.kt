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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

private val Context.modelDataStore by preferencesDataStore("litert_models")

/**
 * Pure rules for the persisted model list, independent of JSON and Android so they can be unit
 * tested on the JVM. [ModelRepository] supplies the JSON codec and file checks.
 */
internal object ModelRecords {
    /** Id of the built-in cloud model. It is synthesised on every read and never persisted. */
    const val BUILT_IN_MISTRAL_ID = "mistral-api"

    /**
     * Decodes each raw record independently: a corrupt record is skipped instead of hiding every
     * other imported model. A persisted cloud entry (written by older versions) is ignored.
     */
    fun <T> decodeEach(records: List<T>, decode: (T) -> ImportedModel, onError: (Throwable) -> Unit = {}): List<ImportedModel> =
        records.mapNotNull { record ->
            try {
                decode(record)
            } catch (error: Exception) {
                onError(error)
                null
            }
        }.filterNot { it.id == BUILT_IN_MISTRAL_ID || it.isApiModel }

    /**
     * Builds the list to persist after importing [added]. Works on the *raw* stored records, so
     * records that are currently unreadable (revoked URI, unmounted storage) or that this version
     * cannot decode are preserved. Only an older record with the same id and the built-in cloud
     * entry are dropped. [idOf] returns null for records without a readable id.
     */
    fun <T> mergeImport(stored: List<T>, idOf: (T) -> String?, added: T, addedId: String): List<T> =
        stored.filterNot {
            val id = idOf(it)
            id == addedId || id == BUILT_IN_MISTRAL_ID
        } + added

    /**
     * Re-evaluates a stored record on every read instead of trusting the persisted `enabled`
     * flag. Records of the removed Parakeet TDT v3 path (portable CPU or Qualcomm NPU exports)
     * stay listed but disabled, pointing the user to Parakeet Ultra.
     */
    fun refresh(model: ImportedModel): ImportedModel {
        if (ParakeetUltraModels.isReplacedV3(model)) {
            return model.copy(enabled = false, note = ParakeetUltraModels.V3_REPLACED_NOTE)
        }
        if (!ParakeetUltraModels.isLocalBackend(model.backend)) return model
        val evaluation = ParakeetUltraModels.evaluate(model.displayName)
        return model.copy(
            architecture = evaluation.architecture, backend = evaluation.backend,
            enabled = evaluation.enabled, note = evaluation.note
        )
    }

    /** Catalog shown to the user: built-in cloud entry, then readable imports (usable ones first). */
    fun catalog(stored: List<ImportedModel>, isReadable: (ImportedModel) -> Boolean): List<ImportedModel> {
        val imported = stored.filter { ParakeetUltraModels.isReplacedV3(it) || isReadable(it) }
            .map(::refresh).sortedByDescending { it.enabled }
        return (listOf(builtInMistral) + imported).distinctBy { it.id }
    }

    val builtInMistral = ImportedModel(
        id = BUILT_IN_MISTRAL_ID,
        displayName = "Mistral Voxtral Mini (API)",
        path = "",
        sizeBytes = 0L,
        architecture = "voxtral-mini-latest",
        backend = "mistral-api",
        enabled = true,
        note = "Uses Mistral API - requires API key",
        isApiModel = true,
        apiKeyRequired = true
    )
}

/**
 * Stores a persistent Storage Access Framework URI. The inference backend caches
 * the model in app-private storage because CompiledModel requires a file path.
 */
class ModelRepository(private val context: Context) {
    private val modelsKey = stringPreferencesKey("imported_models")
    private val selectedKey = stringPreferencesKey("selected_model")

    /** Reads and checks every stored model on [Dispatchers.IO]; never call-site-thread file I/O. */
    suspend fun models(): List<ImportedModel> = withContext(Dispatchers.IO) {
        val raw = context.modelDataStore.data.first()[modelsKey].orEmpty()
        val stored = ModelRecords.decodeEach(rawRecords(raw), ::decodeRecord)
        ModelRecords.catalog(stored, ::isReadable)
    }

    /**
     * Source paths of stored Parakeet v3 records, readable or not. Their private copies in the
     * model cache are no longer used by any session and can be released.
     */
    suspend fun replacedModelPaths(): List<String> = withContext(Dispatchers.IO) {
        val raw = context.modelDataStore.data.first()[modelsKey].orEmpty()
        ModelRecords.decodeEach(rawRecords(raw), ::decodeRecord)
            .filter(ParakeetUltraModels::isReplacedV3)
            .map { it.path }
    }

    suspend fun selectedModelId(): String? = withContext(Dispatchers.IO) {
        context.modelDataStore.data.first()[selectedKey]
    }

    suspend fun select(id: String) {
        withContext(Dispatchers.IO) { context.modelDataStore.edit { it[selectedKey] = id } }
    }

    /**
     * Validates and stores the model. It does not change the selection: the caller decides
     * whether the result still applies (the import may have been cancelled or superseded).
     */
    suspend fun import(uri: Uri, onProgress: (Float) -> Unit): ImportedModel = withContext(Dispatchers.IO) {
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

        validateThenImport(displayName, sourceLength, {
            resolver.openInputStream(uri) ?: error("Cannot read the selected model.")
        }) { evaluation ->
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)

            val hash = MessageDigest.getInstance("SHA-256")
                .digest("${uri}|$sourceLength".encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            val model = ImportedModel(
                id = hash,
                displayName = displayName,
                path = uri.toString(),
                sizeBytes = sourceLength,
                architecture = evaluation.architecture,
                backend = evaluation.backend,
                enabled = evaluation.enabled,
                note = evaluation.note
            )
            // Do not persist an import that was cancelled while it was being inspected.
            coroutineContext.ensureActive()
            context.modelDataStore.edit { prefs ->
                // Read-modify-write inside edit: atomic with concurrent writers, and based on the
                // raw stored records, so temporarily unreadable models are never dropped.
                val stored = rawRecords(prefs[modelsKey].orEmpty())
                val updated = ModelRecords.mergeImport(stored, ::recordId, encodeRecord(model), hash)
                prefs[modelsKey] = JSONArray().apply { updated.forEach { put(it) } }.toString()
            }
            onProgress(1f)
            model
        }
    }

    /** Persists the verified, app-private encoder written by [ModelDownload]. */
    suspend fun registerDownloaded(file: File, spec: DownloadSpec): ImportedModel = withContext(Dispatchers.IO) {
        val evaluation = ParakeetUltraModels.evaluate(spec.fileName)
        val model = ImportedModel(
            id = ParakeetUltraDownload.MODEL_ID,
            displayName = spec.fileName,
            path = file.absolutePath,
            sizeBytes = spec.sizeBytes,
            architecture = evaluation.architecture,
            backend = evaluation.backend,
            enabled = evaluation.enabled,
            note = evaluation.note
        )
        context.modelDataStore.edit { prefs ->
            val stored = rawRecords(prefs[modelsKey].orEmpty())
            val updated = ModelRecords.mergeImport(stored, ::recordId, encodeRecord(model), model.id)
            prefs[modelsKey] = JSONArray().apply { updated.forEach { put(it) } }.toString()
        }
        model
    }

    /** Removes the record [id] (the caller deletes any app-private file). */
    suspend fun remove(id: String) = withContext(Dispatchers.IO) {
        context.modelDataStore.edit { prefs ->
            val kept = rawRecords(prefs[modelsKey].orEmpty()).filterNot { recordId(it) == id }
            prefs[modelsKey] = JSONArray().apply { kept.forEach { put(it) } }.toString()
            if (prefs[selectedKey] == id) prefs.remove(selectedKey)
        }
    }

    private fun rawRecords(value: String): List<Any?> {
        if (value.isBlank()) return emptyList()
        val array = runCatching { JSONArray(value) }.getOrNull() ?: return emptyList()
        return List(array.length()) { array.opt(it) }
    }

    private fun recordId(record: Any?): String? = (record as? JSONObject)?.optString("id")?.takeIf { it.isNotEmpty() }

    private fun encodeRecord(model: ImportedModel) = JSONObject().apply {
        put("id", model.id)
        put("name", model.displayName)
        put("path", model.path)
        put("size", model.sizeBytes)
        put("architecture", model.architecture)
        put("backend", model.backend)
        put("enabled", model.enabled)
        put("note", model.note)
    }

    private fun decodeRecord(record: Any?): ImportedModel {
        val item = record as? JSONObject ?: error("Model record is not an object.")
        return ImportedModel(
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

    private fun isReadable(model: ImportedModel): Boolean = runCatching {
        val uri = Uri.parse(model.path)
        if (uri.scheme == "content") {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length >= 0 } == true
        } else {
            File(model.path).isFile
        }
    }.getOrDefault(false)
}
