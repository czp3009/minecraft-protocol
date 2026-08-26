package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkTypeSerializationTest {
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
        val serializerIds = samples.map { namedNetworkTypeSample ->
            leadingVarInt(
                MinecraftProtocolFormat.encodeToByteArray(
                    namedNetworkTypeSample.value,
                ),
            )
        }.toSet()
        assertEquals((0..42).toSet(), serializerIds)

        samples.forEach { namedNetworkTypeSample ->
            val byteArray = MinecraftProtocolFormat.encodeToByteArray(
                namedNetworkTypeSample.value,
            )
            assertEquals(
                namedNetworkTypeSample.value,
                MinecraftProtocolFormat.decodeFromByteArray<EntityDataValue>(
                    byteArray,
                ),
                namedNetworkTypeSample.name,
            )
        }
    }

    private fun <T> assertSamplesRoundTrip(
        kSerializer: KSerializer<T>,
        samples: List<T>,
    ) {
        samples.forEach { sample ->
            val byteArray = MinecraftProtocolFormat.encodeToByteArray(kSerializer, sample)
            assertEquals(
                sample,
                MinecraftProtocolFormat.decodeFromByteArray(kSerializer, byteArray),
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
