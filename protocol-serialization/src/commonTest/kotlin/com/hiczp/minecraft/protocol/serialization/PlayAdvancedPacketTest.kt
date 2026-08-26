package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class PlayAdvancedPacketTest {
    @Test
    fun `particle registry dispatch writes only the selected payload`() {
        assertBytes(
            ParticleOptions.Simple(ParticleType.SULFUR_BUBBLES),
            ParticleOptions.serializer(),
            "04",
        )
        assertBytes(
            ParticleOptions.Block(ParticleType.BLOCK, 300),
            ParticleOptions.serializer(),
            "01ac02",
        )
        assertBytes(
            ParticleOptions.Color(ParticleType.TINTED_LEAVES, 0x11223344),
            ParticleOptions.serializer(),
            "2b11223344",
        )
    }

    @Test
    fun `entity metadata uses serializer IDs and ff terminator`() {
        assertBytes(
            SetEntityMetadataPacket(
                entityId = 1,
                metadata = EntityMetadata(
                    listOf(
                        EntityMetadataEntry(
                            2,
                            EntityDataValue.IntValue(300),
                        ),
                    ),
                ),
            ),
            SetEntityMetadataPacket.serializer(),
            "010201ac02ff",
        )
        assertBytes(
            EntityMetadata(
                listOf(
                    EntityMetadataEntry(
                        0,
                        EntityDataValue.OptionalBlockState(null),
                    ),
                ),
            ),
            EntityMetadata.serializer(),
            "000f00ff",
        )
    }

    @Test
    fun `recipe displays recursively dispatch through built in type IDs`() {
        val placeGhostRecipePacket = PlaceGhostRecipePacket(
            containerId = 1,
            recipeDisplay = RecipeDisplay.Shapeless(
                ingredients = listOf(SlotDisplay.Empty),
                result = SlotDisplay.Item(2),
                craftingStation = SlotDisplay.AnyFuel,
            ),
        )
        assertBytes(
            placeGhostRecipePacket,
            PlaceGhostRecipePacket.serializer(),
            "01000100040201",
        )
    }

    @Test
    fun `player info action mask controls entry fields`() {
        val playerInfoUpdatePacket = PlayerInfoUpdatePacket(
            PlayerInfoUpdatePayload(
                actions = setOf(
                    PlayerInfoAction.ADD_PLAYER,
                    PlayerInfoAction.UPDATE_LATENCY,
                ),
                entries = listOf(
                    PlayerInfoEntry(
                        profileId = Uuid.fromLongs(0, 0),
                        profile = PlayerListProfile("a", emptyList()),
                        latency = 300,
                    ),
                ),
            ),
        )
        assertBytes(
            playerInfoUpdatePacket,
            PlayerInfoUpdatePacket.serializer(),
            "110100000000000000000000000000000000016100ac02",
        )
    }

    @Test
    fun `objective action owns all conditionally present fields`() {
        val setObjectivePacket = SetObjectivePacket(
            objectiveName = "x",
            update = ObjectiveUpdate.Add(
                displayName = TextComponent(NbtString("x")),
                renderType = ObjectiveRenderType.HEARTS,
                numberFormat = NumberFormat.Blank,
            ),
        )
        assertBytes(
            setObjectivePacket,
            SetObjectivePacket.serializer(),
            "01780008000178010100",
        )
    }

    @Test
    fun `filter masks and waypoint unions round trip`() {
        assertBytes(
            FilterMask.PartiallyFiltered(BitSet(longArrayOf(5))),
            FilterMask.serializer(),
            "02010000000000000005",
        )

        val waypointPacket = WaypointPacket(
            operation = WaypointOperation.UPDATE,
            waypoint = TrackedWaypoint.Chunk(
                identifier = WaypointIdentifier.Entity(Uuid.fromLongs(0, 0)),
                icon = WaypointIcon(Identifier("test"), 0x112233),
                x = -1,
                z = 300,
            ),
        )
        val encoded = MinecraftProtocolFormat.encodeToByteArray(
            waypointPacket,
        )
        assertEquals(
            waypointPacket,
            MinecraftProtocolFormat.decodeFromByteArray<WaypointPacket>(
                encoded,
            ),
        )
    }

    @Test
    fun `waypoint color has one optional marker around three rgb bytes`() {
        val style = Identifier("minecraft:test")
        val styleHex = "0e6d696e6563726166743a74657374"
        assertBytes(
            WaypointIcon(style),
            WaypointIcon.serializer(),
            "${styleHex}00",
        )
        assertBytes(
            WaypointIcon(style, 0x11_22_33),
            WaypointIcon.serializer(),
            "${styleHex}01112233",
        )
    }

    @Test
    fun `map color patch uses zero width as its only absence sentinel`() {
        assertBytes(
            MapDataPacket(
                mapId = 1,
                scale = 1,
                locked = false,
                decorations = null,
                colorPatch = null,
            ),
            MapDataPacket.serializer(),
            "0101000000",
        )
        assertBytes(
            MapDataPacket(
                mapId = 1,
                scale = 1,
                locked = false,
                decorations = emptyList(),
                colorPatch = MapColorPatch(
                    startX = 3,
                    startY = 4,
                    width = 2,
                    height = 1,
                    colors = ByteString(byteArrayOf(5, 6)),
                ),
            ),
            MapDataPacket.serializer(),
            "010100010002010304020506",
        )
    }

    private fun <T> assertBytes(
        value: T,
        kSerializer: KSerializer<T>,
        expectedHex: String,
    ) {
        val expected = expectedHex.hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftProtocolFormat.encodeToByteArray(kSerializer, value),
        )
        assertEquals(
            value,
            MinecraftProtocolFormat.decodeFromByteArray(kSerializer, expected),
        )
    }
}
