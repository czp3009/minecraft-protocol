package com.hiczp.minecraft.protocol.neoforge

import com.hiczp.minecraft.protocol.model.type.*

/**
 * Shareable server-side frozen-registry wire snapshot. Construct it once and
 * pass the same instance to each per-connection profile.
 */
class NeoForgeFrozenRegistrySync(
    registries: List<NeoForgeFrozenRegistryPacket>,
) {
    val registries: List<NeoForgeFrozenRegistryPacket> = registries.toList()
    private val byIdentifier = this.registries.associateBy(NeoForgeFrozenRegistryPacket::registry)

    init {
        require(byIdentifier.size == this.registries.size) {
            "NeoForge frozen registry sync contains duplicate registries"
        }
    }

    val startPacket: NeoForgeFrozenRegistrySyncStartPacket =
        NeoForgeFrozenRegistrySyncStartPacket(
            this.registries.map(NeoForgeFrozenRegistryPacket::registry),
        )

    val remoteSnapshot: RemoteRegistrySnapshot = remoteRegistrySnapshot(this.registries)

    operator fun get(id: Identifier): NeoForgeFrozenRegistryPacket? =
        byIdentifier[id]

    override fun equals(other: Any?): Boolean =
        other is NeoForgeFrozenRegistrySync && registries == other.registries

    override fun hashCode(): Int = registries.hashCode()

    override fun toString(): String =
        "NeoForgeFrozenRegistrySync(registries=$registries)"
}

internal fun remoteRegistrySnapshot(
    packets: Collection<NeoForgeFrozenRegistryPacket>,
): RemoteRegistrySnapshot = RemoteRegistrySnapshot(
    packets.map { packet ->
        packet.snapshot.toRemoteRegistry(packet.registry)
    },
)

private fun NeoForgeRegistrySnapshot.toRemoteRegistry(
    registry: Identifier,
): RemoteRegistry {
    val canonicalIdentifiers = ids.values.toSet()
    require(canonicalIdentifiers.size == ids.size) {
        "NeoForge registry $registry contains duplicate identifiers"
    }
    val aliasesByTarget = linkedMapOf<Identifier, MutableSet<Identifier>>()
    aliases.keys.forEach { source ->
        if (source in canonicalIdentifiers) return@forEach
        val target = resolveAliasTarget(
            registry,
            source,
            canonicalIdentifiers,
        )
        aliasesByTarget.getOrPut(target, ::linkedSetOf).add(source)
    }
    return RemoteRegistry(
        registry,
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
    registry: Identifier,
    source: Identifier,
    canonicalIdentifiers: Set<Identifier>,
): Identifier {
    val visited = linkedSetOf<Identifier>()
    var current = source
    while (current !in canonicalIdentifiers) {
        if (!visited.add(current)) {
            throw IllegalArgumentException(
                "NeoForge registry $registry contains an alias cycle through $current",
            )
        }
        current = aliases[current] ?: throw IllegalArgumentException(
            "NeoForge registry $registry alias $source targets missing $current",
        )
    }
    return current
}

internal fun StaticRegistrySchema.requireCompatible(
    snapshot: RemoteRegistrySnapshot,
): RemoteRegistrySnapshot {
    snapshot.registries.values.forEach { remote ->
        val localEntries = registries[remote.id]
        if (localEntries == null) {
            if (remote.entries.isNotEmpty()) {
                throw NeoForgeNegotiationException(
                    "NeoForge server synchronized unknown registry ${remote.id}",
                )
            }
            return@forEach
        }
        val local = localEntries.toSet()
        val missing = remote.entries.filter { entry ->
            entry.id !in local && entry.aliases.none(local::contains)
        }
        if (missing.isNotEmpty()) {
            throw NeoForgeNegotiationException(
                "NeoForge registry ${remote.id} contains entries absent from the local schema: ${
                    missing.map(
                        RemoteRegistryEntry::id
                    )
                }",
            )
        }
    }
    return snapshot
}
