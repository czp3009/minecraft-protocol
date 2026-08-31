package com.hiczp.minecraft.world.format.datapack

/** Enabled data-pack IDs that neither the world reader nor a caller-supplied source could provide. */
class UnresolvedDataPackIdsException(
    val unresolvedDataPackIds: List<DataPackId>,
) : IllegalArgumentException(
    "Unresolved enabled data packs: ${unresolvedDataPackIds.joinToString()}",
) {
    init {
        require(unresolvedDataPackIds.isNotEmpty()) {
            "An unresolved data-pack failure must identify at least one pack"
        }
    }
}

/**
 * Detached result of loading the file-backed members of one world's persisted data-pack selection.
 *
 * [enabledDataPackIds] retains the complete low-to-high-priority selection. [loadedDataPacks] contains the members
 * supplied by the reader in that priority order; an already ordered input list is retained, while an out-of-order list
 * is normalized because pack order is part of data-pack semantics. Core, built-in, and loader-defined members remain in
 * [unloadedEnabledDataPackIds] until [toDataPackStack] receives their owning source. This value contains no paths, open
 * resources, or filesystem behavior.
 */
class WorldDataPackLoadResult(
    val enabledDataPackIds: List<DataPackId>,
    loadedDataPacks: List<DataPack>,
    val disabledDataPackIds: List<DataPackId> = emptyList(),
    val enabledFeatureFlags: Set<String> = emptySet(),
    val removedFeatureFlags: Set<String> = emptySet(),
) {
    private val loadedDataPacksById: Map<DataPackId, DataPack> = loadedDataPacks
        .associateBy(DataPack::dataPackId)
        .also { loadedDataPacksById ->
            require(loadedDataPacksById.size == loadedDataPacks.size) {
                "Loaded data packs must have distinct IDs"
            }
        }
    val loadedDataPacks: List<DataPack> = if (loadedDataPacks.followEnabledOrder(enabledDataPackIds)) {
        loadedDataPacks
    } else {
        enabledDataPackIds.mapNotNull(loadedDataPacksById::get)
    }
    val unloadedEnabledDataPackIds: List<DataPackId> =
        enabledDataPackIds.filterNot(loadedDataPacksById::containsKey)

    init {
        require(enabledDataPackIds.distinct().size == enabledDataPackIds.size) {
            "Enabled data-pack IDs must be distinct"
        }
        require(disabledDataPackIds.distinct().size == disabledDataPackIds.size) {
            "Disabled data-pack IDs must be distinct"
        }
        require(loadedDataPacksById.keys.all(enabledDataPackIds::contains)) {
            "Every loaded data pack must belong to the enabled selection"
        }
    }

    /** Resolves unloaded members and constructs the complete stack without changing persisted priority order. */
    fun toDataPackStack(resolveUnloadedDataPack: (DataPackId) -> DataPack?): DataPackStack {
        val unresolvedDataPackIds = mutableListOf<DataPackId>()
        val dataPacks = enabledDataPackIds.mapNotNull { dataPackId ->
            loadedDataPacksById[dataPackId] ?: resolveUnloadedDataPack(dataPackId)?.also { dataPack ->
                require(dataPack.dataPackId == dataPackId) {
                    "Resolved data pack ${dataPack.dataPackId} does not match enabled ID $dataPackId"
                }
            } ?: run {
                unresolvedDataPackIds += dataPackId
                null
            }
        }
        if (unresolvedDataPackIds.isNotEmpty()) {
            throw UnresolvedDataPackIdsException(unresolvedDataPackIds)
        }
        return DataPackStack(dataPacks)
    }
}

private fun List<DataPack>.followEnabledOrder(enabledDataPackIds: List<DataPackId>): Boolean {
    var previousIndex = -1
    forEach { dataPack ->
        val index = enabledDataPackIds.indexOf(dataPack.dataPackId)
        if (index <= previousIndex) return false
        previousIndex = index
    }
    return true
}
