package com.hiczp.minecraft.protocol.forge

import com.hiczp.minecraft.protocol.model.type.*

/**
 * Shareable server-owned Forge registry snapshot. Per-connection packets retain
 * references to these immutable snapshots and only add their small ack token.
 */
class ForgeRegistrySync(
    snapshots: Map<Identifier, ForgeRegistrySnapshot>,
    dataPackRegistries: Set<Identifier> = emptySet(),
) {
    val snapshots: Map<Identifier, ForgeRegistrySnapshot> = snapshots.toMap()
    val dataPackRegistries: Set<Identifier> = dataPackRegistries.toSet()
    val registryNames: List<Identifier> = this.snapshots.keys.toList()
    val remoteSnapshot: RemoteRegistrySnapshot =
        forgeRemoteRegistrySnapshot(this.snapshots)

    init {
        require(this.dataPackRegistries.none(this.snapshots::containsKey)) {
            "Forge ordinary and data-pack registry lists must be disjoint"
        }
    }

    fun resolve(schema: StaticRegistrySchema): ProtocolRegistryContext =
        schema.resolve(requireForgeCompatible(schema, remoteSnapshot))
            .withForgeRegistrySizes(snapshots)
}

internal fun forgeRemoteRegistrySnapshot(
    snapshots: Map<Identifier, ForgeRegistrySnapshot>,
): RemoteRegistrySnapshot = RemoteRegistrySnapshot(
    snapshots.map { (registry, snapshot) ->
        snapshot.toRemoteRegistry(registry)
    },
)

private fun ForgeRegistrySnapshot.toRemoteRegistry(
    registry: Identifier,
): RemoteRegistry {
    val canonical = ids.keys
    val aliasesByTarget = linkedMapOf<Identifier, MutableSet<Identifier>>()
    aliases.keys.forEach { source ->
        if (source in canonical) return@forEach
        val target = resolveAliasTarget(registry, source, canonical)
        aliasesByTarget.getOrPut(target, ::linkedSetOf).add(source)
    }
    return RemoteRegistry(
        registry,
        ids.entries.sortedBy(Map.Entry<Identifier, Int>::value).map { (id, rawId) ->
            RemoteRegistryEntry(
                id = id,
                rawId = rawId,
                aliases = aliasesByTarget[id].orEmpty(),
            )
        },
    )
}

private fun ForgeRegistrySnapshot.resolveAliasTarget(
    registry: Identifier,
    source: Identifier,
    canonical: Set<Identifier>,
): Identifier {
    val visited = linkedSetOf<Identifier>()
    var current = source
    while (current !in canonical) {
        if (!visited.add(current)) {
            throw IllegalArgumentException(
                "Forge registry $registry contains an alias cycle through $current",
            )
        }
        current = aliases[current] ?: throw IllegalArgumentException(
            "Forge registry $registry alias $source targets missing $current",
        )
    }
    return current
}

internal fun requireForgeCompatible(
    schema: StaticRegistrySchema,
    snapshot: RemoteRegistrySnapshot,
): RemoteRegistrySnapshot {
    snapshot.registries.values.forEach { remote ->
        val localEntries = schema.registries[remote.id]
        if (localEntries == null) {
            if (remote.entries.isNotEmpty()) {
                throw ForgeNegotiationException(
                    "Forge server synchronized unknown registry ${remote.id}",
                )
            }
            return@forEach
        }
        val local = localEntries.toSet()
        val missing = remote.entries.filter { entry ->
            entry.id !in local && entry.aliases.none(local::contains)
        }
        if (missing.isNotEmpty()) {
            throw ForgeNegotiationException(
                "Forge registry ${remote.id} contains entries absent from the local schema: ${
                    missing.map(
                        RemoteRegistryEntry::id
                    )
                }",
            )
        }
    }
    return snapshot
}

internal fun ProtocolRegistryContext.withForgeRegistrySizes(
    snapshots: Map<Identifier, ForgeRegistrySnapshot>,
): ProtocolRegistryContext {
    val sizes = snapshots.mapNotNull { (registry, snapshot) ->
        snapshot.wireSize.takeIf { it > 0 }?.let { registry to it }
    }.toMap()
    return if (sizes.isEmpty()) this else withRegistrySizes(sizes)
}
