package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.session.MinecraftServerPacketConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration

internal data class RecordingKeepAlive(
    val requestCreated: Deferred<Long>,
    val roundTrip: Deferred<Long>,
)

internal fun MinecraftServerPacketConnection.enableRecordingConfigurationKeepAlive(
    interval: Duration,
): RecordingKeepAlive = enableRecordingKeepAlive(
    extractChallenge = { packet -> (packet as? ConfigurationServerboundKeepAlivePacket)?.id },
    createRequest = ::ConfigurationClientboundKeepAlivePacket,
    interval = interval,
)

internal fun MinecraftServerPacketConnection.enableRecordingPlayKeepAlive(
    interval: Duration,
): RecordingKeepAlive = enableRecordingKeepAlive(
    extractChallenge = { packet -> (packet as? PlayServerboundKeepAlivePacket)?.id },
    createRequest = ::PlayClientboundKeepAlivePacket,
    interval = interval,
)

private fun MinecraftServerPacketConnection.enableRecordingKeepAlive(
    extractChallenge: (ServerboundPacket) -> Long?,
    createRequest: (Long) -> ClientboundPacket,
    interval: Duration,
): RecordingKeepAlive {
    val sentChallenge = MutableStateFlow<Long?>(null)
    val requestCreated = CompletableDeferred<Long>()
    val roundTrip = CompletableDeferred<Long>()
    enableKeepAlive(
        extractChallenge = { packet ->
            extractChallenge(packet)?.also { challenge ->
                if (challenge == sentChallenge.value) roundTrip.complete(challenge)
            }
        },
        createRequest = { challenge ->
            sentChallenge.value = challenge
            requestCreated.complete(challenge)
            createRequest(challenge)
        },
        interval = interval,
    )
    return RecordingKeepAlive(requestCreated, roundTrip)
}
