package com.hiczp.minecraft.world.format.datapack

import kotlinx.serialization.json.*

/** The logical contents of one data-pack tag JSON file. */
data class DataPackTagFile(
    val values: List<DataPackTagEntry>,
    val replace: Boolean = false,
)

/** One required or optional element/tag reference in a [DataPackTagFile]. */
data class DataPackTagEntry(
    val id: DataPackResourceId,
    val tag: Boolean = false,
    val required: Boolean = true,
)

/** Decodes this resolved resource as a typed data-pack tag file. */
fun ResolvedDataPackResource.decodeTagFile(): DataPackTagFile {
    val description = "Tag $path from $sourcePath in data pack $sourcePack"
    val json = when (content) {
        is DataPackFileContent.JsonFile -> content.element.jsonObject
        else -> throw DataPackFormatException("$description must be a JSON object")
    }
    val encodedValues = json.getValue("values").jsonArray
    val values = encodedValues.mapIndexed { index, element ->
        val valueDescription = "$description value $index"
        val (encoded, required) = when (element) {
            is JsonPrimitive -> element.content to true
            is JsonObject -> {
                val encoded = element.getValue("id").jsonPrimitive.content
                encoded to (element["required"]?.jsonPrimitive?.boolean ?: true)
            }

            else -> throw DataPackFormatException("$valueDescription must be a JSON string or object")
        }
        val tag = encoded.startsWith('#')
        val location = if (tag) encoded.removePrefix("#") else encoded
        DataPackTagEntry(id = DataPackResourceId(location), tag = tag, required = required)
    }
    return DataPackTagFile(
        values = values,
        replace = json["replace"]?.jsonPrimitive?.boolean ?: false,
    )
}
