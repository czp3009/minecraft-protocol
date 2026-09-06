package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.serialization.internal.MinecraftDecoder
import com.hiczp.minecraft.protocol.serialization.internal.MinecraftEncoder
import com.hiczp.minecraft.protocol.serialization.internal.MinecraftReader
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

/**
 * A `kotlinx.serialization` binary format for Minecraft packet payloads.
 *
 * It does not add packet IDs, frame lengths, compression, or encryption.
 * [encodeToSink] is the canonical encoding path. Array operations required by
 * [BinaryFormat] are convenience adapters over caller-owned streams.
 */
sealed class MinecraftPacketPayloadFormat(
    val minecraftPacketPayloadFormatConfiguration: MinecraftPacketPayloadFormatConfiguration,
    override val serializersModule: SerializersModule,
) : BinaryFormat {
    companion object Default : MinecraftPacketPayloadFormat(
        MinecraftPacketPayloadFormatConfiguration(),
        EmptySerializersModule(),
    ) {
        /** Creates a format with connection- or application-specific configuration. */
        operator fun invoke(
            minecraftPacketPayloadFormatConfiguration: MinecraftPacketPayloadFormatConfiguration = MinecraftPacketPayloadFormatConfiguration(),
            serializersModule: SerializersModule = EmptySerializersModule(),
        ): MinecraftPacketPayloadFormat =
            ConfiguredMinecraftPacketPayloadFormat(minecraftPacketPayloadFormatConfiguration, serializersModule)
    }

    final override fun <T> encodeToByteArray(
        serializer: SerializationStrategy<T>,
        value: T,
    ): ByteArray {
        val buffer = Buffer()
        encodeToSink(serializer, value, buffer)
        return buffer.readByteArray()
    }

    final override fun <T> decodeFromByteArray(
        deserializer: DeserializationStrategy<T>,
        bytes: ByteArray,
    ): T {
        val buffer = Buffer()
        buffer.write(bytes)
        return decodeFromSource(deserializer, buffer, bytes.size)
    }

    /**
     * Encodes exactly one packet payload to [sink].
     *
     * The format neither flushes nor closes the caller-owned sink.
     */
    fun <T> encodeToSink(
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        sink: Sink,
    ) {
        val minecraftEncoder = MinecraftEncoder(sink, minecraftPacketPayloadFormatConfiguration, serializersModule)
        minecraftEncoder.encodeSerializableValue(serializationStrategy, value)
    }

    /**
     * Decodes exactly [byteCount] bytes from [source] as one packet payload.
     *
     * Minecraft payloads are framed by a higher layer, so their byte boundary
     * is explicit rather than inferred from end-of-stream. The format neither
     * closes nor reads beyond that caller-provided boundary.
     */
    fun <T> decodeFromSource(
        deserializationStrategy: DeserializationStrategy<T>,
        source: Source,
        byteCount: Int,
    ): T {
        val minecraftDecoder = MinecraftDecoder(
            MinecraftReader(source, byteCount),
            minecraftPacketPayloadFormatConfiguration,
            serializersModule,
        )
        val value = minecraftDecoder.decodeSerializableValue(deserializationStrategy)
        if (minecraftDecoder.remaining != 0) {
            throw MinecraftSerializationException(
                "Payload has ${minecraftDecoder.remaining} unread byte(s)",
            )
        }
        return value
    }
}

inline fun <reified T> MinecraftPacketPayloadFormat.encodeToSink(
    value: T,
    sink: Sink,
) {
    encodeToSink(serializersModule.serializer(), value, sink)
}

inline fun <reified T> MinecraftPacketPayloadFormat.decodeFromSource(
    source: Source,
    byteCount: Int,
): T = decodeFromSource(serializersModule.serializer(), source, byteCount)

private class ConfiguredMinecraftPacketPayloadFormat(
    minecraftPacketPayloadFormatConfiguration: MinecraftPacketPayloadFormatConfiguration,
    serializersModule: SerializersModule,
) : MinecraftPacketPayloadFormat(minecraftPacketPayloadFormatConfiguration, serializersModule)
