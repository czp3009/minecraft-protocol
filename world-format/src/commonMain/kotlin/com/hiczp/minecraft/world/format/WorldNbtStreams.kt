package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.listSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

private val compoundDescriptor: SerialDescriptor = MapSerializer(String.serializer(), NbtTag.serializer()).descriptor

/** Reads one compound field at a time in both the binary and tree branches. */
internal abstract class WorldNbtReader<T> : DeserializationStrategy<T> {
    final override val descriptor: SerialDescriptor get() = compoundDescriptor

    abstract fun begin(): WorldNbtReadState<T>

    final override fun deserialize(decoder: Decoder): T {
        val state = begin()
        val compositeDecoder = decoder.beginStructure(descriptor)
        while (true) {
            val keyIndex = compositeDecoder.decodeElementIndex(descriptor)
            if (keyIndex == CompositeDecoder.DECODE_DONE) break
            val name = compositeDecoder.decodeStringElement(descriptor, keyIndex)
            val valueIndex = compositeDecoder.decodeElementIndex(descriptor)
            require(valueIndex != CompositeDecoder.DECODE_DONE) { "Missing NBT field value for $name" }
            state.read(name, WorldNbtFieldInput(compositeDecoder, valueIndex))
        }
        compositeDecoder.endStructure(descriptor)
        return state.finish()
    }
}

internal interface WorldNbtReadState<T> {
    fun read(name: String, worldNbtFieldInput: WorldNbtFieldInput)
    fun finish(): T
}

internal class WorldNbtFieldInput(private val compositeDecoder: CompositeDecoder, private val index: Int) {
    fun <T> read(deserializationStrategy: DeserializationStrategy<T>): T =
        compositeDecoder.decodeSerializableElement(compoundDescriptor, index, deserializationStrategy)

    fun tag(): NbtTag = read(NbtTag.serializer())
}

/** Field values are encoded on demand; roots and Section lists never require an intermediate NbtDocument. */
internal abstract class WorldNbtWriter<T> : SerializationStrategy<T> {
    final override val descriptor: SerialDescriptor get() = compoundDescriptor

    abstract fun fields(value: T): List<WorldNbtField<*>>

    final override fun serialize(encoder: Encoder, value: T) {
        val fields = fields(value)
        val compositeEncoder = encoder.beginCollection(descriptor, fields.size)
        fields.forEachIndexed { index, field -> field.write(compositeEncoder, index) }
        compositeEncoder.endStructure(descriptor)
    }
}

internal class WorldNbtField<T>(
    private val name: String,
    private val serializationStrategy: SerializationStrategy<T>,
    private val value: T,
) {
    fun write(compositeEncoder: CompositeEncoder, index: Int) {
        compositeEncoder.encodeStringElement(compoundDescriptor, index * 2, name)
        compositeEncoder.encodeSerializableElement(compoundDescriptor, index * 2 + 1, serializationStrategy, value)
    }
}

internal fun nbtField(name: String, nbtTag: NbtTag): WorldNbtField<NbtTag> =
    WorldNbtField(name, NbtTag.serializer(), nbtTag)

@OptIn(ExperimentalSerializationApi::class)
internal class WorldNbtListReader<T>(private val elementReader: DeserializationStrategy<T>) :
    DeserializationStrategy<MutableList<T>> {
    override val descriptor: SerialDescriptor = listSerialDescriptor(elementReader.descriptor)

    override fun deserialize(decoder: Decoder): MutableList<T> {
        val compositeDecoder = decoder.beginStructure(descriptor)
        val values = mutableListOf<T>()
        if (compositeDecoder.decodeSequentially()) {
            repeat(compositeDecoder.decodeCollectionSize(descriptor)) { index ->
                values.add(compositeDecoder.decodeSerializableElement(descriptor, index, elementReader))
            }
        } else {
            while (true) {
                val index = compositeDecoder.decodeElementIndex(descriptor)
                if (index == CompositeDecoder.DECODE_DONE) break
                values.add(compositeDecoder.decodeSerializableElement(descriptor, index, elementReader))
            }
        }
        compositeDecoder.endStructure(descriptor)
        return values
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal class WorldNbtListWriter<T>(private val elementWriter: SerializationStrategy<T>) :
    SerializationStrategy<List<T>> {
    override val descriptor: SerialDescriptor = listSerialDescriptor(elementWriter.descriptor)

    override fun serialize(encoder: Encoder, value: List<T>) {
        val compositeEncoder = encoder.beginCollection(descriptor, value.size)
        value.forEachIndexed { index, element ->
            compositeEncoder.encodeSerializableElement(descriptor, index, elementWriter, element)
        }
        compositeEncoder.endStructure(descriptor)
    }
}

internal class WorldNbtCompoundValueReader<T>(private val read: (NbtCompound) -> T) : DeserializationStrategy<T> {
    override val descriptor: SerialDescriptor get() = NbtCompound.serializer().descriptor
    override fun deserialize(decoder: Decoder): T = read(decoder.decodeSerializableValue(NbtCompound.serializer()))
}

internal class WorldNbtCompoundValueWriter<T>(private val write: (T) -> NbtCompound) : SerializationStrategy<T> {
    override val descriptor: SerialDescriptor get() = NbtCompound.serializer().descriptor
    override fun serialize(encoder: Encoder, value: T) =
        encoder.encodeSerializableValue(NbtCompound.serializer(), write(value))
}

/** Region records write an empty root name and discard the name on read, independently of generic root settings. */
internal fun NbtFormat.forWorldRecord(): NbtFormat = NbtFormat(
    nbtFormatConfiguration.copy(nbtRootEncoding = NbtRootEncoding.UNNAMED),
)
