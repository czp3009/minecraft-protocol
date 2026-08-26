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
    val entityChunkNbtContext: EntityChunkNbtContext<E>,
    val nbtFormat: NbtFormat = NbtFormat(NbtFormatConfiguration(nbtRootEncoding = NbtRootEncoding.UNNAMED)),
) {
    constructor(
        expectedDataVersion: Int,
        entityDataRegistry: EntityDataRegistry<E>,
        nbtFormat: NbtFormat = NbtFormat(NbtFormatConfiguration(nbtRootEncoding = NbtRootEncoding.UNNAMED)),
    ) : this(EntityChunkNbtContext(entityDataRegistry, expectedDataVersion), nbtFormat)

    val expectedDataVersion: Int
        get() = entityChunkNbtContext.expectedDataVersion

    init {
        require(expectedDataVersion >= 0) { "A Minecraft data version must be non-negative" }
        require(nbtFormat.nbtFormatConfiguration.nbtRootEncoding == NbtRootEncoding.UNNAMED) {
            "Region Entity Chunk NBT requires NbtRootEncoding.UNNAMED"
        }
    }

    /** Decodes an Entity Chunk using the position carried by its NBT root. */
    fun decodeFromSource(source: Source): EntityChunk<E> = decodeDocument(nbtFormat.decodeDocumentFromSource(source))

    /** Decodes an Entity Chunk and validates its NBT position against its Region entry. */
    fun decodeFromSource(source: Source, expectedPosition: ChunkPosition): EntityChunk<E> =
        decodeDocument(nbtFormat.decodeDocumentFromSource(source), expectedPosition)

    fun encodeToSink(entityChunk: EntityChunk<E>, sink: Sink) {
        nbtFormat.encodeDocumentToSink(encodeDocument(entityChunk), sink)
    }

    /** Decodes an Entity Chunk using the position carried by its NBT root. */
    fun decodeDocument(nbtDocument: NbtDocument): EntityChunk<E> = decodeDocumentInternal(nbtDocument, null)

    /** Decodes an Entity Chunk and validates its NBT position against its Region entry. */
    fun decodeDocument(nbtDocument: NbtDocument, expectedPosition: ChunkPosition): EntityChunk<E> =
        decodeDocumentInternal(nbtDocument, expectedPosition)

    private fun decodeDocumentInternal(nbtDocument: NbtDocument, expectedPosition: ChunkPosition?): EntityChunk<E> {
        val root = nbtDocument.root
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
        val entities = root.requireList(ENTITIES, "Entity Chunk").value.mapIndexed { index, nbtTag ->
            decodeEntity(nbtTag as? NbtCompound ?: wrongEntityType("Entity Chunk entry $index", "TAG_Compound", nbtTag))
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

    fun encodeDocument(entityChunk: EntityChunk<E>): NbtDocument {
        if (entityChunk.dataVersion != expectedDataVersion) {
            throw EntityChunkNbtFormatException(
                "Entity Chunk data version ${entityChunk.dataVersion} does not match $expectedDataVersion",
            )
        }
        entityChunk.rootEntities.firstOrNull { entity -> entity.chunkPosition != entityChunk.chunkPosition }?.let { entity ->
            throw EntityChunkNbtFormatException(
                "Root Entity ${entity.uuid} belongs to Chunk ${entity.chunkPosition}, expected ${entityChunk.chunkPosition}",
            )
        }
        val root = linkedMapOf<String, NbtTag>()
        root[DATA_VERSION] = NbtInt(entityChunk.dataVersion)
        root[ENTITIES] = NbtList(entityChunk.rootEntities.map(::encodeEntity))
        root[POSITION] = NbtIntArray(intArrayOf(entityChunk.chunkPosition.x, entityChunk.chunkPosition.z))
        return NbtDocument(NbtCompound(root))
    }

    private fun decodeEntity(nbtCompound: NbtCompound): Entity<E> {
        val type = nbtCompound.requireString(ID, "Entity")
        if (type.isBlank()) throw EntityChunkNbtFormatException("Entity id must not be blank")
        val uuid = nbtCompound.requireUuid(UUID)
        val position = nbtCompound.requireDoubleVector(POS)
        val velocity = nbtCompound.requireDoubleVector(MOTION)
        val entityRotation = nbtCompound.requireRotation(ROTATION)
        val passengers = nbtCompound.optionalList(PASSENGERS, "Entity")?.value.orEmpty().mapIndexed { index, nbtTag ->
            decodeEntity(nbtTag as? NbtCompound ?: wrongEntityType("Entity passenger $index", "TAG_Compound", nbtTag))
        }
        val persistentData = linkedMapOf<String, NbtTag>()
        nbtCompound.forEachEntry { name, nbtTag ->
            if (name !in ENTITY_STRUCTURE_FIELDS) persistentData[name] = nbtTag
        }
        val data = try {
            entityChunkNbtContext.entityDataRegistry.resolve(type, NbtCompound(persistentData))
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
                entityRotation = entityRotation,
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
        value[ROTATION] = NbtList(listOf(NbtFloat(entity.entityRotation.yaw), NbtFloat(entity.entityRotation.pitch)))
        val persistentData = try {
            entityChunkNbtContext.entityDataRegistry.describe(entity.type, entity.data)?.requireNoEntityStructureFields()
        } catch (failure: IllegalArgumentException) {
            throw EntityChunkNbtFormatException("Invalid Entity data for ${entity.type}", failure)
        } ?: throw EntityChunkNbtFormatException("Unrepresentable Entity data for ${entity.type}")
        persistentData.forEachEntry { name, nbtTag -> value[name] = nbtTag }
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
    val nbtTag = this[name] ?: return null
    return nbtTag as? NbtList ?: wrongEntityFieldType(description, name, "TAG_List")
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
    val byteArray = ByteArray(Uuid.SIZE_BYTES)
    repeat(UUID_INT_COUNT) { index ->
        val value = values[index]
        val offset = index * Int.SIZE_BYTES
        byteArray[offset] = (value ushr 24).toByte()
        byteArray[offset + 1] = (value ushr 16).toByte()
        byteArray[offset + 2] = (value ushr 8).toByte()
        byteArray[offset + 3] = value.toByte()
    }
    return Uuid.fromByteArray(byteArray)
}

private fun EntityVector3d.toDoubleList(): NbtList =
    NbtList(listOf(NbtDouble(x), NbtDouble(y), NbtDouble(z)))

private fun Uuid.toNbtIntArray(): NbtIntArray {
    val byteArray = toByteArray()
    return NbtIntArray(IntArray(UUID_INT_COUNT) { index ->
        val offset = index * Int.SIZE_BYTES
        ((byteArray[offset].toInt() and 0xFF) shl 24) or
                ((byteArray[offset + 1].toInt() and 0xFF) shl 16) or
                ((byteArray[offset + 2].toInt() and 0xFF) shl 8) or
                (byteArray[offset + 3].toInt() and 0xFF)
    })
}

private fun NbtCompound.wrongEntityFieldType(description: String, name: String, expected: String): Nothing {
    val actual = this[name]?.let { nbtTag -> nbtTag::class.simpleName } ?: "missing"
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
