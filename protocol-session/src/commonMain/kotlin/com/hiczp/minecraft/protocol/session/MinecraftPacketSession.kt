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
    internal val minecraftFrameStream: MinecraftFrameStream,
    protected val inboundDirection: PacketDirection,
    protected val outboundDirection: PacketDirection,
    private val packetRegistry: PacketRegistry,
    minecraftProtocolFormat: MinecraftProtocolFormat,
) {
    private val stateValue = MutableStateFlow(ConnectionState.HANDSHAKE)
    private val formatValue = MutableStateFlow(minecraftProtocolFormat)
    private val activeRoutesValue = MutableStateFlow(emptySet<PacketRouteKey>())
    private val loginQueryMutex = Mutex()
    private val loginQueries = mutableMapOf<Int, Identifier>()

    val connectionState: ConnectionState
        get() = stateValue.value

    internal val inboundState: ConnectionState
        get() = stateValue.value

    val minecraftProtocolFormat: MinecraftProtocolFormat
        get() = formatValue.value

    val protocolRegistryContext: ProtocolRegistryContext
        get() = formatValue.value.minecraftProtocolFormatConfiguration.protocolRegistryContext

    val declaredExtensionRoutes: Set<PacketRouteKey>
        get() = packetRegistry.declaredExtensionRoutes

    val activeExtensionRoutes: Set<PacketRouteKey>
        get() = activeRoutesValue.value

    fun installProtocolRegistryContext(protocolRegistryContext: ProtocolRegistryContext) {
        val current = formatValue.value
        formatValue.value = MinecraftProtocolFormat(
            minecraftProtocolFormatConfiguration = current.minecraftProtocolFormatConfiguration.copy(
                protocolRegistryContext = protocolRegistryContext,
            ),
            serializersModule = current.serializersModule,
        )
    }

    /** Atomically replaces the active subset of routes declared at construction. The supplied set must remain stable. */
    fun activateExtensionRoutes(routes: Set<PacketRouteKey>) {
        val undeclared = routes - declaredExtensionRoutes
        require(undeclared.isEmpty()) {
            "Cannot activate undeclared extension routes: $undeclared"
        }
        activeRoutesValue.value = routes
    }

    suspend fun awaitState(expected: ConnectionState) {
        stateValue.first { it == expected }
    }

    open suspend fun send(packet: Outgoing) {
        when (packet) {
            is UnknownPacket -> sendUnknown(packet)
            is ClientboundPacket.Extension,
            is ServerboundPacket.Extension,
                -> sendExtension(packet)

            else -> sendKnown(packet)
        }
    }

    open suspend fun receive(): Incoming {
        val legacyAware =
            stateValue.value == ConnectionState.HANDSHAKE &&
                    inboundDirection == PacketDirection.SERVERBOUND
        val packetData = Buffer()
        if (legacyAware) {
            minecraftFrameStream.receivePacketDataOrLegacyToSink(
                packetData,
                legacyPacketId = LEGACY_SERVER_LIST_PING_ID,
                legacyPayloadSize = LEGACY_SERVER_LIST_PING_PAYLOAD_SIZE,
            )
        } else {
            minecraftFrameStream.receivePacketDataToSink(packetData)
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
            connectionState = inboundState,
            packetDirection = inboundDirection,
            id = id,
        )
        val expectedFraming = if (legacy) PacketFraming.LEGACY_UNFRAMED else PacketFraming.NORMAL
        if (codec != null && codec.packetFraming != expectedFraming) {
            throw MinecraftSessionException(
                "Packet 0x${id.toString(16)} used $expectedFraming framing but its codec requires ${codec.packetFraming}",
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
            connectionState = inboundState,
            packetDirection = inboundDirection,
            id = id,
            source = packetData,
            byteCount = packetData.size.toInt(),
            minecraftProtocolFormat = formatValue.value,
        )
        val incoming = requireIncoming(liftIncoming(packet, id))
        applyInboundEffects(packet)
        return incoming
    }

    fun encodeCustomPayload(packet: Outgoing): RoutedCustomPayload {
        val outboundState = stateValue.value
        val packetCodecRegistration = packetRegistry.registration(
            packet,
            outboundState,
            outboundDirection,
        ) ?: throw MinecraftSessionException(
            "No extension codec for ${packet::class.simpleName}",
        )
        val routeKey = packetCodecRegistration.packetRouteKey as? PacketRouteKey.CustomPayload
            ?: throw MinecraftSessionException(
                "${packet::class.simpleName} is not a custom-payload extension in $outboundState $outboundDirection",
            )
        if (routeKey !in activeRoutesValue.value) {
            throw MinecraftSessionException("Extension route $routeKey is not active")
        }
        val customPayload = packetRegistry.extensionRoute(
            packet,
            outboundState,
            outboundDirection,
            customPayloadPacketId(routeKey),
        ) as PacketRoute.CustomPayload
        validateRoute(customPayload, outboundDirection)
        val body = Buffer()
        packetRegistry.encodeExtensionPayloadToSink(
            packet,
            outboundState,
            outboundDirection,
            body,
            formatValue.value,
        )
        return RoutedCustomPayload(
            customPayload,
            ByteString(body.readByteArray()),
        )
    }

    fun decodeCustomPayload(routedCustomPayload: RoutedCustomPayload): Incoming {
        val customPayload = routedCustomPayload.route
        validateRoute(customPayload, inboundDirection)
        val expectedPacketId = customPayloadPacketId(
            customPayload.packetRouteKey as PacketRouteKey.CustomPayload,
        )
        if (customPayload.packetId != expectedPacketId) {
            val preservedRoute = "Custom payload route preserves outer ID ${customPayload.packetId}"
            throw MinecraftSessionException(
                "$preservedRoute, but the active registry uses $expectedPacketId",
            )
        }
        return requireIncoming(liftRoute(customPayload, routedCustomPayload.data))
    }

    private suspend fun sendUnknown(unknownPacket: UnknownPacket) {
        validateRoute(unknownPacket.packetRoute, outboundDirection)
        when (val packetRoute = unknownPacket.packetRoute) {
            is PacketRoute.TopLevel -> {
                sendRawTopLevel(packetRoute.packetId, unknownPacket.data)
            }

            is PacketRoute.CustomPayload,
            is PacketRoute.LoginQuery,
                -> {
                val wirePacket = routedWirePacket(packetRoute, unknownPacket.data)
                sendKnown(wirePacket)
            }
        }
    }

    private suspend fun sendExtension(packet: Outgoing) {
        val outboundState = stateValue.value
        val packetCodecRegistration = packetRegistry.registration(
            packet,
            outboundState,
            outboundDirection,
        )
            ?: throw MinecraftSessionException(
                "No extension codec for ${packet::class.simpleName}",
            )
        val declaredRoute = packetCodecRegistration.packetRouteKey
        if (declaredRoute !in activeRoutesValue.value) {
            throw MinecraftSessionException(
                "Extension route $declaredRoute is not active",
            )
        }
        if (declaredRoute is PacketRouteKey.CustomPayload) {
            val routedCustomPayload = encodeCustomPayload(packet)
            sendKnown(routedWirePacket(routedCustomPayload.route, routedCustomPayload.data))
            return
        }
        val outerPacketId: Int? = null
        val packetRoute = packetRegistry.extensionRoute(
            packet,
            outboundState,
            outboundDirection,
            outerPacketId,
        )
        validateRoute(packetRoute, outboundDirection)
        if (packetRoute is PacketRoute.TopLevel) {
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
        sendKnown(routedWirePacket(packetRoute, data))
    }

    private suspend fun sendKnown(packet: Packet) {
        val outboundState = stateValue.value
        val packetCodec = packetRegistry.codec(packet, outboundState, outboundDirection)
            ?: throw MinecraftSessionException(
                "No packet codec for ${packet::class.simpleName}",
            )
        val nextState = transitionState(packet)
        val encryption = outboundEncryptionFor(packet)
        val packetData = Buffer()
        when (packetCodec.packetFraming) {
            PacketFraming.NORMAL -> packetData.writeVarInt(packetCodec.packetKey.id)
            PacketFraming.LEGACY_UNFRAMED -> packetData.writeByte(packetCodec.packetKey.id.toByte())
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
        when (packetCodec.packetFraming) {
            PacketFraming.NORMAL -> {
                if (requiresWireCommit(packet, nextState, encryption)) {
                    minecraftFrameStream.sendPacketDataAndCommit(packetData, packetDataBytes) {
                        commitOutboundEffects(packet, nextState, encryption)
                    }
                } else {
                    minecraftFrameStream.sendPacketData(packetData, packetDataBytes)
                }
            }

            PacketFraming.LEGACY_UNFRAMED -> {
                check(!requiresWireCommit(packet, nextState, encryption)) {
                    "Legacy unframed packets cannot commit wire effects"
                }
                minecraftFrameStream.sendUnframedPacketData(packetData, packetDataBytes)
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
        minecraftFrameStream.sendPacketData(packetData, packetData.size.toInt())
    }

    private suspend fun liftIncoming(
        packet: Packet,
        packetId: Int,
    ): Packet = when (packet) {
        is LoginPluginRequestPacket -> {
            val loginQuery = PacketRoute.LoginQuery(
                PacketDirection.CLIENTBOUND,
                packet.messageId,
                packet.channel,
            )
            recordLoginQuery(loginQuery)
            liftRoute(loginQuery, packet.data)
        }

        is LoginPluginResponsePacket -> {
            val channel = consumeLoginQuery(packet.messageId) ?: return packet
            val loginQuery = PacketRoute.LoginQuery(
                PacketDirection.SERVERBOUND,
                packet.messageId,
                channel,
                hasPayload = packet.data != null,
            )
            liftRoute(loginQuery, packet.data ?: ByteString(byteArrayOf()))
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
        customPayload: CustomPayload,
        packetId: Int,
        connectionState: ConnectionState,
        packetDirection: PacketDirection,
    ): Packet = when (customPayload) {
        is CustomPayload.Brand -> when {
            connectionState == ConnectionState.CONFIGURATION &&
                    packetDirection == PacketDirection.CLIENTBOUND ->
                ConfigurationClientboundPluginMessagePacket(customPayload)

            connectionState == ConnectionState.CONFIGURATION ->
                ConfigurationServerboundPluginMessagePacket(customPayload)

            packetDirection == PacketDirection.CLIENTBOUND ->
                PlayClientboundPluginMessagePacket(customPayload)

            else -> PlayServerboundPluginMessagePacket(customPayload)
        }

        is CustomPayload.Unknown -> liftRoute(
            PacketRoute.CustomPayload(
                connectionState,
                packetDirection,
                packetId,
                customPayload.channel,
            ),
            customPayload.data,
        )
    }

    private fun liftRoute(
        packetRoute: PacketRoute,
        data: ByteString,
    ): Packet {
        val packetCodecRegistration = packetRegistry.registration(packetRoute.packetRouteKey)
        if (
            packetCodecRegistration == null ||
            packetRoute.packetRouteKey !in activeRoutesValue.value
        ) {
            return unknownPacket(packetRoute, data)
        }
        val source = Buffer().apply { write(data.toByteArray()) }
        return packetRegistry.decodeExtensionPayloadFromSource(
            packetRoute,
            source,
            data.size,
            formatValue.value,
        )
    }

    private fun routedWirePacket(
        packetRoute: PacketRoute,
        data: ByteString,
    ): Packet = when (packetRoute) {
        is PacketRoute.TopLevel -> error("Top-level routes do not have an outer packet")
        is PacketRoute.LoginQuery -> when (packetRoute.packetDirection) {
            PacketDirection.CLIENTBOUND -> {
                require(packetRoute.hasPayload) {
                    "A Login query request always has a payload body"
                }
                LoginPluginRequestPacket(
                    packetRoute.transactionId,
                    packetRoute.channel,
                    data,
                )
            }

            PacketDirection.SERVERBOUND -> LoginPluginResponsePacket(
                packetRoute.transactionId,
                data.takeIf { packetRoute.hasPayload },
            )
        }

        is PacketRoute.CustomPayload -> {
            val packet = customPayloadPacket(
                packetRoute.connectionState,
                packetRoute.packetDirection,
                CustomPayload.Unknown(packetRoute.channel, data),
            )
            val actualId = packetRegistry.codec(
                packet,
                packetRoute.connectionState,
                packetRoute.packetDirection,
            )?.packetKey?.id
                ?: throw MinecraftSessionException(
                    "No vanilla outer custom-payload codec for ${packetRoute.connectionState} ${packetRoute.packetDirection}",
                )
            if (actualId != packetRoute.packetId) {
                throw MinecraftSessionException(
                    "Custom payload route preserves outer ID ${packetRoute.packetId}, but the active registry uses $actualId",
                )
            }
            packet
        }
    }

    private fun customPayloadPacketId(
        route: PacketRouteKey.CustomPayload,
    ): Int {
        val packet = customPayloadPacket(
            route.connectionState,
            route.packetDirection,
            CustomPayload.Unknown(route.channel, ByteString(byteArrayOf())),
        )
        return packetRegistry.codec(
            packet,
            route.connectionState,
            route.packetDirection,
        )?.packetKey?.id
            ?: throw MinecraftSessionException(
                "No vanilla outer custom-payload codec for ${route.connectionState} ${route.packetDirection}",
            )
    }

    private fun customPayloadPacket(
        connectionState: ConnectionState,
        packetDirection: PacketDirection,
        customPayload: CustomPayload,
    ): Packet = when {
        connectionState == ConnectionState.CONFIGURATION &&
                packetDirection == PacketDirection.CLIENTBOUND ->
            ConfigurationClientboundPluginMessagePacket(customPayload)

        connectionState == ConnectionState.CONFIGURATION &&
                packetDirection == PacketDirection.SERVERBOUND ->
            ConfigurationServerboundPluginMessagePacket(customPayload)

        connectionState == ConnectionState.PLAY &&
                packetDirection == PacketDirection.CLIENTBOUND ->
            PlayClientboundPluginMessagePacket(customPayload)

        connectionState == ConnectionState.PLAY &&
                packetDirection == PacketDirection.SERVERBOUND ->
            PlayServerboundPluginMessagePacket(customPayload)

        else -> throw MinecraftSessionException(
            "Custom payloads are not valid in $connectionState",
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
        packetRoute: PacketRoute,
        expectedDirection: PacketDirection,
    ) {
        val expectedState = stateValue.value
        if (packetRoute.connectionState != expectedState) {
            throw MinecraftSessionException(
                "Route ${packetRoute.packetRouteKey} belongs to ${packetRoute.connectionState}, but the $expectedDirection session is in $expectedState",
            )
        }
        if (packetRoute.packetDirection != expectedDirection) {
            throw MinecraftSessionException(
                "Route ${packetRoute.packetRouteKey} is ${packetRoute.packetDirection}, but this session sends $expectedDirection packets",
            )
        }
    }

    private fun unknownPacket(
        packetRoute: PacketRoute,
        data: ByteString,
    ): UnknownPacket = when (packetRoute.packetDirection) {
        PacketDirection.CLIENTBOUND -> UnknownPacket.Clientbound(packetRoute, data)
        PacketDirection.SERVERBOUND -> UnknownPacket.Serverbound(packetRoute, data)
    }

    private fun applyInboundEffects(packet: Packet) {
        if (packet is SetCompressionPacket) {
            minecraftFrameStream.configureCompression(packet.threshold)
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
                    packetDirection = PacketDirection.CLIENTBOUND,
                    transactionId = packet.messageId,
                    channel = packet.channel,
                ),
            )

            is LoginPluginResponsePacket -> consumeLoginQuery(packet.messageId)
            else -> Unit
        }
        if (packet is SetCompressionPacket) {
            minecraftFrameStream.configureCompression(packet.threshold)
        }
        if (nextState != null) {
            stateValue.value = nextState
        }
        if (encryption != null) {
            try {
                minecraftFrameStream.enableEncryption(encryption)
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
