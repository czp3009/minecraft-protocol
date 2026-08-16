package com.hiczp.minecraft.protocol.neoforge

import com.hiczp.minecraft.protocol.model.packet.ConnectionState
import com.hiczp.minecraft.protocol.model.packet.PacketDirection
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.TextComponent

/**
 * Immutable NeoForge component metadata. It is independent from packet codecs:
 * callers may declare any kotlinx-backed extension packet routes they need.
 */
class NeoForgeNetworkConfiguration(
    configuration: Collection<NeoForgeNetworkComponent> = emptyList(),
    play: Collection<NeoForgeNetworkComponent> = emptyList(),
    includeNeoForgePackets: Boolean = true,
) {
    private val configurationComponents = componentMap(
        if (includeNeoForgePackets) {
            BUILT_IN_CONFIGURATION_COMPONENTS + configuration
        } else {
            configuration
        },
        NeoForgeConnectionProtocol.CONFIGURATION,
    )
    private val playComponents = componentMap(
        if (includeNeoForgePackets) {
            BUILT_IN_PLAY_COMPONENTS + play
        } else {
            play
        },
        NeoForgeConnectionProtocol.PLAY,
    )

    val components: Map<NeoForgeConnectionProtocol, Set<NeoForgeNetworkComponent>> =
        linkedMapOf(
            NeoForgeConnectionProtocol.CONFIGURATION to
                    configurationComponents.values.toSet(),
            NeoForgeConnectionProtocol.PLAY to playComponents.values.toSet(),
        )

    val queryPacket: NeoForgeModdedNetworkQueryPacket = NeoForgeModdedNetworkQueryPacket(components)

    fun components(
        protocol: NeoForgeConnectionProtocol,
    ): Collection<NeoForgeNetworkComponent> = when (protocol) {
        NeoForgeConnectionProtocol.CONFIGURATION -> configurationComponents.values
        NeoForgeConnectionProtocol.PLAY -> playComponents.values
        else -> emptyList()
    }

    fun component(
        protocol: NeoForgeConnectionProtocol,
        channel: Identifier,
    ): NeoForgeNetworkComponent? = when (protocol) {
        NeoForgeConnectionProtocol.CONFIGURATION -> configurationComponents[channel]
        NeoForgeConnectionProtocol.PLAY -> playComponents[channel]
        else -> null
    }

    fun optionalChannels(
        protocol: NeoForgeConnectionProtocol,
        flow: NeoForgePacketFlow,
    ): Set<Identifier> = components(protocol)
        .filter(NeoForgeNetworkComponent::optional)
        .filter { component -> component.accepts(flow) }
        .mapTo(linkedSetOf(), NeoForgeNetworkComponent::id)

    override fun equals(other: Any?): Boolean =
        other is NeoForgeNetworkConfiguration && components == other.components

    override fun hashCode(): Int = components.hashCode()

    override fun toString(): String =
        "NeoForgeNetworkConfiguration(components=$components)"
}

internal data class NeoForgeNetworkNegotiation(
    val setup: NeoForgeNetworkSetup,
    val failureReasons: Map<Identifier, TextComponent>,
) {
    val successful: Boolean
        get() = failureReasons.isEmpty()
}

internal fun negotiateNeoForgeNetwork(
    local: NeoForgeNetworkConfiguration,
    remote: Map<NeoForgeConnectionProtocol, Set<NeoForgeNetworkComponent>>,
): NeoForgeNetworkNegotiation {
    val channels = linkedMapOf<
            NeoForgeConnectionProtocol,
            Map<Identifier, NeoForgeNetworkChannel>,
            >()
    val failures = linkedMapOf<Identifier, TextComponent>()
    NEGOTIATED_PROTOCOLS.forEach { protocol ->
        val localById = local.components(protocol)
            .associateBy(NeoForgeNetworkComponent::id)
        val remoteById = remote[protocol].orEmpty()
            .also { components ->
                if (components.distinctBy(NeoForgeNetworkComponent::id).size != components.size) {
                    throw NeoForgeNegotiationException(
                        "Remote NeoForge $protocol component query contains duplicate identifiers",
                    )
                }
            }
            .associateBy(NeoForgeNetworkComponent::id)
        val negotiated = linkedMapOf<Identifier, NeoForgeNetworkChannel>()
        val identifiers = LinkedHashSet<Identifier>().apply {
            addAll(localById.keys)
            addAll(remoteById.keys)
        }
        identifiers.forEach { id ->
            val ours = localById[id]
            val theirs = remoteById[id]
            when {
                ours == null -> {
                    if (theirs != null && !theirs.optional) {
                        failures[id] = TextComponent.literal(
                            "Client requires NeoForge $protocol channel $id, but the server does not provide it",
                        )
                    }
                }

                theirs == null -> {
                    if (!ours.optional) {
                        failures[id] = TextComponent.literal(
                            "Server requires NeoForge $protocol channel $id, but the client does not provide it",
                        )
                    }
                }

                ours.version != theirs.version -> failures[id] =
                    TextComponent.literal(
                        "NeoForge $protocol channel $id has incompatible versions ${ours.version} and ${theirs.version}",
                    )

                ours.flow != theirs.flow -> failures[id] =
                    TextComponent.literal(
                        "NeoForge $protocol channel $id has incompatible packet flows ${ours.flow} and ${theirs.flow}",
                    )

                else -> negotiated[id] = NeoForgeNetworkChannel(
                    id,
                    ours.version,
                )
            }
        }
        channels[protocol] = negotiated
    }
    return NeoForgeNetworkNegotiation(
        NeoForgeNetworkSetup(channels),
        failures,
    )
}

internal fun NeoForgeNetworkConfiguration.validateSetup(
    setup: NeoForgeNetworkSetup,
) {
    NEGOTIATED_PROTOCOLS.forEach { protocol ->
        val selected = setup.channels(protocol)
        selected.forEach { (id, channel) ->
            val local = component(protocol, id)
                ?: throw NeoForgeNegotiationException(
                    "Server selected unknown NeoForge $protocol channel $id",
                )
            if (channel.chosenVersion != local.version) {
                throw NeoForgeNegotiationException(
                    "Server selected NeoForge $protocol channel $id version ${channel.chosenVersion}; local version is ${local.version}",
                )
            }
        }
        val missing = components(protocol)
            .filterNot(NeoForgeNetworkComponent::optional)
            .map(NeoForgeNetworkComponent::id)
            .filterNot(selected::containsKey)
        if (missing.isNotEmpty()) {
            throw NeoForgeNegotiationException(
                "Server omitted mandatory NeoForge $protocol channels $missing",
            )
        }
    }
}

internal fun NeoForgeNetworkComponent.accepts(flow: NeoForgePacketFlow): Boolean =
    this.flow == null || this.flow == flow

internal fun NeoForgeConnectionProtocol.toConnectionState(): ConnectionState =
    when (this) {
        NeoForgeConnectionProtocol.CONFIGURATION -> ConnectionState.CONFIGURATION
        NeoForgeConnectionProtocol.PLAY -> ConnectionState.PLAY
        else -> throw NeoForgeNegotiationException(
            "NeoForge protocol $this has no extension-payload state",
        )
    }

internal fun NeoForgePacketFlow.toPacketDirection(): PacketDirection = when (this) {
    NeoForgePacketFlow.SERVERBOUND -> PacketDirection.SERVERBOUND
    NeoForgePacketFlow.CLIENTBOUND -> PacketDirection.CLIENTBOUND
}

private fun componentMap(
    components: Collection<NeoForgeNetworkComponent>,
    protocol: NeoForgeConnectionProtocol,
): Map<Identifier, NeoForgeNetworkComponent> {
    val snapshot = components.toList()
    val result = snapshot.associateBy(NeoForgeNetworkComponent::id)
    require(result.size == snapshot.size) {
        "NeoForge $protocol components contain duplicate identifiers"
    }
    require(result.keys.none { it.namespace == "minecraft" }) {
        "NeoForge components cannot use the minecraft namespace"
    }
    return result
}

private val NEGOTIATED_PROTOCOLS = listOf(
    NeoForgeConnectionProtocol.CONFIGURATION,
    NeoForgeConnectionProtocol.PLAY,
)

private val BUILT_IN_CONFIGURATION_COMPONENTS = listOf(
    component(NeoForgeChannels.ConfigFile, NeoForgePacketFlow.CLIENTBOUND),
    component(
        NeoForgeChannels.FrozenRegistrySyncStart,
        NeoForgePacketFlow.CLIENTBOUND,
    ),
    component(NeoForgeChannels.FrozenRegistry, NeoForgePacketFlow.CLIENTBOUND),
    component(NeoForgeChannels.FrozenRegistrySyncCompleted),
    component(
        NeoForgeChannels.KnownRegistryDataMaps,
        NeoForgePacketFlow.CLIENTBOUND,
    ),
    component(
        NeoForgeChannels.KnownRegistryDataMapsReply,
        NeoForgePacketFlow.SERVERBOUND,
    ),
    component(NeoForgeChannels.ExtensibleEnumData, NeoForgePacketFlow.CLIENTBOUND),
    component(
        NeoForgeChannels.ExtensibleEnumAcknowledge,
        NeoForgePacketFlow.SERVERBOUND,
    ),
    component(NeoForgeChannels.FeatureFlagData, NeoForgePacketFlow.CLIENTBOUND),
    component(
        NeoForgeChannels.FeatureFlagAcknowledge,
        NeoForgePacketFlow.SERVERBOUND,
    ),
    component(NeoForgeChannels.Split),
)

private val BUILT_IN_PLAY_COMPONENTS = listOf(
    component(NeoForgeChannels.ConfigFile, NeoForgePacketFlow.CLIENTBOUND),
    component(
        NeoForgeChannels.RegistryDataMapSync,
        NeoForgePacketFlow.CLIENTBOUND,
    ),
    component(NeoForgeChannels.Split),
)

private fun component(
    id: Identifier,
    flow: NeoForgePacketFlow? = null,
): NeoForgeNetworkComponent = NeoForgeNetworkComponent(
    id = id,
    version = "1",
    flow = flow,
    optional = true,
)

open class NeoForgeNegotiationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
