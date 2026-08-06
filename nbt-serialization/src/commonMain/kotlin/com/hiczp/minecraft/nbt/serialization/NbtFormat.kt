@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

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
 * Generic [BinaryFormat] operations use [NbtFormatConfiguration.rootEncoding].
 * Explicit any-tag, named, unnamed, and compound-document methods avoid root
 * framing inference. Stream methods consume or emit exactly one value and
 * never close a caller-owned [Source] or [Sink]. Byte-array decoders require
 * full input consumption.
 */
sealed class NbtFormat(
    val configuration: NbtFormatConfiguration,
) : BinaryFormat {
    final override val serializersModule: SerializersModule
        get() = configuration.serializersModule

    companion object Default : NbtFormat(NbtFormatConfiguration()) {
        operator fun invoke(
            configuration: NbtFormatConfiguration = NbtFormatConfiguration(),
        ): NbtFormat = ConfiguredNbtFormat(configuration)
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
        serializer: SerializationStrategy<T>,
        value: T,
    ): NbtTag = encodeOperation("${serializer.descriptor.serialName} tree") {
        var result: NbtTag? = null
        val encoder = NbtTreeEncoder(configuration, "$") { tag ->
            if (result != null) {
                throw NbtEncodingException(
                    "Serializer ${serializer.descriptor.serialName} emitted more than one root value",
                )
            }
            result = tag
        }
        encoder.encodeSerializableValue(serializer, value)
        val tag = result ?: throw NbtEncodingException(
            "Serializer ${serializer.descriptor.serialName} emitted no root value",
        )
        validateTree(tag, configuration)
        tag
    }

    fun <T> decodeFromNbtTag(
        deserializer: DeserializationStrategy<T>,
        tag: NbtTag,
    ): T = decodeOperation("${deserializer.descriptor.serialName} tree") {
        validateTree(tag, configuration)
        NbtTreeDecoder(tag, configuration, "$")
            .decodeSerializableValue(deserializer)
    }

    fun <T> encodeToSink(
        serializer: SerializationStrategy<T>,
        value: T,
        sink: Sink,
    ) = encodeOperation("${serializer.descriptor.serialName} binary value") {
        val writer = NbtBinaryWriter(sink, configuration)
        val encoder = NbtBinaryEncoder(
            writer,
            configuration,
            "$",
            depth = 0,
        ) { type ->
            when (configuration.rootEncoding) {
                NbtRootEncoding.ANY -> writer.writeByte(type)
                NbtRootEncoding.UNNAMED -> {
                    writer.writeByte(type)
                    if (type != TAG_END) writer.writeModifiedUtf("")
                }

                NbtRootEncoding.NAMED -> {
                    if (type == TAG_END) {
                        throw NbtEncodingException(
                            "A named NBT root cannot be TAG_End",
                        )
                    }
                    writer.writeByte(type)
                    writer.writeModifiedUtf(configuration.rootName)
                }
            }
        }
        encoder.encodeSerializableValue(serializer, value)
        encoder.requireValue()
    }

    fun <T> decodeFromSource(
        deserializer: DeserializationStrategy<T>,
        source: Source,
    ): T = decodeOperation("${deserializer.descriptor.serialName} binary value") {
        val reader = NbtBinaryReader(source, configuration)
        val type = reader.readUnsignedByte()
        when (configuration.rootEncoding) {
            NbtRootEncoding.ANY -> validateType(type)
            NbtRootEncoding.UNNAMED -> {
                if (type != TAG_END) {
                    validateType(type)
                    reader.readModifiedUtf()
                }
            }

            NbtRootEncoding.NAMED -> {
                if (type == TAG_END) {
                    throw NbtDecodingException(
                        "A named NBT root cannot be TAG_End",
                    )
                }
                validateType(type)
                val name = reader.readModifiedUtf()
                if (name != configuration.rootName) {
                    throw NbtDecodingException(
                        "Expected NBT root name '${configuration.rootName}', got '$name'",
                    )
                }
            }
        }
        NbtBinaryDecoder(
            reader,
            configuration,
            "$",
            depth = 0,
            type = type,
        ).decodeSerializableValue(deserializer)
    }

    /** Writes one no-name any-tag value without closing or flushing [sink]. */
    fun encodeAnyTagToSink(tag: NbtTag, sink: Sink) =
        encodeOperation("any NBT tag") {
            validateTree(tag, configuration)
            NbtBinaryWriter(sink, configuration).writeAnyTag(tag)
        }

    /** Reads one no-name any-tag value without closing [source]. */
    fun decodeAnyTagFromSource(source: Source): NbtTag =
        decodeOperation("any NBT tag") {
            NbtBinaryReader(source, configuration).readAnyTag()
        }

    /** Writes one named tag without closing or flushing [sink]. */
    fun encodeNamedTagToSink(value: NamedNbtTag, sink: Sink) =
        encodeOperation("named NBT tag") {
            validateTree(value.tag, configuration)
            NbtBinaryWriter(sink, configuration).writeNamedTag(value)
        }

    /** Reads one named tag without closing [source]. */
    fun decodeNamedTagFromSource(source: Source): NamedNbtTag =
        decodeOperation("named NBT tag") {
            NbtBinaryReader(source, configuration).readNamedTag()
        }

    /** Writes one unnamed tag without closing or flushing [sink]. */
    fun encodeUnnamedTagToSink(tag: NbtTag, sink: Sink) =
        encodeOperation("unnamed NBT tag") {
            validateTree(tag, configuration)
            NbtBinaryWriter(sink, configuration).writeUnnamedTag(tag)
        }

    /** Reads one unnamed tag without closing [source]. */
    fun decodeUnnamedTagFromSource(source: Source): NbtTag =
        decodeOperation("unnamed NBT tag") {
            NbtBinaryReader(source, configuration).readUnnamedTag()
        }

    /**
     * Writes the strict compound-document form used by vanilla `NbtIo.write`.
     * Unlike vanilla's emergency `writeUnnamedTagWithFallback`, this method
     * never replaces overlong strings with empty strings.
     */
    fun encodeDocumentToSink(document: NbtDocument, sink: Sink) =
        encodeUnnamedTagToSink(document.root, sink)

    /** Reads one compound document without closing [source]. */
    fun decodeDocumentFromSource(source: Source): NbtDocument {
        val root = decodeUnnamedTagFromSource(source) as? NbtCompound
            ?: throw NbtDecodingException(
                "NBT document root must be TAG_Compound",
            )
        return NbtDocument(root)
    }

    fun encodeAnyTagToByteArray(tag: NbtTag): ByteArray =
        encodeBytes { encodeAnyTagToSink(tag, it) }

    fun decodeAnyTagFromByteArray(bytes: ByteArray): NbtTag =
        decodeFully(bytes, ::decodeAnyTagFromSource)

    fun encodeNamedTagToByteArray(value: NamedNbtTag): ByteArray =
        encodeBytes { encodeNamedTagToSink(value, it) }

    fun decodeNamedTagFromByteArray(bytes: ByteArray): NamedNbtTag =
        decodeFully(bytes, ::decodeNamedTagFromSource)

    fun encodeUnnamedTagToByteArray(tag: NbtTag): ByteArray =
        encodeBytes { encodeUnnamedTagToSink(tag, it) }

    fun decodeUnnamedTagFromByteArray(bytes: ByteArray): NbtTag =
        decodeFully(bytes, ::decodeUnnamedTagFromSource)

    fun encodeDocumentToByteArray(document: NbtDocument): ByteArray =
        encodeBytes { encodeDocumentToSink(document, it) }

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
        if (bytes.size.toLong() > configuration.maximumEncodedBytes) {
            throw NbtLimitException(
                "NBT input size ${bytes.size} exceeds configured limit ${configuration.maximumEncodedBytes}",
            )
        }
        val buffer = Buffer()
        buffer.write(bytes)
        val value = block(buffer)
        if (!buffer.exhausted()) {
            throw NbtDecodingException(
                "NBT input has ${buffer.size} trailing byte(s)",
            )
        }
        return value
    }
}

inline fun <reified T> NbtFormat.encodeToSink(value: T, sink: Sink) {
    encodeToSink(serializersModule.serializer(), value, sink)
}

inline fun <reified T> NbtFormat.decodeFromSource(source: Source): T =
    decodeFromSource(serializersModule.serializer(), source)

private class ConfiguredNbtFormat(
    configuration: NbtFormatConfiguration,
) : NbtFormat(configuration)

private inline fun <T> encodeOperation(kind: String, block: () -> T): T =
    try {
        block()
    } catch (exception: NbtSerializationException) {
        throw exception
    } catch (exception: SerializationException) {
        throw NbtEncodingException("Cannot encode $kind: ${exception.message}", exception)
    } catch (exception: IllegalArgumentException) {
        throw NbtEncodingException("Cannot encode $kind: ${exception.message}", exception)
    }

private inline fun <T> decodeOperation(kind: String, block: () -> T): T =
    try {
        block()
    } catch (exception: NbtSerializationException) {
        throw exception
    } catch (exception: EOFException) {
        throw NbtDecodingException("Unexpected end of $kind", exception)
    } catch (exception: SerializationException) {
        throw NbtDecodingException("Cannot decode $kind: ${exception.message}", exception)
    } catch (exception: IllegalArgumentException) {
        throw NbtDecodingException("Malformed $kind: ${exception.message}", exception)
    }
