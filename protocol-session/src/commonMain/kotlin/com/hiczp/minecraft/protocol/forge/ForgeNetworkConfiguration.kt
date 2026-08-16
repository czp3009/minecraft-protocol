package com.hiczp.minecraft.protocol.forge

import com.hiczp.minecraft.protocol.model.type.Identifier

enum class ForgeChannelPresence {
    VANILLA,
    MISSING,
    PRESENT,
}

fun interface ForgeVersionAcceptance {
    fun accepts(presence: ForgeChannelPresence, version: Int): Boolean

    companion object {
        fun exact(expected: Int): ForgeVersionAcceptance {
            require(expected >= 0) { "Forge channel version must be non-negative" }
            return ForgeVersionAcceptance { presence, version ->
                presence == ForgeChannelPresence.PRESENT && version == expected
            }
        }

        fun optionalExact(expected: Int): ForgeVersionAcceptance {
            require(expected >= 0) { "Forge channel version must be non-negative" }
            return ForgeVersionAcceptance { presence, version ->
                presence == ForgeChannelPresence.MISSING ||
                        presence == ForgeChannelPresence.PRESENT &&
                        version == expected
            }
        }

        fun vanillaOrExact(expected: Int): ForgeVersionAcceptance {
            require(expected >= 0) { "Forge channel version must be non-negative" }
            return ForgeVersionAcceptance { presence, version ->
                presence == ForgeChannelPresence.VANILLA ||
                        presence == ForgeChannelPresence.PRESENT &&
                        version == expected
            }
        }

        val Any: ForgeVersionAcceptance = ForgeVersionAcceptance { _, _ -> true }
    }
}

class ForgeChannelDefinition(
    val id: Identifier,
    val version: Int,
    val acceptsClient: ForgeVersionAcceptance = ForgeVersionAcceptance.exact(version),
    val acceptsServer: ForgeVersionAcceptance = ForgeVersionAcceptance.exact(version),
) {
    init {
        require(version >= 0) { "Forge channel version must be non-negative" }
    }
}

class ForgeNetworkConfiguration(
    channels: Collection<ForgeChannelDefinition> = emptyList(),
    payloadChannels: Set<Identifier> = channels.mapTo(linkedSetOf()) { definition ->
        definition.id
    },
) {
    private val channelsById: Map<Identifier, ForgeChannelDefinition> = channels.associateBy(ForgeChannelDefinition::id)

    val channels: Collection<ForgeChannelDefinition> = channelsById.values.toList()

    val payloadChannels: Set<Identifier> = buildSet {
        add(ForgeChannels.Handshake)
        addAll(payloadChannels)
        addAll(channelsById.keys)
    }

    val versionsPacket: ForgeChannelVersionsMessage =
        ForgeChannelVersionsMessage(
            channelsById.mapValues { (_, definition) -> definition.version },
        )

    init {
        require(channelsById.size == channels.size) {
            "Forge network configuration contains duplicate channel identifiers"
        }
        require(this.payloadChannels.none { it.namespace == "minecraft" }) {
            "Forge payload registration cannot claim the minecraft namespace"
        }
    }

    fun validateClient(
        remote: Map<Identifier, Int>,
    ): ForgeChannelValidation = validate(remote, ForgeRemoteSide.CLIENT)

    fun validateServer(
        remote: Map<Identifier, Int>,
    ): ForgeChannelValidation = validate(remote, ForgeRemoteSide.SERVER)

    fun acceptsVanillaClient(): Boolean = channels.all { definition ->
        definition.acceptsClient.accepts(ForgeChannelPresence.VANILLA, -1)
    }

    fun canConnectToVanillaServer(): Boolean = channels.all { definition ->
        definition.acceptsServer.accepts(ForgeChannelPresence.VANILLA, -1)
    }

    private fun validate(
        remote: Map<Identifier, Int>,
        side: ForgeRemoteSide,
    ): ForgeChannelValidation {
        val mismatched = linkedMapOf<Identifier, ForgeVersionMismatch>()
        val missing = linkedSetOf<Identifier>()
        channelsById.values.forEach { definition ->
            val remoteVersion = remote[definition.id]
            val presence = if (remoteVersion == null) {
                ForgeChannelPresence.MISSING
            } else {
                ForgeChannelPresence.PRESENT
            }
            val acceptance = when (side) {
                ForgeRemoteSide.CLIENT -> definition.acceptsClient
                ForgeRemoteSide.SERVER -> definition.acceptsServer
            }
            if (!acceptance.accepts(presence, remoteVersion ?: 0)) {
                if (remoteVersion == null) {
                    missing += definition.id
                } else {
                    mismatched[definition.id] = ForgeVersionMismatch(
                        remoteVersion.toString(),
                        definition.version.toString(),
                    )
                }
            }
        }
        return ForgeChannelValidation(mismatched, missing)
    }
}

data class ForgeChannelValidation(
    val mismatched: Map<Identifier, ForgeVersionMismatch>,
    val missing: Set<Identifier>,
) {
    val successful: Boolean
        get() = mismatched.isEmpty() && missing.isEmpty()

    fun toFailureMessage(): ForgeMismatchDataMessage =
        ForgeMismatchDataMessage(mismatched, missing)
}

private enum class ForgeRemoteSide {
    CLIENT,
    SERVER,
}

open class ForgeNegotiationException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
