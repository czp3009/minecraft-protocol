@file:OptIn(
    InternalDataComponentRegistryApi::class,
)

package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal data class NamedDataComponentSample(
    val name: String,
    val dataComponentType: DataComponentType,
    val value: DataComponent,
)

internal fun dataComponentTestSamples(): List<NamedDataComponentSample> =
    buildList {
        for (dataComponentType in DataComponentType.entries) {
            val kSerializer = serializerForDataComponentType(dataComponentType)
            for (packetSampleProfile in PacketSampleProfile.entries) {
                val dataComponent = runCatching {
                    kSerializer.packetSampleValue(packetSampleProfile)
                }.getOrNull() ?: continue
                add(
                    NamedDataComponentSample(
                        name = "${dataComponentType.wireName.substringAfter(':')}-${packetSampleProfile.name.lowercase()}",
                        dataComponentType = dataComponentType,
                        value = dataComponent,
                    ),
                )
            }
        }
        val trimMaterialHolder = TrimMaterialHolder.Direct(
            TrimMaterialValue(Identifier("minecraft:gold"), TextComponent.literal("Gold")),
        )
        add(
            NamedDataComponentSample(
                "trim-direct", DataComponentType.TRIM,
                DataComponent.Trim(
                    trimMaterialHolder,
                    TrimPatternHolder.Direct(
                        TrimPatternValue(
                            Identifier("minecraft:sentry"),
                            TextComponent.literal("Sentry"),
                            true
                        )
                    ),
                ),
            ),
        )
        add(
            NamedDataComponentSample(
                "provides_trim_material-direct",
                DataComponentType.PROVIDES_TRIM_MATERIAL,
                DataComponent.ProvidesTrimMaterial(trimMaterialHolder)
            )
        )
        add(
            NamedDataComponentSample(
                "instrument-direct", DataComponentType.INSTRUMENT,
                DataComponent.Instrument(
                    InstrumentHolder.Direct(
                        InstrumentValue(
                            SoundEventHolder.Reference(0),
                            0.0f,
                            256.0f,
                            300,
                            TextComponent.literal("Horn")
                        )
                    ),
                ),
            ),
        )
    }

class DataComponentSerializationTest {
    @Test
    fun `every registered data component has an executable network sample`() {
        val samples = dataComponentTestSamples()
        val expectedTypes = DataComponentType.entries.toSet()
        assertEquals(expectedTypes, samples.map { it.dataComponentType }.toSet())

        val samplesByType = samples.groupingBy { it.dataComponentType }.eachCount()
        assertTrue(samplesByType.values.all { it > 0 })

        for (namedDataComponentSample in samples) {
            val itemStack = ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(namedDataComponentSample.value),
                ),
            )
            val byteArray = MinecraftPacketPayloadFormat.encodeToByteArray(
                itemStack,
            )
            assertEquals(
                itemStack,
                MinecraftPacketPayloadFormat.decodeFromByteArray<ItemStack>(
                    byteArray,
                ),
                namedDataComponentSample.name,
            )
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun serializerForDataComponentType(
    dataComponentType: DataComponentType,
): KSerializer<DataComponent> =
    GeneratedDataComponentSerializers.serializer(dataComponentType) as
            KSerializer<DataComponent>
