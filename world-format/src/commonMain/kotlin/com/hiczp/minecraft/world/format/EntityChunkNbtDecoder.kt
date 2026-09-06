package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import kotlinx.io.Source
import kotlin.uuid.Uuid

data class EntityChunkNbtMetadata(val dataVersion: Int)

data class EntityChunkNbtDecodeResult(val entityChunk: EntityChunk, val entityChunkNbtMetadata: EntityChunkNbtMetadata)

/** NBT interpretation inputs; the same domain-context reference is attached to each decoded Entity Chunk. */
data class EntityChunkNbtDecoderContext(
    val entityChunkContext: EntityChunkContext,
    val nbtFormat: NbtFormat,
    val nbtPropertyReadMappings: NbtPropertyReadMappings,
)

/** Reads persistent root Entities and their passenger graphs from decompressed NBT with explicit property mappings. */
class EntityChunkNbtDecoder(val entityChunkNbtDecoderContext: EntityChunkNbtDecoderContext) {
    private val nbtFormat = entityChunkNbtDecoderContext.nbtFormat.forWorldRecord()
    private val reader = EntityChunkReader(entityChunkNbtDecoderContext)

    /** Consumes one NBT record, leaving the source open; returns domain data and the stored DataVersion separately. */
    fun decode(source: Source): EntityChunkNbtDecodeResult = entityChunkNbtOperation {
        nbtFormat.decodeFromSource(reader, source)
    }

    /** Applies the same semantic reader to an already materialized NBT document. */
    fun decodeDocument(nbtDocument: NbtDocument): EntityChunkNbtDecodeResult = entityChunkNbtOperation {
        entityChunkNbtDecoderContext.nbtFormat.decodeFromNbtTag(reader, nbtDocument.root)
    }
}

private class EntityChunkReader(private val context: EntityChunkNbtDecoderContext) :
    WorldNbtReader<EntityChunkNbtDecodeResult>() {
    override fun begin(): WorldNbtReadState<EntityChunkNbtDecodeResult> =
        object : WorldNbtReadState<EntityChunkNbtDecodeResult> {
            private var dataVersion: Int? = null
            private var chunkPosition: ChunkPosition? = null
            private var entities: MutableList<Entity>? = null
            private val properties = DataProperties()

            override fun read(name: String, worldNbtFieldInput: WorldNbtFieldInput) {
                when (name) {
                    "DataVersion" -> dataVersion = worldNbtFieldInput.tag().int()
                    "Position" -> {
                        val position = worldNbtFieldInput.tag() as? NbtIntArray
                            ?: throw EntityChunkNbtFormatException("Entity Chunk Position must be an NBT Int Array")
                        require(position.size == 2) { "Entity Chunk Position needs two coordinates" }
                        chunkPosition = ChunkPosition(position[0], position[1])
                    }

                    "Entities" -> entities =
                        worldNbtFieldInput.read(WorldNbtListReader(EntityNbtReader(context.nbtPropertyReadMappings)))

                    else -> properties[name] = context.nbtPropertyReadMappings.read(
                        NbtPropertyScope.EntityChunk,
                        name,
                        worldNbtFieldInput.tag()
                    )
                }
            }

            override fun finish(): EntityChunkNbtDecodeResult = EntityChunkNbtDecodeResult(
                EntityChunk(
                    requireNotNull(chunkPosition) { "Missing Entity Chunk Position" },
                    context.entityChunkContext,
                    requireNotNull(entities) { "Missing Entity Chunk Entities" },
                    properties
                ),
                EntityChunkNbtMetadata(requireNotNull(dataVersion) { "Missing Entity Chunk DataVersion" }),
            )
        }
}

internal class EntityNbtReader(private val mappings: NbtPropertyReadMappings) : WorldNbtReader<Entity>() {
    override fun begin(): WorldNbtReadState<Entity> = object : WorldNbtReadState<Entity> {
        private var entityTypeId: EntityTypeId? = null
        private var uuid: Uuid? = null
        private var position = EntityVector3d.ZERO
        private var deltaMovement = EntityVector3d.ZERO
        private var rotation = EntityRotation.ZERO
        private var passengers = mutableListOf<Entity>()
        private val dynamicFields = linkedMapOf<String, NbtTag>()

        override fun read(name: String, worldNbtFieldInput: WorldNbtFieldInput) {
            if (name == "Passengers") {
                passengers = worldNbtFieldInput.read(WorldNbtListReader(this@EntityNbtReader))
                return
            }
            val tag = worldNbtFieldInput.tag()
            when (name) {
                "id" -> entityTypeId = EntityTypeId.parse(tag.string())
                "UUID" -> uuid = (tag as? NbtIntArray)?.toUuid()
                    ?: throw EntityChunkNbtFormatException("Entity UUID must be an NBT Int Array")

                "Pos" -> position = decodeEntityVector(tag)
                "Motion" -> deltaMovement = decodeEntityVector(tag)
                "Rotation" -> {
                    val values = tag.list()
                    require(values.size == 2) { "Entity Rotation must have two components" }
                    rotation = EntityRotation(
                        (values[0] as? NbtFloat)?.value
                            ?: throw EntityChunkNbtFormatException("Entity yaw must be an NBT Float"),
                        (values[1] as? NbtFloat)?.value
                            ?: throw EntityChunkNbtFormatException("Entity pitch must be an NBT Float"),
                    )
                }

                else -> dynamicFields[name] = tag
            }
        }

        override fun finish(): Entity {
            val type = requireNotNull(entityTypeId) { "Missing Entity id" }
            return Entity(
                type, requireNotNull(uuid) { "Missing Entity UUID" }, position, deltaMovement, rotation, passengers,
                mappings.readProperties(
                    NbtCompound(dynamicFields),
                    NbtPropertyScope("entity", type.toString()),
                    emptySet()
                )
            )
        }
    }
}

internal fun decodeEntityVector(nbtTag: NbtTag): EntityVector3d {
    val values = nbtTag.list()
    require(values.size == 3) { "An Entity vector needs three coordinates" }
    fun component(index: Int): Double = (values[index] as? NbtDouble)?.value
        ?: throw EntityChunkNbtFormatException("Entity vector components must be NBT Doubles")
    return EntityVector3d(component(0), component(1), component(2))
}

class EntityChunkNbtFormatException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

internal inline fun <T> entityChunkNbtOperation(block: () -> T): T = try {
    block()
} catch (failure: EntityChunkNbtFormatException) {
    throw failure
} catch (failure: IllegalArgumentException) {
    throw EntityChunkNbtFormatException(failure.message ?: "Invalid Entity Chunk NBT", failure)
}

internal val ENTITY_CHUNK_NBT_FIELDS: Set<String> = setOf("DataVersion", "Position", "Entities")
internal val ENTITY_NBT_FIELDS: Set<String> = setOf("id", "UUID", "Pos", "Motion", "Rotation", "Passengers")
