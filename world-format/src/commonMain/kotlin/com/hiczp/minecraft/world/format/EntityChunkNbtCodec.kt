package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlin.uuid.Uuid

/** A selected-release Entity Chunk NBT error. */
class EntityChunkNbtFormatException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Selected-release conversion between unnamed-root Entity Chunk NBT and a positioned semantic [EntityChunk]. */
class EntityChunkNbtCodec<E : Any>(
    val context: EntityChunkNbtContext<E>,
    val nbt: NbtFormat = NbtFormat(NbtFormatConfiguration(rootEncoding = NbtRootEncoding.UNNAMED)),
) {
    constructor(
        expectedDataVersion: Int,
        entityDataRegistry: EntityDataRegistry<E>,
        nbt: NbtFormat = NbtFormat(NbtFormatConfiguration(rootEncoding = NbtRootEncoding.UNNAMED)),
    ) : this(EntityChunkNbtContext(entityDataRegistry, expectedDataVersion), nbt)

    val expectedDataVersion: Int
        get() = context.expectedDataVersion

    init {
        require(expectedDataVersion >= 0) { "A Minecraft data version must be non-negative" }
        require(nbt.configuration.rootEncoding == NbtRootEncoding.UNNAMED) {
            "Region Entity Chunk NBT requires NbtRootEncoding.UNNAMED"
        }
    }

    /** Decodes an Entity Chunk using the position carried by its NBT root. */
    fun decodeFromSource(source: Source): EntityChunk<E> = decodeDocument(nbt.decodeDocumentFromSource(source))

    /** Decodes an Entity Chunk and validates its NBT position against its Region entry. */
    fun decodeFromSource(source: Source, expectedPosition: ChunkPosition): EntityChunk<E> =
        decodeDocument(nbt.decodeDocumentFromSource(source), expectedPosition)

    fun encodeToSink(chunk: EntityChunk<E>, sink: Sink) {
        nbt.encodeDocumentToSink(encodeDocument(chunk), sink)
    }

    /** Decodes an Entity Chunk using the position carried by its NBT root. */
    fun decodeDocument(document: NbtDocument): EntityChunk<E> = decodeDocumentInternal(document, null)

    /** Decodes an Entity Chunk and validates its NBT position against its Region entry. */
    fun decodeDocument(document: NbtDocument, expectedPosition: ChunkPosition): EntityChunk<E> =
        decodeDocumentInternal(document, expectedPosition)

    private fun decodeDocumentInternal(document: NbtDocument, expectedPosition: ChunkPosition?): EntityChunk<E> {
        val root = document.root
        root.requireOnlyKeys(ENTITY_CHUNK_ROOT_FIELDS, "Entity Chunk")
        val dataVersion = root.requireInt(DATA_VERSION, "Entity Chunk")
        if (dataVersion != expectedDataVersion) {
            throw EntityChunkNbtFormatException(
                "Expected Entity Chunk data version $expectedDataVersion, got $dataVersion",
            )
        }
        val actualPosition = root.requireChunkPosition(POSITION)
        if (expectedPosition != null && actualPosition != expectedPosition) {
            throw EntityChunkNbtFormatException(
                "Expected Entity Chunk position $expectedPosition, got $actualPosition",
            )
        }
        val entities = root.requireList(ENTITIES, "Entity Chunk").value.mapIndexed { index, tag ->
            decodeEntity(tag as? NbtCompound ?: wrongEntityType("Entity Chunk entry $index", "TAG_Compound", tag))
        }
        entities.firstOrNull { entity -> entity.chunkPosition != actualPosition }?.let { entity ->
            throw EntityChunkNbtFormatException(
                "Root Entity ${entity.uuid} belongs to Chunk ${entity.chunkPosition}, expected $actualPosition",
            )
        }
        return try {
            EntityChunk(actualPosition, dataVersion, entities)
        } catch (failure: IllegalArgumentException) {
            throw EntityChunkNbtFormatException("Invalid Entity Chunk", failure)
        }
    }

    fun encodeDocument(chunk: EntityChunk<E>): NbtDocument {
        if (chunk.dataVersion != expectedDataVersion) {
            throw EntityChunkNbtFormatException(
                "Entity Chunk data version ${chunk.dataVersion} does not match $expectedDataVersion",
            )
        }
        chunk.rootEntities.firstOrNull { entity -> entity.chunkPosition != chunk.position }?.let { entity ->
            throw EntityChunkNbtFormatException(
                "Root Entity ${entity.uuid} belongs to Chunk ${entity.chunkPosition}, expected ${chunk.position}",
            )
        }
        val root = linkedMapOf<String, NbtTag>()
        root[DATA_VERSION] = NbtInt(chunk.dataVersion)
        root[ENTITIES] = NbtList(chunk.rootEntities.map(::encodeEntity))
        root[POSITION] = NbtIntArray(intArrayOf(chunk.position.x, chunk.position.z))
        return NbtDocument(NbtCompound(root))
    }

    private fun decodeEntity(compound: NbtCompound): Entity<E> {
        val type = compound.requireString(ID, "Entity")
        if (type.isBlank()) throw EntityChunkNbtFormatException("Entity id must not be blank")
        val uuid = compound.requireUuid(UUID)
        val position = compound.requireDoubleVector(POS)
        val velocity = compound.requireDoubleVector(MOTION)
        val rotation = compound.requireRotation(ROTATION)
        val passengers = compound.optionalList(PASSENGERS, "Entity")?.value.orEmpty().mapIndexed { index, tag ->
            decodeEntity(tag as? NbtCompound ?: wrongEntityType("Entity passenger $index", "TAG_Compound", tag))
        }
        val persistentData = linkedMapOf<String, NbtTag>()
        compound.forEachEntry { name, tag ->
            if (name !in ENTITY_STRUCTURE_FIELDS) persistentData[name] = tag
        }
        val data = try {
            context.entityDataRegistry.resolve(type, NbtCompound(persistentData))
        } catch (failure: IllegalArgumentException) {
            throw EntityChunkNbtFormatException("Invalid Entity data for $type", failure)
        } ?: throw EntityChunkNbtFormatException("Unknown Entity data for $type")
        return try {
            Entity(
                type = type,
                uuid = uuid,
                data = data,
                position = position,
                velocity = velocity,
                rotation = rotation,
                passengers = passengers,
            )
        } catch (failure: IllegalArgumentException) {
            throw EntityChunkNbtFormatException("Invalid Entity $type", failure)
        }
    }

    private fun encodeEntity(entity: Entity<E>): NbtCompound {
        val value = linkedMapOf<String, NbtTag>()
        value[ID] = NbtString(entity.type)
        value[POS] = entity.position.toDoubleList()
        value[MOTION] = entity.velocity.toDoubleList()
        value[ROTATION] = NbtList(listOf(NbtFloat(entity.rotation.yaw), NbtFloat(entity.rotation.pitch)))
        val persistentData = try {
            context.entityDataRegistry.describe(entity.type, entity.data)?.requireNoEntityStructureFields()
        } catch (failure: IllegalArgumentException) {
            throw EntityChunkNbtFormatException("Invalid Entity data for ${entity.type}", failure)
        } ?: throw EntityChunkNbtFormatException("Unrepresentable Entity data for ${entity.type}")
        persistentData.forEachEntry { name, tag -> value[name] = tag }
        value[UUID] = entity.uuid.toNbtIntArray()
        if (entity.passengers.isNotEmpty()) value[PASSENGERS] = NbtList(entity.passengers.map(::encodeEntity))
        return NbtCompound(value)
    }
}

private fun NbtCompound.requireOnlyKeys(known: Set<String>, description: String) {
    val unknown = value.keys - known
    if (unknown.isNotEmpty()) {
        val fields = unknown.sorted().joinToString()
        throw EntityChunkNbtFormatException("$description contains unmodeled fields: $fields")
    }
}

private fun NbtCompound.requireInt(name: String, description: String): Int =
    (this[name] as? NbtInt)?.value ?: wrongEntityFieldType(description, name, "TAG_Int")

private fun NbtCompound.requireString(name: String, description: String): String =
    (this[name] as? NbtString)?.value ?: wrongEntityFieldType(description, name, "TAG_String")

private fun NbtCompound.requireList(name: String, description: String): NbtList =
    this[name] as? NbtList ?: wrongEntityFieldType(description, name, "TAG_List")

private fun NbtCompound.optionalList(name: String, description: String): NbtList? {
    val tag = this[name] ?: return null
    return tag as? NbtList ?: wrongEntityFieldType(description, name, "TAG_List")
}

private fun NbtCompound.requireChunkPosition(name: String): ChunkPosition {
    val values = this[name] as? NbtIntArray
        ?: wrongEntityFieldType("Entity Chunk", name, "two-entry TAG_Int_Array")
    if (values.size != 2) throw EntityChunkNbtFormatException("Entity Chunk Position must contain two integers")
    return ChunkPosition(values[0], values[1])
}

private fun NbtCompound.requireDoubleVector(name: String): EntityVector3d {
    val values = requireList(name, "Entity")
    if (values.size != 3) throw EntityChunkNbtFormatException("Entity $name must contain three doubles")
    fun component(index: Int): Double =
        (values[index] as? NbtDouble)?.value ?: wrongEntityType("Entity $name[$index]", "TAG_Double", values[index])
    return try {
        EntityVector3d(component(0), component(1), component(2))
    } catch (failure: IllegalArgumentException) {
        throw EntityChunkNbtFormatException("Entity $name contains a non-finite coordinate", failure)
    }
}

private fun NbtCompound.requireRotation(name: String): EntityRotation {
    val values = requireList(name, "Entity")
    if (values.size != 2) throw EntityChunkNbtFormatException("Entity Rotation must contain yaw and pitch")
    val yaw = (values[0] as? NbtFloat)?.value ?: wrongEntityType("Entity Rotation yaw", "TAG_Float", values[0])
    val pitch = (values[1] as? NbtFloat)?.value ?: wrongEntityType("Entity Rotation pitch", "TAG_Float", values[1])
    return try {
        EntityRotation(yaw, pitch)
    } catch (failure: IllegalArgumentException) {
        throw EntityChunkNbtFormatException("Entity Rotation contains a non-finite angle", failure)
    }
}

private fun NbtCompound.requireUuid(name: String): Uuid {
    val values = this[name] as? NbtIntArray ?: wrongEntityFieldType("Entity", name, "four-entry TAG_Int_Array")
    if (values.size != UUID_INT_COUNT) {
        throw EntityChunkNbtFormatException("Entity UUID must contain $UUID_INT_COUNT integers")
    }
    val bytes = ByteArray(Uuid.SIZE_BYTES)
    repeat(UUID_INT_COUNT) { index ->
        val value = values[index]
        val offset = index * Int.SIZE_BYTES
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
    return Uuid.fromByteArray(bytes)
}

private fun EntityVector3d.toDoubleList(): NbtList =
    NbtList(listOf(NbtDouble(x), NbtDouble(y), NbtDouble(z)))

private fun Uuid.toNbtIntArray(): NbtIntArray {
    val bytes = toByteArray()
    return NbtIntArray(IntArray(UUID_INT_COUNT) { index ->
        val offset = index * Int.SIZE_BYTES
        ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    })
}

private fun NbtCompound.wrongEntityFieldType(description: String, name: String, expected: String): Nothing {
    val actual = this[name]?.let { tag -> tag::class.simpleName } ?: "missing"
    throw EntityChunkNbtFormatException("$description field $name must be $expected, got $actual")
}

private fun wrongEntityType(description: String, expected: String, actual: NbtTag): Nothing =
    throw EntityChunkNbtFormatException("$description must be $expected, got ${actual::class.simpleName}")

private const val DATA_VERSION = "DataVersion"
private const val ENTITIES = "Entities"
private const val POSITION = "Position"
private const val ID = "id"
private const val UUID = "UUID"
private const val POS = "Pos"
private const val MOTION = "Motion"
private const val ROTATION = "Rotation"
private const val PASSENGERS = "Passengers"
private const val UUID_INT_COUNT = Uuid.SIZE_BYTES / Int.SIZE_BYTES
private val ENTITY_CHUNK_ROOT_FIELDS = setOf(DATA_VERSION, ENTITIES, POSITION)
