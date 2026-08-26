package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Enables official server KeepAlive while the connection is in Configuration. */
fun MinecraftServerPacketConnection.enableConfigurationKeepAlive(
    interval: Duration = MinecraftServerPacketConnection.DEFAULT_KEEP_ALIVE_INTERVAL,
) {
    enableKeepAlive(
        extractChallenge = { packet -> (packet as? ConfigurationServerboundKeepAlivePacket)?.id },
        createRequest = ::ConfigurationClientboundKeepAlivePacket,
        interval = interval,
    )
}

/** Enables official server KeepAlive while the connection is in Play. */
fun MinecraftServerPacketConnection.enablePlayKeepAlive(
    interval: Duration = MinecraftServerPacketConnection.DEFAULT_KEEP_ALIVE_INTERVAL,
) {
    enableKeepAlive(
        extractChallenge = { packet -> (packet as? PlayServerboundKeepAlivePacket)?.id },
        createRequest = ::PlayClientboundKeepAlivePacket,
        interval = interval,
    )
}

internal class MinecraftServerKeepAliveController(
    private val core: MinecraftPacketConnectionCore<ServerboundPacket, ClientboundPacket>,
) {
    private val state = MutableStateFlow<KeepAliveState>(KeepAliveState.Disabled)

    fun enable(
        extractChallenge: (ServerboundPacket) -> Long?,
        createRequest: (Long) -> ClientboundPacket,
        interval: Duration,
    ) {
        require(interval > Duration.ZERO) {
            "Minecraft KeepAlive interval must be positive"
        }
        core.ensureOpen()
        val run = KeepAliveRun(extractChallenge, createRequest, interval)
        run.timerJob = core.launchTask(start = CoroutineStart.LAZY) {
            runTimer(run)
        }

        var replacedRun: KeepAliveRun? = null
        while (true) {
            when (val current = state.value) {
                KeepAliveState.Disabled -> if (
                    state.compareAndSet(current, KeepAliveState.Active(run, pendingChallenge = null))
                ) {
                    break
                }

                is KeepAliveState.Active -> if (
                    state.compareAndSet(current, KeepAliveState.Active(run, pendingChallenge = null))
                ) {
                    replacedRun = current.run
                    break
                }

                is KeepAliveState.Terminating -> {
                    run.timerJob.cancel()
                    throw current.failure
                }
            }
        }
        replacedRun?.timerJob?.cancel()
        run.timerJob.start()
    }

    fun disable() {
        while (true) {
            when (val current = state.value) {
                KeepAliveState.Disabled,
                is KeepAliveState.Terminating,
                    -> return

                is KeepAliveState.Active -> if (
                    state.compareAndSet(current, KeepAliveState.Disabled)
                ) {
                    current.run.timerJob.cancel()
                    return
                }
            }
        }
    }

    fun handle(packet: ServerboundPacket): Boolean {
        while (true) {
            when (val current = state.value) {
                KeepAliveState.Disabled -> return false
                is KeepAliveState.Terminating -> throw current.failure
                is KeepAliveState.Active -> {
                    val challenge = current.run.extractChallenge(packet) ?: return false
                    val pendingChallenge = current.pendingChallenge
                    if (pendingChallenge == challenge) {
                        if (state.compareAndSet(current, current.copy(pendingChallenge = null))) return true
                        continue
                    }

                    val failure = if (pendingChallenge == null) {
                        MinecraftSessionException(
                            "Received a KeepAlive reply without a pending challenge: $challenge",
                        )
                    } else {
                        MinecraftSessionException(
                            "KeepAlive reply $challenge did not match pending challenge $pendingChallenge",
                        )
                    }
                    if (state.compareAndSet(current, KeepAliveState.Terminating(failure))) throw failure
                }
            }
        }
    }

    private suspend fun runTimer(run: KeepAliveRun) = coroutineScope {
        try {
            while (true) {
                delay(run.interval)
                if (!checkAndScheduleRequest(run)) return@coroutineScope
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            core.fail(cause)
        }
    }

    private fun CoroutineScope.checkAndScheduleRequest(run: KeepAliveRun): Boolean {
        while (true) {
            val current = state.value
            if (current !is KeepAliveState.Active || current.run !== run) return false
            val pendingChallenge = current.pendingChallenge
            if (pendingChallenge != null) {
                val failure = MinecraftSessionException(
                    "Minecraft KeepAlive timed out with pending challenge $pendingChallenge",
                )
                if (state.compareAndSet(current, KeepAliveState.Terminating(failure))) {
                    core.fail(failure)
                    return false
                }
                continue
            }

            val challenge = minecraftMonotonicTimeMillis()
            if (!state.compareAndSet(current, current.copy(pendingChallenge = challenge))) continue
            launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    core.sendConnectionOwned(run.createRequest(challenge))
                } catch (cause: CancellationException) {
                    throw cause
                } catch (cause: Throwable) {
                    core.fail(cause)
                }
            }
            return true
        }
    }

    private class KeepAliveRun(
        val extractChallenge: (ServerboundPacket) -> Long?,
        val createRequest: (Long) -> ClientboundPacket,
        val interval: Duration,
    ) {
        lateinit var timerJob: Job
    }

    private sealed interface KeepAliveState {
        data object Disabled : KeepAliveState

        data class Active(
            val run: KeepAliveRun,
            val pendingChallenge: Long?,
        ) : KeepAliveState

        data class Terminating(
            val failure: MinecraftSessionException,
        ) : KeepAliveState
    }
}

private val minecraftMonotonicOrigin = TimeSource.Monotonic.markNow()

private fun minecraftMonotonicTimeMillis(): Long = minecraftMonotonicOrigin.elapsedNow().inWholeMilliseconds
