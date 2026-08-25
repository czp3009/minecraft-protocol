package com.hiczp.minecraft.protocol.datapack.vanilla

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.protocol.datapack.DataPackRegistryEntryProjector
import com.hiczp.minecraft.protocol.datapack.DataPackRegistryProjector
import com.hiczp.minecraft.world.format.datapack.DataPackFileContent
import kotlinx.serialization.json.*

/**
 * Release-matched defaults for every vanilla registry synchronized during Configuration.
 *
 * Vanilla registry resources are JSON files. Their client-visible fields retain the same JSON representation when the
 * official network codecs read NBT, while server-only fields are ignored by those codecs. These projectors preserve the
 * complete JSON value tree so a normal vanilla data pack needs no caller-written registry mapping.
 */
val vanillaDataPackRegistryProjectors: List<DataPackRegistryProjector> by lazy(
    LazyThreadSafetyMode.PUBLICATION,
) {
    VanillaProtocolData.synchronizedRegistryPackets(emptyList()).map { registryDataPacket ->
        DataPackRegistryProjector(
            registryId = registryDataPacket.registryId,
            dataPackRegistryEntryProjector = vanillaDataPackRegistryEntryProjector,
        )
    }
}

private val vanillaDataPackRegistryEntryProjector = DataPackRegistryEntryProjector { _, resolvedDataPackResource, _ ->
    val jsonFile = resolvedDataPackResource.dataPackFileContent as? DataPackFileContent.JsonFile
        ?: throw IllegalArgumentException(
            "Vanilla registry resource ${resolvedDataPackResource.sourceDataPackFilePath} must be JSON",
        )
    jsonFile.jsonElement.toVanillaRegistryNbtTag()
}

private fun JsonElement.toVanillaRegistryNbtTag(): NbtTag = when (this) {
    JsonNull -> throw IllegalArgumentException("Vanilla registry JSON cannot contain null values")
    is JsonObject -> NbtCompound(mapValues { (_, jsonElement) -> jsonElement.toVanillaRegistryNbtTag() })
    is JsonArray -> NbtList(map(JsonElement::toVanillaRegistryNbtTag))
    is JsonPrimitive -> toVanillaRegistryNbtTag()
}

private fun JsonPrimitive.toVanillaRegistryNbtTag(): NbtTag {
    if (isString) return NbtString(content)
    booleanOrNull?.let { value -> return NbtByte(if (value) 1 else 0) }
    longOrNull?.let { value ->
        return when (value) {
            in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() -> NbtInt(value.toInt())
            else -> NbtLong(value)
        }
    }
    val doubleValue = requireNotNull(doubleOrNull) { "Invalid vanilla registry JSON number: $content" }
    require(doubleValue.isFinite()) { "Vanilla registry JSON numbers must be finite: $content" }
    val floatValue = doubleValue.toFloat()
    return if (floatValue.toDouble() == doubleValue) NbtFloat(floatValue) else NbtDouble(doubleValue)
}
