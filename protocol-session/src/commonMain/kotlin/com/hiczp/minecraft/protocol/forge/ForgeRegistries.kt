package com.hiczp.minecraft.protocol.forge

import com.hiczp.minecraft.protocol.model.type.*

/**
 * Shareable server-owned Forge registry snapshot. Per-connection packets retain
 * references to these immutable snapshots and only add their small ack token.
 */
class ForgeRegistrySync(
    forgeRegistrySnapshots: Map<Identifier, ForgeRegistrySnapshot>,
    dataPackRegistryIds: Set<Identifier> = emptySet(),
) {
    val forgeRegistrySnapshots: Map<Identifier, ForgeRegistrySnapshot> = forgeRegistrySnapshots.toMap()
    val dataPackRegistryIds: Set<Identifier> = dataPackRegistryIds.toSet()
    val registryIds: List<Identifier> = this.forgeRegistrySnapshots.keys.toList()
    val remoteRegistrySnapshot: RemoteRegistrySnapshot =
        forgeRemoteRegistrySnapshot(this.forgeRegistrySnapshots)

    init {
        require(this.dataPackRegistryIds.none(this.forgeRegistrySnapshots::containsKey)) {
            "Forge ordinary and data-pack registry lists must be disjoint"
        }
    }

    fun resolve(staticRegistrySchema: StaticRegistrySchema): ProtocolRegistryContext =
        staticRegistrySchema.resolve(requireForgeCompatible(staticRegistrySchema, remoteRegistrySnapshot))
            .withForgeRegistrySizes(forgeRegistrySnapshots)
}

internal fun forgeRemoteRegistrySnapshot(
    forgeRegistrySnapshots: Map<Identifier, ForgeRegistrySnapshot>,
): RemoteRegistrySnapshot = RemoteRegistrySnapshot(
    forgeRegistrySnapshots.map { (registryId, forgeRegistrySnapshot) ->
        forgeRegistrySnapshot.toRemoteRegistry(registryId)
    },
)

private fun ForgeRegistrySnapshot.toRemoteRegistry(
    registryId: Identifier,
): RemoteRegistry {
    val canonical = ids.keys
    val aliasesByTarget = linkedMapOf<Identifier, MutableSet<Identifier>>()
    aliases.keys.forEach { source ->
        if (source in canonical) return@forEach
        val target = resolveAliasTarget(registryId, source, canonical)
        aliasesByTarget.getOrPut(target, ::linkedSetOf).add(source)
    }
    return RemoteRegistry(
        registryId,
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
    registryId: Identifier,
    source: Identifier,
    canonical: Set<Identifier>,
): Identifier {
    val visited = linkedSetOf<Identifier>()
    var current = source
    while (current !in canonical) {
        if (!visited.add(current)) {
            throw IllegalArgumentException(
                "Forge registry $registryId contains an alias cycle through $current",
            )
        }
        current = aliases[current] ?: throw IllegalArgumentException(
            "Forge registry $registryId alias $source targets missing $current",
        )
    }
    return current
}

internal fun requireForgeCompatible(
    staticRegistrySchema: StaticRegistrySchema,
    remoteRegistrySnapshot: RemoteRegistrySnapshot,
): RemoteRegistrySnapshot {
    remoteRegistrySnapshot.registries.values.forEach { remoteRegistry ->
        val localEntries = staticRegistrySchema.registries[remoteRegistry.id]
        if (localEntries == null) {
            if (remoteRegistry.entries.isNotEmpty()) {
                throw ForgeNegotiationException(
                    "Forge server synchronized unknown registry ${remoteRegistry.id}",
                )
            }
            return@forEach
        }
        val local = localEntries.toSet()
        val missing = remoteRegistry.entries.filter { entry ->
            entry.id !in local && entry.aliases.none(local::contains)
        }
        if (missing.isNotEmpty()) {
            throw ForgeNegotiationException(
                "Forge registry ${remoteRegistry.id} contains entries absent from the local schema: ${
                    missing.map(
                        RemoteRegistryEntry::id
                    )
                }",
            )
        }
    }
    return remoteRegistrySnapshot
}

internal fun ProtocolRegistryContext.withForgeRegistrySizes(
    forgeRegistrySnapshots: Map<Identifier, ForgeRegistrySnapshot>,
): ProtocolRegistryContext {
    val registrySizes = forgeRegistrySnapshots.mapNotNull { (registryId, forgeRegistrySnapshot) ->
        forgeRegistrySnapshot.wireSize.takeIf { it > 0 }?.let { registryId to it }
    }.toMap()
    return if (registrySizes.isEmpty()) this else withRegistrySizes(registrySizes)
}
