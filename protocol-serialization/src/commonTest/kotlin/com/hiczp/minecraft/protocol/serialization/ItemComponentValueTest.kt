package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ItemComponentValueTest {
    @Test
    fun `pot sides each carry a Boolean optional item stack template`() {
        assertBytes(
            DataComponent.PotDecorations(back = ItemStackTemplate(1, 2), right = ItemStackTemplate(3, 4)),
            DataComponent.PotDecorations.serializer(),
            "010102000000010304000000",
        )
    }

    @Test
    fun `resolvable numbers distinguish fixed-width literals from identifiers`() {
        assertBytes(ResolvableInt.Constant(-2), ResolvableInt.serializer(), "01fffffffe")
        assertBytes(ResolvableFloat.Constant(0.5f), ResolvableFloat.serializer(), "013f000000")
        assertBytes(ResolvableInt.Reference(Identifier("test:x")), ResolvableInt.serializer(), "0006746573743a78")
        assertBytes(ResolvableFloat.Reference(Identifier("test:y")), ResolvableFloat.serializer(), "0006746573743a79")
        assertBytes(
            DataComponent.CookingFuel(ResolvableInt.Constant(300), ResolvableFloat.Reference(Identifier("test:y"))),
            DataComponent.CookingFuel.serializer(),
            "010000012c0006746573743a79",
        )
    }

    @Test
    fun `sign text has four unprefixed lines and optional filtered lines`() {
        val messages = List(4) { TextComponent.literal("x") }
        assertBytes(
            SignText(messages, color = DyeColor.BLACK, hasGlowingText = true),
            SignText.serializer(),
            "08000178080001780800017808000178000f01"
        )
        assertBytes(
            SignText(messages, List(4) { TextComponent.literal("y") }, DyeColor.RED),
            SignText.serializer(),
            "0800017808000178080001780800017801080001790800017908000179080001790e00",
        )
        assertFails { SignText(emptyList()) }
        assertFails { SignText(messages, emptyList()) }
    }

    @Test
    fun `teleport consume effect carries its directional particle flag`() {
        assertBytes(ConsumeEffect.TeleportRandomly(16.0f, false), ConsumeEffect.serializer(), "034180000000")
    }

    private fun <T> assertBytes(value: T, serializer: KSerializer<T>, hex: String) {
        val bytes = hex.hexToByteArray()
        assertContentEquals(bytes, MinecraftPacketPayloadFormat.encodeToByteArray(serializer, value))
        assertEquals(value, MinecraftPacketPayloadFormat.decodeFromByteArray(serializer, bytes))
    }
}
