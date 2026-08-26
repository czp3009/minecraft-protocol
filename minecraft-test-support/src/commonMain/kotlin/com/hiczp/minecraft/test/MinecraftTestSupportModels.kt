package com.hiczp.minecraft.test

import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Serializable
data class MinecraftTestEndpoint(
    val host: String,
    val port: Int,
)

@Serializable
data class OfficialMinecraftServerConfiguration(
    val properties: Map<String, String> = emptyMap(),
    val startupTimeout: Duration = 2.minutes,
    val stopTimeout: Duration = 30.seconds,
    val maximumBindAttempts: Int = 5,
) {
    init {
        require(startupTimeout.isPositive() && startupTimeout.isFinite()) {
            "startupTimeout must be positive and finite"
        }
        require(stopTimeout.isPositive() && stopTimeout.isFinite()) {
            "stopTimeout must be positive and finite"
        }
        require(maximumBindAttempts > 0) {
            "maximumBindAttempts must be positive"
        }
    }
}

@Serializable
data class HeadlessMinecraftClientConfiguration(
    val playerName: String,
    val startupTimeout: Duration = 2.minutes,
    val stopTimeout: Duration = 30.seconds,
) {
    init {
        require(playerName.matches(Regex("[A-Za-z0-9_]{1,16}"))) {
            "Invalid offline player name: $playerName"
        }
        require(startupTimeout.isPositive() && startupTimeout.isFinite()) {
            "startupTimeout must be positive and finite"
        }
        require(stopTimeout.isPositive() && stopTimeout.isFinite()) {
            "stopTimeout must be positive and finite"
        }
    }
}

@Serializable
sealed interface MinecraftTestResource {
    val id: String
}

/** Serializable reference to an official server owned by the Fixture Host. */
@Serializable
data class OfficialMinecraftServer(
    override val id: String,
    val minecraftTestEndpoint: MinecraftTestEndpoint,
) : MinecraftTestResource

/** Serializable reference to a headless client owned by the Fixture Host. */
@Serializable
data class HeadlessMinecraftClient(
    override val id: String,
) : MinecraftTestResource

/**
 * The GUI state reported by a correlated HMC-Specifics `gui` command.
 *
 * A null [screenClassName] means that Minecraft is not displaying a screen.
 * This is control/liveness evidence only; protocol packets remain the oracle
 * for Login, Configuration, and Play state.
 */
@Serializable
data class HeadlessMinecraftClientState(
    val screenClassName: String?,
)

@Serializable
data class MinecraftTestResourceStatus(
    val alive: Boolean,
    val exitCode: Int? = null,
)
