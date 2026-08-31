package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.NamedNbtTag
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtTag
import kotlinx.io.*
import kotlinx.serialization.*
import kotlinx.serialization.modules.SerializersModule

/**
 * Java Edition NBT tree and binary serialization.
 *
 * Generic [BinaryFormat] operations use [NbtFormatConfiguration.nbtRootEncoding].
 * Explicit any-tag, named, unnamed, and compound-document methods avoid root
 * framing inference. Stream methods consume or emit exactly one value and
 * never close a caller-owned [Source] or [Sink]. Byte-array decoders require
 * full input consumption.
 */
sealed class NbtFormat(
    val nbtFormatConfiguration: NbtFormatConfiguration,
) : BinaryFormat {
    final override val serializersModule: SerializersModule
        get() = nbtFormatConfiguration.serializersModule

    companion object Default : NbtFormat(NbtFormatConfiguration()) {
        operator fun invoke(
            nbtFormatConfiguration: NbtFormatConfiguration = NbtFormatConfiguration(),
        ): NbtFormat = ConfiguredNbtFormat(nbtFormatConfiguration)
    }

    final override fun <T> encodeToByteArray(
        serializer: SerializationStrategy<T>,
        value: T,
    ): ByteArray = encodeBytes { encodeToSink(serializer, value, it) }

    final override fun <T> decodeFromByteArray(
        deserializer: DeserializationStrategy<T>,
        bytes: ByteArray,
    ): T = decodeFully(bytes) { decodeFromSource(deserializer, it) }

    fun <T> encodeToNbtTag(
        serializationStrategy: SerializationStrategy<T>,
        value: T,
    ): NbtTag = encodeOperation("${serializationStrategy.descriptor.serialName} tree") {
        var result: NbtTag? = null
        val nbtTreeEncoder = NbtTreeEncoder(nbtFormatConfiguration, "$") { nbtTag ->
            if (result != null) {
                throw NbtEncodingException(
                    "Serializer ${serializationStrategy.descriptor.serialName} emitted more than one root value",
                )
            }
            result = nbtTag
        }
        nbtTreeEncoder.encodeSerializableValue(serializationStrategy, value)
        val nbtTag = result ?: throw NbtEncodingException(
            "Serializer ${serializationStrategy.descriptor.serialName} emitted no root value",
        )
        nbtTag
    }

    fun <T> decodeFromNbtTag(
        deserializationStrategy: DeserializationStrategy<T>,
        nbtTag: NbtTag,
    ): T = decodeOperation("${deserializationStrategy.descriptor.serialName} tree") {
        NbtTreeDecoder(nbtTag, nbtFormatConfiguration, "$")
            .decodeSerializableValue(deserializationStrategy)
    }

    fun <T> encodeToSink(
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        sink: Sink,
    ) = encodeOperation("${serializationStrategy.descriptor.serialName} binary value") {
        val nbtBinaryWriter = NbtBinaryWriter(sink)
        val nbtBinaryEncoder = NbtBinaryEncoder(
            nbtBinaryWriter,
            nbtFormatConfiguration,
            "$",
        ) { type ->
            when (nbtFormatConfiguration.nbtRootEncoding) {
                NbtRootEncoding.ANY -> nbtBinaryWriter.writeByte(type)
                NbtRootEncoding.UNNAMED -> {
                    nbtBinaryWriter.writeByte(type)
                    if (type != TAG_END) nbtBinaryWriter.writeModifiedUtf("")
                }

                NbtRootEncoding.NAMED -> {
                    if (type == TAG_END) {
                        throw NbtEncodingException(
                            "A named NBT root cannot be TAG_End",
                        )
                    }
                    nbtBinaryWriter.writeByte(type)
                    nbtBinaryWriter.writeModifiedUtf(nbtFormatConfiguration.rootName)
                }
            }
        }
        nbtBinaryEncoder.encodeSerializableValue(serializationStrategy, value)
        nbtBinaryEncoder.requireValue()
    }

    fun <T> decodeFromSource(
        deserializationStrategy: DeserializationStrategy<T>,
        source: Source,
    ): T = decodeOperation(
        "${deserializationStrategy.descriptor.serialName} binary value",
        binaryInput = true,
    ) {
        val nbtBinaryReader = NbtBinaryReader(source)
        val type = nbtBinaryReader.readUnsignedByte()
        when (nbtFormatConfiguration.nbtRootEncoding) {
            NbtRootEncoding.ANY -> validateType(type)
            NbtRootEncoding.UNNAMED -> {
                if (type != TAG_END) {
                    validateType(type)
                    nbtBinaryReader.readModifiedUtf()
                }
            }

            NbtRootEncoding.NAMED -> {
                if (type == TAG_END) {
                    throw NbtBinaryFormatException(
                        "A named NBT root cannot be TAG_End",
                    )
                }
                validateType(type)
                val name = nbtBinaryReader.readModifiedUtf()
                if (name != nbtFormatConfiguration.rootName) {
                    throw NbtDecodingException(
                        "Expected NBT root name '${nbtFormatConfiguration.rootName}', got '$name'",
                    )
                }
            }
        }
        NbtBinaryDecoder(
            nbtBinaryReader,
            nbtFormatConfiguration,
            "$",
            type = type,
        ).decodeSerializableValue(deserializationStrategy)
    }

    /** Writes one no-name any-tag value without closing or flushing [sink]. */
    fun encodeAnyTagToSink(nbtTag: NbtTag, sink: Sink) =
        encodeOperation("any NBT tag") {
            NbtBinaryWriter(sink).writeAnyTag(nbtTag)
        }

    /** Reads one no-name any-tag value without closing [source]. */
    fun decodeAnyTagFromSource(source: Source): NbtTag =
        decodeOperation("any NBT tag", binaryInput = true) {
            NbtBinaryReader(source).readAnyTag()
        }

    /** Writes one named tag without closing or flushing [sink]. */
    fun encodeNamedTagToSink(namedNbtTag: NamedNbtTag, sink: Sink) =
        encodeOperation("named NBT tag") {
            NbtBinaryWriter(sink).writeNamedTag(namedNbtTag)
        }

    /** Reads one named tag without closing [source]. */
    fun decodeNamedTagFromSource(source: Source): NamedNbtTag =
        decodeOperation("named NBT tag", binaryInput = true) {
            NbtBinaryReader(source).readNamedTag()
        }

    /** Writes one unnamed tag without closing or flushing [sink]. */
    fun encodeUnnamedTagToSink(nbtTag: NbtTag, sink: Sink) =
        encodeOperation("unnamed NBT tag") {
            NbtBinaryWriter(sink).writeUnnamedTag(nbtTag)
        }

    /** Reads one unnamed tag without closing [source]. */
    fun decodeUnnamedTagFromSource(source: Source): NbtTag =
        decodeOperation("unnamed NBT tag", binaryInput = true) {
            NbtBinaryReader(source).readUnnamedTag()
        }

    /**
     * Writes the strict compound-document form used by vanilla `NbtIo.write`.
     * Unlike vanilla's emergency `writeUnnamedTagWithFallback`, this method
     * never replaces overlong strings with empty strings.
     */
    fun encodeDocumentToSink(nbtDocument: NbtDocument, sink: Sink) =
        encodeUnnamedTagToSink(nbtDocument.root, sink)

    /** Reads one compound document without closing [source]. */
    fun decodeDocumentFromSource(source: Source): NbtDocument {
        val root = decodeUnnamedTagFromSource(source) as? NbtCompound
            ?: throw NbtBinaryFormatException(
                "NBT document root must be TAG_Compound",
            )
        return NbtDocument(root)
    }

    fun encodeAnyTagToByteArray(nbtTag: NbtTag): ByteArray =
        encodeBytes { encodeAnyTagToSink(nbtTag, it) }

    fun decodeAnyTagFromByteArray(bytes: ByteArray): NbtTag =
        decodeFully(bytes, ::decodeAnyTagFromSource)

    fun encodeNamedTagToByteArray(namedNbtTag: NamedNbtTag): ByteArray =
        encodeBytes { encodeNamedTagToSink(namedNbtTag, it) }

    fun decodeNamedTagFromByteArray(bytes: ByteArray): NamedNbtTag =
        decodeFully(bytes, ::decodeNamedTagFromSource)

    fun encodeUnnamedTagToByteArray(nbtTag: NbtTag): ByteArray =
        encodeBytes { encodeUnnamedTagToSink(nbtTag, it) }

    fun decodeUnnamedTagFromByteArray(bytes: ByteArray): NbtTag =
        decodeFully(bytes, ::decodeUnnamedTagFromSource)

    fun encodeDocumentToByteArray(nbtDocument: NbtDocument): ByteArray =
        encodeBytes { encodeDocumentToSink(nbtDocument, it) }

    fun decodeDocumentFromByteArray(bytes: ByteArray): NbtDocument =
        decodeFully(bytes, ::decodeDocumentFromSource)

    private fun encodeBytes(block: (Sink) -> Unit): ByteArray {
        val buffer = Buffer()
        block(buffer)
        return buffer.readByteArray()
    }

    private fun <T> decodeFully(
        bytes: ByteArray,
        block: (Source) -> T,
    ): T {
        val buffer = Buffer()
        buffer.write(bytes)
        val value = block(buffer)
        if (!buffer.exhausted()) {
            throw NbtBinaryFormatException(
                "NBT input has ${buffer.size} trailing byte(s)",
            )
        }
        return value
    }
}

inline fun <reified T> NbtFormat.encodeToNbtTag(value: T): NbtTag =
    encodeToNbtTag(serializersModule.serializer(), value)

inline fun <reified T> NbtFormat.decodeFromNbtTag(nbtTag: NbtTag): T =
    decodeFromNbtTag(serializersModule.serializer(), nbtTag)

inline fun <reified T> NbtFormat.encodeToSink(value: T, sink: Sink) =
    encodeToSink(serializersModule.serializer(), value, sink)

inline fun <reified T> NbtFormat.decodeFromSource(source: Source): T =
    decodeFromSource(serializersModule.serializer(), source)

private class ConfiguredNbtFormat(
    nbtFormatConfiguration: NbtFormatConfiguration,
) : NbtFormat(nbtFormatConfiguration)

private inline fun <T> encodeOperation(kind: String, block: () -> T): T =
    try {
        block()
    } catch (nbtSerializationException: NbtSerializationException) {
        throw nbtSerializationException
    } catch (serializationException: SerializationException) {
        throw NbtEncodingException("Cannot encode $kind: ${serializationException.message}", serializationException)
    } catch (illegalArgumentException: IllegalArgumentException) {
        throw NbtEncodingException("Cannot encode $kind: ${illegalArgumentException.message}", illegalArgumentException)
    }

private inline fun <T> decodeOperation(
    kind: String,
    binaryInput: Boolean = false,
    block: () -> T,
): T =
    try {
        block()
    } catch (nbtSerializationException: NbtSerializationException) {
        throw nbtSerializationException
    } catch (eofException: EOFException) {
        if (binaryInput) {
            throw NbtBinaryFormatException("Unexpected end of $kind", eofException)
        }
        throw NbtDecodingException("Unexpected end of $kind", eofException)
    } catch (serializationException: SerializationException) {
        throw NbtDecodingException("Cannot decode $kind: ${serializationException.message}", serializationException)
    } catch (illegalArgumentException: IllegalArgumentException) {
        throw NbtDecodingException("Malformed $kind: ${illegalArgumentException.message}", illegalArgumentException)
    }
