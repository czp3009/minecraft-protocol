package com.hiczp.minecraft.protocol.neoforge

import com.hiczp.minecraft.protocol.model.type.*

/**
 * Shareable server-side frozen-registry wire snapshot. Construct it once and
 * pass the same instance to each per-connection profile.
 */
class NeoForgeFrozenRegistrySync(
    frozenRegistryPackets: List<NeoForgeFrozenRegistryPacket>,
) {
    val frozenRegistryPackets: List<NeoForgeFrozenRegistryPacket> = frozenRegistryPackets.toList()
    private val frozenRegistryPacketsById =
        this.frozenRegistryPackets.associateBy(NeoForgeFrozenRegistryPacket::registryId)

    init {
        require(frozenRegistryPacketsById.size == this.frozenRegistryPackets.size) {
            "NeoForge frozen registry sync contains duplicate registries"
        }
    }

    val neoForgeFrozenRegistrySyncStartPacket: NeoForgeFrozenRegistrySyncStartPacket =
        NeoForgeFrozenRegistrySyncStartPacket(
            this.frozenRegistryPackets.map(NeoForgeFrozenRegistryPacket::registryId),
        )

    val remoteRegistrySnapshot: RemoteRegistrySnapshot = neoForgeRemoteRegistrySnapshot(this.frozenRegistryPackets)

    operator fun get(registryId: Identifier): NeoForgeFrozenRegistryPacket? =
        frozenRegistryPacketsById[registryId]

    override fun equals(other: Any?): Boolean =
        other is NeoForgeFrozenRegistrySync && frozenRegistryPackets == other.frozenRegistryPackets

    override fun hashCode(): Int = frozenRegistryPackets.hashCode()

    override fun toString(): String =
        "NeoForgeFrozenRegistrySync(frozenRegistryPackets=$frozenRegistryPackets)"
}

internal fun neoForgeRemoteRegistrySnapshot(
    frozenRegistryPackets: Collection<NeoForgeFrozenRegistryPacket>,
): RemoteRegistrySnapshot = RemoteRegistrySnapshot(
    frozenRegistryPackets.map { neoForgeFrozenRegistryPacket ->
        neoForgeFrozenRegistryPacket.neoForgeRegistrySnapshot.toRemoteRegistry(
            neoForgeFrozenRegistryPacket.registryId,
        )
    },
)

private fun NeoForgeRegistrySnapshot.toRemoteRegistry(
    registryId: Identifier,
): RemoteRegistry {
    val canonicalIdentifiers = ids.values.toSet()
    require(canonicalIdentifiers.size == ids.size) {
        "NeoForge registry $registryId contains duplicate identifiers"
    }
    val aliasesByTarget = linkedMapOf<Identifier, MutableSet<Identifier>>()
    aliases.keys.forEach { source ->
        if (source in canonicalIdentifiers) return@forEach
        val target = resolveAliasTarget(
            registryId,
            source,
            canonicalIdentifiers,
        )
        aliasesByTarget.getOrPut(target, ::linkedSetOf).add(source)
    }
    return RemoteRegistry(
        registryId,
        ids.map { (rawId, id) ->
            RemoteRegistryEntry(
                id = id,
                rawId = rawId,
                aliases = aliasesByTarget[id].orEmpty(),
            )
        },
    )
}

private fun NeoForgeRegistrySnapshot.resolveAliasTarget(
    registryId: Identifier,
    source: Identifier,
    canonicalIdentifiers: Set<Identifier>,
): Identifier {
    val visited = linkedSetOf<Identifier>()
    var current = source
    while (current !in canonicalIdentifiers) {
        if (!visited.add(current)) {
            throw IllegalArgumentException(
                "NeoForge registry $registryId contains an alias cycle through $current",
            )
        }
        current = aliases[current] ?: throw IllegalArgumentException(
            "NeoForge registry $registryId alias $source targets missing $current",
        )
    }
    return current
}

internal fun StaticRegistrySchema.requireNeoForgeCompatible(
    remoteRegistrySnapshot: RemoteRegistrySnapshot,
): RemoteRegistrySnapshot {
    remoteRegistrySnapshot.registries.values.forEach { remoteRegistry ->
        val localEntries = registries[remoteRegistry.id]
        if (localEntries == null) {
            if (remoteRegistry.entries.isNotEmpty()) {
                throw NeoForgeNegotiationException(
                    "NeoForge server synchronized unknown registry ${remoteRegistry.id}",
                )
            }
            return@forEach
        }
        val local = localEntries.toSet()
        val missing = remoteRegistry.entries.filter { remoteRegistryEntry ->
            remoteRegistryEntry.id !in local && remoteRegistryEntry.aliases.none(local::contains)
        }
        if (missing.isNotEmpty()) {
            throw NeoForgeNegotiationException(
                "NeoForge registry ${remoteRegistry.id} contains entries absent from the local schema: ${
                    missing.map(
                        RemoteRegistryEntry::id
                    )
                }",
            )
        }
    }
    return remoteRegistrySnapshot
}
