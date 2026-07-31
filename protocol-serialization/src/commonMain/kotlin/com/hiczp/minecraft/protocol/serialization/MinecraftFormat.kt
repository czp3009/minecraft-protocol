package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.serialization.internal.MinecraftDecoder
import com.hiczp.minecraft.protocol.serialization.internal.MinecraftEncoder
import com.hiczp.minecraft.protocol.serialization.internal.MinecraftReader
import com.hiczp.minecraft.protocol.serialization.internal.MinecraftWriter
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

/**
 * A `kotlinx.serialization` binary format for Minecraft packet payloads.
 *
 * It does not add packet IDs, frame lengths, compression, or encryption.
 */
sealed class MinecraftFormat(
    val configuration: MinecraftFormatConfiguration,
    override val serializersModule: SerializersModule,
) : BinaryFormat {
    companion object Default : MinecraftFormat(
        MinecraftFormatConfiguration(),
        EmptySerializersModule(),
    ) {
        /** Creates a format with connection- or application-specific configuration. */
        operator fun invoke(
            configuration: MinecraftFormatConfiguration = MinecraftFormatConfiguration(),
            serializersModule: SerializersModule = EmptySerializersModule(),
        ): MinecraftFormat = ConfiguredMinecraftFormat(configuration, serializersModule)
    }

    override fun <T> encodeToByteArray(
        serializer: SerializationStrategy<T>,
        value: T,
    ): ByteArray {
        val writer = MinecraftWriter()
        val encoder = MinecraftEncoder(writer, configuration, serializersModule)
        encoder.encodeSerializableValue(serializer, value)
        return writer.toByteArray()
    }

    override fun <T> decodeFromByteArray(
        deserializer: DeserializationStrategy<T>,
        bytes: ByteArray,
    ): T {
        val decoder = MinecraftDecoder(
            MinecraftReader(bytes),
            configuration,
            serializersModule,
        )
        val value = decoder.decodeSerializableValue(deserializer)
        if (decoder.remaining != 0) {
            throw MinecraftSerializationException(
                "Payload has ${decoder.remaining} unread byte(s)",
            )
        }
        return value
    }
}

private class ConfiguredMinecraftFormat(
    configuration: MinecraftFormatConfiguration,
    serializersModule: SerializersModule,
) : MinecraftFormat(configuration, serializersModule)
