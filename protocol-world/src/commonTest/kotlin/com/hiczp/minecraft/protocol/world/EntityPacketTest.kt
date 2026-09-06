package com.hiczp.minecraft.protocol.world

import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.BlockStateIdMapping
import com.hiczp.minecraft.protocol.model.type.DataComponent
import com.hiczp.minecraft.protocol.model.type.DataComponentType
import com.hiczp.minecraft.protocol.model.type.EntityDataValue
import com.hiczp.minecraft.protocol.model.type.EntityMetadata
import com.hiczp.minecraft.protocol.model.type.EntityMetadataEntry
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.PacketCodecContext
import com.hiczp.minecraft.protocol.model.type.RegistryIdMap
import com.hiczp.minecraft.protocol.model.type.RegistryIdMapping
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketPayloadFormat
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketPayloadFormatConfiguration
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketRegistry
import com.hiczp.minecraft.world.format.*
import kotlin.test.*
import kotlin.uuid.Uuid

class EntityPacketTest {
    private val entityTypeId = EntityTypeId("example:creature")
    private val carriedState = PropertyKey("example:carried_state", PropertyTypes.BlockState)
    private val sand = BlockState(BlockId("minecraft:sand"))
    private val gravel = BlockState(BlockId("minecraft:gravel"))
    private val speed = AttributeId("minecraft:movement_speed")
    private val health = AttributeId("minecraft:max_health")
    private val damage = ComponentId("minecraft:damage")
    private val registry = PacketCodecContext(
        listOf(
            registry("entity_type", entityTypeId.value),
            registry("attribute", speed.value, health.value),
            registry("item", "minecraft:stone"),
        ),
        listOf(
            BlockStateIdMapping(7, Identifier(sand.blockId.value), sand.properties.toMap(), true),
            BlockStateIdMapping(8, Identifier(gravel.blockId.value), gravel.properties.toMap(), true),
        ),
    )
    private val itemEncoder = ItemStackPacketEncoder(
        ItemStackPacketEncoderContext(
            registry,
            ItemStackPacketWriteMappings { componentId, propertyValue, _ ->
                require(componentId == damage)
                DataComponent.Damage(propertyValue.get(PropertyTypes.Int))
            },
        )
    )
    private val itemDecoder = ItemStackPacketDecoder(
        ItemStackPacketDecoderContext(
            registry,
            ItemStackPacketReadMappings(
                { component, _ -> PropertyValue(PropertyTypes.Int, assertIs<DataComponent.Damage>(component).value) },
                { DataProperties() },
            ),
        )
    )
    private val supplier = AttributeSupplier(mapOf(speed to AttributeInstance(0.2), health to AttributeInstance(20.0)))
    private val writeMappings = entityPacketWriteMappings(
        spawnData = { entity, packetCodecContext ->
            val blockState = entity.properties.require(carriedState)
            requireNotNull(
                packetCodecContext.blockState(
                    Identifier(blockState.blockId.value),
                    blockState.properties.toMap()
                )
            ).id
        },
        metadata = { entity, _ ->
            entity.properties[EntityProperties.SharedFlags]?.let {
                EntityMetadata(listOf(EntityMetadataEntry(0, EntityDataValue.ByteValue(it))))
            }
        },
        itemStackPacketEncoder = itemEncoder,
        attributeSupplier = { supplier },
        synchronizedAttribute = { _, _ -> true },
    )
    private val readMappings = entityPacketReadMappings(
        spawnData = { entity, data, packetCodecContext ->
            val blockState = requireNotNull(packetCodecContext.blockState(data))
            entity.properties[carriedState] =
                BlockState(BlockId(blockState.block.value), StateProperties(blockState.properties))
        },
        metadata = { entity, metadata, _ ->
            metadata.entries.forEach {
                require(it.index == 0)
                entity.properties[EntityProperties.SharedFlags] = assertIs<EntityDataValue.ByteValue>(it.value).value
            }
        },
        itemStackPacketDecoder = itemDecoder,
        modifierPermanent = { _, _, _ -> false },
    )
    private val encoder = EntityPacketEncoder(
        EntityPacketEncoderContext(
            registry, writeMappings, EntityPacketRequiredDataProvider.RequirePresent,
        )
    )

    @Test
    fun pairingReadsCurrentPropertiesAndLeavesConnectionRelationsForTheCaller() {
        val entity = entity(1)
        val passenger = entity(2)
        entity.passengers!!.add(passenger)
        entity.properties[EntityProperties.HeadYaw] = 90f
        entity.properties[carriedState] = sand
        entity.properties[EntityProperties.SharedFlags] = 3
        val modifierId = ModifierId("example:boost")
        val attributes = EntityAttributes(
            linkedMapOf(
                speed to AttributeInstance(
                    0.4, linkedMapOf(modifierId to AttributeModifier(0.1, AttributeOperation.ADD_VALUE, true)),
                )
            )
        )
        entity.properties[EntityProperties.Attributes] = attributes
        val itemStack = ItemStack(
            ItemId("minecraft:stone"), 4, DataComponentPatch(
                linkedMapOf(
                    damage to ComponentPatchEntry.SetValue(PropertyValue(PropertyTypes.Int, 8)),
                    ComponentId("minecraft:custom_name") to ComponentPatchEntry.Removed,
                )
            )
        )
        entity.properties[EntityProperties.Equipment] =
            EntityEquipment(linkedMapOf(EquipmentSlotId("mainhand") to itemStack))
        val pairing = EntityPairingData(41, mapOf(passenger.uuid to 42), EntityPassengerRelation(40, listOf(41)), 43)
        val packets = encoder.encode(entity, pairing)

        assertEquals(
            listOf(
                ClientboundAddEntityPacket::class,
                ClientboundSetEntityDataPacket::class,
                ClientboundUpdateAttributesPacket::class,
                ClientboundSetEquipmentPacket::class,
                ClientboundSetPassengersPacket::class,
                ClientboundSetPassengersPacket::class,
                ClientboundSetEntityLinkPacket::class,
            ), packets.map { it::class })
        assertEquals(listOf(42), assertIs<ClientboundSetPassengersPacket>(packets[4]).passengers)
        assertFalse(health in attributes.entries)
        assertEquals(0.2, supplier.instances.getValue(speed).baseValue)
        val properties = DataProperties()
        var missingCalls = 0
        val decoder = EntityPacketDecoder(EntityPacketDecoderContext(registry, readMappings) { spawn, type ->
            missingCalls++
            assertEquals(41, spawn.id)
            assertEquals(entityTypeId, type)
            EntityPacketMissingData(properties, null)
        })
        val foreignMetadata = ClientboundSetEntityDataPacket(99, EntityMetadata(emptyList()))
        val repeatedMetadata = ClientboundSetEntityDataPacket(
            41, EntityMetadata(
                listOf(
                    EntityMetadataEntry(0, EntityDataValue.ByteValue(5)),
                )
            )
        )
        val decoded = decoder.decode(packets + foreignMetadata + repeatedMetadata + ClientboundBundleDelimiterPacket)
        assertEquals(1, missingCalls)
        assertSame(properties, decoded.entity.properties)
        assertEquals(5, properties.require(EntityProperties.SharedFlags).toInt())
        assertEquals(90f, properties.require(EntityProperties.HeadYaw))
        assertEquals(sand, properties.require(carriedState))
        assertNull(properties["spawn_data"])
        assertNull(decoded.entity.passengers)
        assertEquals(packets.drop(4) + foreignMetadata + ClientboundBundleDelimiterPacket, decoded.pendingPackets)
        val decodedAttributes = properties.require(EntityProperties.Attributes).entries
        assertEquals(0.4, decodedAttributes.getValue(speed).baseValue)
        assertEquals(20.0, decodedAttributes.getValue(health).baseValue)
        assertFalse(decodedAttributes.getValue(speed).modifiers.getValue(modifierId).permanent)
        val decodedItem =
            assertNotNull(properties.require(EntityProperties.Equipment).slots[EquipmentSlotId("mainhand")])
        assertEquals(itemStack, decodedItem)

        attributes.entries.getValue(speed).baseValue = 0.8
        entity.passengers!!.clear()
        itemStack.count = 9
        val changed = encoder.encode(entity, pairing.copy(vehiclePassengerRelation = null, leashHolderEntityId = null))
        assertEquals(4, changed.size)
        assertEquals(0.8, assertIs<ClientboundUpdateAttributesPacket>(changed[2]).attributes.first().base)
        val equipment = assertIs<ClientboundSetEquipmentPacket>(changed[3]).slots.entries.single()
        assertEquals(9, assertNotNull(itemDecoder.decode(equipment.item)).count)
    }

    @Test
    fun persistedEntityCanBeEditedTransmittedDecodedAndSavedWithIndependentContexts() {
        val entity = entity(4).apply {
            properties[EntityProperties.HeadYaw] = 90f
            properties[carriedState] = sand
            properties[EntityProperties.SharedFlags] = 3
            properties["example:private"] = PropertyValue(PropertyTypes.String, "server-only")
        }
        val entityChunkContext = EntityChunkContext(DimensionId.parse("example:server"))
        val entityChunk = EntityChunk(entity.chunkPosition, entityChunkContext, mutableListOf(entity), DataProperties())
        val entityChunkNbtEncoder = EntityChunkNbtEncoder(
            EntityChunkNbtEncoderContext(
                NbtFormat, NbtPropertyWriteMappings(types = NbtPropertyWriters.types), EntityChunkNbtMetadata(12345),
            )
        )
        val nbtReadMappings = NbtPropertyReadMappings(
            mapOf(
                NbtPropertyPath(NbtPropertyScope("entity"), carriedState.name) to NbtPropertyReaders.blockState,
                NbtPropertyPath(NbtPropertyScope("entity"), "attributes") to NbtPropertyReaders.entityAttributes,
            )
        )
        val entityChunkNbtDecoder = EntityChunkNbtDecoder(
            EntityChunkNbtDecoderContext(
                entityChunkContext, NbtFormat, nbtReadMappings,
            )
        )
        val loaded = entityChunk.toCompressedChunk(entityChunkNbtEncoder).toEntityChunk(entityChunkNbtDecoder)
        assertSame(entityChunkContext, loaded.entityChunk.entityChunkContext)
        assertEquals(12345, loaded.entityChunkNbtMetadata.dataVersion)
        val current = loaded.entityChunk.rootEntities.single()
        current.position = EntityVector3d(-10.5, 81.0, 5.25)
        current.properties[EntityProperties.SharedFlags] = 4

        val minecraftPacketPayloadFormat = MinecraftPacketPayloadFormat(
            MinecraftPacketPayloadFormatConfiguration(packetCodecContext = registry),
        )
        val received = encoder.encode(current, EntityPairingData(41, emptyMap(), null, null)).map { packet ->
            val encoded = MinecraftPacketRegistry.encodePayload(
                packet, ConnectionState.PLAY, PacketDirection.CLIENTBOUND, minecraftPacketPayloadFormat,
            )
            assertIs<ClientboundPacket>(
                MinecraftPacketRegistry.decodePayload(
                    ConnectionState.PLAY, PacketDirection.CLIENTBOUND, encoded.packetKey.id, encoded.payload,
                    minecraftPacketPayloadFormat,
                )
            )
        }
        val properties = DataProperties()
        val entityPacketDecoder = EntityPacketDecoder(EntityPacketDecoderContext(registry, readMappings) { _, _ ->
            EntityPacketMissingData(properties, mutableListOf())
        })
        val decoded = entityPacketDecoder.decode(received)
        assertEquals(41, decoded.entityId)
        assertSame(properties, decoded.entity.properties)
        assertEquals(current.uuid, decoded.entity.uuid)
        assertEquals(current.position, decoded.entity.position)
        assertEquals(4.toByte(), properties.require(EntityProperties.SharedFlags))
        assertEquals(sand, properties.require(carriedState))
        assertNull(properties["spawn_data"])
        assertNull(properties["example:private"])
        assertNotNull(current.properties["example:private"])

        val clientContext = EntityChunkContext(DimensionId.parse("example:client"))
        val clientChunk =
            EntityChunk(decoded.entity.chunkPosition, clientContext, mutableListOf(decoded.entity), DataProperties())
        val clientDecoder =
            EntityChunkNbtDecoder(EntityChunkNbtDecoderContext(clientContext, NbtFormat, nbtReadMappings))
        val clientSaved = clientChunk.toCompressedChunk(entityChunkNbtEncoder).toEntityChunk(clientDecoder).entityChunk
        assertSame(clientContext, clientSaved.entityChunkContext)
        assertEquals(decoded.entity.position, clientSaved.rootEntities.single().position)
        assertEquals(4.toByte(), clientSaved.rootEntities.single().properties.require(EntityProperties.SharedFlags))
        assertEquals(sand, clientSaved.rootEntities.single().properties.require(carriedState))
        assertNull(clientSaved.rootEntities.single().properties["spawn_data"])
        assertNull(clientSaved.rootEntities.single().properties["example:private"])
    }

    @Test
    fun missingEntityFactsAreProvidedWhileSpawnDataIsAlwaysProjected() {
        val entity = entity(3).copy(passengers = null)
        val pairing = EntityPairingData(1, emptyMap(), null, null)
        assertFailsWith<IllegalStateException> { encoder.encode(entity, pairing) }
        val calls = mutableListOf<String>()
        val supplied = EntityPacketEncoder(
            EntityPacketEncoderContext(
                registry, writeMappings.copy(spawnData = { _, _ -> calls.add("data"); 2 }),
                EntityPacketRequiredDataProvider(
                    { calls.add("head"); 30f },
                    { calls.add("passengers"); emptyList() },
                ),
            )
        )
        supplied.encode(entity, pairing)
        assertEquals(listOf("head", "passengers", "data"), calls)
        assertTrue(entity.properties.entries.isEmpty())
        assertNull(entity.passengers)
        entity.properties[EntityProperties.HeadYaw] = 10f
        entity.passengers = mutableListOf()
        calls.clear()
        supplied.encode(entity, pairing)
        assertEquals(listOf("data"), calls)
    }

    @Test
    fun spawnDataUsesTheCurrentRegistryWhileMemoryKeepsCanonicalState() {
        val entity = entity(5).apply {
            properties[EntityProperties.HeadYaw] = 0f
            properties[carriedState] = sand
        }
        val pairing = EntityPairingData(41, emptyMap(), null, null)
        assertEquals(7, assertIs<ClientboundAddEntityPacket>(encoder.encode(entity, pairing).first()).data)

        val otherRegistry = PacketCodecContext(
            registry.registries.values.toList(),
            listOf(
                BlockStateIdMapping(91, Identifier(sand.blockId.value), sand.properties.toMap(), true),
                BlockStateIdMapping(92, Identifier(gravel.blockId.value), gravel.properties.toMap(), true),
            ),
        )
        val otherEncoder =
            EntityPacketEncoder(encoder.entityPacketEncoderContext.copy(packetCodecContext = otherRegistry))
        val spawn = assertIs<ClientboundAddEntityPacket>(otherEncoder.encode(entity, pairing).first())
        assertEquals(91, spawn.data)
        val decoder = EntityPacketDecoder(EntityPacketDecoderContext(otherRegistry, readMappings) { _, _ ->
            EntityPacketMissingData(DataProperties(), mutableListOf())
        })
        val decoded = decoder.decode(spawn).entity
        assertEquals(sand, decoded.properties.require(carriedState))
        assertEquals(setOf(EntityProperties.HeadYaw.name, carriedState.name), decoded.properties.entries.keys)
        assertEquals(7, assertIs<ClientboundAddEntityPacket>(encoder.encode(decoded, pairing).first()).data)

        decoded.properties[carriedState] = gravel
        assertEquals(8, assertIs<ClientboundAddEntityPacket>(encoder.encode(decoded, pairing).first()).data)
        assertEquals(92, assertIs<ClientboundAddEntityPacket>(otherEncoder.encode(decoded, pairing).first()).data)
        assertEquals(sand, entity.properties.require(carriedState))
    }

    @Test
    fun itemPatchRemovalAndEmptySlotsKeepTheirDistinctMeanings() {
        assertNull(itemDecoder.decode(itemEncoder.encode(null)))
        val item = ItemStack(ItemId("minecraft:stone"), 1)
        item.components.entries[damage] = ComponentPatchEntry.SetValue(PropertyValue(PropertyTypes.Int, 2))
        val decoded = assertNotNull(itemDecoder.decode(itemEncoder.encode(item)))
        assertEquals(item.components, decoded.components)
        assertFalse(ComponentId(DataComponentType.CUSTOM_NAME.wireName) in decoded.components.entries)
        val invalid =
            ItemStackPacketEncoder(ItemStackPacketEncoderContext(registry, ItemStackPacketWriteMappings { _, _, _ ->
                DataComponent.MaxDamage(2)
            }))
        assertFailsWith<IllegalArgumentException> { invalid.encode(item) }
    }

    private fun entity(value: Long): Entity = Entity(
        entityTypeId, Uuid.fromLongs(0, value), EntityVector3d(-12.5, 80.0, 4.25), EntityVector3d.ZERO,
        EntityRotation(45f, -10f), mutableListOf(),
    )

    private fun registry(name: String, vararg ids: String): RegistryIdMap = RegistryIdMap(
        Identifier(name), ids.mapIndexed { index, id -> RegistryIdMapping(Identifier(id), index) },
    )
}
