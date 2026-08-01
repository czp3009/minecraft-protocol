package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayInventoryPacketTest {
    @Test
    fun `container content slot cursor and player inventory use item stacks`() {
        assertPacketBytes(
            SetContainerContentPacket(
                containerId = 300,
                stateId = 1,
                items = listOf(ItemStack.Empty, ItemStack.of(1)),
                carriedItem = ItemStack.Empty,
            ),
            SetContainerContentPacket.serializer(),
            "ac020102000101000000",
        )
        assertPacketBytes(
            SetContainerSlotPacket(
                containerId = 1,
                stateId = 2,
                slot = -1,
                item = ItemStack.Empty,
            ),
            SetContainerSlotPacket.serializer(),
            "0102ffff00",
        )
        assertPacketBytes(
            SetCursorItemPacket(ItemStack.of(2)),
            SetCursorItemPacket.serializer(),
            "01020000",
        )
        assertPacketBytes(
            SetPlayerInventorySlotPacket(300, ItemStack.Empty),
            SetPlayerInventorySlotPacket.serializer(),
            "ac0200",
        )
    }

    @Test
    fun `equipment entries use the high bit continuation marker`() {
        assertPacketBytes(
            SetEquipmentPacket(
                entityId = 1,
                updates = EquipmentUpdates(
                    listOf(
                        EquipmentUpdate(
                            EquipmentSlot.MAINHAND,
                            ItemStack.Empty,
                        ),
                        EquipmentUpdate(
                            EquipmentSlot.HEAD,
                            ItemStack.of(1),
                        ),
                    ),
                ),
            ),
            SetEquipmentPacket.serializer(),
            "0180000501010000",
        )
        assertFailsWith<IllegalArgumentException> {
            EquipmentUpdates(emptyList())
        }
        assertFailsWith<SerializationException> {
            MinecraftFormat.decodeFromByteArray(
                SetEquipmentPacket.serializer(),
                "017f00".hexToByteArray(),
            )
        }
    }

    @Test
    fun `container click uses hashed stacks introduced by 26_2`() {
        assertPacketBytes(
            ClickContainerPacket(
                containerId = 1,
                stateId = 2,
                slot = -1,
                button = 3,
                input = ContainerInput.SWAP,
                changedSlots = listOf(
                    ChangedHashedSlot(
                        slot = 300,
                        value = HashedStack.Present(
                            itemRegistryId = 1,
                            count = 2,
                            components = HashedComponentPatch(
                                added = listOf(
                                    HashedComponent(
                                        typeId = 2,
                                        hash = 0x11223344,
                                    ),
                                ),
                                removedTypeIds = setOf(3),
                            ),
                        ),
                    ),
                ),
                carriedItem = HashedStack.Empty,
            ),
            ClickContainerPacket.serializer(),
            "0102ffff030201012c010102010211223344010300",
        )
    }

    @Test
    fun `creative slot delimits every untrusted component value`() {
        val packet = SetCreativeModeSlotPacket(
            slot = 1,
            item = ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(DataComponent.MaxDamage(300)),
                ),
            ),
        )
        assertPacketBytes(
            packet,
            SetCreativeModeSlotPacket.serializer(),
            "0001010101000202ac02",
        )
        assertFailsWith<SerializationException> {
            MinecraftFormat.decodeFromByteArray(
                SetCreativeModeSlotPacket.serializer(),
                "000164010000".hexToByteArray(),
            )
        }
    }

    private fun <T> assertPacketBytes(
        packet: T,
        serializer: KSerializer<T>,
        expectedHex: String,
    ) {
        val expected = expectedHex.hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftFormat.encodeToByteArray(serializer, packet),
        )
        assertEquals(
            packet,
            MinecraftFormat.decodeFromByteArray(serializer, expected),
        )
    }
}
