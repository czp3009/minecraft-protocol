package com.hiczp.minecraft.buildlogic

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VanillaConfigurationCaptureResultTest {
    @Test
    fun analysisKeepsCompleteAndKnownPackRegistryBranchesIndependent() {
        val vanillaConfigurationCaptureResult = VanillaConfigurationCaptureResult(
            offeredKnownPacksPayload = KnownPacksPayload(listOf(KnownPackPayload("test", "pack", "1"))),
            enabledFeatureFlagsPayload = FeatureFlagsPayload(listOf("test:feature")),
            completeSynchronizedRegistryPayloads = listOf(
                RegistryPayload("test:complete", emptyList()),
            ),
            knownPackSynchronizedRegistryPayloads = listOf(
                RegistryPayload(
                    "test:known_first",
                    listOf(RegistryEntryPayload("test:entry", null)),
                ),
                RegistryPayload("test:known_second", emptyList()),
            ),
            registryTagsPayload = TagsPayload(listOf(RegistryTagsPayload("test:tags", emptyList()))),
            completeConfigurationPacketSequence = listOf("configuration/clientbound/registry_data"),
            knownPackConfigurationPacketSequence = listOf("configuration/clientbound/known_packs"),
        )

        val analysisJson = vanillaConfigurationCaptureResult.toAnalysisJson()
        val decoded = VanillaConfigurationCaptureResult.fromAnalysisJson(
            analysisJson,
        )

        assertEquals(2, analysisJson.getValue("schema_version").jsonPrimitive.content.toInt())
        assertEquals(1, analysisJson.getValue("complete_registries").jsonArray.size)
        assertEquals(2, analysisJson.getValue("known_pack_registries").jsonArray.size)
        assertEquals(vanillaConfigurationCaptureResult, decoded)
    }

    @Test
    fun completeRegistryCaptureRequiresEntryDataForItsOwnContract() {
        assertFailsWith<IllegalArgumentException> {
            VanillaConfigurationCaptureResult(
                offeredKnownPacksPayload = KnownPacksPayload(emptyList()),
                enabledFeatureFlagsPayload = FeatureFlagsPayload(emptyList()),
                completeSynchronizedRegistryPayloads = listOf(
                    RegistryPayload(
                        "test:complete",
                        listOf(RegistryEntryPayload("test:missing_data", null)),
                    ),
                ),
                knownPackSynchronizedRegistryPayloads = emptyList(),
                registryTagsPayload = TagsPayload(emptyList()),
                completeConfigurationPacketSequence = emptyList(),
                knownPackConfigurationPacketSequence = emptyList(),
            )
        }
    }
}
