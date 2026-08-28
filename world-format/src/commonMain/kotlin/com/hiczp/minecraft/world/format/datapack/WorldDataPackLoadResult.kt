package com.hiczp.minecraft.world.format.datapack

/** Enabled data-pack IDs that neither the world reader nor a caller-supplied source could provide. */
class UnresolvedDataPackIdsException(
    unresolvedDataPackIds: List<DataPackId>,
) : IllegalArgumentException(
    "Unresolved enabled data packs: ${unresolvedDataPackIds.joinToString()}",
) {
    val unresolvedDataPackIds: List<DataPackId> = unresolvedDataPackIds.toList()

    init {
        require(this.unresolvedDataPackIds.isNotEmpty()) {
            "An unresolved data-pack failure must identify at least one pack"
        }
    }
}

/**
 * Detached result of loading the file-backed members of one world's persisted data-pack selection.
 *
 * [enabledDataPackIds] retains the complete low-to-high-priority selection. [loadedDataPacks] contains the members
 * supplied by the reader, normally the world's `file/...` packs; core, built-in, and loader-defined members remain in
 * [unloadedEnabledDataPackIds] until [toDataPackStack] receives their owning source. This value contains no paths, open
 * resources, or filesystem behavior.
 */
class WorldDataPackLoadResult(
    enabledDataPackIds: List<DataPackId>,
    loadedDataPacks: List<DataPack>,
    disabledDataPackIds: List<DataPackId> = emptyList(),
    enabledFeatureFlags: Set<String> = emptySet(),
    removedFeatureFlags: Set<String> = emptySet(),
) {
    val enabledDataPackIds: List<DataPackId> = enabledDataPackIds.toList()
    val disabledDataPackIds: List<DataPackId> = disabledDataPackIds.toList()
    val enabledFeatureFlags: Set<String> = enabledFeatureFlags.toSet()
    val removedFeatureFlags: Set<String> = removedFeatureFlags.toSet()
    private val loadedDataPacksById: Map<DataPackId, DataPack> = loadedDataPacks
        .associateBy(DataPack::dataPackId)
        .also { loadedDataPacksById ->
            require(loadedDataPacksById.size == loadedDataPacks.size) {
                "Loaded data packs must have distinct IDs"
            }
        }
    val loadedDataPacks: List<DataPack> = this.enabledDataPackIds.mapNotNull(loadedDataPacksById::get)
    val unloadedEnabledDataPackIds: List<DataPackId> =
        this.enabledDataPackIds.filterNot(loadedDataPacksById::containsKey)

    init {
        require(this.enabledDataPackIds.distinct().size == this.enabledDataPackIds.size) {
            "Enabled data-pack IDs must be distinct"
        }
        require(this.disabledDataPackIds.distinct().size == this.disabledDataPackIds.size) {
            "Disabled data-pack IDs must be distinct"
        }
        require(loadedDataPacksById.keys.all(this.enabledDataPackIds::contains)) {
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
