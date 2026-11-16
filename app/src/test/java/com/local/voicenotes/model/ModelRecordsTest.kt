package com.local.voicenotes.model

import com.local.voicenotes.domain.ImportedModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRecordsTest {
    private fun record(id: String, name: String = "parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite", backend: String = "litert-cpu", enabled: Boolean = true) =
        ImportedModel(id, name, "content://models/$id", 1L, "parakeet-tdt-0.6b-v3", backend, enabled)

    /** Stand-in for a raw JSON record: either a decodable model or garbage. */
    private data class Raw(val id: String?, val model: ImportedModel?)

    private fun decode(raw: Raw) = raw.model ?: error("corrupt")

    @Test fun oneCorruptRecordDoesNotHideTheOthers() {
        val errors = mutableListOf<Throwable>()
        val decoded = ModelRecords.decodeEach(
            listOf(Raw("a", record("a")), Raw(null, null), Raw("b", record("b"))), ::decode, { errors += it }
        )
        assertEquals(listOf("a", "b"), decoded.map { it.id })
        assertEquals(1, errors.size)
    }

    @Test fun persistedCloudEntryIsIgnoredOnRead() {
        val decoded = ModelRecords.decodeEach(listOf(Raw("m", ModelRecords.builtInMistral), Raw("a", record("a"))), ::decode)
        assertEquals(listOf("a"), decoded.map { it.id })
    }

    @Test fun importKeepsUnreadableAndUndecodableRecordsAndDropsOnlyDuplicatesAndCloud() {
        val stored = listOf(
            Raw("unreadable", record("unreadable")), Raw(null, null), Raw("dup", record("dup")),
            Raw(ModelRecords.BUILT_IN_MISTRAL_ID, ModelRecords.builtInMistral)
        )
        val added = Raw("dup", record("dup", name = "new.tflite"))
        val merged = ModelRecords.mergeImport(stored, { it.id }, added, "dup")
        assertEquals(listOf(stored[0], stored[1], added), merged)
    }

    @Test fun catalogFiltersUnreadableButDoesNotTouchStorageAndPutsCloudFirst() {
        val stored = listOf(record("a"), record("gone"))
        val catalog = ModelRecords.catalog(stored, "SM8750") { it.id != "gone" }
        assertEquals(listOf(ModelRecords.BUILT_IN_MISTRAL_ID, "a"), catalog.map { it.id })
        assertEquals(2, stored.size)
    }

    @Test fun enabledIsReEvaluatedForTheCurrentSocOnEveryRead() {
        val sm8450 = record("npu", name = "parakeet_tdt_0.6b_v3_5s_f32_stateful_Qualcomm_SM8450.tflite", backend = "litert-qnn")
        val onFold4 = ModelRecords.refresh(sm8450, "SM8475")
        assertFalse(onFold4.enabled)
        assertTrue(onFold4.note.contains("blank"))
        // Persisted enabled=false (e.g. imported on a Fold 4) is not trusted on an SM8450 device.
        assertTrue(ModelRecords.refresh(sm8450.copy(enabled = false), "SM8450").enabled)
        // Duplicate-download names route to CPU even if an old record stored the NPU backend.
        val renamed = ModelRecords.refresh(record("cpu", name = "parakeet_tdt_0.6b_v3_5s_i8_stateful (1).tflite", backend = "litert-qnn"), "SM8475")
        assertEquals("litert-cpu", renamed.backend)
        assertTrue(renamed.enabled)
    }

    @Test fun nonLiteRtRecordsAreLeftAlone() {
        val other = record("x", backend = "other", enabled = false)
        assertEquals(other, ModelRecords.refresh(other, "SM8475"))
    }
}
