package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.DebugBlockValuePacket
import com.hiczp.minecraft.protocol.model.packet.DebugChunkValuePacket
import com.hiczp.minecraft.protocol.model.packet.DebugEntityValuePacket
import com.hiczp.minecraft.protocol.model.packet.DebugEventPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.SerializationException
import kotlin.test.*

class DebugSubscriptionPacketTest {
    @Test
    fun `every official debug subscription value has its exact wire shape`() {
        val node = DebugPathNode(
            x = 1,
            y = -1,
            z = 2,
            walkedDistance = 1.0f,
            costMalus = -0.5f,
            closed = true,
            type = DebugPathType.BIG_MOBS_CLOSE_TO_DANGER,
            totalCost = 2.0f,
        )
        val nodeHex = (
                "00000001ffffffff00000002" +
                        "3f800000bf00000001" +
                        "1a" +
                        "40000000"
                )
        val zeroBox = DebugBoundingBox(ZERO_POSITION, ZERO_POSITION)
        val cases = listOf(
            DebugSubscriptionData.Bee(
                hivePosition = null,
                flowerPosition = ZERO_POSITION,
                travelTicks = 2,
                blacklistedHives = listOf(ZERO_POSITION),
            ) to (
                    "01" +
                            "00" +
                            "01$ZERO_POSITION_HEX" +
                            "02" +
                            "01$ZERO_POSITION_HEX"
                    ),
            DebugSubscriptionData.VillagerBrain(
                name = "n",
                profession = "p",
                experience = 1,
                health = 1.0f,
                maximumHealth = 2.0f,
                inventory = "i",
                wantsGolem = true,
                angerLevel = -1,
                activities = listOf("a"),
                behaviors = emptyList(),
                memories = emptyList(),
                gossips = emptyList(),
                pointsOfInterest = setOf(ZERO_POSITION),
                potentialPointsOfInterest = emptySet(),
            ) to (
                    "02" +
                            "016e0170" +
                            "00000001" +
                            "3f80000040000000" +
                            "0169" +
                            "01" +
                            "ffffffff" +
                            "010161" +
                            "000000" +
                            "01$ZERO_POSITION_HEX" +
                            "00"
                    ),
            DebugSubscriptionData.Breeze(
                attackTargetEntityId = 300,
                jumpTarget = null,
            ) to "0301ac0200",
            DebugSubscriptionData.GoalSelector(
                listOf(DebugGoal(priority = 2, running = true, name = "x")),
            ) to "040102010178",
            DebugSubscriptionData.EntityPath(
                reached = true,
                nextNodeIndex = 2,
                target = ZERO_POSITION,
                nodes = listOf(node),
                targetNodes = setOf(node),
                openSet = emptyList(),
                closedSet = emptyList(),
                maximumNodeDistance = 1.0f,
            ) to (
                    "05" +
                            "01" +
                            "00000002" +
                            ZERO_POSITION_HEX +
                            "01$nodeHex" +
                            "01$nodeHex" +
                            "0000" +
                            "3f800000"
                    ),
            DebugSubscriptionData.EntityBlockIntersection(
                DebugEntityBlockIntersection.IN_AIR,
            ) to "0602",
            DebugSubscriptionData.BeeHive(
                blockTypeId = 300,
                occupantCount = 2,
                honeyLevel = 3,
                sedated = true,
            ) to "07ac02020301",
            DebugSubscriptionData.PointOfInterest(
                position = ZERO_POSITION,
                pointOfInterestTypeId = 130,
                freeTicketCount = 1,
            ) to "08${ZERO_POSITION_HEX}820101",
            DebugSubscriptionData.RedstoneWireOrientation(47) to "092f",
            DebugSubscriptionData.VillageSection to "0a",
            DebugSubscriptionData.Raid(listOf(ZERO_POSITION)) to
                    "0b01$ZERO_POSITION_HEX",
            DebugSubscriptionData.Structures(
                listOf(
                    DebugStructure(
                        boundingBox = zeroBox,
                        pieces = listOf(DebugStructurePiece(zeroBox, start = true)),
                    ),
                ),
            ) to (
                    "0c01" +
                            ZERO_POSITION_HEX + ZERO_POSITION_HEX +
                            "01" +
                            ZERO_POSITION_HEX + ZERO_POSITION_HEX +
                            "01"
                    ),
            DebugSubscriptionData.GameEventListener(300) to "0dac02",
            DebugSubscriptionData.NeighborUpdate(ZERO_POSITION) to
                    "0e$ZERO_POSITION_HEX",
            DebugSubscriptionData.GameEvent(
                eventTypeId = 2,
                position = Vector3d(1.0, -2.0, 3.0),
            ) to (
                    "0f02" +
                            "3ff0000000000000" +
                            "c000000000000000" +
                            "4008000000000000"
                    ),
        )

        for ((data, expectedHex) in cases) {
            val event = DebugSubscriptionEvent(data)
            val expected = expectedHex.hexBytes()
            assertContentEquals(
                expected,
                MinecraftFormat.encodeToByteArray(
                    DebugSubscriptionEvent.serializer(),
                    event,
                ),
                data.toString(),
            )
            assertEquals(
                event,
                MinecraftFormat.decodeFromByteArray(
                    DebugSubscriptionEvent.serializer(),
                    expected,
                ),
                data.toString(),
            )
        }
    }

    @Test
    fun `debug packet wrappers preserve vanilla field ordering`() {
        val block = DebugBlockValuePacket(
            ZERO_POSITION,
            DebugSubscriptionUpdate(
                DebugSubscriptionType.VILLAGE_SECTION,
                DebugSubscriptionData.VillageSection,
            ),
        )
        assertPacketBytes(
            block,
            DebugBlockValuePacket.serializer(),
            "${ZERO_POSITION_HEX}0a01",
        )

        val chunk = DebugChunkValuePacket(
            chunkZ = 2,
            chunkX = 1,
            update = DebugSubscriptionUpdate(
                DebugSubscriptionType.RAID,
                null,
            ),
        )
        assertPacketBytes(
            chunk,
            DebugChunkValuePacket.serializer(),
            "00000002000000010b00",
        )

        val entity = DebugEntityValuePacket(
            entityId = 300,
            update = DebugSubscriptionUpdate(
                DebugSubscriptionType.NEIGHBOR_UPDATE,
                DebugSubscriptionData.NeighborUpdate(ZERO_POSITION),
            ),
        )
        assertPacketBytes(
            entity,
            DebugEntityValuePacket.serializer(),
            "ac020e01$ZERO_POSITION_HEX",
        )

        val event = DebugEventPacket(
            DebugSubscriptionEvent(
                DebugSubscriptionData.GameEventListener(2),
            ),
        )
        assertPacketBytes(event, DebugEventPacket.serializer(), "0d02")
    }

    @Test
    fun `official-only debug details stay distinct from stale Wiki tables`() {
        val goalSelector = DebugSubscriptionEvent(
            DebugSubscriptionData.GoalSelector(
                listOf(DebugGoal(2, true, "x")),
            ),
        )
        // The 0x01 immediately after type 0x04 is the list length omitted by
        // the current Wiki table but present in DebugGoalInfo.STREAM_CODEC.
        assertContentEquals(
            "040102010178".hexBytes(),
            MinecraftFormat.encodeToByteArray(
                DebugSubscriptionEvent.serializer(),
                goalSelector,
            ),
        )

        val latestPathType = DebugSubscriptionEvent(
            DebugSubscriptionData.EntityPath(
                reached = true,
                nextNodeIndex = 0,
                target = ZERO_POSITION,
                nodes = emptyList(),
                targetNodes = setOf(
                    DebugPathNode(
                        0,
                        0,
                        0,
                        0.0f,
                        0.0f,
                        false,
                        DebugPathType.BIG_MOBS_CLOSE_TO_DANGER,
                        0.0f,
                    ),
                ),
                openSet = emptyList(),
                closedSet = emptyList(),
                maximumNodeDistance = 0.0f,
            ),
        )
        val encoded = MinecraftFormat.encodeToByteArray(
            DebugSubscriptionEvent.serializer(),
            latestPathType,
        )
        // The official PathType has one entry beyond the pinned Wiki table.
        assertEquals(0x1A, encoded[37].toInt() and 0xFF)
    }

    @Test
    fun `debug enum failure policies match their official codecs`() {
        val intersection = MinecraftFormat.decodeFromByteArray(
            DebugSubscriptionEvent.serializer(),
            "067f".hexBytes(),
        )
        assertEquals(
            DebugSubscriptionEvent(
                DebugSubscriptionData.EntityBlockIntersection(
                    DebugEntityBlockIntersection.IN_BLOCK,
                ),
            ),
            intersection,
        )
        assertContentEquals(
            "0600".hexBytes(),
            MinecraftFormat.encodeToByteArray(
                DebugSubscriptionEvent.serializer(),
                intersection,
            ),
        )

        assertFails {
            MinecraftFormat.decodeFromByteArray(
                DebugSubscriptionEvent.serializer(),
                "0930".hexBytes(),
            )
        }
        assertFails {
            MinecraftFormat.decodeFromByteArray(
                DebugSubscriptionEvent.serializer(),
                (
                        "050100000000" +
                                ZERO_POSITION_HEX +
                                "00" +
                                "01" +
                                "000000000000000000000000" +
                                "000000000000000000" +
                                "1b" +
                                "00000000" +
                                "0000" +
                                "00000000"
                        ).hexBytes(),
            )
        }
    }

    @Test
    fun `debug dispatch rejects impossible vanilla states`() {
        assertFailsWith<SerializationException> {
            MinecraftFormat.decodeFromByteArray(
                DebugSubscriptionUpdate.serializer(),
                // Vanilla's type 0 subscription has a null valueStreamCodec,
                // so even an absent optional cannot be dispatched.
                "0000".hexBytes(),
            )
        }

        assertFailsWith<SerializationException> {
            MinecraftFormat.encodeToByteArray(
                DebugSubscriptionUpdate.serializer(),
                DebugSubscriptionUpdate(
                    DebugSubscriptionType.BEE,
                    DebugSubscriptionData.VillageSection,
                ),
            )
        }

        assertFailsWith<SerializationException> {
            MinecraftFormat.decodeFromByteArray(
                DebugSubscriptionEvent.serializer(),
                "10".hexBytes(),
            )
        }

    }

    private fun <T> assertPacketBytes(
        packet: T,
        serializer: kotlinx.serialization.KSerializer<T>,
        expectedHex: String,
    ) {
        val expected = expectedHex.hexBytes()
        assertContentEquals(
            expected,
            MinecraftFormat.encodeToByteArray(serializer, packet),
        )
        assertEquals(
            packet,
            MinecraftFormat.decodeFromByteArray(serializer, expected),
        )
    }

    private companion object {
        const val ZERO_POSITION_HEX: String = "0000000000000000"
        val ZERO_POSITION: BlockPosition = BlockPosition(0, 0, 0)
    }
}

private fun String.hexBytes(): ByteArray {
    require(length % 2 == 0)
    return ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
