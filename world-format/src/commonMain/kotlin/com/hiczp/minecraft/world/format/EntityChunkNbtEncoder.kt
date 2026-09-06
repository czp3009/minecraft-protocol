package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import kotlinx.io.Sink

/** NBT format, property mappings and output DataVersion; Entity encoding requires no domain layout. */
data class EntityChunkNbtEncoderContext(
    val nbtFormat: NbtFormat,
    val nbtPropertyWriteMappings: NbtPropertyWriteMappings,
    val entityChunkNbtMetadata: EntityChunkNbtMetadata,
)

/** Writes the current persistent root Entities and their passenger graphs using the supplied mappings and persistence metadata. */
class EntityChunkNbtEncoder(val entityChunkNbtEncoderContext: EntityChunkNbtEncoderContext) {
    private val nbtFormat = entityChunkNbtEncoderContext.nbtFormat.forWorldRecord()
    private val writer = EntityChunkWriter(entityChunkNbtEncoderContext)

    /** Writes decompressed NBT without mutating the graph, flushing the sink or closing it. */
    fun encode(entityChunk: EntityChunk, sink: Sink) = entityChunkNbtOperation {
        nbtFormat.encodeToSink(entityChunk, sink, writer)
    }

    /** Materializes the same persisted representation as [encode] in a document. */
    fun encodeDocument(entityChunk: EntityChunk): NbtDocument = entityChunkNbtOperation {
        NbtDocument(entityChunkNbtEncoderContext.nbtFormat.encodeToNbtTag(entityChunk, writer).compound())
    }
}

private class EntityChunkWriter(private val context: EntityChunkNbtEncoderContext) : WorldNbtWriter<EntityChunk>() {
    override fun fields(value: EntityChunk): List<WorldNbtField<*>> = buildList {
        add(nbtField("DataVersion", NbtInt(context.entityChunkNbtMetadata.dataVersion)))
        add(nbtField("Position", NbtIntArray(intArrayOf(value.chunkPosition.x, value.chunkPosition.z))))
        add(
            WorldNbtField(
                "Entities",
                WorldNbtListWriter(EntityNbtWriter(context.nbtPropertyWriteMappings)),
                value.rootEntities
            )
        )
        context.nbtPropertyWriteMappings.writeProperties(
            value.properties,
            NbtPropertyScope.EntityChunk,
            ENTITY_CHUNK_NBT_FIELDS
        )
            .forEach { (name, tag) -> add(nbtField(name, tag)) }
    }
}

internal class EntityNbtWriter(
    private val mappings: NbtPropertyWriteMappings,
    private val ancestors: List<Entity> = emptyList(),
    private val vehicle: Entity? = null,
) : WorldNbtWriter<Entity>() {
    override fun fields(value: Entity): List<WorldNbtField<*>> {
        require(ancestors.none { it === value }) { "An Entity passenger graph contains a cycle" }
        val passengers = requireNotNull(value.passengers) { "Entity ${value.uuid} has unknown passenger relationships" }
        // Entity.saveWithoutId persists the vehicle's X/Z and this passenger's Y, without moving either object.
        val position = vehicle?.let { EntityVector3d(it.position.x, value.position.y, it.position.z) } ?: value.position
        return buildList {
            add(nbtField("id", NbtString(value.entityTypeId.toString())))
            add(nbtField("UUID", value.uuid.toNbtIntArray()))
            add(nbtField("Pos", position.toNbt()))
            add(nbtField("Motion", value.deltaMovement.toNbt()))
            add(
                nbtField(
                    "Rotation",
                    NbtList(listOf(NbtFloat(value.entityRotation.yaw), NbtFloat(value.entityRotation.pitch)))
                )
            )
            if (passengers.isNotEmpty()) {
                add(
                    WorldNbtField(
                        "Passengers",
                        WorldNbtListWriter(EntityNbtWriter(mappings, ancestors + value, value)),
                        passengers
                    )
                )
            }
            mappings.writeProperties(
                value.properties,
                NbtPropertyScope("entity", value.entityTypeId.toString()),
                ENTITY_NBT_FIELDS
            )
                .forEach { (name, tag) -> add(nbtField(name, tag)) }
        }
    }
}

internal fun EntityVector3d.toNbt(): NbtList = NbtList(listOf(NbtDouble(x), NbtDouble(y), NbtDouble(z)))
