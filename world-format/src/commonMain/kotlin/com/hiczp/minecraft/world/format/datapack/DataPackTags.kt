package com.hiczp.minecraft.world.format.datapack

import kotlinx.serialization.json.*

/** The logical contents of one data-pack tag JSON file. */
data class DataPackTagFile(
    val dataPackTagValues: List<DataPackTagValue>,
    val replacesExistingValues: Boolean = false,
)

/** One required or optional element/tag reference in a [DataPackTagFile]. */
data class DataPackTagValue(
    val dataPackResourceId: DataPackResourceId,
    val isTagReference: Boolean = false,
    val isRequired: Boolean = true,
)

/** Decodes this resolved resource as a typed data-pack tag file. */
fun ResolvedDataPackResource.decodeDataPackTagFile(): DataPackTagFile {
    val description =
        "Tag $dataPackResourcePath from $sourceDataPackFilePath in data pack $sourceDataPackId"
    val dataPackTagJson = when (dataPackFileContent) {
        is DataPackFileContent.JsonFile -> dataPackFileContent.jsonElement.jsonObject
        else -> throw DataPackFormatException("$description must be a JSON object")
    }
    val encodedDataPackTagValues = dataPackTagJson.getValue("values").jsonArray
    val dataPackTagValues = encodedDataPackTagValues.mapIndexed { index, jsonElement ->
        val valueDescription = "$description value $index"
        val (encodedValue, isRequired) = when (jsonElement) {
            is JsonPrimitive -> jsonElement.content to true
            is JsonObject -> {
                val encodedValue = jsonElement.getValue("id").jsonPrimitive.content
                encodedValue to (jsonElement["required"]?.jsonPrimitive?.boolean ?: true)
            }

            else -> throw DataPackFormatException("$valueDescription must be a JSON string or object")
        }
        val isTagReference = encodedValue.startsWith('#')
        val resourceLocation = if (isTagReference) encodedValue.removePrefix("#") else encodedValue
        DataPackTagValue(
            dataPackResourceId = DataPackResourceId(resourceLocation),
            isTagReference = isTagReference,
            isRequired = isRequired,
        )
    }
    return DataPackTagFile(
        dataPackTagValues = dataPackTagValues,
        replacesExistingValues = dataPackTagJson["replace"]?.jsonPrimitive?.boolean ?: false,
    )
}
