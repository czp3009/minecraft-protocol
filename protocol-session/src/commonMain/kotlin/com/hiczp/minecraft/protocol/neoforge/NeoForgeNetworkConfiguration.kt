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

    val neoForgeModdedNetworkQueryPacket: NeoForgeModdedNetworkQueryPacket =
        NeoForgeModdedNetworkQueryPacket(components)

    fun components(
        neoForgeConnectionProtocol: NeoForgeConnectionProtocol,
    ): Collection<NeoForgeNetworkComponent> = when (neoForgeConnectionProtocol) {
        NeoForgeConnectionProtocol.CONFIGURATION -> configurationComponents.values
        NeoForgeConnectionProtocol.PLAY -> playComponents.values
        else -> emptyList()
    }

    fun component(
        neoForgeConnectionProtocol: NeoForgeConnectionProtocol,
        channel: Identifier,
    ): NeoForgeNetworkComponent? = when (neoForgeConnectionProtocol) {
        NeoForgeConnectionProtocol.CONFIGURATION -> configurationComponents[channel]
        NeoForgeConnectionProtocol.PLAY -> playComponents[channel]
        else -> null
    }

    fun optionalChannels(
        neoForgeConnectionProtocol: NeoForgeConnectionProtocol,
        neoForgePacketFlow: NeoForgePacketFlow,
    ): Set<Identifier> = components(neoForgeConnectionProtocol)
        .filter(NeoForgeNetworkComponent::optional)
        .filter { component -> component.accepts(neoForgePacketFlow) }
        .mapTo(linkedSetOf(), NeoForgeNetworkComponent::id)

    override fun equals(other: Any?): Boolean =
        other is NeoForgeNetworkConfiguration && components == other.components

    override fun hashCode(): Int = components.hashCode()

    override fun toString(): String =
        "NeoForgeNetworkConfiguration(components=$components)"
}

internal data class NeoForgeNetworkNegotiation(
    val neoForgeNetworkSetup: NeoForgeNetworkSetup,
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
    NEGOTIATED_PROTOCOLS.forEach { neoForgeConnectionProtocol ->
        val localById = local.components(neoForgeConnectionProtocol)
            .associateBy(NeoForgeNetworkComponent::id)
        val remoteById = remote[neoForgeConnectionProtocol].orEmpty()
            .also { components ->
                if (components.distinctBy(NeoForgeNetworkComponent::id).size != components.size) {
                    throw NeoForgeNegotiationException(
                        "Remote NeoForge $neoForgeConnectionProtocol component query contains duplicate identifiers",
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
                            "Client requires NeoForge $neoForgeConnectionProtocol channel $id, but the server does not provide it",
                        )
                    }
                }

                theirs == null -> {
                    if (!ours.optional) {
                        failures[id] = TextComponent.literal(
                            "Server requires NeoForge $neoForgeConnectionProtocol channel $id, but the client does not provide it",
                        )
                    }
                }

                ours.version != theirs.version -> failures[id] =
                    TextComponent.literal(
                        "NeoForge $neoForgeConnectionProtocol channel $id has incompatible versions ${ours.version} and ${theirs.version}",
                    )

                ours.neoForgePacketFlow != theirs.neoForgePacketFlow -> failures[id] =
                    TextComponent.literal(
                        "NeoForge $neoForgeConnectionProtocol channel $id has incompatible packet flows ${ours.neoForgePacketFlow} and ${theirs.neoForgePacketFlow}",
                    )

                else -> negotiated[id] = NeoForgeNetworkChannel(
                    id,
                    ours.version,
                )
            }
        }
        channels[neoForgeConnectionProtocol] = negotiated
    }
    return NeoForgeNetworkNegotiation(
        NeoForgeNetworkSetup(channels),
        failures,
    )
}

internal fun NeoForgeNetworkConfiguration.validateSetup(
    neoForgeNetworkSetup: NeoForgeNetworkSetup,
) {
    NEGOTIATED_PROTOCOLS.forEach { neoForgeConnectionProtocol ->
        val selected = neoForgeNetworkSetup.channels(neoForgeConnectionProtocol)
        selected.forEach { (id, neoForgeNetworkChannel) ->
            val localNeoForgeNetworkComponent = component(neoForgeConnectionProtocol, id)
                ?: throw NeoForgeNegotiationException(
                    "Server selected unknown NeoForge $neoForgeConnectionProtocol channel $id",
                )
            if (neoForgeNetworkChannel.chosenVersion != localNeoForgeNetworkComponent.version) {
                throw NeoForgeNegotiationException(
                    "Server selected NeoForge $neoForgeConnectionProtocol channel $id version ${neoForgeNetworkChannel.chosenVersion}; local version is ${localNeoForgeNetworkComponent.version}",
                )
            }
        }
        val missing = components(neoForgeConnectionProtocol)
            .filterNot(NeoForgeNetworkComponent::optional)
            .map(NeoForgeNetworkComponent::id)
            .filterNot(selected::containsKey)
        if (missing.isNotEmpty()) {
            throw NeoForgeNegotiationException(
                "Server omitted mandatory NeoForge $neoForgeConnectionProtocol channels $missing",
            )
        }
    }
}

internal fun NeoForgeNetworkComponent.accepts(neoForgePacketFlow: NeoForgePacketFlow): Boolean =
    this.neoForgePacketFlow == null || this.neoForgePacketFlow == neoForgePacketFlow

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
    neoForgeConnectionProtocol: NeoForgeConnectionProtocol,
): Map<Identifier, NeoForgeNetworkComponent> {
    val snapshot = components.toList()
    val result = snapshot.associateBy(NeoForgeNetworkComponent::id)
    require(result.size == snapshot.size) {
        "NeoForge $neoForgeConnectionProtocol components contain duplicate identifiers"
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
    neoForgePacketFlow: NeoForgePacketFlow? = null,
): NeoForgeNetworkComponent = NeoForgeNetworkComponent(
    id = id,
    version = "1",
    neoForgePacketFlow = neoForgePacketFlow,
    optional = true,
)

open class NeoForgeNegotiationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
