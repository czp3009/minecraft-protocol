package com.hiczp.minecraft.demo.webmap

import com.hiczp.minecraft.protocol.model.type.Identifier
import kotlinx.serialization.json.*

data class AssetModelReference(
    val model: Identifier,
    val xRotation: Int = 0,
    val yRotation: Int = 0,
    val uvLock: Boolean = false,
    val weight: Int = 1,
) {
    init {
        require(xRotation in ROTATIONS && yRotation in ROTATIONS) { "Model rotations must be multiples of 90 degrees" }
        require(weight > 0) { "Model weight must be positive" }
    }
}

data class AssetBlockStateDefinition(
    val variants: List<AssetVariant>,
    val multipart: List<AssetMultipart>,
) {
    fun modelChoices(surfaceBlockState: SurfaceBlockState): List<List<AssetModelReference>> = buildList {
        variants.firstOrNull { variant -> variant.matches(surfaceBlockState.properties) }
            ?.models
            ?.let(::add)
        multipart.forEach { assetMultipart ->
            if (assetMultipart.condition.matches(surfaceBlockState.properties)) add(assetMultipart.models)
        }
    }

    fun select(surfaceBlockState: SurfaceBlockState, blockX: Int, blockZ: Int): List<AssetModelReference> {
        val canonicalState = surfaceBlockState.canonicalAssetKey()
        val positionSeed = "$canonicalState@$blockX,$blockZ"
        return modelChoices(surfaceBlockState).mapIndexed { index, models ->
            models.selectWeighted("$positionSeed#choice-$index")
        }
    }
}

data class AssetVariant(
    val requiredProperties: Map<String, String>,
    val models: List<AssetModelReference>,
) {
    fun matches(properties: Map<String, String>): Boolean = requiredProperties.all { (name, expected) ->
        properties[name] == expected
    }
}

data class AssetMultipart(
    val condition: AssetMultipartCondition,
    val models: List<AssetModelReference>,
)

sealed interface AssetMultipartCondition {
    fun matches(properties: Map<String, String>): Boolean

    data object Always : AssetMultipartCondition {
        override fun matches(properties: Map<String, String>): Boolean = true
    }

    data class Property(
        val name: String,
        val acceptedValues: Set<String>,
        val rejectedValues: Set<String>,
    ) : AssetMultipartCondition {
        override fun matches(properties: Map<String, String>): Boolean {
            val value = properties[name] ?: return false
            return (acceptedValues.isEmpty() || value in acceptedValues) && value !in rejectedValues
        }
    }

    data class All(val conditions: List<AssetMultipartCondition>) : AssetMultipartCondition {
        override fun matches(properties: Map<String, String>): Boolean = conditions.all { condition ->
            condition.matches(properties)
        }
    }

    data class Any(val conditions: List<AssetMultipartCondition>) : AssetMultipartCondition {
        override fun matches(properties: Map<String, String>): Boolean = conditions.any { condition ->
            condition.matches(properties)
        }
    }
}

data class AssetModel(
    val parent: Identifier?,
    val textures: Map<String, AssetTextureSlot>,
    val elements: List<AssetModelElement>?,
)

data class ResolvedAssetModel(
    val textures: Map<String, AssetTextureSlot>,
    val elements: List<AssetModelElement>,
)

data class AssetTextureSlot(
    val sprite: String,
    val forceTranslucent: Boolean = false,
)

data class AssetModelElement(
    val from: AssetVector,
    val to: AssetVector,
    val faces: Map<AssetDirection, AssetModelFace>,
)

data class AssetVector(
    val x: Float,
    val y: Float,
    val z: Float,
)

data class AssetModelFace(
    val texture: String,
    val uv: List<Float>?,
    val rotation: Int,
    val tintIndex: Int?,
) {
    init {
        require(uv == null || uv.size == MODEL_FACE_UV_COMPONENT_COUNT) { "A model face UV must have four components" }
        require(rotation in ROTATIONS) { "Model face rotation must be a multiple of 90 degrees" }
    }
}

enum class AssetDirection {
    DOWN,
    UP,
    NORTH,
    SOUTH,
    WEST,
    EAST,
}

object MinecraftAssetJsonParser {
    fun parseBlockState(jsonElement: JsonElement): AssetBlockStateDefinition {
        val root = jsonElement.jsonObject
        val variants = root["variants"]?.jsonObject?.map { (key, value) ->
            AssetVariant(parseVariantProperties(key), parseModelReferences(value))
        }.orEmpty()
        val multipart = root["multipart"]?.jsonArray?.map { part ->
            val partObject = part.jsonObject
            AssetMultipart(
                condition = partObject["when"]?.let(::parseMultipartCondition) ?: AssetMultipartCondition.Always,
                models = parseModelReferences(checkNotNull(partObject["apply"]) { "Multipart entry has no apply model" }),
            )
        }.orEmpty()
        require(variants.isNotEmpty() || multipart.isNotEmpty()) { "Block-state JSON has neither variants nor multipart" }
        return AssetBlockStateDefinition(variants, multipart)
    }

    fun parseModel(jsonElement: JsonElement, defaultNamespace: String): AssetModel {
        val root = jsonElement.jsonObject
        val parent = root["parent"]?.jsonPrimitive?.contentOrNull?.let { value ->
            parseAssetIdentifier(value, defaultNamespace)
        }
        val textures = root["textures"]?.jsonObject?.mapValues { (_, value) -> parseTextureSlot(value) }.orEmpty()
        val elements = root["elements"]?.jsonArray?.map(::parseElement)
        return AssetModel(parent, textures, elements)
    }

    fun firstAnimationFrame(jsonElement: JsonElement?): Int {
        val animation = jsonElement?.jsonObject?.get("animation")?.jsonObject ?: return 0
        val frames = animation["frames"] as? JsonArray ?: return 0
        val firstFrame = frames.firstOrNull() ?: return 0
        return when (firstFrame) {
            is JsonPrimitive -> firstFrame.intOrNull ?: 0
            is JsonObject -> firstFrame["index"]?.jsonPrimitive?.intOrNull ?: 0
            else -> 0
        }.coerceAtLeast(0)
    }

    private fun parseVariantProperties(value: String): Map<String, String> {
        if (value.isEmpty()) return emptyMap()
        return value.split(',').associate { entry ->
            val separatorIndex = entry.indexOf('=')
            require(separatorIndex > 0 && separatorIndex < entry.lastIndex) { "Invalid block-state variant key: $value" }
            entry.substring(0, separatorIndex) to entry.substring(separatorIndex + 1)
        }
    }

    private fun parseModelReferences(jsonElement: JsonElement): List<AssetModelReference> = when (jsonElement) {
        is JsonArray -> jsonElement.map(::parseModelReference)
        is JsonObject -> listOf(parseModelReference(jsonElement))
        else -> error("A block-state model reference must be an object or array")
    }.also { models -> require(models.isNotEmpty()) { "A model-reference array must not be empty" } }

    private fun parseModelReference(jsonElement: JsonElement): AssetModelReference {
        val modelObject = jsonElement.jsonObject
        return AssetModelReference(
            model = Identifier(checkNotNull(modelObject["model"]?.jsonPrimitive?.contentOrNull) {
                "A block-state model reference has no model"
            }),
            xRotation = modelObject["x"]?.jsonPrimitive?.intOrNull ?: 0,
            yRotation = modelObject["y"]?.jsonPrimitive?.intOrNull ?: 0,
            uvLock = modelObject["uvlock"]?.jsonPrimitive?.booleanOrNull ?: false,
            weight = modelObject["weight"]?.jsonPrimitive?.intOrNull ?: 1,
        )
    }

    private fun parseTextureSlot(jsonElement: JsonElement): AssetTextureSlot = when (jsonElement) {
        is JsonPrimitive -> {
            require(jsonElement.isString) { "A model texture slot string must contain a sprite reference" }
            AssetTextureSlot(jsonElement.content)
        }

        is JsonObject -> AssetTextureSlot(
            sprite = checkNotNull(jsonElement["sprite"]?.jsonPrimitive?.contentOrNull) {
                "A model texture slot object has no sprite"
            },
            forceTranslucent = jsonElement["force_translucent"]?.jsonPrimitive?.booleanOrNull ?: false,
        )

        else -> error("A model texture slot must be a string or object")
    }

    private fun parseMultipartCondition(jsonElement: JsonElement): AssetMultipartCondition {
        val conditionObject = jsonElement.jsonObject
        if (conditionObject.isEmpty()) return AssetMultipartCondition.Always
        val conditions = conditionObject.map { (name, value) ->
            when (name) {
                "OR" -> AssetMultipartCondition.Any(value.jsonArray.map(::parseMultipartCondition))
                "AND" -> AssetMultipartCondition.All(value.jsonArray.map(::parseMultipartCondition))
                else -> parsePropertyCondition(name, value.jsonPrimitive.content)
            }
        }
        return conditions.singleOrNull() ?: AssetMultipartCondition.All(conditions)
    }

    private fun parsePropertyCondition(name: String, expression: String): AssetMultipartCondition.Property {
        val acceptedValues = linkedSetOf<String>()
        val rejectedValues = linkedSetOf<String>()
        expression.split('|').forEach { value ->
            if (value.startsWith('!')) {
                rejectedValues += value.substring(1)
            } else {
                acceptedValues += value
            }
        }
        return AssetMultipartCondition.Property(name, acceptedValues, rejectedValues)
    }

    private fun parseElement(jsonElement: JsonElement): AssetModelElement {
        val elementObject = jsonElement.jsonObject
        return AssetModelElement(
            from = parseVector(checkNotNull(elementObject["from"]) { "Model element has no from vector" }),
            to = parseVector(checkNotNull(elementObject["to"]) { "Model element has no to vector" }),
            faces = checkNotNull(elementObject["faces"]?.jsonObject) { "Model element has no faces" }.mapNotNull { (key, value) ->
                val direction =
                    AssetDirection.entries.firstOrNull { direction -> direction.name.equals(key, ignoreCase = true) }
                direction?.let { it to parseFace(value) }
            }.toMap(),
        )
    }

    private fun parseVector(jsonElement: JsonElement): AssetVector {
        val values = jsonElement.jsonArray
        require(values.size == 3) { "A model vector must have three components" }
        return AssetVector(
            values[0].jsonPrimitive.floatOrNull ?: error("Invalid model X component"),
            values[1].jsonPrimitive.floatOrNull ?: error("Invalid model Y component"),
            values[2].jsonPrimitive.floatOrNull ?: error("Invalid model Z component"),
        )
    }

    private fun parseFace(jsonElement: JsonElement): AssetModelFace {
        val faceObject = jsonElement.jsonObject
        val uv = faceObject["uv"]?.jsonArray?.map { value ->
            value.jsonPrimitive.floatOrNull ?: error("Invalid model face UV component")
        }
        val rotation = faceObject["rotation"]?.jsonPrimitive?.intOrNull ?: 0
        return AssetModelFace(
            texture = checkNotNull(faceObject["texture"]?.jsonPrimitive?.contentOrNull) { "Model face has no texture" },
            uv = uv,
            rotation = rotation,
            tintIndex = faceObject["tintindex"]?.jsonPrimitive?.intOrNull,
        )
    }
}

fun resolveTextureReference(textures: Map<String, AssetTextureSlot>, value: String): AssetTextureSlot? {
    var assetTextureSlot = AssetTextureSlot(value)
    val visited = mutableSetOf<String>()
    while (assetTextureSlot.sprite.startsWith('#')) {
        val key = assetTextureSlot.sprite.substring(1)
        if (!visited.add(key)) return null
        val referencedTextureSlot = textures[key] ?: return null
        assetTextureSlot = AssetTextureSlot(
            sprite = referencedTextureSlot.sprite,
            forceTranslucent = assetTextureSlot.forceTranslucent || referencedTextureSlot.forceTranslucent,
        )
    }
    return assetTextureSlot
}

fun Identifier.textureEntryName(): String = "assets/$namespace/textures/$path.png"

fun parseAssetIdentifier(value: String, defaultNamespace: String): Identifier =
    if (':' in value) Identifier(value) else Identifier(defaultNamespace, value)

private fun List<AssetModelReference>.selectWeighted(seed: String): AssetModelReference {
    val totalWeight = sumOf(AssetModelReference::weight)
    var selectedWeight = stableAssetHash(seed).mod(totalWeight)
    forEach { model ->
        if (selectedWeight < model.weight) return model
        selectedWeight -= model.weight
    }
    return last()
}

fun SurfaceBlockState.canonicalAssetKey(): String =
    properties.entries.sortedBy { entry -> entry.key }.joinToString(",", prefix = "$name[") { entry ->
        "${entry.key}=${entry.value}"
    }.let { value -> "$value]" }

fun stableAssetHash(value: String): Int {
    var hash = FNV_OFFSET_BASIS
    value.forEach { character ->
        hash = (hash xor character.code) * FNV_PRIME
    }
    return hash and Int.MAX_VALUE
}

private val ROTATIONS: Set<Int> = setOf(0, 90, 180, 270)
private const val MODEL_FACE_UV_COMPONENT_COUNT: Int = 4
private const val FNV_OFFSET_BASIS: Int = -2128831035
private const val FNV_PRIME: Int = 16777619
