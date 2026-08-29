package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import com.hiczp.minecraft.world.format.datapack.DataPackFileContent
import kotlinx.io.Buffer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.test.Test
import kotlin.test.assertEquals

class SerializationModuleLookupTest {
    @Test
    fun reifiedCompressedNbtOperationsResolveContextualSerializersFromTheirFormat() {
        val serializersModule = contextualWorldValueSerializersModule()
        val compressedNbtFormat = CompressedNbtFormat(
            NbtFormat(
                NbtFormatConfiguration(
                    serializersModule = serializersModule,
                    nbtRootEncoding = NbtRootEncoding.UNNAMED,
                ),
            ),
        )
        val contextualWorldValue = ContextualWorldValue(7)

        val compressedChunk = compressedNbtFormat.encode(contextualWorldValue, Compression.NONE)
        assertEquals(contextualWorldValue, compressedNbtFormat.decode<ContextualWorldValue>(compressedChunk))

        val buffer = Buffer()
        compressedNbtFormat.encodeToSink(contextualWorldValue, Compression.NONE, buffer)
        assertEquals(
            contextualWorldValue,
            compressedNbtFormat.decodeFromSource<ContextualWorldValue>(buffer, Compression.NONE),
        )
    }

    @Test
    fun reifiedDataPackJsonDecodeResolvesContextualSerializersFromItsJsonFormat() {
        val json = Json {
            serializersModule = contextualWorldValueSerializersModule()
        }
        val jsonFile = DataPackFileContent.JsonFile(Json.parseToJsonElement("{\"value\":7}"))

        assertEquals(ContextualWorldValue(7), jsonFile.decode<ContextualWorldValue>(json))
    }
}

private data class ContextualWorldValue(val value: Int)

@Serializable
private data class ContextualWorldValueSurrogate(val value: Int)

private object ContextualWorldValueSerializer : KSerializer<ContextualWorldValue> {
    override val descriptor = ContextualWorldValueSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ContextualWorldValue) {
        encoder.encodeSerializableValue(
            ContextualWorldValueSurrogate.serializer(),
            ContextualWorldValueSurrogate(value.value),
        )
    }

    override fun deserialize(decoder: Decoder): ContextualWorldValue =
        ContextualWorldValue(
            decoder.decodeSerializableValue(ContextualWorldValueSurrogate.serializer()).value,
        )
}

private fun contextualWorldValueSerializersModule(): SerializersModule = SerializersModule {
    contextual(ContextualWorldValue::class, ContextualWorldValueSerializer)
}
