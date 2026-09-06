package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import kotlin.test.*
import kotlin.uuid.Uuid
import kotlinx.io.Buffer

class EntityPoiNbtTest {
    private val nbtFormat = NbtFormat(NbtFormatConfiguration(nbtRootEncoding = NbtRootEncoding.UNNAMED))
    private val readMappings = NbtPropertyReadMappings()
    private val writeMappings = NbtPropertyWriteMappings()
    private val entityContext = EntityChunkContext(DimensionId.Overworld)
    private val entityDecoder =
        EntityChunkNbtDecoder(EntityChunkNbtDecoderContext(entityContext, nbtFormat, readMappings))
    private val entityEncoder =
        EntityChunkNbtEncoder(EntityChunkNbtEncoderContext(nbtFormat, writeMappings, EntityChunkNbtMetadata(-123)))
    private val poiContext = PoiChunkContext(DimensionId.Overworld, testChunkContext().dimensionTypeLayout.chunkLayout)
    private val poiPosition = ChunkPosition(0, 0)
    private val poiDecoder =
        PoiChunkNbtDecoder(PoiChunkNbtDecoderContext(poiContext, poiPosition, nbtFormat, readMappings))
    private val poiEncoder = PoiChunkNbtEncoder(
        PoiChunkNbtEncoderContext(
            poiContext.chunkLayout,
            nbtFormat,
            writeMappings,
            PoiChunkNbtMetadata(-321)
        )
    )

    @Test
    fun entityStreamsKeepPropertiesAndApplyPassengerPositionOnlyToTheRepresentation() {
        val passenger = entity(2, EntityVector3d(100.0, -1000.0, -100.0))
        val root = entity(1, EntityVector3d(1.25, 70.0, 2.5))
        root.passengers!!.add(passenger)
        root.properties["fabric:attachments"] = readMappings.readValue(NbtCompound(mapOf("example:state" to NbtInt(3))))
        passenger.properties["example:passenger"] = PropertyValue(PropertyTypes.Short, 7.toShort())
        val chunk = EntityChunk(ChunkPosition(0, 0), entityContext, mutableListOf(root), DataProperties())
        chunk.properties["example:root"] = PropertyValue(PropertyTypes.Long, 9L)
        val buffer = Buffer()
        entityEncoder.encode(chunk, buffer)
        val result = entityDecoder.decode(buffer)
        assertEquals(-123, result.entityChunkNbtMetadata.dataVersion)
        assertSame(entityContext, result.entityChunk.entityChunkContext)
        val decodedRoot = result.entityChunk.rootEntities.single()
        assertEquals(root.position, decodedRoot.position)
        assertEquals(EntityVector3d(1.25, -1000.0, 2.5), decodedRoot.passengers!!.single().position)
        assertEquals(EntityVector3d(100.0, -1000.0, -100.0), passenger.position)
        assertEquals(passenger.properties, decodedRoot.passengers!!.single().properties)
        assertEquals(root.properties, decodedRoot.properties)
        assertEquals(chunk.properties, result.entityChunk.properties)
        assertEquals(entityEncoder.encodeDocument(chunk), entityEncoder.encodeDocument(result.entityChunk))
        val properties = decodedRoot.passengers!!.single().properties
        decodedRoot.passengers!!.clear()
        properties["example:passenger"] = PropertyValue(PropertyTypes.Short, 8.toShort())
        val tag = entityEncoder.encodeDocument(result.entityChunk).root.requiredTag<NbtList>("Entities")[0].compound()
        assertNull(tag["Passengers"])
    }

    @Test
    fun sharingIsAllowedWhileCyclesAndUnknownPassengerRelationshipsFailAtEncoding() {
        val shared = entity(2, EntityVector3d.ZERO)
        val root = entity(1, EntityVector3d.ZERO)
        root.passengers!!.addAll(listOf(shared, shared))
        val chunk = EntityChunk(ChunkPosition(0, 0), entityContext, mutableListOf(root), DataProperties())
        val result = chunk.toCompressedChunk(entityEncoder).toEntityChunk(entityDecoder).entityChunk
        assertEquals(2, result.rootEntities.single().passengers!!.size)
        shared.passengers!!.add(root)
        assertFailsWith<EntityChunkNbtFormatException> { entityEncoder.encodeDocument(chunk) }
        shared.passengers = null
        assertFailsWith<EntityChunkNbtFormatException> { entityEncoder.encodeDocument(chunk) }
        root.passengers!!.clear()
        assertNotNull(entityEncoder.encodeDocument(chunk))
        assertNull(shared.passengers)
    }

    @Test
    fun fieldsMayPrecedeEntityIdentityAndExplicitMappingKeepsASharedTypedValue() {
        val propertyType = PropertyType<DataProperties>("machine")
        val key = PropertyKey("example:state", propertyType)
        val mapping = NbtPropertyPath(NbtPropertyScope("entity", "example:entity"), key.name)
        val customReader = NbtPropertyReadMappings(mapOf(mapping to NbtPropertyReader { tag, mappings ->
            PropertyValue(propertyType, mappings.readValue(tag).get(PropertyTypes.Properties))
        }))
        val customWriter =
            NbtPropertyWriteMappings(types = listOf(NbtPropertyValueWriter(propertyType) { properties, mappings ->
                mappings.writeValue(PropertyValue(PropertyTypes.Properties, properties))
            }))
        val decoder = EntityChunkNbtDecoder(EntityChunkNbtDecoderContext(entityContext, nbtFormat, customReader))
        val encoder =
            EntityChunkNbtEncoder(EntityChunkNbtEncoderContext(nbtFormat, customWriter, EntityChunkNbtMetadata(10)))
        val entity = NbtCompound(
            linkedMapOf(
                "example:state" to NbtCompound(mapOf("energy" to NbtInt(3))),
                "id" to NbtString("example:entity"), "UUID" to Uuid.fromLongs(0, 1).toNbtIntArray()
            )
        )
        val document = NbtDocument(
            NbtCompound(
                mapOf(
                    "DataVersion" to NbtInt(10), "Position" to NbtIntArray(intArrayOf(0, 0)),
                    "Entities" to NbtList(listOf(entity))
                )
            )
        )
        val chunk = decoder.decodeDocument(document).entityChunk
        val properties = chunk.rootEntities.single().properties.require(key)
        properties[PropertyKey("energy", PropertyTypes.Int)] = 5
        val output = encoder.encodeDocument(chunk).root.requiredTag<NbtList>("Entities")[0].compound()
        assertEquals(NbtInt(5), output.requiredTag<NbtCompound>(key.name)["energy"])
    }

    @Test
    fun poiSchemaDefaultsAndDynamicScopesRoundTripThroughThePositionedDecoder() {
        val document = NbtDocument(
            NbtCompound(
                mapOf(
                    "DataVersion" to NbtInt(-321), "extra" to NbtInt(1),
                    "Sections" to NbtCompound(
                        mapOf(
                            "0" to NbtCompound(
                                mapOf(
                                    "extra" to NbtInt(2), "Records" to NbtList(
                                        listOf(
                                            NbtCompound(
                                                mapOf(
                                                    "pos" to NbtIntArray(intArrayOf(1, 2, 3)),
                                                    "type" to NbtString("minecraft:home"),
                                                    "extra" to NbtInt(3)
                                                )
                                            ),
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        val result = poiDecoder.decodeDocument(document)
        assertEquals(-321, result.poiChunkNbtMetadata.dataVersion)
        assertSame(poiContext, result.poiChunk.poiChunkContext)
        val section = result.poiChunk.sections.getValue(0)
        assertFalse(section.isValid)
        assertEquals(0, section.records.values.single().freeTickets)
        assertEquals(document, poiEncoder.encodeDocument(result.poiChunk))
        section.isValid = true
        section.records.values.single().freeTickets = -7
        val decoded = result.poiChunk.toCompressedChunk(poiEncoder, Compression.ZLIB).toPoiChunk(poiDecoder).poiChunk
        assertEquals(-7, decoded.sections.getValue(0).records.values.single().freeTickets)
        section.records.clear()
        assertTrue(section.isValid)
        val afterRemoval = poiDecoder.decodeDocument(poiEncoder.encodeDocument(result.poiChunk)).poiChunk
        assertTrue(afterRemoval.sections.getValue(0).records.isEmpty())
        assertEquals(section.properties, afterRemoval.sections.getValue(0).properties)
    }

    @Test
    fun poiEncodingChecksTheCurrentGraphRatherThanInstallingAnOwner() {
        val section = PoiSection(true)
        val poiChunk = PoiChunk(poiPosition, poiContext, linkedMapOf(0 to section), DataProperties())
        section.records[BlockPosition(32, 0, 0)] = PoiRecord(PoiTypeId.parse("home"), 1)
        assertFailsWith<PoiChunkNbtFormatException> { poiEncoder.encodeDocument(poiChunk) }
        section.records.clear()
        section.records[BlockPosition(1, 0, 0)] = PoiRecord(PoiTypeId.parse("home"), 1)
        val document = poiEncoder.encodeDocument(poiChunk)
        val wrongSlot =
            PoiChunkNbtDecoder(poiDecoder.poiChunkNbtDecoderContext.copy(chunkPosition = ChunkPosition(2, 0)))
        assertFailsWith<PoiChunkNbtFormatException> { wrongSlot.decodeDocument(document) }
    }

    private fun entity(id: Long, position: EntityVector3d): Entity = Entity(
        EntityTypeId.parse("pig"),
        Uuid.fromLongs(0, id),
        position,
        EntityVector3d.ZERO,
        EntityRotation.ZERO,
        mutableListOf(),
    )
}
