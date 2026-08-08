package com.hiczp.minecraft.test.host

import com.hiczp.minecraft.test.HeadlessMinecraftClientConfiguration
import com.hiczp.minecraft.test.OfficialMinecraftServerConfiguration
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FixtureTemplateSelectionTest {
    @Test
    fun officialServerUsesTemplateOnlyForTheCompleteDefaultConfiguration() {
        assertTrue(OfficialMinecraftServerConfiguration().usesDefaultTemplate())
        assertFalse(
            OfficialMinecraftServerConfiguration(
                properties = mapOf("level-name" to "fresh"),
            ).usesDefaultTemplate(),
        )
        assertFalse(
            OfficialMinecraftServerConfiguration(
                startupTimeout = 119.seconds,
            ).usesDefaultTemplate(),
        )
        assertFalse(
            OfficialMinecraftServerConfiguration(
                stopTimeout = 29.seconds,
            ).usesDefaultTemplate(),
        )
        assertFalse(
            OfficialMinecraftServerConfiguration(
                maximumBindAttempts = 4,
            ).usesDefaultTemplate(),
        )
    }

    @Test
    fun headlessClientIgnoresRequiredIdentityWhenSelectingDefaultOptions() {
        assertTrue(
            HeadlessMinecraftClientConfiguration(
                playerName = "AnyPlayer",
            ).usesDefaultTemplate(),
        )
        assertFalse(
            HeadlessMinecraftClientConfiguration(
                playerName = "AnyPlayer",
                startupTimeout = 119.seconds,
            ).usesDefaultTemplate(),
        )
        assertFalse(
            HeadlessMinecraftClientConfiguration(
                playerName = "AnyPlayer",
                stopTimeout = 29.seconds,
            ).usesDefaultTemplate(),
        )
    }
}
