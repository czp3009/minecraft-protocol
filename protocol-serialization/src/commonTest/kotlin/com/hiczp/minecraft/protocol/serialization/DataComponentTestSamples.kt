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
            for (protocolSampleProfile in ProtocolSampleProfile.entries) {
                val dataComponent = runCatching {
                    kSerializer.protocolValue(protocolSampleProfile)
                }.getOrNull() ?: continue
                add(
                    NamedDataComponentSample(
                        name = "${dataComponentType.wireName.substringAfter(':')}-${protocolSampleProfile.name.lowercase()}",
                        dataComponentType = dataComponentType,
                        value = dataComponent,
                    ),
                )
            }
        }
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
            val byteArray = MinecraftProtocolFormat.encodeToByteArray(
                itemStack,
            )
            assertEquals(
                itemStack,
                MinecraftProtocolFormat.decodeFromByteArray<ItemStack>(
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
