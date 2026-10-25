package com.hiczp.minecraft.demo.worldmcp

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.SnbtFormat
import com.hiczp.minecraft.world.format.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** JSON settings shared by the tool boundary and opaque pagination tokens. */
object EditorJson {
    val format = Json { encodeDefaults = true }
}

/** Complete expanded block state; omitted properties on input are filled from the matching vanilla definition. */
@Serializable
data class BlockValue(val name: String, val properties: Map<String, String> = emptyMap()) {
    constructor(blockState: BlockState) : this(blockState.blockId.toString(), blockState.properties.asMap())
}

/** Inclusive absolute block bounds. Iteration and JSON arrays use X fastest, then Z, then Y. */
data class BlockBox(val min: BlockPosition, val max: BlockPosition) {
    init {
        require(min.x <= max.x && min.y <= max.y && min.z <= max.z) { "Bounds must be ordered and inclusive" }
    }

    val width: Long = max.x.toLong() - min.x + 1
    val depth: Long = max.z.toLong() - min.z + 1
    val height: Long = max.y.toLong() - min.y + 1
    val volume: Long
        get() {
            require(width <= Long.MAX_VALUE / depth && width * depth <= Long.MAX_VALUE / height) { "Volume exceeds the supported coordinate count" }
            return width * depth * height
        }
    val chunkRange: ChunkRange get() = min.chunkPosition..max.chunkPosition

    fun position(index: Long): BlockPosition {
        require(index in 0 until volume) { "Block index outside requested bounds" }
        return BlockPosition(
            (min.x + index % width).toInt(),
            (min.y + index / (width * depth)).toInt(),
            (min.z + index / width % depth).toInt(),
        )
    }

    fun index(blockPosition: BlockPosition): Long =
        (blockPosition.y.toLong() - min.y) * width * depth + (blockPosition.z.toLong() - min.z) * width + blockPosition.x - min.x

    fun intersect(chunkPosition: ChunkPosition): BlockBox = BlockBox(
        BlockPosition(
            maxOf(min.x, MinecraftCoordinates.blockCoordinate(chunkPosition.x, 0)),
            min.y,
            maxOf(min.z, MinecraftCoordinates.blockCoordinate(chunkPosition.z, 0))
        ),
        BlockPosition(
            minOf(max.x, MinecraftCoordinates.blockCoordinate(chunkPosition.x, 15)),
            max.y,
            minOf(max.z, MinecraftCoordinates.blockCoordinate(chunkPosition.z, 15))
        ),
    )

    fun json(): JsonObject = buildJsonObject {
        put("min", min.json())
        put("max", max.json())
    }
}

/** Storage families are independent; replacing terrain never implicitly replaces entities or POI. */
enum class RegionKind(val directory: String) {
    TERRAIN("region"), ENTITIES("entities"), POI("poi");

    companion object {
        fun parse(value: String): RegionKind = entries.singleOrNull { it.name.lowercase() == value }
            ?: error("kind must be terrain, entities or poi")
    }
}

internal fun JsonObject.text(name: String, default: String? = null): String =
    this[name]?.jsonPrimitive?.let { require(it.isString) { "$name must be a string" }; it.content }
        ?: default ?: error("Missing $name")

internal fun JsonObject.int(name: String, default: Int? = null): Int =
    this[name]?.jsonPrimitive?.int ?: default ?: error("Missing $name")

internal fun JsonObject.flag(name: String, default: Boolean = false): Boolean =
    this[name]?.jsonPrimitive?.boolean ?: default

internal fun JsonObject.array(name: String): JsonArray = this[name]?.jsonArray ?: error("Missing $name")
internal fun JsonObject.obj(name: String): JsonObject = this[name]?.jsonObject ?: error("Missing $name")
internal fun JsonObject.kind(): RegionKind = RegionKind.parse(text("kind", "terrain"))
internal fun JsonObject.dimensionId(): DimensionId = DimensionId.parse(text("dimension"))
internal fun JsonObject.chunkPosition(): ChunkPosition = obj("chunk").let { ChunkPosition(it.int("x"), it.int("z")) }
internal fun JsonObject.regionPosition(): RegionPosition =
    obj("region").let { RegionPosition(it.int("x"), it.int("z")) }

internal fun JsonObject.blockBox(): BlockBox = BlockBox(array("min").blockPosition(), array("max").blockPosition())
internal fun JsonArray.blockPosition(): BlockPosition {
    require(size == 3) { "A block position needs [x,y,z]" }
    return BlockPosition(this[0].jsonPrimitive.int, this[1].jsonPrimitive.int, this[2].jsonPrimitive.int)
}

internal fun BlockPosition.json(): JsonArray = JsonArray(listOf(x, y, z).map(::JsonPrimitive))
internal fun ChunkPosition.json(): JsonObject = buildJsonObject { put("x", x); put("z", z) }
internal fun RegionPosition.json(): JsonObject = buildJsonObject { put("x", x); put("z", z) }
internal fun BlockState.json(): JsonElement = EditorJson.format.encodeToJsonElement(BlockValue(this))
internal fun NbtCompound.withFields(vararg fields: Pair<String, NbtTag>): NbtCompound = NbtCompound(toMap() + fields)
internal fun NbtCompound.without(vararg names: String): NbtCompound = NbtCompound(toMap().filterKeys { it !in names })
internal fun NbtCompound.toMap(): Map<String, NbtTag> = buildMap { forEachEntry { name, nbtTag -> put(name, nbtTag) } }
internal fun NbtCompound.compound(name: String): NbtCompound =
    this[name] as? NbtCompound ?: error("Missing compound $name")

internal fun NbtCompound.integer(name: String): Int = when (val nbtTag = this[name]) {
    is NbtInt -> nbtTag.value
    is NbtByte -> nbtTag.value.toInt()
    else -> error("Missing integer $name")
}

internal fun NbtCompound.list(name: String): List<NbtTag> = (this[name] as? NbtList)?.value.orEmpty()
internal fun NbtCompound.snbt(): String = SnbtFormat.encodeTagToString(this)
internal fun String.compoundSnbt(): NbtCompound = SnbtFormat.decodeDocumentFromString(this).root

/** A tool failure may carry an exact record of completed and pending writes. */
class EditorException(val details: JsonObject, cause: Throwable? = null) : IllegalStateException(
    details["message"]?.jsonPrimitive?.content ?: "World operation failed", cause,
)
