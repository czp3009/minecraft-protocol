package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.CustomPayload
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.serialization.MinecraftPacketRegistry
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import com.hiczp.minecraft.protocol.serialization.PacketRegistry
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

enum class MinecraftSessionSide {
    CLIENT,
    SERVER,
}

/** A custom-payload body paired with its validated vanilla outer route. */
data class RoutedCustomPayload(
    val route: PacketRoute.CustomPayload,
    val data: ByteString,
)

/**
 * Low-level, sequential packet session. High-level connections place exactly
 * one reader pump and one writer pump around this object.
 */
class MinecraftSession(
    val frames: MinecraftFrameStream,
    val side: MinecraftSessionSide,
    val packetRegistry: PacketRegistry = MinecraftPacketRegistry.compose(emptyList()),
    format: MinecraftProtocolFormat = MinecraftProtocolFormat.Default,
) {
    private val stateValue = MutableStateFlow(ConnectionState.HANDSHAKE)
    private val formatValue = MutableStateFlow(format)
    private val registriesValue = MutableStateFlow(format.configuration.registries)
    private val activeRoutesValue = MutableStateFlow(emptySet<PacketRouteKey>())
    private val contextMutex = Mutex()
    private val queryMutex = Mutex()
    private val loginQueries = mutableMapOf<Int, Identifier>()
    private val encryptionMutex = Mutex()
    private var outboundEncryption: ByteArray? = null
    private var encryptionEnabled: Boolean = false
    private val inboundEncryptionActivation = CompletableDeferred<Unit>()
    private val initialPlayContext = CompletableDeferred<Unit>()

    val state: ConnectionState
        get() = stateValue.value

    val format: MinecraftProtocolFormat
        get() = formatValue.value

    val registries: ProtocolRegistryContext
        get() = registriesValue.value

    val declaredExtensionRoutes: Set<PacketRouteKey>
        get() = packetRegistry.declaredExtensionRoutes

    val activeExtensionRoutes: Set<PacketRouteKey>
        get() = activeRoutesValue.value

    val outboundDirection: PacketDirection
        get() =
            if (side == MinecraftSessionSide.CLIENT) {
                PacketDirection.SERVERBOUND
            } else {
                PacketDirection.CLIENTBOUND
            }

    val inboundDirection: PacketDirection
        get() =
            if (side == MinecraftSessionSide.CLIENT) {
                PacketDirection.CLIENTBOUND
            } else {
                PacketDirection.SERVERBOUND
            }

    suspend fun installRegistryContext(context: ProtocolRegistryContext) {
        contextMutex.withLock {
            registriesValue.value = context
            val current = formatValue.value
            formatValue.value = MinecraftProtocolFormat(
                configuration = current.configuration.copy(registries = context),
                serializersModule = current.serializersModule,
            )
            if (context.chunkSectionCount != null) {
                initialPlayContext.complete(Unit)
            }
        }
    }

    /** Atomically replaces the active subset of routes declared at construction. */
    suspend fun activateExtensionRoutes(routes: Set<PacketRouteKey>) {
        val snapshot = routes.toSet()
        val undeclared = snapshot - declaredExtensionRoutes
        require(undeclared.isEmpty()) {
            "Cannot activate undeclared extension routes: $undeclared"
        }
        contextMutex.withLock {
            activeRoutesValue.value = snapshot
        }
    }

    suspend fun awaitState(expected: ConnectionState) {
        stateValue.first { it == expected }
    }

    /**
     * Arms the next outbound Encryption Response. The writer enables the
     * continuous stream cipher only after that packet has been fully flushed.
     */
    suspend fun prepareOutboundEncryption(sharedSecret: ByteArray) {
        require(sharedSecret.isNotEmpty()) { "Shared secret must not be empty" }
        encryptionMutex.withLock {
            check(!encryptionEnabled) { "Stream encryption is already enabled" }
            check(outboundEncryption == null) {
                "Outbound stream encryption is already pending"
            }
            outboundEncryption = sharedSecret.copyOf()
        }
    }

    /** Enables encryption after a fully received Encryption Response. */
    suspend fun enableEncryption(sharedSecret: ByteArray) {
        require(sharedSecret.isNotEmpty()) { "Shared secret must not be empty" }
        encryptionMutex.withLock {
            check(!encryptionEnabled) { "Stream encryption is already enabled" }
            frames.enableEncryption(sharedSecret)
            encryptionEnabled = true
            inboundEncryptionActivation.complete(Unit)
        }
    }

    /** Used by a server reader pump to stop before the first encrypted frame. */
    suspend fun awaitInboundEncryptionActivation() {
        inboundEncryptionActivation.await()
    }

    suspend fun awaitInitialPlayContext() {
        initialPlayContext.await()
    }

    suspend fun send(packet: Packet) {
        when (packet) {
            is UnknownPacket -> sendUnknown(packet)
            is ClientboundPacket.Extension,
            is ServerboundPacket.Extension,
                -> sendExtension(packet)

            else -> sendKnown(packet)
        }
    }

    suspend fun receive(): Packet {
        val legacyAware =
            state == ConnectionState.HANDSHAKE &&
                    inboundDirection == PacketDirection.SERVERBOUND
        val packetData = Buffer()
        if (legacyAware) {
            frames.receivePacketDataOrLegacyToSink(
                packetData,
                legacyPacketId = LEGACY_SERVER_LIST_PING_ID,
                legacyPayloadSize = LEGACY_SERVER_LIST_PING_PAYLOAD_SIZE,
            )
        } else {
            frames.receivePacketDataToSink(packetData)
        }
        val legacy =
            legacyAware &&
                    packetData.peek().readByte().toInt().and(0xFF) ==
                    LEGACY_SERVER_LIST_PING_ID
        val id = if (legacy) {
            packetData.readByte().toInt() and 0xFF
        } else {
            packetData.readPacketId()
        }
        val codec = packetRegistry.codec(
            state = state,
            direction = inboundDirection,
            id = id,
        )
        val expectedFraming = if (legacy) PacketFraming.LEGACY_UNFRAMED else PacketFraming.NORMAL
        if (codec != null && codec.framing != expectedFraming) {
            throw MinecraftSessionException(
                "Packet 0x${id.toString(16)} used $expectedFraming framing but its codec requires ${codec.framing}",
            )
        }
        if (
            codec == null ||
            codec.extensionRoute?.let { it !in activeRoutesValue.value } == true
        ) {
            return unknownPacket(
                PacketRoute.TopLevel(state, inboundDirection, id),
                ByteString(packetData.readByteArray()),
            )
        }
        val packet = packetRegistry.decodePayloadFromSource(
            state = state,
            direction = inboundDirection,
            id = id,
            source = packetData,
            byteCount = packetData.size.toInt(),
            format = formatValue.value,
        )
        applyPostWireEffects(packet)
        return liftIncoming(packet, id)
    }

    fun encodeCustomPayload(packet: Packet): RoutedCustomPayload {
        val registration = packetRegistry.registration(
            packet,
            state,
            outboundDirection,
        ) ?: throw MinecraftSessionException(
            "No extension codec for ${packet::class.simpleName}",
        )
        val routeKey = registration.route as? PacketRouteKey.CustomPayload
            ?: throw MinecraftSessionException(
                "${packet::class.simpleName} is not a custom-payload extension in $state $outboundDirection",
            )
        if (routeKey !in activeRoutesValue.value) {
            throw MinecraftSessionException("Extension route $routeKey is not active")
        }
        val route = packetRegistry.extensionRoute(
            packet,
            state,
            outboundDirection,
            customPayloadPacketId(routeKey),
        ) as PacketRoute.CustomPayload
        validateRoute(route, outboundDirection)
        val body = Buffer()
        packetRegistry.encodeExtensionPayloadToSink(
            packet,
            state,
            outboundDirection,
            body,
            formatValue.value,
        )
        return RoutedCustomPayload(
            route,
            ByteString(body.readByteArray()),
        )
    }

    fun decodeCustomPayload(payload: RoutedCustomPayload): Packet {
        val route = payload.route
        validateRoute(route, inboundDirection)
        val expectedPacketId = customPayloadPacketId(
            route.key as PacketRouteKey.CustomPayload,
        )
        if (route.packetId != expectedPacketId) {
            throw MinecraftSessionException(
                "Custom payload route preserves outer ID ${route.packetId}, but the active registry uses $expectedPacketId",
            )
        }
        return liftRoute(route, payload.data)
    }

    private suspend fun sendUnknown(packet: UnknownPacket) {
        validateRoute(packet.route, outboundDirection)
        when (val route = packet.route) {
            is PacketRoute.TopLevel -> {
                sendRawTopLevel(route.packetId, packet.data)
            }

            is PacketRoute.CustomPayload,
            is PacketRoute.LoginQuery,
                -> {
                beforeRoutedSend(route)
                val wirePacket = routedWirePacket(route, packet.data)
                sendKnown(wirePacket)
            }
        }
    }

    private suspend fun sendExtension(packet: Packet) {
        val registration = packetRegistry.registration(
            packet,
            state,
            outboundDirection,
        )
            ?: throw MinecraftSessionException(
                "No extension codec for ${packet::class.simpleName}",
            )
        val declaredRoute = registration.route
        if (declaredRoute !in activeRoutesValue.value) {
            throw MinecraftSessionException(
                "Extension route $declaredRoute is not active",
            )
        }
        if (declaredRoute is PacketRouteKey.CustomPayload) {
            val payload = encodeCustomPayload(packet)
            sendKnown(routedWirePacket(payload.route, payload.data))
            return
        }
        val outerPacketId: Int? = null
        val route = packetRegistry.extensionRoute(
            packet,
            state,
            outboundDirection,
            outerPacketId,
        )
        validateRoute(route, outboundDirection)
        if (route is PacketRoute.TopLevel) {
            sendKnown(packet)
            return
        }

        val body = Buffer()
        packetRegistry.encodeExtensionPayloadToSink(
            packet,
            state,
            outboundDirection,
            body,
            formatValue.value,
        )
        val data = ByteString(body.readByteArray())
        beforeRoutedSend(route)
        sendKnown(routedWirePacket(route, data))
    }

    private suspend fun sendKnown(packet: Packet) {
        val codec = packetRegistry.codec(packet, state, outboundDirection)
            ?: throw MinecraftSessionException(
                "No packet codec for ${packet::class.simpleName}",
            )
        if (codec.key.state != state) {
            throw MinecraftSessionException(
                "${packet::class.simpleName} belongs to ${codec.key.state}, but the session is in $state",
            )
        }
        if (codec.key.direction != outboundDirection) {
            throw MinecraftSessionException(
                "${packet::class.simpleName} is ${codec.key.direction}, but $side sends $outboundDirection packets",
            )
        }
        if (
            codec.extensionRoute != null &&
            codec.extensionRoute !in activeRoutesValue.value
        ) {
            throw MinecraftSessionException(
                "Extension route ${codec.extensionRoute} is not active",
            )
        }
        val encryption = takeOutboundEncryption(packet)
        var recordedRequest: LoginPluginRequestPacket? = null
        try {
            val packetData = Buffer()
            when (codec.framing) {
                PacketFraming.NORMAL -> packetData.writeVarInt(codec.key.id)
                PacketFraming.LEGACY_UNFRAMED ->
                    packetData.writeByte(codec.key.id.toByte())
            }
            packetRegistry.encodePayloadToSink(
                packet,
                state,
                outboundDirection,
                packetData,
                formatValue.value,
            )
            when (packet) {
                is LoginPluginRequestPacket -> {
                    recordLoginQuery(
                        PacketRoute.LoginQuery(
                            direction = PacketDirection.CLIENTBOUND,
                            transactionId = packet.messageId,
                            channel = packet.channel,
                        ),
                    )
                    recordedRequest = packet
                }

                is LoginPluginResponsePacket ->
                    consumeLoginQuery(packet.messageId)

                else -> Unit
            }
            val packetDataBytes = packetData.size.toInt()
            when (codec.framing) {
                PacketFraming.NORMAL -> {
                    if (requiresWireCommit(packet, encryption)) {
                        frames.sendPacketDataAndCommit(
                            packetData,
                            packetDataBytes,
                        ) {
                            commitOutboundEffects(packet, encryption)
                        }
                    } else {
                        frames.sendPacketData(packetData, packetDataBytes)
                    }
                }

                PacketFraming.LEGACY_UNFRAMED -> {
                    check(!requiresWireCommit(packet, encryption)) {
                        "Legacy unframed packets cannot commit wire effects"
                    }
                    frames.sendUnframedPacketData(packetData, packetDataBytes)
                }
            }
        } catch (cause: Throwable) {
            withContext(NonCancellable) {
                recordedRequest?.let { request ->
                    discardLoginQuery(request.messageId, request.channel)
                }
            }
            throw cause
        } finally {
            encryption?.fill(0)
        }
    }

    private suspend fun sendRawTopLevel(
        packetId: Int,
        data: ByteString,
    ) {
        val packetData = Buffer()
        packetData.writeVarInt(packetId)
        packetData.write(data.toByteArray())
        frames.sendPacketData(packetData, packetData.size.toInt())
    }

    private suspend fun liftIncoming(
        packet: Packet,
        packetId: Int,
    ): Packet = when (packet) {
        is LoginPluginRequestPacket -> {
            val route = PacketRoute.LoginQuery(
                PacketDirection.CLIENTBOUND,
                packet.messageId,
                packet.channel,
            )
            recordLoginQuery(route)
            liftRoute(route, packet.data)
        }

        is LoginPluginResponsePacket -> {
            val channel = consumeLoginQuery(packet.messageId)
            val route = PacketRoute.LoginQuery(
                PacketDirection.SERVERBOUND,
                packet.messageId,
                channel,
                hasPayload = packet.data != null,
            )
            liftRoute(route, packet.data ?: ByteString(byteArrayOf()))
        }

        is ConfigurationClientboundPluginMessagePacket ->
            liftCustomPayload(packet.payload, packetId, ConnectionState.CONFIGURATION, PacketDirection.CLIENTBOUND)

        is ConfigurationServerboundPluginMessagePacket ->
            liftCustomPayload(packet.payload, packetId, ConnectionState.CONFIGURATION, PacketDirection.SERVERBOUND)

        is PlayClientboundPluginMessagePacket ->
            liftCustomPayload(packet.payload, packetId, ConnectionState.PLAY, PacketDirection.CLIENTBOUND)

        is PlayServerboundPluginMessagePacket ->
            liftCustomPayload(packet.payload, packetId, ConnectionState.PLAY, PacketDirection.SERVERBOUND)

        else -> packet
    }

    private fun liftCustomPayload(
        payload: CustomPayload,
        packetId: Int,
        state: ConnectionState,
        direction: PacketDirection,
    ): Packet = when (payload) {
        is CustomPayload.Brand -> when {
            state == ConnectionState.CONFIGURATION &&
                    direction == PacketDirection.CLIENTBOUND ->
                ConfigurationClientboundPluginMessagePacket(payload)

            state == ConnectionState.CONFIGURATION ->
                ConfigurationServerboundPluginMessagePacket(payload)

            direction == PacketDirection.CLIENTBOUND ->
                PlayClientboundPluginMessagePacket(payload)

            else -> PlayServerboundPluginMessagePacket(payload)
        }

        is CustomPayload.Unknown -> liftRoute(
            PacketRoute.CustomPayload(
                state,
                direction,
                packetId,
                payload.channel,
            ),
            payload.data,
        )
    }

    private fun liftRoute(
        route: PacketRoute,
        data: ByteString,
    ): Packet {
        val registration = packetRegistry.registration(route.key)
        if (
            registration == null ||
            route.key !in activeRoutesValue.value
        ) {
            return unknownPacket(route, data)
        }
        val source = Buffer().apply { write(data.toByteArray()) }
        return packetRegistry.decodeExtensionPayloadFromSource(
            route,
            source,
            data.size,
            formatValue.value,
        )
    }

    private fun routedWirePacket(
        route: PacketRoute,
        data: ByteString,
    ): Packet = when (route) {
        is PacketRoute.TopLevel -> error("Top-level routes do not have an outer packet")
        is PacketRoute.LoginQuery -> when (route.direction) {
            PacketDirection.CLIENTBOUND -> {
                require(route.hasPayload) {
                    "A Login query request always has a payload body"
                }
                LoginPluginRequestPacket(
                    route.transactionId,
                    route.channel,
                    data,
                )
            }

            PacketDirection.SERVERBOUND -> LoginPluginResponsePacket(
                route.transactionId,
                data.takeIf { route.hasPayload },
            )
        }

        is PacketRoute.CustomPayload -> {
            val packet = customPayloadPacket(
                route.state,
                route.direction,
                CustomPayload.Unknown(route.channel, data),
            )
            val actualId = packetRegistry.codec(
                packet,
                route.state,
                route.direction,
            )?.key?.id
                ?: throw MinecraftSessionException(
                    "No vanilla outer custom-payload codec for ${route.state} ${route.direction}",
                )
            if (actualId != route.packetId) {
                throw MinecraftSessionException(
                    "Custom payload route preserves outer ID ${route.packetId}, but the active registry uses $actualId",
                )
            }
            packet
        }
    }

    private fun customPayloadPacketId(
        route: PacketRouteKey.CustomPayload,
    ): Int {
        val packet = customPayloadPacket(
            route.state,
            route.direction,
            CustomPayload.Unknown(route.channel, ByteString(byteArrayOf())),
        )
        return packetRegistry.codec(
            packet,
            route.state,
            route.direction,
        )?.key?.id
            ?: throw MinecraftSessionException(
                "No vanilla outer custom-payload codec for ${route.state} ${route.direction}",
            )
    }

    private fun customPayloadPacket(
        state: ConnectionState,
        direction: PacketDirection,
        payload: CustomPayload,
    ): Packet = when {
        state == ConnectionState.CONFIGURATION &&
                direction == PacketDirection.CLIENTBOUND ->
            ConfigurationClientboundPluginMessagePacket(payload)

        state == ConnectionState.CONFIGURATION &&
                direction == PacketDirection.SERVERBOUND ->
            ConfigurationServerboundPluginMessagePacket(payload)

        state == ConnectionState.PLAY &&
                direction == PacketDirection.CLIENTBOUND ->
            PlayClientboundPluginMessagePacket(payload)

        state == ConnectionState.PLAY &&
                direction == PacketDirection.SERVERBOUND ->
            PlayServerboundPluginMessagePacket(payload)

        else -> throw MinecraftSessionException(
            "Custom payloads are not valid in $state",
        )
    }

    private suspend fun beforeRoutedSend(route: PacketRoute) {
        if (
            route is PacketRoute.LoginQuery &&
            route.direction == PacketDirection.SERVERBOUND
        ) {
            queryMutex.withLock {
                val channel = loginQueries[route.transactionId]
                    ?: throw MinecraftSessionException(
                        "Login query response ${route.transactionId} has no pending request",
                    )
                if (channel != route.channel) {
                    throw MinecraftSessionException(
                        "Login query response ${route.transactionId} uses ${route.channel}, but the request used $channel",
                    )
                }
            }
        }
    }

    private suspend fun recordLoginQuery(route: PacketRoute.LoginQuery) {
        queryMutex.withLock {
            val previous = loginQueries.put(route.transactionId, route.channel)
            if (previous != null) {
                loginQueries[route.transactionId] = previous
                throw MinecraftSessionException(
                    "Duplicate Login query transaction ${route.transactionId}",
                )
            }
        }
    }

    private suspend fun consumeLoginQuery(transactionId: Int): Identifier =
        queryMutex.withLock {
            loginQueries.remove(transactionId)
                ?: throw MinecraftSessionException(
                    "Login query response $transactionId has no pending request",
                )
        }

    private suspend fun discardLoginQuery(
        transactionId: Int,
        channel: Identifier,
    ) {
        queryMutex.withLock {
            if (loginQueries[transactionId] == channel) {
                loginQueries.remove(transactionId)
            }
        }
    }

    private suspend fun takeOutboundEncryption(packet: Packet): ByteArray? =
        encryptionMutex.withLock {
            val pending = outboundEncryption
            if (pending != null && packet !is EncryptionResponsePacket) {
                throw MinecraftSessionException(
                    "Encryption Response must be the next outbound packet after encryption is prepared",
                )
            }
            if (packet is EncryptionResponsePacket) {
                outboundEncryption = null
                pending
            } else {
                null
            }
        }

    private fun validateRoute(
        route: PacketRoute,
        expectedDirection: PacketDirection,
    ) {
        if (route.state != state) {
            throw MinecraftSessionException(
                "Route ${route.key} belongs to ${route.state}, but the session is in $state",
            )
        }
        if (route.direction != expectedDirection) {
            throw MinecraftSessionException(
                "Route ${route.key} is ${route.direction}, but $side sends $expectedDirection packets",
            )
        }
    }

    private fun unknownPacket(
        route: PacketRoute,
        data: ByteString,
    ): UnknownPacket = when (route.direction) {
        PacketDirection.CLIENTBOUND -> UnknownPacket.Clientbound(route, data)
        PacketDirection.SERVERBOUND -> UnknownPacket.Serverbound(route, data)
    }

    private fun applyPostWireEffects(packet: Packet) {
        when (packet) {
            is SetCompressionPacket ->
                frames.configureCompression(packet.threshold.takeIf { it >= 0 })

            is HandshakePacket ->
                stateValue.value = when (packet.nextState) {
                    HandshakeNextState.STATUS -> ConnectionState.STATUS
                    HandshakeNextState.LOGIN,
                    HandshakeNextState.TRANSFER,
                        -> ConnectionState.LOGIN

                    HandshakeNextState.UNUSED ->
                        throw MinecraftSessionException(
                            "Handshake next state zero is invalid",
                        )
                }

            is LoginAcknowledgedPacket ->
                stateValue.value = ConnectionState.CONFIGURATION

            is AcknowledgeFinishConfigurationPacket ->
                stateValue.value = ConnectionState.PLAY

            is AcknowledgeConfigurationPacket ->
                stateValue.value = ConnectionState.CONFIGURATION

            else -> Unit
        }
    }

    private fun requiresWireCommit(
        packet: Packet,
        encryption: ByteArray?,
    ): Boolean = encryption != null ||
            packet is LoginPluginRequestPacket ||
            packet is LoginPluginResponsePacket ||
            packet is SetCompressionPacket ||
            packet is HandshakePacket ||
            packet is LoginAcknowledgedPacket ||
            packet is AcknowledgeFinishConfigurationPacket ||
            packet is AcknowledgeConfigurationPacket

    private suspend fun commitOutboundEffects(
        packet: Packet,
        encryption: ByteArray?,
    ) {
        applyPostWireEffects(packet)
        if (encryption == null) return
        encryptionMutex.withLock {
            check(!encryptionEnabled) {
                "Stream encryption is already enabled"
            }
            frames.enableEncryption(encryption)
            encryptionEnabled = true
            inboundEncryptionActivation.complete(Unit)
        }
    }

    companion object {
        private const val LEGACY_SERVER_LIST_PING_ID = 0xFE
        private const val LEGACY_SERVER_LIST_PING_PAYLOAD_SIZE = 1
    }
}

/** Invalid packet direction, state, identity, or session transition. */
class MinecraftSessionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

private fun Buffer.readPacketId(): Int {
    var result = 0
    var shift = 0
    repeat(5) {
        if (exhausted()) {
            throw MinecraftSessionException("Truncated packet ID VarInt")
        }
        val current = readByte().toInt() and 0xFF
        result = result or ((current and 0x7F) shl shift)
        if (current and 0x80 == 0) return result
        shift += 7
    }
    throw MinecraftSessionException("Packet ID VarInt is too wide")
}

private fun Buffer.writeVarInt(value: Int) {
    var remaining = value
    do {
        var current = remaining and 0x7F
        remaining = remaining ushr 7
        if (remaining != 0) current = current or 0x80
        writeByte(current.toByte())
    } while (remaining != 0)
}
