package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.LevelChunkSectionData
import com.hiczp.minecraft.protocol.model.type.PacketCodecContext
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

data class MinecraftChunkSectionPayloadFormatConfiguration(
    val packetCodecContext: PacketCodecContext,
    val sectionCount: Int,
) {
    init {
        require(sectionCount >= 0) { "Section count must be non-negative" }
    }
}

/**
 * Physical encoding of the consecutive LevelChunkSection.read/write payloads, without a length or count prefix.
 * The caller supplies the count from its dimension. No world-format value or layout is required here.
 */
class MinecraftChunkSectionPayloadFormat(
    val minecraftChunkSectionPayloadFormatConfiguration: MinecraftChunkSectionPayloadFormatConfiguration,
) {
    private val minecraftPacketPayloadFormat = MinecraftPacketPayloadFormat(
        MinecraftPacketPayloadFormatConfiguration(
            packetCodecContext = minecraftChunkSectionPayloadFormatConfiguration.packetCodecContext,
        ),
    )
    private val sectionPayloadSerializer =
        SectionPayloadSerializer(minecraftChunkSectionPayloadFormatConfiguration.sectionCount)

    fun encodeToSink(sections: List<LevelChunkSectionData>, sink: Sink) =
        minecraftPacketPayloadFormat.encodeToSink(sections, sink, sectionPayloadSerializer)

    /** Consumes exactly [byteCount] bytes and leaves the caller's source open. */
    fun decodeFromSource(source: Source, byteCount: Int): List<LevelChunkSectionData> =
        minecraftPacketPayloadFormat.decodeFromSource(source, byteCount, sectionPayloadSerializer)

    fun encode(sections: List<LevelChunkSectionData>): ByteString {
        val buffer = Buffer()
        encodeToSink(sections, buffer)
        return ByteString(buffer.readByteArray())
    }

    fun decode(byteString: ByteString): List<LevelChunkSectionData> {
        val buffer = Buffer()
        buffer.write(byteString.toByteArray())
        return decodeFromSource(buffer, byteString.size)
    }
}

private class SectionPayloadSerializer(private val sectionCount: Int) : KSerializer<List<LevelChunkSectionData>> {
    private val sectionSerializer = LevelChunkSectionData.serializer()
    override val descriptor = ListSerializer(sectionSerializer).descriptor

    override fun serialize(encoder: Encoder, value: List<LevelChunkSectionData>) {
        require(value.size == sectionCount) { "Expected $sectionCount Sections, got ${value.size}" }
        value.forEach { section -> encoder.encodeSerializableValue(sectionSerializer, section) }
    }

    override fun deserialize(decoder: Decoder): List<LevelChunkSectionData> = buildList {
        repeat(sectionCount) { add(decoder.decodeSerializableValue(sectionSerializer)) }
    }
}
