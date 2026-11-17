package com.local.voicenotes.model

import com.local.voicenotes.chooseSelection
import com.local.voicenotes.domain.ImportedModel
import com.local.voicenotes.ui.localModelFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelSpecsTest {
    private val parakeet = ImportedModel(ParakeetUltraDownload.MODEL_ID, "p.tflite", "/p", 1, "parakeet-ultra", ParakeetUltraModels.CPU_BACKEND, true)
    private val arabic = CohereArabicModel.record("/models/cohere.gguf")
    private val cloud = ModelRecords.builtInMistral

    @Test fun downloadsArePinnedAndDistinct() {
        assertEquals(ModelSpecs.all.size, ModelSpecs.all.map { it.id }.toSet().size)
        assertEquals(ModelSpecs.all.size, ModelSpecs.all.map { it.fileName }.toSet().size)
        for (spec in ModelSpecs.all - ModelSpecs.PARAKEET) {
            assertTrue(spec.url, Regex("/resolve/[0-9a-f]{40}/").containsMatchIn(spec.url))
            assertTrue(spec.url.endsWith("/" + spec.fileName))
            assertTrue(spec.sha256.matches(Regex("[0-9a-f]{64}")))
        }
        assertEquals(1_510_365_440L, ModelSpecs.COHERE_ARABIC.sizeBytes)
        assertEquals(ModelSpecs.GEMMA_E2B, ModelSpecs.byId("gemma-e2b"))
        assertNull(ModelSpecs.byId("unknown"))
    }

    @Test fun settingsParseWithDefaults() {
        assertEquals(LocalModelChoice.PARAKEET, LocalModelChoice.parse(null))
        assertEquals(LocalModelChoice.ARABIC, LocalModelChoice.parse("ARABIC"))
        assertEquals(TranslationModel.GEMMA_E4B, TranslationModel.parse("bogus"))
        assertEquals(TargetLanguage.GERMAN, TargetLanguage.parse(null))
        val defaults = TranslationSettings()
        assertFalse(defaults.enabled)
        assertEquals("German", defaults.languageName)
    }

    @Test fun customTargetNeedsAValidName() {
        val custom = TranslationSettings(enabled = true, target = TargetLanguage.CUSTOM)
        assertFalse(custom.languageReady)
        val french = custom.copy(customLanguage = "  Swiss   French ")
        assertTrue(french.languageReady)
        assertEquals("Swiss French", french.languageName)
    }

    @Test fun bothTranslationModelsCanBeInstalledAtOnce() {
        val both = setOf(ModelSpecs.GEMMA_E4B.id, ModelSpecs.GEMMA_E2B.id, ModelSpecs.COHERE_ARABIC.id)
        assertEquals(listOf(ModelSpecs.GEMMA_E4B, ModelSpecs.GEMMA_E2B), installedTranslationSpecs(both))
        assertEquals(listOf(ModelSpecs.GEMMA_E2B), installedTranslationSpecs(setOf(ModelSpecs.GEMMA_E2B.id)))
        assertEquals(emptyList<DownloadSpec>(), installedTranslationSpecs(setOf(ModelSpecs.PARAKEET.id)))
    }

    @Test fun localChoicePicksOnlyItsOwnModel() {
        val models = listOf(cloud, parakeet, arabic)
        assertEquals(arabic.id, localModelFor(models, LocalModelChoice.ARABIC)?.id)
        assertEquals(parakeet.id, localModelFor(models, LocalModelChoice.PARAKEET, selectedId = arabic.id)?.id)
        assertNull(localModelFor(listOf(cloud, parakeet), LocalModelChoice.ARABIC))
        assertNull(localModelFor(listOf(cloud, arabic), LocalModelChoice.PARAKEET))
    }

    /** With Arabic chosen but not installed, an automatic selection must not fall back to Parakeet. */
    @Test fun automaticSelectionFollowsTheLocalChoice() {
        val arabicChosen = { models: List<ImportedModel> -> localModelFor(models, LocalModelChoice.ARABIC) }
        assertNull(chooseSelection(listOf(cloud, parakeet), current = null, saved = "", pickLocal = arabicChosen))
        assertEquals(arabic.id, chooseSelection(listOf(cloud, parakeet, arabic), null, "", arabicChosen))
        // An explicit choice still wins.
        assertEquals(parakeet.id, chooseSelection(listOf(cloud, parakeet, arabic), null, parakeet.id, arabicChosen))
    }
}
