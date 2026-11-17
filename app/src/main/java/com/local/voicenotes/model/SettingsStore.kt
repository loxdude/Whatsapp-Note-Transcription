package com.local.voicenotes.model

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

private val Context.settingsDataStore by preferencesDataStore("model_settings")

/** Local speech model and translation settings, persisted in DataStore. */
class SettingsStore(private val context: Context) {
    private val localModelKey = stringPreferencesKey("local_model")
    private val translationEnabledKey = booleanPreferencesKey("translation_enabled")
    private val translationModelKey = stringPreferencesKey("translation_model")
    private val targetLanguageKey = stringPreferencesKey("translation_target")
    private val customLanguageKey = stringPreferencesKey("translation_custom_language")

    data class Snapshot(val localModel: LocalModelChoice, val translation: TranslationSettings)

    suspend fun load(): Snapshot = withContext(Dispatchers.IO) {
        val prefs: Preferences = context.settingsDataStore.data.first()
        Snapshot(
            localModel = LocalModelChoice.parse(prefs[localModelKey]),
            translation = TranslationSettings(
                enabled = prefs[translationEnabledKey] ?: false,
                model = TranslationModel.parse(prefs[translationModelKey]),
                target = TargetLanguage.parse(prefs[targetLanguageKey]),
                customLanguage = prefs[customLanguageKey].orEmpty()
            )
        )
    }

    suspend fun saveLocalModel(choice: LocalModelChoice) = withContext(Dispatchers.IO) {
        context.settingsDataStore.edit { it[localModelKey] = choice.name }
        Unit
    }

    suspend fun saveTranslation(settings: TranslationSettings) = withContext(Dispatchers.IO) {
        context.settingsDataStore.edit {
            it[translationEnabledKey] = settings.enabled
            it[translationModelKey] = settings.model.name
            it[targetLanguageKey] = settings.target.name
            it[customLanguageKey] = settings.customLanguage
        }
        Unit
    }
}
