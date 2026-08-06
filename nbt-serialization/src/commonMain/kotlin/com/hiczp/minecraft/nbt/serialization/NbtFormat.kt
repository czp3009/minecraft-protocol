@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.*
import kotlinx.io.*
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.SerializationStrategy
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
    ) {
        val tag = encodeToNbtTag(serializer, value)
        when (configuration.rootEncoding) {
            NbtRootEncoding.ANY -> encodeAnyTag(sink, tag)
            NbtRootEncoding.UNNAMED -> encodeUnnamedTag(sink, tag)
            NbtRootEncoding.NAMED -> {
                if (tag === NbtEnd) {
                    throw NbtEncodingException(
                        "A named NBT root cannot be TAG_End",
                    )
                }
                encodeNamedTag(
                    sink,
                    NamedNbtTag(configuration.rootName, tag),
                )
            }
        }
    }

    fun <T> decodeFromSource(
        deserializer: DeserializationStrategy<T>,
        source: Source,
    ): T {
        val tag = when (configuration.rootEncoding) {
            NbtRootEncoding.ANY -> decodeAnyTag(source)
            NbtRootEncoding.UNNAMED -> decodeUnnamedTag(source)
            NbtRootEncoding.NAMED -> {
                val named = decodeNamedTag(source)
                if (named.name != configuration.rootName) {
                    throw NbtDecodingException(
                        "Expected NBT root name '${configuration.rootName}', got '${named.name}'",
                    )
                }
                named.tag
            }
        }
        return decodeFromNbtTag(deserializer, tag)
    }

    fun encodeAnyTag(sink: Sink, tag: NbtTag) =
        encodeOperation("any NBT tag") {
            validateTree(tag, configuration)
            NbtBinaryWriter(sink, configuration).writeAnyTag(tag)
        }

    fun decodeAnyTag(source: Source): NbtTag =
        decodeOperation("any NBT tag") {
            NbtBinaryReader(source, configuration).readAnyTag()
        }

    fun encodeNamedTag(sink: Sink, value: NamedNbtTag) =
        encodeOperation("named NBT tag") {
            validateTree(value.tag, configuration)
            NbtBinaryWriter(sink, configuration).writeNamedTag(value)
        }

    fun decodeNamedTag(source: Source): NamedNbtTag =
        decodeOperation("named NBT tag") {
            NbtBinaryReader(source, configuration).readNamedTag()
        }

    fun encodeUnnamedTag(sink: Sink, tag: NbtTag) =
        encodeOperation("unnamed NBT tag") {
            validateTree(tag, configuration)
            NbtBinaryWriter(sink, configuration).writeUnnamedTag(tag)
        }

    fun decodeUnnamedTag(source: Source): NbtTag =
        decodeOperation("unnamed NBT tag") {
            NbtBinaryReader(source, configuration).readUnnamedTag()
        }

    /**
     * Writes the strict compound-document form used by vanilla `NbtIo.write`.
     * Unlike vanilla's emergency `writeUnnamedTagWithFallback`, this method
     * never replaces overlong strings with empty strings.
     */
    fun encodeDocument(sink: Sink, document: NbtDocument) =
        encodeUnnamedTag(sink, document.root)

    fun decodeDocument(source: Source): NbtDocument {
        val root = decodeUnnamedTag(source) as? NbtCompound
            ?: throw NbtDecodingException(
                "NBT document root must be TAG_Compound",
            )
        return NbtDocument(root)
    }

    fun encodeAnyTagToByteArray(tag: NbtTag): ByteArray =
        encodeBytes { encodeAnyTag(it, tag) }

    fun decodeAnyTagFromByteArray(bytes: ByteArray): NbtTag =
        decodeFully(bytes, ::decodeAnyTag)

    fun encodeNamedTagToByteArray(value: NamedNbtTag): ByteArray =
        encodeBytes { encodeNamedTag(it, value) }

    fun decodeNamedTagFromByteArray(bytes: ByteArray): NamedNbtTag =
        decodeFully(bytes, ::decodeNamedTag)

    fun encodeUnnamedTagToByteArray(tag: NbtTag): ByteArray =
        encodeBytes { encodeUnnamedTag(it, tag) }

    fun decodeUnnamedTagFromByteArray(bytes: ByteArray): NbtTag =
        decodeFully(bytes, ::decodeUnnamedTag)

    fun encodeDocumentToByteArray(document: NbtDocument): ByteArray =
        encodeBytes { encodeDocument(it, document) }

    fun decodeDocumentFromByteArray(bytes: ByteArray): NbtDocument =
        decodeFully(bytes, ::decodeDocument)

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
