package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PlayInventoryPacketTest {
    @Test
    fun `container content slot cursor and player inventory use item stacks`() {
        assertPacketBytes(
            ClientboundContainerSetContentPacket(
                containerId = 300,
                stateId = 1,
                items = listOf(ItemStack.Empty, ItemStack.of(1)),
                carriedItem = ItemStack.Empty,
            ),
            ClientboundContainerSetContentPacket.serializer(),
            "ac020102000101000000",
        )
        assertPacketBytes(
            ClientboundContainerSetSlotPacket(
                containerId = 1,
                stateId = 2,
                slot = -1,
                itemStack = ItemStack.Empty,
            ),
            ClientboundContainerSetSlotPacket.serializer(),
            "0102ffff00",
        )
        assertPacketBytes(
            ClientboundSetCursorItemPacket(ItemStack.of(2)),
            ClientboundSetCursorItemPacket.serializer(),
            "01020000",
        )
        assertPacketBytes(
            ClientboundSetPlayerInventoryPacket(300, ItemStack.Empty),
            ClientboundSetPlayerInventoryPacket.serializer(),
            "ac0200",
        )
    }

    @Test
    fun `equipment entries use the high bit continuation marker`() {
        assertPacketBytes(
            ClientboundSetEquipmentPacket(
                entity = 1,
                slots = EquipmentUpdates(
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
            ClientboundSetEquipmentPacket.serializer(),
            "0180000501010000",
        )
        assertFailsWith<SerializationException> {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ClientboundSetEquipmentPacket.serializer(),
                ClientboundSetEquipmentPacket(
                    entity = 1,
                    slots = EquipmentUpdates(emptyList()),
                ),
            )
        }
        assertFailsWith<SerializationException> {
            MinecraftPacketPayloadFormat.decodeFromByteArray<ClientboundSetEquipmentPacket>(
                "017f00".hexToByteArray(),
            )
        }
    }

    @Test
    fun `container click uses hashed stacks introduced by 26_2`() {
        assertPacketBytes(
            ServerboundContainerClickPacket(
                containerId = 1,
                stateId = 2,
                slotNum = -1,
                buttonNum = 3,
                containerInput = ContainerInput.SWAP,
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
            ServerboundContainerClickPacket.serializer(),
            "0102ffff030201012c010102010211223344010300",
        )
    }

    @Test
    fun `creative slot delimits every untrusted component value`() {
        val serverboundSetCreativeModeSlotPacket = ServerboundSetCreativeModeSlotPacket(
            slotNum = 1,
            itemStack = ItemStack.of(
                itemId = 1,
                components = DataComponentPatch(
                    added = listOf(DataComponent.MaxDamage(300)),
                ),
            ),
        )
        assertPacketBytes(
            serverboundSetCreativeModeSlotPacket,
            ServerboundSetCreativeModeSlotPacket.serializer(),
            "0001010101000202ac02",
        )
        assertFailsWith<SerializationException> {
            MinecraftPacketPayloadFormat.decodeFromByteArray<ServerboundSetCreativeModeSlotPacket>(
                "000164010000".hexToByteArray(),
            )
        }
    }

    private fun <T> assertPacketBytes(
        packet: T,
        kSerializer: KSerializer<T>,
        expectedHex: String,
    ) {
        val expected = expectedHex.hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftPacketPayloadFormat.encodeToByteArray(kSerializer, packet),
        )
        assertEquals(
            packet,
            MinecraftPacketPayloadFormat.decodeFromByteArray(kSerializer, expected),
        )
    }
}
