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
    val snbtFormatConfiguration: SnbtFormatConfiguration,
) : StringFormat {
    private val treeNbtFormatConfiguration = snbtFormatConfiguration.treeConfiguration()

    final override val serializersModule: SerializersModule
        get() = snbtFormatConfiguration.serializersModule

    companion object Default : SnbtFormat(SnbtFormatConfiguration()) {
        operator fun invoke(
            snbtFormatConfiguration: SnbtFormatConfiguration = SnbtFormatConfiguration(),
        ): SnbtFormat = ConfiguredSnbtFormat(snbtFormatConfiguration)
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
        serializationStrategy: SerializationStrategy<T>,
        value: T,
    ): NbtTag = encodeSnbtOperation("${serializationStrategy.descriptor.serialName} tree") {
        var result: NbtTag? = null
        val nbtTreeEncoder = NbtTreeEncoder(treeNbtFormatConfiguration, "$") { nbtTag ->
            if (result != null) {
                throw NbtEncodingException(
                    "Serializer ${serializationStrategy.descriptor.serialName} emitted more than one root value",
                )
            }
            result = nbtTag
        }
        nbtTreeEncoder.encodeSerializableValue(serializationStrategy, value)
        result ?: throw NbtEncodingException(
            "Serializer ${serializationStrategy.descriptor.serialName} emitted no root value",
        )
    }

    fun <T> decodeFromNbtTag(
        deserializationStrategy: DeserializationStrategy<T>,
        nbtTag: NbtTag,
    ): T = decodeSnbtOperation("${deserializationStrategy.descriptor.serialName} tree") {
        NbtTreeDecoder(nbtTag, treeNbtFormatConfiguration, "$")
            .decodeSerializableValue(deserializationStrategy)
    }

    /** Writes one generic value as SNBT without closing or flushing [sink]. */
    fun <T> encodeToSink(
        serializationStrategy: SerializationStrategy<T>,
        value: T,
        sink: Sink,
    ) {
        encodeTagToSink(encodeToNbtTag(serializationStrategy, value), sink)
    }

    /** Reads one complete UTF-8 SNBT value without closing [source]. */
    fun <T> decodeFromSource(
        deserializationStrategy: DeserializationStrategy<T>,
        source: Source,
    ): T = decodeFromNbtTag(deserializationStrategy, decodeTagFromSource(source))

    /** Writes [nbtTag] directly as SNBT without closing or flushing [sink]. */
    fun encodeTagToSink(nbtTag: NbtTag, sink: Sink) =
        encodeSnbtOperation("SNBT tag") {
            SnbtWriter(SinkSnbtOutput(sink), snbtFormatConfiguration).writeTag(nbtTag)
        }

    /** Reads one complete UTF-8 SNBT tag without closing [source]. */
    fun decodeTagFromSource(source: Source): NbtTag =
        decodeSnbtOperation("SNBT tag") {
            SnbtParser(SourceSnbtInput(source), snbtFormatConfiguration).parseFully()
        }

    fun encodeTagToString(nbtTag: NbtTag): String =
        encodeSnbtOperation("SNBT tag") {
            val stringSnbtOutput = StringSnbtOutput()
            SnbtWriter(stringSnbtOutput, snbtFormatConfiguration).writeTag(nbtTag)
            stringSnbtOutput.toString()
        }

    fun decodeTagFromString(string: String): NbtTag =
        decodeSnbtOperation("SNBT tag") {
            SnbtParser(StringSnbtInput(string), snbtFormatConfiguration).parseFully()
        }

    /** Writes the compound root of [nbtDocument] directly as SNBT. */
    fun encodeDocumentToSink(nbtDocument: NbtDocument, sink: Sink) =
        encodeTagToSink(nbtDocument.root, sink)

    /** Reads one complete compound-root SNBT document. */
    fun decodeDocumentFromSource(source: Source): NbtDocument =
        document(decodeTagFromSource(source))

    fun encodeDocumentToString(nbtDocument: NbtDocument): String =
        encodeTagToString(nbtDocument.root)

    fun decodeDocumentFromString(string: String): NbtDocument =
        document(decodeTagFromString(string))

    private fun document(nbtTag: NbtTag): NbtDocument {
        val root = nbtTag as? NbtCompound
            ?: throw NbtDecodingException("SNBT document root must be TAG_Compound")
        return NbtDocument(root)
    }
}

inline fun <reified T> SnbtFormat.encodeToNbtTag(value: T): NbtTag =
    encodeToNbtTag(serializersModule.serializer(), value)

inline fun <reified T> SnbtFormat.decodeFromNbtTag(nbtTag: NbtTag): T =
    decodeFromNbtTag(serializersModule.serializer(), nbtTag)

inline fun <reified T> SnbtFormat.encodeToSink(value: T, sink: Sink) {
    encodeToSink(serializersModule.serializer(), value, sink)
}

inline fun <reified T> SnbtFormat.decodeFromSource(source: Source): T =
    decodeFromSource(serializersModule.serializer(), source)

private class ConfiguredSnbtFormat(
    snbtFormatConfiguration: SnbtFormatConfiguration,
) : SnbtFormat(snbtFormatConfiguration)

private inline fun <T> encodeSnbtOperation(kind: String, block: () -> T): T =
    try {
        block()
    } catch (nbtSerializationException: NbtSerializationException) {
        throw nbtSerializationException
    } catch (serializationException: SerializationException) {
        throw NbtEncodingException("Cannot encode $kind: ${serializationException.message}", serializationException)
    } catch (illegalArgumentException: IllegalArgumentException) {
        throw NbtEncodingException("Cannot encode $kind: ${illegalArgumentException.message}", illegalArgumentException)
    }

private inline fun <T> decodeSnbtOperation(kind: String, block: () -> T): T =
    try {
        block()
    } catch (nbtSerializationException: NbtSerializationException) {
        throw nbtSerializationException
    } catch (eofException: EOFException) {
        throw NbtDecodingException("Unexpected end of $kind", eofException)
    } catch (serializationException: SerializationException) {
        throw NbtDecodingException("Cannot decode $kind: ${serializationException.message}", serializationException)
    } catch (illegalArgumentException: IllegalArgumentException) {
        throw NbtDecodingException("Malformed $kind: ${illegalArgumentException.message}", illegalArgumentException)
    }
