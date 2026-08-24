package com.hiczp.minecraft.protocol.session

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.CustomPayload
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.ProtocolRegistryContext
import com.hiczp.minecraft.protocol.serialization.MinecraftProtocolFormat
import com.hiczp.minecraft.protocol.serialization.PacketRegistry
import com.hiczp.minecraft.protocol.transport.MinecraftFrameStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/** A custom-payload body paired with its validated vanilla outer route. */
data class RoutedCustomPayload(
    val route: PacketRoute.CustomPayload,
    val data: ByteString,
)

/**
 * Low-level, sequential packet session shared by the two direction-bound
 * endpoint sessions.
 */
sealed class MinecraftPacketSession<Incoming : Packet, Outgoing : Packet> protected constructor(
    internal val frameStream: MinecraftFrameStream,
    protected val inboundDirection: PacketDirection,
    protected val outboundDirection: PacketDirection,
    private val packetRegistry: PacketRegistry,
    format: MinecraftProtocolFormat,
) {
    private val stateValue = MutableStateFlow(ConnectionState.HANDSHAKE)
    private val formatValue = MutableStateFlow(format)
    private val activeRoutesValue = MutableStateFlow(emptySet<PacketRouteKey>())
    private val loginQueryMutex = Mutex()
    private val loginQueries = mutableMapOf<Int, Identifier>()

    val state: ConnectionState
        get() = stateValue.value

    internal val inboundState: ConnectionState
        get() = stateValue.value

    val format: MinecraftProtocolFormat
        get() = formatValue.value

    val registries: ProtocolRegistryContext
        get() = formatValue.value.configuration.registries

    val declaredExtensionRoutes: Set<PacketRouteKey>
        get() = packetRegistry.declaredExtensionRoutes

    val activeExtensionRoutes: Set<PacketRouteKey>
        get() = activeRoutesValue.value

    fun installRegistryContext(context: ProtocolRegistryContext) {
        val current = formatValue.value
        formatValue.value = MinecraftProtocolFormat(
            configuration = current.configuration.copy(registries = context),
            serializersModule = current.serializersModule,
        )
    }

    /** Atomically replaces the active subset of routes declared at construction. */
    fun activateExtensionRoutes(routes: Set<PacketRouteKey>) {
        val snapshot = routes.toSet()
        val undeclared = snapshot - declaredExtensionRoutes
        require(undeclared.isEmpty()) {
            "Cannot activate undeclared extension routes: $undeclared"
        }
        activeRoutesValue.value = snapshot
    }

    suspend fun awaitState(expected: ConnectionState) {
        stateValue.first { it == expected }
    }

    suspend fun send(packet: Outgoing) {
        when (packet) {
            is UnknownPacket -> sendUnknown(packet)
            is ClientboundPacket.Extension,
            is ServerboundPacket.Extension,
                -> sendExtension(packet)

            else -> sendKnown(packet)
        }
    }

    suspend fun receive(): Incoming {
        val legacyAware =
            stateValue.value == ConnectionState.HANDSHAKE &&
                    inboundDirection == PacketDirection.SERVERBOUND
        val packetData = Buffer()
        if (legacyAware) {
            frameStream.receivePacketDataOrLegacyToSink(
                packetData,
                legacyPacketId = LEGACY_SERVER_LIST_PING_ID,
                legacyPayloadSize = LEGACY_SERVER_LIST_PING_PAYLOAD_SIZE,
            )
        } else {
            frameStream.receivePacketDataToSink(packetData)
        }
        val inboundState = stateValue.value
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
            state = inboundState,
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
            return requireIncoming(
                unknownPacket(
                    PacketRoute.TopLevel(inboundState, inboundDirection, id),
                    ByteString(packetData.readByteArray()),
                ),
            )
        }
        val packet = packetRegistry.decodePayloadFromSource(
            state = inboundState,
            direction = inboundDirection,
            id = id,
            source = packetData,
            byteCount = packetData.size.toInt(),
            format = formatValue.value,
        )
        val incoming = requireIncoming(liftIncoming(packet, id))
        applyInboundEffects(packet)
        return incoming
    }

    fun encodeCustomPayload(packet: Outgoing): RoutedCustomPayload {
        val outboundState = stateValue.value
        val registration = packetRegistry.registration(
            packet,
            outboundState,
            outboundDirection,
        ) ?: throw MinecraftSessionException(
            "No extension codec for ${packet::class.simpleName}",
        )
        val routeKey = registration.route as? PacketRouteKey.CustomPayload
            ?: throw MinecraftSessionException(
                "${packet::class.simpleName} is not a custom-payload extension in $outboundState $outboundDirection",
            )
        if (routeKey !in activeRoutesValue.value) {
            throw MinecraftSessionException("Extension route $routeKey is not active")
        }
        val route = packetRegistry.extensionRoute(
            packet,
            outboundState,
            outboundDirection,
            customPayloadPacketId(routeKey),
        ) as PacketRoute.CustomPayload
        validateRoute(route, outboundDirection)
        val body = Buffer()
        packetRegistry.encodeExtensionPayloadToSink(
            packet,
            outboundState,
            outboundDirection,
            body,
            formatValue.value,
        )
        return RoutedCustomPayload(
            route,
            ByteString(body.readByteArray()),
        )
    }

    fun decodeCustomPayload(payload: RoutedCustomPayload): Incoming {
        val route = payload.route
        validateRoute(route, inboundDirection)
        val expectedPacketId = customPayloadPacketId(
            route.key as PacketRouteKey.CustomPayload,
        )
        if (route.packetId != expectedPacketId) {
            val preservedRoute = "Custom payload route preserves outer ID ${route.packetId}"
            throw MinecraftSessionException(
                "$preservedRoute, but the active registry uses $expectedPacketId",
            )
        }
        return requireIncoming(liftRoute(route, payload.data))
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
                val wirePacket = routedWirePacket(route, packet.data)
                sendKnown(wirePacket)
            }
        }
    }

    private suspend fun sendExtension(packet: Outgoing) {
        val outboundState = stateValue.value
        val registration = packetRegistry.registration(
            packet,
            outboundState,
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
            outboundState,
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
            outboundState,
            outboundDirection,
            body,
            formatValue.value,
        )
        val data = ByteString(body.readByteArray())
        sendKnown(routedWirePacket(route, data))
    }

    private suspend fun sendKnown(packet: Packet) {
        val outboundState = stateValue.value
        val codec = packetRegistry.codec(packet, outboundState, outboundDirection)
            ?: throw MinecraftSessionException(
                "No packet codec for ${packet::class.simpleName}",
            )
        val nextState = transitionState(packet)
        val encryption = outboundEncryptionFor(packet)
        val packetData = Buffer()
        when (codec.framing) {
            PacketFraming.NORMAL -> packetData.writeVarInt(codec.key.id)
            PacketFraming.LEGACY_UNFRAMED -> packetData.writeByte(codec.key.id.toByte())
        }
        try {
            packetRegistry.encodePayloadToSink(
                packet,
                outboundState,
                outboundDirection,
                packetData,
                formatValue.value,
            )
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Exception) {
            if (packet is SkippableClientboundPacket) {
                throw SkippablePacketEncodingException(cause)
            }
            throw cause
        }
        val packetDataBytes = packetData.size.toInt()
        when (codec.framing) {
            PacketFraming.NORMAL -> {
                if (requiresWireCommit(packet, nextState, encryption)) {
                    frameStream.sendPacketDataAndCommit(packetData, packetDataBytes) {
                        commitOutboundEffects(packet, nextState, encryption)
                    }
                } else {
                    frameStream.sendPacketData(packetData, packetDataBytes)
                }
            }

            PacketFraming.LEGACY_UNFRAMED -> {
                check(!requiresWireCommit(packet, nextState, encryption)) {
                    "Legacy unframed packets cannot commit wire effects"
                }
                frameStream.sendUnframedPacketData(packetData, packetDataBytes)
            }
        }
    }

    private suspend fun sendRawTopLevel(
        packetId: Int,
        data: ByteString,
    ) {
        val packetData = Buffer()
        packetData.writeVarInt(packetId)
        packetData.write(data.toByteArray())
        frameStream.sendPacketData(packetData, packetData.size.toInt())
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
            val channel = consumeLoginQuery(packet.messageId) ?: return packet
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

    private suspend fun recordLoginQuery(route: PacketRoute.LoginQuery) {
        loginQueryMutex.withLock {
            loginQueries[route.transactionId] = route.channel
        }
    }

    private suspend fun consumeLoginQuery(transactionId: Int): Identifier? =
        loginQueryMutex.withLock {
            loginQueries.remove(transactionId)
        }

    private fun validateRoute(
        route: PacketRoute,
        expectedDirection: PacketDirection,
    ) {
        val expectedState = stateValue.value
        if (route.state != expectedState) {
            throw MinecraftSessionException(
                "Route ${route.key} belongs to ${route.state}, but the $expectedDirection session is in $expectedState",
            )
        }
        if (route.direction != expectedDirection) {
            throw MinecraftSessionException(
                "Route ${route.key} is ${route.direction}, but this session sends $expectedDirection packets",
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

    private fun applyInboundEffects(packet: Packet) {
        if (packet is SetCompressionPacket) {
            frameStream.configureCompression(packet.threshold)
        }
        transitionState(packet)?.let { nextState ->
            stateValue.value = nextState
        }
    }

    private fun requiresWireCommit(
        packet: Packet,
        nextState: ConnectionState?,
        encryption: ByteArray?,
    ): Boolean = encryption != null ||
            packet is LoginPluginRequestPacket ||
            packet is LoginPluginResponsePacket ||
            packet is SetCompressionPacket ||
            nextState != null

    private suspend fun commitOutboundEffects(
        packet: Packet,
        nextState: ConnectionState?,
        encryption: ByteArray?,
    ) {
        when (packet) {
            is LoginPluginRequestPacket -> recordLoginQuery(
                PacketRoute.LoginQuery(
                    direction = PacketDirection.CLIENTBOUND,
                    transactionId = packet.messageId,
                    channel = packet.channel,
                ),
            )

            is LoginPluginResponsePacket -> consumeLoginQuery(packet.messageId)
            else -> Unit
        }
        if (packet is SetCompressionPacket) {
            frameStream.configureCompression(packet.threshold)
        }
        if (nextState != null) {
            stateValue.value = nextState
        }
        if (encryption != null) {
            try {
                frameStream.enableEncryption(encryption)
            } finally {
                try {
                    outboundEncryptionCommitted(encryption)
                } finally {
                    encryption.fill(0)
                }
            }
        }
    }

    internal fun clearSensitiveState() {
        clearEndpointSensitiveState()
    }

    private fun transitionState(packet: Packet): ConnectionState? = when (packet) {
        is HandshakePacket -> when (packet.nextState) {
            HandshakeNextState.STATUS -> ConnectionState.STATUS
            HandshakeNextState.LOGIN,
            HandshakeNextState.TRANSFER,
                -> ConnectionState.LOGIN

            HandshakeNextState.UNUSED ->
                throw MinecraftSessionException(
                    "Handshake next state zero is invalid",
                )
        }

        is LoginAcknowledgedPacket -> ConnectionState.CONFIGURATION
        is AcknowledgeFinishConfigurationPacket -> ConnectionState.PLAY
        is AcknowledgeConfigurationPacket -> ConnectionState.CONFIGURATION
        else -> null
    }

    protected abstract fun requireIncoming(packet: Packet): Incoming

    protected open fun outboundEncryptionFor(packet: Packet): ByteArray? = null

    protected open fun outboundEncryptionCommitted(sharedSecret: ByteArray) = Unit

    protected open fun clearEndpointSensitiveState() = Unit

    companion object {
        private const val LEGACY_SERVER_LIST_PING_ID = 0xFE
        private const val LEGACY_SERVER_LIST_PING_PAYLOAD_SIZE = 1
    }
}

internal fun requireMinecraftEncryptionKey(sharedSecret: ByteArray) {
    require(sharedSecret.size == 16) {
        "Minecraft stream encryption requires a 16-byte shared secret"
    }
}

/** Invalid packet direction, state, identity, or session transition. */
class MinecraftSessionException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

internal class SkippablePacketEncodingException(
    cause: Throwable,
) : Exception("A skippable clientbound packet could not be encoded", cause)

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
