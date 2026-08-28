package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.NbtCompound
import kotlin.uuid.Uuid

/** A finite three-dimensional value used by persisted Entity position and velocity. */
data class EntityVector3d(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "Entity vector coordinates must be finite" }
    }

    companion object {
        val ZERO: EntityVector3d = EntityVector3d(0.0, 0.0, 0.0)
    }
}

/** The yaw and pitch persisted for one Entity. */
data class EntityRotation(
    val yaw: Float,
    val pitch: Float,
) {
    init {
        require(yaw.isFinite() && pitch.isFinite()) { "Entity rotations must be finite" }
    }

    companion object {
        val ZERO: EntityRotation = EntityRotation(0.0f, 0.0f)
    }
}

/** Caller-supplied conversion between persisted subtype NBT and one runtime Entity data value. */
interface EntityDataRegistry<E : Any> {
    fun resolve(type: String, persistentData: NbtCompound): E?

    fun describe(type: String, value: E): NbtCompound?
}

/** Open Entity data mapping that retains every subtype and mod field as raw NBT. */
class NbtEntityDataRegistry : EntityDataRegistry<NbtCompound> {
    override fun resolve(type: String, persistentData: NbtCompound): NbtCompound = persistentData

    override fun describe(type: String, value: NbtCompound): NbtCompound = value
}

/**
 * A mutable, detached semantic Entity loaded from an Entity Chunk.
 *
 * Common persisted state is modeled directly. [data] is caller-selected subtype state produced by an
 * [EntityDataRegistry], so vanilla- or mod-specific data can be strongly typed without introducing a vanilla-data
 * dependency into `world-format`.
 */
class Entity<E : Any>(
    val type: String,
    val uuid: Uuid,
    data: E,
    position: EntityVector3d,
    velocity: EntityVector3d = EntityVector3d.ZERO,
    entityRotation: EntityRotation = EntityRotation.ZERO,
    passengers: Collection<Entity<E>> = emptyList(),
) {
    private val storedPassengers = passengers.toMutableList()

    init {
        require(type.isNotBlank()) { "An Entity type must not be blank" }
        val passengerEntities = storedPassengers.asSequence().flatMap { passenger -> passenger.allEntities() }.toList()
        require(passengerEntities.none { passenger -> passenger === this }) { "An Entity passenger graph cannot cycle" }
        val passengerUuids = passengerEntities.map { passenger -> passenger.uuid }
        require(uuid !in passengerUuids && passengerUuids.distinct().size == passengerUuids.size) {
            "An Entity cannot contain duplicate passenger UUIDs"
        }
    }

    var data: E = data

    var position: EntityVector3d = position

    var velocity: EntityVector3d = velocity

    var entityRotation: EntityRotation = entityRotation

    val passengers: List<Entity<E>>
        get() = storedPassengers.toList()

    val blockPosition: BlockPosition
        get() = MinecraftCoordinates.block(position.x, position.y, position.z)

    val sectionPosition: SectionPosition
        get() = blockPosition.sectionPosition

    val chunkPosition: ChunkPosition
        get() = blockPosition.chunkPosition

    val regionPosition: RegionPosition
        get() = blockPosition.regionPosition

    fun isIn(sectionPosition: SectionPosition): Boolean = this.sectionPosition == sectionPosition

    fun isIn(chunkPosition: ChunkPosition): Boolean = this.chunkPosition == chunkPosition

    fun isIn(regionPosition: RegionPosition): Boolean = this.regionPosition == regionPosition

    fun entity(uuid: Uuid): Entity<E>? = allEntities().firstOrNull { entity -> entity.uuid == uuid }

    fun hasEntity(uuid: Uuid): Boolean = entity(uuid) != null

    fun addPassenger(passenger: Entity<E>) {
        require(passenger !== this) { "An Entity cannot ride itself" }
        val passengerEntities = passenger.allEntities().toList()
        require(passengerEntities.none { entity -> entity === this }) { "An Entity passenger graph cannot cycle" }
        val addedUuids = passengerEntities.map { entity -> entity.uuid }
        require(addedUuids.distinct().size == addedUuids.size) { "An added passenger tree contains duplicate UUIDs" }
        val existingUuids = allEntities().map { entity -> entity.uuid }.toSet()
        require(addedUuids.none(existingUuids::contains)) {
            "An Entity already contains one of the added passenger UUIDs"
        }
        storedPassengers += passenger
    }

    fun removePassenger(uuid: Uuid): Entity<E>? {
        val index = storedPassengers.indexOfFirst { passenger -> passenger.uuid == uuid }
        if (index >= 0) return storedPassengers.removeAt(index)
        storedPassengers.forEach { passenger ->
            passenger.removePassenger(uuid)?.let { removed -> return removed }
        }
        return null
    }

    /** This Entity followed by every recursively nested passenger in persisted order. */
    fun allEntities(): Sequence<Entity<E>> = sequence {
        yield(this@Entity)
        storedPassengers.forEach { passenger -> yieldAll(passenger.allEntities()) }
    }

    /** Creates a detached recursive snapshot while retaining caller-owned subtype values. */
    fun snapshot(): Entity<E> = snapshot { value -> value }

    /** Creates a detached recursive snapshot and lets the caller copy its subtype value. */
    fun snapshot(copyData: (E) -> E): Entity<E> = Entity(
        type = type,
        uuid = uuid,
        data = copyData(data),
        position = position,
        velocity = velocity,
        entityRotation = entityRotation,
        passengers = storedPassengers.map { passenger -> passenger.snapshot(copyData) },
    )
}

/** A mutable Entity Chunk at one absolute X/Z position. It deliberately has no Section ownership layer. */
class EntityChunk<E : Any>(
    val chunkPosition: ChunkPosition,
    val dataVersion: Int,
    rootEntities: Collection<Entity<E>> = emptyList(),
) {
    private val storedRootEntities = rootEntities.toMutableList()

    init {
        requireUniqueEntityUuids(storedRootEntities)
        require(storedRootEntities.all { entity -> entity.chunkPosition == chunkPosition }) {
            "An Entity Chunk contains a root Entity outside $chunkPosition"
        }
    }

    val rootEntities: List<Entity<E>>
        get() = storedRootEntities.toList()

    val rootEntityCount: Int
        get() = storedRootEntities.size

    val entityCount: Int
        get() = allEntities().count()

    val isEmpty: Boolean
        get() = storedRootEntities.isEmpty()

    fun rootEntity(uuid: Uuid): Entity<E>? = storedRootEntities.firstOrNull { entity -> entity.uuid == uuid }

    fun entity(uuid: Uuid): Entity<E>? = allEntities().firstOrNull { entity -> entity.uuid == uuid }

    fun hasEntity(uuid: Uuid): Boolean = entity(uuid) != null

    /** Every root Entity and recursively nested passenger in persisted order. */
    fun allEntities(): Sequence<Entity<E>> = storedRootEntities.asSequence().flatMap { entity -> entity.allEntities() }

    /** Every Entity whose current position belongs to [chunkPosition], including nested passengers. */
    fun entitiesIn(chunkPosition: ChunkPosition): Sequence<Entity<E>> =
        allEntities().filter { entity -> entity.isIn(chunkPosition) }

    /** Every Entity whose current position belongs to [sectionPosition], including nested passengers. */
    fun entitiesIn(sectionPosition: SectionPosition): Sequence<Entity<E>> =
        allEntities().filter { entity -> entity.isIn(sectionPosition) }

    /** Every Entity whose current position belongs to [regionPosition], including nested passengers. */
    fun entitiesIn(regionPosition: RegionPosition): Sequence<Entity<E>> =
        allEntities().filter { entity -> entity.isIn(regionPosition) }

    fun addEntity(entity: Entity<E>) {
        require(entity.chunkPosition == chunkPosition) {
            "Root Entity ${entity.uuid} belongs to Chunk ${entity.chunkPosition}, expected $chunkPosition"
        }
        val existingUuids = allEntities().map { existing -> existing.uuid }.toSet()
        val addedUuids = entity.allEntities().map { added -> added.uuid }.toList()
        require(addedUuids.distinct().size == addedUuids.size) { "An Entity tree contains duplicate UUIDs" }
        require(addedUuids.none(existingUuids::contains)) { "An Entity Chunk already contains one of the added UUIDs" }
        storedRootEntities += entity
    }

    fun removeRootEntity(uuid: Uuid): Entity<E>? {
        val index = storedRootEntities.indexOfFirst { entity -> entity.uuid == uuid }
        return if (index >= 0) storedRootEntities.removeAt(index) else null
    }

    fun removeEntity(uuid: Uuid): Entity<E>? {
        removeRootEntity(uuid)?.let { removed -> return removed }
        storedRootEntities.forEach { entity ->
            entity.removePassenger(uuid)?.let { removed -> return removed }
        }
        return null
    }

    /** Creates a detached recursive snapshot while retaining caller-owned subtype values. */
    fun snapshot(): EntityChunk<E> =
        EntityChunk(chunkPosition, dataVersion, storedRootEntities.map { entity -> entity.snapshot() })

    /** Creates a detached recursive snapshot and lets the caller copy subtype values. */
    fun snapshot(copyData: (E) -> E): EntityChunk<E> =
        EntityChunk(chunkPosition, dataVersion, storedRootEntities.map { entity -> entity.snapshot(copyData) })
}

private fun <E : Any> requireUniqueEntityUuids(entities: Collection<Entity<E>>) {
    val uuids = entities.asSequence()
        .flatMap { entity -> entity.allEntities() }
        .map { entity -> entity.uuid }
        .toList()
    require(uuids.distinct().size == uuids.size) { "An Entity Chunk contains duplicate UUIDs" }
}

internal fun NbtCompound.requireNoEntityStructureFields(): NbtCompound {
    val reserved = value.keys intersect ENTITY_STRUCTURE_FIELDS
    require(reserved.isEmpty()) {
        "Entity persistent data cannot contain structural fields: ${reserved.sorted().joinToString()}"
    }
    return this
}

internal val ENTITY_STRUCTURE_FIELDS: Set<String> = setOf("id", "UUID", "Pos", "Motion", "Rotation", "Passengers")
