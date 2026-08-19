@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtTag
import kotlinx.io.EOFException
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.serialization.*
import kotlinx.serialization.modules.SerializersModule

/**
 * Java Edition stringified NBT (SNBT) serialization.
 *
 * String and stream decoding accept the repository-selected release's SNBT
 * value grammar and require exactly one value plus optional trailing
 * whitespace. [Source] input is decoded incrementally as UTF-8, and [Sink]
 * output is written incrementally without closing or flushing caller-owned
 * streams. Direct tag operations retain only the returned NBT tree; generic
 * kotlinx.serialization operations additionally use the shared NBT tree
 * mapping while translating between Kotlin values and tags.
 */
sealed class SnbtFormat(
    val configuration: SnbtFormatConfiguration,
) : StringFormat {
    private val treeConfiguration = configuration.treeConfiguration()

    final override val serializersModule: SerializersModule
        get() = configuration.serializersModule

    companion object Default : SnbtFormat(SnbtFormatConfiguration()) {
        operator fun invoke(
            configuration: SnbtFormatConfiguration = SnbtFormatConfiguration(),
        ): SnbtFormat = ConfiguredSnbtFormat(configuration)
    }

    final override fun <T> encodeToString(
        serializer: SerializationStrategy<T>,
        value: T,
    ): String = encodeTagToString(encodeToNbtTag(serializer, value))

    final override fun <T> decodeFromString(
        deserializer: DeserializationStrategy<T>,
        string: String,
    ): T = decodeFromNbtTag(deserializer, decodeTagFromString(string))

    fun <T> encodeToNbtTag(
        serializer: SerializationStrategy<T>,
        value: T,
    ): NbtTag = encodeSnbtOperation("${serializer.descriptor.serialName} tree") {
        var result: NbtTag? = null
        val encoder = NbtTreeEncoder(treeConfiguration, "$") { tag ->
            if (result != null) {
                throw NbtEncodingException(
                    "Serializer ${serializer.descriptor.serialName} emitted more than one root value",
                )
            }
            result = tag
        }
        encoder.encodeSerializableValue(serializer, value)
        result ?: throw NbtEncodingException(
            "Serializer ${serializer.descriptor.serialName} emitted no root value",
        )
    }

    fun <T> decodeFromNbtTag(
        deserializer: DeserializationStrategy<T>,
        tag: NbtTag,
    ): T = decodeSnbtOperation("${deserializer.descriptor.serialName} tree") {
        NbtTreeDecoder(tag, treeConfiguration, "$")
            .decodeSerializableValue(deserializer)
    }

    /** Writes one generic value as SNBT without closing or flushing [sink]. */
    fun <T> encodeToSink(
        serializer: SerializationStrategy<T>,
        value: T,
        sink: Sink,
    ) {
        encodeTagToSink(encodeToNbtTag(serializer, value), sink)
    }

    /** Reads one complete UTF-8 SNBT value without closing [source]. */
    fun <T> decodeFromSource(
        deserializer: DeserializationStrategy<T>,
        source: Source,
    ): T = decodeFromNbtTag(deserializer, decodeTagFromSource(source))

    /** Writes [tag] directly as SNBT without closing or flushing [sink]. */
    fun encodeTagToSink(tag: NbtTag, sink: Sink) =
        encodeSnbtOperation("SNBT tag") {
            SnbtWriter(SinkSnbtOutput(sink), configuration).writeTag(tag)
        }

    /** Reads one complete UTF-8 SNBT tag without closing [source]. */
    fun decodeTagFromSource(source: Source): NbtTag =
        decodeSnbtOperation("SNBT tag") {
            SnbtParser(SourceSnbtInput(source), configuration).parseFully()
        }

    fun encodeTagToString(tag: NbtTag): String =
        encodeSnbtOperation("SNBT tag") {
            val output = StringSnbtOutput()
            SnbtWriter(output, configuration).writeTag(tag)
            output.toString()
        }

    fun decodeTagFromString(string: String): NbtTag =
        decodeSnbtOperation("SNBT tag") {
            SnbtParser(StringSnbtInput(string), configuration).parseFully()
        }

    /** Writes the compound root of [document] directly as SNBT. */
    fun encodeDocumentToSink(document: NbtDocument, sink: Sink) =
        encodeTagToSink(document.root, sink)

    /** Reads one complete compound-root SNBT document. */
    fun decodeDocumentFromSource(source: Source): NbtDocument =
        document(decodeTagFromSource(source))

    fun encodeDocumentToString(document: NbtDocument): String =
        encodeTagToString(document.root)

    fun decodeDocumentFromString(string: String): NbtDocument =
        document(decodeTagFromString(string))

    private fun document(tag: NbtTag): NbtDocument {
        val root = tag as? NbtCompound
            ?: throw NbtDecodingException("SNBT document root must be TAG_Compound")
        return NbtDocument(root)
    }
}

inline fun <reified T> SnbtFormat.encodeToSink(value: T, sink: Sink) {
    encodeToSink(serializersModule.serializer(), value, sink)
}

inline fun <reified T> SnbtFormat.decodeFromSource(source: Source): T =
    decodeFromSource(serializersModule.serializer(), source)

private class ConfiguredSnbtFormat(
    configuration: SnbtFormatConfiguration,
) : SnbtFormat(configuration)

private inline fun <T> encodeSnbtOperation(kind: String, block: () -> T): T =
    try {
        block()
    } catch (exception: NbtSerializationException) {
        throw exception
    } catch (exception: SerializationException) {
        throw NbtEncodingException("Cannot encode $kind: ${exception.message}", exception)
    } catch (exception: IllegalArgumentException) {
        throw NbtEncodingException("Cannot encode $kind: ${exception.message}", exception)
    }

private inline fun <T> decodeSnbtOperation(kind: String, block: () -> T): T =
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
