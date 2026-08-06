@file:OptIn(
    InternalDataComponentRegistryApi::class,
)

package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal data class NamedDataComponentSample(
    val name: String,
    val type: DataComponentType,
    val value: DataComponent,
)

internal fun dataComponentTestSamples(): List<NamedDataComponentSample> =
    buildList {
        for (type in DataComponentType.entries) {
            val serializer = serializerForDataComponentType(type)
            for (profile in ProtocolSampleProfile.entries) {
                val value = runCatching {
                    serializer.protocolValue(profile)
                }.getOrNull() ?: continue
                add(
                    NamedDataComponentSample(
                        name = "${type.wireName.substringAfter(':')}-${profile.name.lowercase()}",
                        type = type,
                        value = value,
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
        assertEquals(expectedTypes, samples.map { it.type }.toSet())

        val samplesByType = samples.groupingBy { it.type }.eachCount()
        assertTrue(samplesByType.values.all { it > 0 })

        for (sample in samples) {
            val stack = ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(sample.value),
                ),
            )
            val bytes = MinecraftProtocolFormat.encodeToByteArray(
                ItemStack.serializer(),
                stack,
            )
            assertEquals(
                stack,
                MinecraftProtocolFormat.decodeFromByteArray(
                    ItemStack.serializer(),
                    bytes,
                ),
                sample.name,
            )
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun serializerForDataComponentType(
    type: DataComponentType,
): KSerializer<DataComponent> =
    GeneratedDataComponentSerializers.serializer(type) as
            KSerializer<DataComponent>
