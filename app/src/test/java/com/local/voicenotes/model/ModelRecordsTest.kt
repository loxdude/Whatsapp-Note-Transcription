package com.local.voicenotes.model

import com.local.voicenotes.chooseSelection
import com.local.voicenotes.domain.ImportedModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRecordsTest {
    private fun record(
        id: String,
        name: String = "parakeet_ultra_encoder_30s_i8.tflite",
        backend: String = "litert-ultra-cpu",
        enabled: Boolean = true,
        architecture: String = "parakeet-ultra"
    ) = ImportedModel(id, name, "content://models/$id", 1L, architecture, backend, enabled)

    /** Records as persisted by versions that ran Parakeet TDT v3 (CPU and Qualcomm NPU). */
    private fun v3(id: String, name: String = "parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite", backend: String = "litert-cpu") =
        record(id, name, backend, enabled = true, architecture = "parakeet-tdt-0.6b-v3")

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
        val catalog = ModelRecords.catalog(stored) { it.id != "gone" }
        assertEquals(listOf(ModelRecords.BUILT_IN_MISTRAL_ID, "a"), catalog.map { it.id })
        assertEquals(2, stored.size)
    }

    @Test fun enabledIsReEvaluatedOnEveryRead() {
        // A persisted enabled=false is not trusted for a usable encoder, and vice versa.
        assertTrue(ModelRecords.refresh(record("a", enabled = false)).enabled)
        assertFalse(ModelRecords.refresh(record("b", name = "parakeet_ultra_encoder_30s_f16.tflite")).enabled)
    }

    @Test fun v3RecordsStayListedButDisabledWithAPointerToUltra() {
        val records = listOf(
            v3("cpu"),
            v3("f32", name = "parakeet_tdt_0.6b_v3_5s_f32_stateful.tflite"),
            v3("npu", name = "parakeet_tdt_0.6b_v3_5s_f32_stateful_Qualcomm_SM8650.tflite", backend = "litert-qnn"),
            v3("npu-sm8650", backend = "litert-qnn-sm8650"),
            // Older records identified only by their backend.
            record("legacy-backend", name = "x.tflite", backend = "litert-cpu", architecture = "unknown")
        )
        for (stored in records) {
            val refreshed = ModelRecords.refresh(stored)
            assertTrue(stored.id, ParakeetUltraModels.isReplacedV3(stored))
            assertFalse(stored.id, refreshed.enabled)
            assertEquals(stored.id, ParakeetUltraModels.V3_REPLACED_NOTE, refreshed.note)
            assertTrue(refreshed.note.contains("parakeet_ultra_encoder_30s_i8.tflite"))
            assertTrue(refreshed.note.contains("10s on slower phones"))
            // Identity and source are kept, so the record does not silently disappear.
            assertEquals(stored.copy(enabled = false, note = refreshed.note), refreshed)
        }
        val catalog = ModelRecords.catalog(records + record("ultra")) { true }
        assertEquals(
            listOf(ModelRecords.BUILT_IN_MISTRAL_ID, "ultra", "cpu", "f32", "npu", "npu-sm8650", "legacy-backend"),
            catalog.map { it.id }
        )
        assertFalse(ParakeetUltraModels.isReplacedV3(record("ultra")))
        assertFalse(ParakeetUltraModels.isReplacedV3(ModelRecords.builtInMistral))
    }

    @Test fun selectedV3RecordSelectsNothingAndNeverTheCloudModel() {
        val catalog = ModelRecords.catalog(listOf(v3("v3"))) { true }
        assertNull(chooseSelection(catalog, current = null, saved = "v3"))
        assertNull(chooseSelection(catalog, current = "v3", saved = "v3"))
        // An imported Ultra encoder is picked instead of the replaced model.
        val withUltra = ModelRecords.catalog(listOf(v3("v3"), record("ultra"))) { true }
        assertEquals("ultra", chooseSelection(withUltra, current = null, saved = "v3"))
    }

    @Test fun nonLiteRtRecordsAreLeftAlone() {
        val other = record("x", backend = "other", enabled = false, architecture = "other")
        assertEquals(other, ModelRecords.refresh(other))
    }
}
