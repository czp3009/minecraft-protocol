package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.world.format.Entity
import kotlin.test.*
import kotlin.uuid.Uuid

class MinecraftWorldEntityProjectionTest {
    @Test
    fun decodesSpawnPacketIntoStrongEntity() {
        val pig = Identifier("pig")
        val protocolRegistryContext = ProtocolRegistryContext(
            registries = listOf(
                ProtocolRegistry(
                    ProtocolRegistryContext.ENTITY_TYPE_REGISTRY,
                    listOf(ProtocolRegistryEntry(pig, 7)),
                ),
            ),
            blockStates = emptyList(),
        )
        val minecraftEntityPacketDecoder = MinecraftEntityPacketDecoder(protocolRegistryContext)
        val uuid = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff")
        val spawnEntityPacket = SpawnEntityPacket(
            entityId = 42,
            entityUuid = uuid,
            typeId = 7,
            x = -12.5,
            y = 64.25,
            z = 8.75,
            velocity = Vector3d(0.125, -0.25, 0.5),
            pitch = Angle.fromDegrees(22.5f),
            yaw = Angle.fromDegrees(270.0f),
            headYaw = Angle.fromDegrees(180.0f),
            data = 3,
        )

        val entity = spawnEntityPacket.toEntity(minecraftEntityPacketDecoder)

        assertEquals("minecraft:pig", entity.type)
        assertEquals(uuid, entity.uuid)
        assertEquals(-12.5, entity.position.x)
        assertEquals(64.25, entity.position.y)
        assertEquals(8.75, entity.position.z)
        assertEquals(0.125, entity.velocity.x)
        assertEquals(-0.25, entity.velocity.y)
        assertEquals(0.5, entity.velocity.z)
        assertEquals(spawnEntityPacket.yaw.degrees, entity.rotation.yaw)
        assertEquals(spawnEntityPacket.pitch.degrees, entity.rotation.pitch)
        assertEquals(0, entity.data.size)
        assertTrue(entity.passengers.isEmpty())

        val runtimeData = TestRuntimeEntityData(health = 10)
        val bundle = ClientboundBundlePacket(
            listOf(
                spawnEntityPacket,
                LinkEntitiesPacket(attachedEntityId = 42, holdingEntityId = 5),
                SetPassengersPacket(vehicleEntityId = 5, passengerEntityIds = listOf(42)),
            ),
        )
        val runtimeEntity = bundle.toEntity(minecraftEntityPacketDecoder, runtimeData)
        assertSame(runtimeData, runtimeEntity.data)
        assertTrue(bundle.isEntityPairingBundle)
        assertSame(spawnEntityPacket, bundle.spawnEntityPacket())
        assertSame(spawnEntityPacket, bundle.spawnEntityPacketOrNull())

        val nonEntityBundle = ClientboundBundlePacket(listOf(ChunkBatchStartPacket, spawnEntityPacket))
        assertFalse(nonEntityBundle.isEntityPairingBundle)
        assertNull(nonEntityBundle.spawnEntityPacketOrNull())
        assertNull(nonEntityBundle.toEntityOrNull(minecraftEntityPacketDecoder))
        assertNull(nonEntityBundle.toEntitiesOrNull(minecraftEntityPacketDecoder))
        assertFailsWith<IllegalArgumentException> {
            nonEntityBundle.toEntity(minecraftEntityPacketDecoder)
        }
        assertFailsWith<IllegalArgumentException> {
            nonEntityBundle.toEntities(minecraftEntityPacketDecoder)
        }
    }

    @Test
    fun adaptsPairingPacketsByTypeWithoutRequiringTailOrder() {
        val pig = Identifier("pig")
        val protocolRegistryContext = ProtocolRegistryContext(
            registries = listOf(
                ProtocolRegistry(
                    ProtocolRegistryContext.ENTITY_TYPE_REGISTRY,
                    listOf(ProtocolRegistryEntry(pig, 7)),
                ),
            ),
            blockStates = emptyList(),
        )
        val minecraftEntityPacketDecoder = MinecraftEntityPacketDecoder(protocolRegistryContext)
        val spawnEntityPacket = SpawnEntityPacket(
            entityId = 42,
            entityUuid = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff"),
            typeId = 7,
            x = 1.0,
            y = 2.0,
            z = 3.0,
            velocity = Vector3d(0.0, 0.0, 0.0),
            pitch = Angle(0),
            yaw = Angle(0),
            headYaw = Angle.fromDegrees(90.0f),
            data = 12,
        )
        val bundle = ClientboundBundlePacket(
            listOf(
                spawnEntityPacket,
                LinkEntitiesPacket(attachedEntityId = 42, holdingEntityId = 5),
                SetEquipmentPacket(
                    entityId = 42,
                    updates = EquipmentUpdates(
                        listOf(EquipmentUpdate(EquipmentSlot.HEAD, ItemStack.EMPTY)),
                    ),
                ),
                SetEntityMetadataPacket(entityId = 42, metadata = EntityMetadata(emptyList())),
                SetPassengersPacket(vehicleEntityId = 5, passengerEntityIds = listOf(42)),
                UpdateAttributesPacket(entityId = 42, attributes = emptyList()),
            ),
        )
        val entitiesById = mutableMapOf<Int, Entity<TestAdaptedEntityData>>()
        val adapter = object : MinecraftEntityPacketAdapter<TestAdaptedEntityData> {
            override fun createData(packet: SpawnEntityPacket, type: Identifier): TestAdaptedEntityData =
                TestAdaptedEntityData(type, packet.data, packet.headYaw.degrees)

            override fun registerEntity(
                packet: SpawnEntityPacket,
                entity: Entity<TestAdaptedEntityData>,
            ) {
                entitiesById[packet.entityId] = entity
                entity.data.appliedPackets += "registered"
            }

            override fun applyMetadata(
                entity: Entity<TestAdaptedEntityData>,
                packet: SetEntityMetadataPacket,
            ) {
                assertSame(entitiesById[packet.entityId], entity)
                entity.data.appliedPackets += "metadata"
            }

            override fun applyAttributes(
                entity: Entity<TestAdaptedEntityData>,
                packet: UpdateAttributesPacket,
            ) {
                assertSame(entitiesById[packet.entityId], entity)
                entity.data.appliedPackets += "attributes"
            }

            override fun applyEquipment(
                entity: Entity<TestAdaptedEntityData>,
                packet: SetEquipmentPacket,
            ) {
                assertSame(entitiesById[packet.entityId], entity)
                entity.data.appliedPackets += "equipment"
            }

            override fun applyPassengers(
                entity: Entity<TestAdaptedEntityData>,
                packet: SetPassengersPacket,
            ) {
                assertSame(entitiesById[spawnEntityPacket.entityId], entity)
                assertEquals(listOf(42), packet.passengerEntityIds)
                entity.data.appliedPackets += "passengers"
            }

            override fun applyLink(
                entity: Entity<TestAdaptedEntityData>,
                packet: LinkEntitiesPacket,
            ) {
                assertSame(entitiesById[packet.attachedEntityId], entity)
                entity.data.appliedPackets += "link"
            }
        }

        val entity = bundle.toEntity(minecraftEntityPacketDecoder, adapter)

        assertSame(entity, entitiesById[spawnEntityPacket.entityId])
        assertEquals(pig, entity.data.type)
        assertEquals(12, entity.data.spawnData)
        assertEquals(spawnEntityPacket.headYaw.degrees, entity.data.headYaw)
        assertEquals(
            listOf("registered", "link", "equipment", "metadata", "passengers", "attributes"),
            entity.data.appliedPackets,
        )
    }

    @Test
    fun decodesSeveralConsecutiveEntityPairingsFromOneBundleOrRawPacketList() {
        val pig = Identifier("pig")
        val cow = Identifier("cow")
        val protocolRegistryContext = ProtocolRegistryContext(
            registries = listOf(
                ProtocolRegistry(
                    ProtocolRegistryContext.ENTITY_TYPE_REGISTRY,
                    listOf(
                        ProtocolRegistryEntry(pig, 7),
                        ProtocolRegistryEntry(cow, 8),
                    ),
                ),
            ),
            blockStates = emptyList(),
        )
        val minecraftEntityPacketDecoder = MinecraftEntityPacketDecoder(protocolRegistryContext)
        val pigSpawnPacket = SpawnEntityPacket(
            entityId = 42,
            entityUuid = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff"),
            typeId = 7,
            x = 1.0,
            y = 2.0,
            z = 3.0,
            velocity = Vector3d(0.0, 0.0, 0.0),
            pitch = Angle(0),
            yaw = Angle(0),
            headYaw = Angle(0),
            data = 1,
        )
        val cowSpawnPacket = SpawnEntityPacket(
            entityId = 43,
            entityUuid = Uuid.parse("11223344-5566-7788-99aa-bbccddeeff00"),
            typeId = 8,
            x = 4.0,
            y = 5.0,
            z = 6.0,
            velocity = Vector3d(0.0, 0.0, 0.0),
            pitch = Angle(0),
            yaw = Angle(0),
            headYaw = Angle(0),
            data = 2,
        )
        val bundle = ClientboundBundlePacket(
            listOf(
                pigSpawnPacket,
                SetEntityMetadataPacket(entityId = 42, metadata = EntityMetadata(emptyList())),
                cowSpawnPacket,
                SetEquipmentPacket(
                    entityId = 43,
                    updates = EquipmentUpdates(
                        listOf(EquipmentUpdate(EquipmentSlot.HEAD, ItemStack.EMPTY)),
                    ),
                ),
                SetEntityMetadataPacket(entityId = 43, metadata = EntityMetadata(emptyList())),
            ),
        )
        val entitiesById = mutableMapOf<Int, Entity<TestAdaptedEntityData>>()
        val adapter = object : MinecraftEntityPacketAdapter<TestAdaptedEntityData> {
            override fun createData(packet: SpawnEntityPacket, type: Identifier): TestAdaptedEntityData =
                TestAdaptedEntityData(type, packet.data, packet.headYaw.degrees)

            override fun registerEntity(
                packet: SpawnEntityPacket,
                entity: Entity<TestAdaptedEntityData>,
            ) {
                entitiesById[packet.entityId] = entity
                entity.data.appliedPackets += "registered"
            }

            override fun applyMetadata(
                entity: Entity<TestAdaptedEntityData>,
                packet: SetEntityMetadataPacket,
            ) {
                assertSame(entitiesById[packet.entityId], entity)
                entity.data.appliedPackets += "metadata"
            }

            override fun applyEquipment(
                entity: Entity<TestAdaptedEntityData>,
                packet: SetEquipmentPacket,
            ) {
                assertSame(entitiesById[packet.entityId], entity)
                entity.data.appliedPackets += "equipment"
            }
        }

        val entities = bundle.toEntities(minecraftEntityPacketDecoder, adapter)

        assertTrue(bundle.isEntityPairingBundle)
        assertEquals(listOf(pigSpawnPacket, cowSpawnPacket), bundle.spawnEntityPackets().toList())
        assertEquals(listOf(pig, cow), entities.map { entity -> entity.data.type })
        assertEquals(listOf("registered", "metadata"), entities[0].data.appliedPackets)
        assertEquals(listOf("registered", "equipment", "metadata"), entities[1].data.appliedPackets)
        assertNull(bundle.toEntityOrNull(minecraftEntityPacketDecoder))
        assertFailsWith<IllegalArgumentException> {
            bundle.toEntity(minecraftEntityPacketDecoder)
        }

        val rawEntities = bundle.subPackets.toEntities(minecraftEntityPacketDecoder)
        assertEquals(listOf("minecraft:pig", "minecraft:cow"), rawEntities.map { entity -> entity.type })
    }

    @Test
    fun rejectsUnknownEntityTypeId() {
        val protocolRegistryContext = ProtocolRegistryContext(
            registries = listOf(
                ProtocolRegistry(
                    ProtocolRegistryContext.ENTITY_TYPE_REGISTRY,
                    listOf(ProtocolRegistryEntry(Identifier("pig"), 0)),
                ),
            ),
            blockStates = emptyList(),
        )
        val minecraftEntityPacketDecoder = MinecraftEntityPacketDecoder(protocolRegistryContext)
        val spawnEntityPacket = SpawnEntityPacket(
            entityId = 1,
            entityUuid = Uuid.parse("00112233-4455-6677-8899-aabbccddeeff"),
            typeId = 1,
            x = 0.0,
            y = 0.0,
            z = 0.0,
            velocity = Vector3d(0.0, 0.0, 0.0),
            pitch = Angle(0),
            yaw = Angle(0),
            headYaw = Angle(0),
            data = 0,
        )

        assertFailsWith<IllegalArgumentException> {
            minecraftEntityPacketDecoder.decode(spawnEntityPacket)
        }
    }

    private data class TestRuntimeEntityData(val health: Int)

    private data class TestAdaptedEntityData(
        val type: Identifier,
        val spawnData: Int,
        val headYaw: Float,
        val appliedPackets: MutableList<String> = mutableListOf(),
    )
}
