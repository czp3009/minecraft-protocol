package com.hiczp.minecraft.world.format

import kotlin.uuid.Uuid

/** Dimension identity shared by a group of persistent Entities; contains no connection tracking state. */
data class EntityChunkContext(val dimensionId: DimensionId)

/** Mutable root Entities, passenger graphs and open properties for one absolute Chunk position. Retains supplied references. */
data class EntityChunk(
    var chunkPosition: ChunkPosition,
    var entityChunkContext: EntityChunkContext,
    var rootEntities: MutableList<Entity>,
    var properties: DataProperties,
) {
    constructor(chunkPosition: ChunkPosition, entityChunkContext: EntityChunkContext) : this(
        chunkPosition, entityChunkContext, mutableListOf(), DataProperties(),
    )

    /** Traverses every root and passenger occurrence, reporting cycles without modifying the graph. */
    fun allEntities(): Sequence<Entity> = rootEntities.asSequence().flatMap(Entity::allEntities)
}

/** No owner, reverse vehicle link, UUID index or mutation tracking is installed on this object. */
class Entity(
    var entityTypeId: EntityTypeId,
    var uuid: Uuid,
    var position: EntityVector3d,
    var deltaMovement: EntityVector3d,
    var entityRotation: EntityRotation,
    var passengers: MutableList<Entity>?,
    var properties: DataProperties = DataProperties(),
) {
    val blockPosition: BlockPosition get() = MinecraftCoordinates.block(position.x, position.y, position.z)
    val sectionPosition: SectionPosition get() = blockPosition.sectionPosition
    val chunkPosition: ChunkPosition get() = blockPosition.chunkPosition
    val regionPosition: RegionPosition get() = blockPosition.regionPosition

    /** Visits each occurrence. Cycles are reported by this traversal, without changing the graph. */
    fun allEntities(): Sequence<Entity> = traverseEntities(this)
}

private fun traverseEntities(entity: Entity): Sequence<Entity> = sequence {
    val path = HashSet<Entity>()
    val stack = ArrayDeque<Pair<Entity, Iterator<Entity>>>()
    var current: Entity? = entity
    while (current != null || stack.isNotEmpty()) {
        if (current != null) {
            val next = current
            require(path.add(next)) { "An Entity passenger graph contains a cycle" }
            yield(next)
            stack.addLast(next to next.passengers.orEmpty().iterator())
            current = null
        } else {
            val (parent, children) = stack.last()
            if (children.hasNext()) current = children.next() else {
                stack.removeLast()
                path.remove(parent)
            }
        }
    }
}

data class EntityVector3d(val x: Double, val y: Double, val z: Double) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Entity vector coordinates must be finite" }
    }

    companion object {
        val ZERO: EntityVector3d = EntityVector3d(0.0, 0.0, 0.0)
    }
}

data class EntityRotation(val yaw: Float, val pitch: Float) {
    init {
        require(yaw.isFinite() && pitch.isFinite()) { "Entity rotations must be finite" }
    }

    companion object {
        val ZERO: EntityRotation = EntityRotation(0.0f, 0.0f)
    }
}
