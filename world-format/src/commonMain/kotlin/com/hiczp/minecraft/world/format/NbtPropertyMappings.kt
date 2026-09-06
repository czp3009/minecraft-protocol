package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*

/** Scope names describe the persisted owner. typeId optionally selects a particular block/entity/component type. */
data class NbtPropertyScope(val name: String, val typeId: String? = null) {
    companion object {
        val Chunk: NbtPropertyScope = NbtPropertyScope("chunk")
        val Section: NbtPropertyScope = NbtPropertyScope("section")
        val EntityChunk: NbtPropertyScope = NbtPropertyScope("entity_chunk")
        val PoiChunk: NbtPropertyScope = NbtPropertyScope("poi_chunk")
        val PoiSection: NbtPropertyScope = NbtPropertyScope("poi_section")
        val PoiRecord: NbtPropertyScope = NbtPropertyScope("poi_record")
    }
}

data class NbtPropertyPath(val scope: NbtPropertyScope, val name: String)

fun interface NbtPropertyReader {
    fun read(nbtTag: NbtTag, nbtPropertyReadMappings: NbtPropertyReadMappings): PropertyValue<*>
}

fun interface NbtPropertyWriter {
    /** Returning null explicitly excludes this field from this representation. */
    fun write(propertyValue: PropertyValue<*>, nbtPropertyWriteMappings: NbtPropertyWriteMappings): NbtTag?
}

/** Optional semantic mappings; every unregistered NBT field still has the generic dynamic path. */
data class NbtPropertyReadMappings(val fields: Map<NbtPropertyPath, NbtPropertyReader> = emptyMap()) {
    fun read(scope: NbtPropertyScope, name: String, nbtTag: NbtTag): PropertyValue<*> {
        val reader = fields[NbtPropertyPath(scope, name)]
            ?: fields[NbtPropertyPath(scope.copy(typeId = null), name)]
        return reader?.read(nbtTag, this) ?: readValue(nbtTag)
    }

    fun readValue(nbtTag: NbtTag): PropertyValue<*> = when (nbtTag) {
        is NbtByte -> PropertyValue(PropertyTypes.Byte, nbtTag.value)
        is NbtShort -> PropertyValue(PropertyTypes.Short, nbtTag.value)
        is NbtInt -> PropertyValue(PropertyTypes.Int, nbtTag.value)
        is NbtLong -> PropertyValue(PropertyTypes.Long, nbtTag.value)
        is NbtFloat -> PropertyValue(PropertyTypes.Float, nbtTag.value)
        is NbtDouble -> PropertyValue(PropertyTypes.Double, nbtTag.value)
        is NbtString -> PropertyValue(PropertyTypes.String, nbtTag.value)
        is NbtCompound -> PropertyValue(
            PropertyTypes.Properties,
            DataProperties(nbtTag.value.mapValuesTo(linkedMapOf()) { (_, value) -> readValue(value) }),
        )

        is NbtList -> PropertyValue(PropertyTypes.List, PropertyList(nbtTag.value.mapTo(mutableListOf(), ::readValue)))
        else -> PropertyValue(PropertyTypes.Nbt, nbtTag)
    }
}

/** A type writer associates one token with its checked conversion; unregistered Kotlin objects cannot be guessed. */
class NbtPropertyValueWriter<T : Any>(
    val propertyType: PropertyType<T>,
    private val encode: (T, NbtPropertyWriteMappings) -> NbtTag,
) {
    internal fun write(propertyValue: PropertyValue<*>, nbtPropertyWriteMappings: NbtPropertyWriteMappings): NbtTag =
        encode(propertyValue.get(propertyType), nbtPropertyWriteMappings)
}

class NbtPropertyWriteMappings private constructor(
    val fields: Map<NbtPropertyPath, NbtPropertyWriter>,
    val types: List<NbtPropertyValueWriter<*>>,
    private val ancestors: List<PropertyWriteVisit>,
) {
    constructor(
        fields: Map<NbtPropertyPath, NbtPropertyWriter> = emptyMap(),
        types: List<NbtPropertyValueWriter<*>> = emptyList(),
    ) : this(fields, types, emptyList()) {
        require(types.map { it.propertyType }
            .distinct().size == types.size) { "A property type has multiple NBT writers" }
    }

    fun write(scope: NbtPropertyScope, name: String, propertyValue: PropertyValue<*>): NbtTag? {
        val writer = fields[NbtPropertyPath(scope, name)]
            ?: fields[NbtPropertyPath(scope.copy(typeId = null), name)]
        return if (writer == null) writeValue(propertyValue) else {
            writer.write(propertyValue, descend(propertyValue.value, NbtPropertyPath(scope, name)))
        }
    }

    /** Callbacks use the supplied mappings to encode children; the operation's cycle path follows those calls. */
    fun writeValue(propertyValue: PropertyValue<*>): NbtTag {
        val nestedMappings = descend(propertyValue.value, propertyValue.propertyType)
        types.firstOrNull { it.propertyType === propertyValue.propertyType }?.let {
            return it.write(propertyValue, nestedMappings)
        }
        return when (propertyValue.propertyType) {
            PropertyTypes.Byte -> NbtByte(propertyValue.get(PropertyTypes.Byte))
            PropertyTypes.Short -> NbtShort(propertyValue.get(PropertyTypes.Short))
            PropertyTypes.Int -> NbtInt(propertyValue.get(PropertyTypes.Int))
            PropertyTypes.Long -> NbtLong(propertyValue.get(PropertyTypes.Long))
            PropertyTypes.Float -> NbtFloat(propertyValue.get(PropertyTypes.Float))
            PropertyTypes.Double -> NbtDouble(propertyValue.get(PropertyTypes.Double))
            PropertyTypes.Boolean -> NbtByte(if (propertyValue.get(PropertyTypes.Boolean)) 1 else 0)
            PropertyTypes.String -> NbtString(propertyValue.get(PropertyTypes.String))
            PropertyTypes.Uuid -> propertyValue.get(PropertyTypes.Uuid).toNbtIntArray()
            PropertyTypes.Properties -> {
                val properties = propertyValue.get(PropertyTypes.Properties)
                NbtCompound(properties.entries.mapValues { (_, value) -> nestedMappings.writeValue(value) })
            }

            PropertyTypes.List -> {
                val list = propertyValue.get(PropertyTypes.List)
                NbtList(list.values.map(nestedMappings::writeValue))
            }

            PropertyTypes.Nbt -> propertyValue.get(PropertyTypes.Nbt)
            else -> throw NbtPropertyFormatException("No NBT mapping for property type ${propertyValue.propertyType.name}")
        }
    }

    private fun descend(value: Any, mapping: Any): NbtPropertyWriteMappings {
        require(ancestors.none { it.value === value && it.mapping == mapping }) { "A property graph contains a cycle" }
        return NbtPropertyWriteMappings(fields, types, ancestors + PropertyWriteVisit(value, mapping))
    }
}

private data class PropertyWriteVisit(val value: Any, val mapping: Any)

class NbtPropertyFormatException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** Reads a compound's open fields using the same path bindings as its enclosing decoder. */
fun NbtPropertyReadMappings.readProperties(
    nbtCompound: NbtCompound,
    scope: NbtPropertyScope,
    structuralFields: Set<String>,
): DataProperties = DataProperties().also { dataProperties ->
    nbtCompound.forEachEntry { name, nbtTag ->
        if (name !in structuralFields) dataProperties[name] = read(scope, name, nbtTag)
    }
}

/** Writes current open fields and rejects names reserved by the enclosing custom schema. */
fun NbtPropertyWriteMappings.writeProperties(
    dataProperties: DataProperties,
    scope: NbtPropertyScope,
    structuralFields: Set<String>,
): MutableMap<String, NbtTag> = linkedMapOf<String, NbtTag>().also { fields ->
    dataProperties.entries.forEach { (name, propertyValue) ->
        require(name !in structuralFields) { "Property $name conflicts with a structural field in ${scope.name}" }
        write(scope, name, propertyValue)?.let { fields[name] = it }
    }
}
