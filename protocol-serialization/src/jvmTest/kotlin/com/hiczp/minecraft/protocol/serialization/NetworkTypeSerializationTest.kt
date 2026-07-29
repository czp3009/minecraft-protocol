package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkTypeSerializationTest {
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
            val bytes = MinecraftFormat.encodeToByteArray(
                ItemStack.serializer(),
                stack,
            )
            assertEquals(
                stack,
                MinecraftFormat.decodeFromByteArray(
                    ItemStack.serializer(),
                    bytes,
                ),
                sample.name,
            )
        }
    }

    @Test
    fun `finite registry unions cover every executable branch`() {
        assertSamplesRoundTrip(
            ParticleOptionsHolder.serializer(),
            particleRegistrySamples().map {
                ParticleOptionsHolder(it.value)
            } + additionalParticleBranchSamples().map {
                ParticleOptionsHolder(it.value)
            },
        )
        assertSamplesRoundTrip(
            CommandParserHolder.serializer(),
            commandParserRegistrySamples().map {
                CommandParserHolder(it.value)
            },
        )
        assertSamplesRoundTrip(
            ConsumeEffectHolder.serializer(),
            consumeEffectRegistrySamples().map {
                ConsumeEffectHolder(it.value)
            },
        )
        assertSamplesRoundTrip(
            NumberFormatHolder.serializer(),
            numberFormatRegistrySamples().map {
                NumberFormatHolder(it.value)
            },
        )
        assertSamplesRoundTrip(
            SlotDisplayHolder.serializer(),
            slotDisplayRegistrySamples().map {
                SlotDisplayHolder(it.value)
            },
        )
        assertSamplesRoundTrip(
            RecipeDisplayHolder.serializer(),
            recipeDisplayRegistrySamples().map {
                RecipeDisplayHolder(it.value)
            },
        )
    }

    @Test
    fun `entity metadata exercises every vanilla serializer id`() {
        val samples = entityDataValueSamples()
        val serializerIds = samples.map { sample ->
            leadingVarInt(
                MinecraftFormat.encodeToByteArray(
                    EntityDataValue.serializer(),
                    sample.value,
                ),
            )
        }.toSet()
        assertEquals((0..42).toSet(), serializerIds)

        samples.forEach { sample ->
            val bytes = MinecraftFormat.encodeToByteArray(
                EntityDataValue.serializer(),
                sample.value,
            )
            assertEquals(
                sample.value,
                MinecraftFormat.decodeFromByteArray(
                    EntityDataValue.serializer(),
                    bytes,
                ),
                sample.name,
            )
        }
    }

    private fun <T> assertSamplesRoundTrip(
        serializer: KSerializer<T>,
        samples: List<T>,
    ) {
        samples.forEach { sample ->
            val bytes = MinecraftFormat.encodeToByteArray(serializer, sample)
            assertEquals(
                sample,
                MinecraftFormat.decodeFromByteArray(serializer, bytes),
            )
        }
    }

    private fun leadingVarInt(bytes: ByteArray): Int {
        var result = 0
        var shift = 0
        for (byte in bytes.take(5)) {
            val current = byte.toInt() and 0xFF
            result = result or ((current and 0x7F) shl shift)
            if (current and 0x80 == 0) {
                return result
            }
            shift += 7
        }
        error("Value does not start with a valid VarInt")
    }
}

@Serializable
private data class ParticleOptionsHolder(
    val value: ParticleOptions,
)

@Serializable
private data class CommandParserHolder(
    val value: CommandParser,
)

@Serializable
private data class ConsumeEffectHolder(
    val value: ConsumeEffect,
)

@Serializable
private data class NumberFormatHolder(
    val value: NumberFormat,
)

@Serializable
private data class SlotDisplayHolder(
    val value: SlotDisplay,
)

@Serializable
private data class RecipeDisplayHolder(
    val value: RecipeDisplay,
)
