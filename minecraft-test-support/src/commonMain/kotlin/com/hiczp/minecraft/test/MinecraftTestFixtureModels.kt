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

data class HeadlessMinecraftClientConfiguration(
    val playerName: String,
    val endpoint: MinecraftTestEndpoint,
) {
    init {
        require(playerName.matches(Regex("[A-Za-z0-9_]{1,16}"))) {
            "Invalid offline player name: $playerName"
        }
        require(endpoint.host == LOOPBACK && endpoint.port in 1..0xFFFF) {
            "Headless client tests require a valid loopback endpoint"
        }
    }
}

private const val LOOPBACK = "127.0.0.1"
