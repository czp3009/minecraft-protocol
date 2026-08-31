package com.hiczp.minecraft.protocol.model.type

/** Structured failure produced when a negotiated block mapping has no local state schema. */
class MissingStaticBlockSchemas(
    val blockIds: List<Identifier>,
) : IllegalArgumentException(
    "Missing local block-state schemas for ${blockIds.joinToString()}",
) {
    init {
        require(blockIds.isNotEmpty()) { "At least one missing block schema is required" }
        require(blockIds.distinct().size == blockIds.size) { "Missing block schema IDs must be distinct" }
    }
}

/** One locally known state of a block, in its protocol iteration order. */
data class StaticBlockState(
    val properties: Map<String, String>,
    val isDefault: Boolean,
)

/** Complete local state schema for one block. */
data class StaticBlockSchema(
    val id: Identifier,
    val states: List<StaticBlockState>,
) {
    init {
        require(states.isNotEmpty()) { "$id has no block states" }
        require(states.distinctBy(StaticBlockState::properties).size == states.size) {
            "$id has duplicate block-state property combinations"
        }
        require(states.count(StaticBlockState::isDefault) == 1) {
            "$id must have exactly one default block state"
        }
    }

    val defaultState: StaticBlockState
        get() = states.single(StaticBlockState::isDefault)
}

/** Client- or server-known static registry order and complete block schemas. */
data class StaticRegistrySchema(
    val registries: Map<Identifier, List<Identifier>>,
    val blocks: List<StaticBlockSchema>,
) {
    private val blocksById: Map<Identifier, StaticBlockSchema> = blocks.associateBy(StaticBlockSchema::id)

    init {
        registries.forEach { (id, entries) ->
            require(entries.distinct().size == entries.size) {
                "$id has duplicate static registry entries"
            }
        }
        require(blocksById.size == blocks.size) {
            "Static block schemas contain duplicate identifiers"
        }
        registries[BLOCK_REGISTRY]?.let { blockEntries ->
            require(blockEntries.all(blocksById::containsKey)) {
                "The block registry contains an entry without a local state schema"
            }
        }
    }

    fun resolve(
        remoteRegistrySnapshot: RemoteRegistrySnapshot = RemoteRegistrySnapshot.Empty,
    ): ProtocolRegistryContext {
        val resolvedRegistries = linkedMapOf<Identifier, ProtocolRegistry>()
        val registryIds = LinkedHashSet<Identifier>().apply {
            addAll(registries.keys)
            addAll(remoteRegistrySnapshot.registries.keys)
        }
        registryIds.forEach { registryId ->
            val remoteRegistry = remoteRegistrySnapshot.registry(registryId)
            val entries = if (remoteRegistry == null) {
                registries[registryId].orEmpty().mapIndexed { rawId, id ->
                    ProtocolRegistryEntry(id, rawId)
                }
            } else {
                remoteRegistry.entries
                    .filterNot(RemoteRegistryEntry::blocked)
                    .map { remoteRegistryEntry ->
                        ProtocolRegistryEntry(
                            id = remoteRegistryEntry.overrideTarget ?: remoteRegistryEntry.id,
                            rawId = remoteRegistryEntry.rawId,
                            aliases = remoteRegistryEntry.aliases,
                        )
                    }
            }
            resolvedRegistries[registryId] = ProtocolRegistry(registryId, entries)
        }

        val blockRegistry = resolvedRegistries[BLOCK_REGISTRY]
        val resolvedBlocks = blockRegistry?.entries
            ?.sortedBy(ProtocolRegistryEntry::rawId)
            ?.map { protocolRegistryEntry ->
                val staticBlockSchema =
                    blocksById[protocolRegistryEntry.id] ?: protocolRegistryEntry.aliases.firstNotNullOfOrNull(
                        blocksById::get
                    )
                protocolRegistryEntry to staticBlockSchema
            }
            .orEmpty()
        val missingBlocks = resolvedBlocks.mapNotNull { (protocolRegistryEntry, staticBlockSchema) ->
            if (staticBlockSchema == null) protocolRegistryEntry.id else null
        }
        if (missingBlocks.isNotEmpty()) throw MissingStaticBlockSchemas(missingBlocks)
        val blockStates = resolvedBlocks
            .flatMap { (protocolRegistryEntry, staticBlockSchema) ->
                requireNotNull(staticBlockSchema).states.map { staticBlockState ->
                    protocolRegistryEntry.id to staticBlockState
                }
            }
            .mapIndexed { globalId, (block, staticBlockState) ->
                ProtocolBlockState(
                    id = globalId,
                    block = block,
                    properties = staticBlockState.properties,
                    isDefault = staticBlockState.isDefault,
                )
            }

        return ProtocolRegistryContext(
            registries = resolvedRegistries,
            blockStates = blockStates,
        )
    }

    companion object {
        val BLOCK_REGISTRY: Identifier = Identifier("block")

        val Empty: StaticRegistrySchema = StaticRegistrySchema(emptyMap(), emptyList())
    }
}

/** One wire-provided raw-ID mapping entry from a loader protocol. */
data class RemoteRegistryEntry(
    val id: Identifier,
    val rawId: Int,
    val aliases: Set<Identifier> = emptySet(),
    val overrideTarget: Identifier? = null,
    val blocked: Boolean = false,
) {
    init {
        require(rawId >= 0) { "Remote registry IDs must be non-negative" }
    }
}

data class RemoteRegistry(
    val id: Identifier,
    val entries: List<RemoteRegistryEntry>,
) {
    init {
        require(entries.map(RemoteRegistryEntry::rawId).distinct().size == entries.size) {
            "$id has duplicate remote raw IDs"
        }
        require(entries.map(RemoteRegistryEntry::id).distinct().size == entries.size) {
            "$id has duplicate remote identifiers"
        }
    }
}

/** Detached loader-provided mappings, kept distinct from the local static schema. */
class RemoteRegistrySnapshot(
    registries: Map<Identifier, RemoteRegistry>,
) {
    val registries: Map<Identifier, RemoteRegistry> = registries.mapValues { (_, remoteRegistry) ->
        remoteRegistry.copy(
            entries = remoteRegistry.entries.map { remoteRegistryEntry ->
                remoteRegistryEntry.copy(aliases = remoteRegistryEntry.aliases.toSet())
            },
        )
    }

    constructor(registries: List<RemoteRegistry>) : this(registries.associateBy(RemoteRegistry::id)) {
        require(this.registries.size == registries.size) {
            "Remote registry snapshot contains duplicate registry identifiers"
        }
    }

    init {
        require(registries.all { (id, remoteRegistry) -> id == remoteRegistry.id }) {
            "Remote registry snapshot keys must match their registry identifiers"
        }
    }

    fun registry(id: Identifier): RemoteRegistry? = registries[id]

    override fun equals(other: Any?): Boolean =
        other is RemoteRegistrySnapshot && registries == other.registries

    override fun hashCode(): Int = registries.hashCode()

    override fun toString(): String = "RemoteRegistrySnapshot(registries=$registries)"

    companion object {
        val Empty: RemoteRegistrySnapshot = RemoteRegistrySnapshot(emptyMap())
    }
}

data class ProtocolRegistryEntry(
    val id: Identifier,
    val rawId: Int,
    val aliases: Set<Identifier> = emptySet(),
) {
    init {
        require(rawId >= 0) { "Protocol registry IDs must be non-negative" }
    }
}

data class ProtocolRegistry(
    val id: Identifier,
    val entries: List<ProtocolRegistryEntry>,
) {
    private val byRawId: Map<Int, ProtocolRegistryEntry> = entries.associateBy(ProtocolRegistryEntry::rawId)
    private val byIdentifier: Map<Identifier, ProtocolRegistryEntry> =
        buildMap {
            this@ProtocolRegistry.entries.forEach { protocolRegistryEntry ->
                put(protocolRegistryEntry.id, protocolRegistryEntry)
                protocolRegistryEntry.aliases.forEach { alias -> put(alias, protocolRegistryEntry) }
            }
        }

    init {
        require(byRawId.size == entries.size) {
            "$id has duplicate resolved raw IDs"
        }
        val identifiers = entries.flatMap { protocolRegistryEntry ->
            listOf(protocolRegistryEntry.id) + protocolRegistryEntry.aliases
        }
        require(identifiers.distinct().size == identifiers.size) {
            "$id has colliding resolved identifiers or aliases"
        }
    }

    val size: Int
        get() = (entries.maxOfOrNull(ProtocolRegistryEntry::rawId) ?: -1) + 1

    operator fun get(rawId: Int): ProtocolRegistryEntry? = byRawId[rawId]

    fun entry(id: Identifier): ProtocolRegistryEntry? = byIdentifier[id]
}

data class ProtocolBlockState(
    val id: Int,
    val block: Identifier,
    val properties: Map<String, String>,
    val isDefault: Boolean,
) {
    init {
        require(id >= 0) { "Block-state IDs must be non-negative" }
    }
}

/** Registry view used by one connection's physical codecs. */
data class ProtocolRegistryContext(
    val registries: Map<Identifier, ProtocolRegistry>,
    val blockStates: List<ProtocolBlockState>,
    val registrySizeOverrides: Map<Identifier, Int> = emptyMap(),
    val chunkSectionCount: Int? = null,
) {
    constructor(
        registries: List<ProtocolRegistry>,
        blockStates: List<ProtocolBlockState>,
        registrySizeOverrides: Map<Identifier, Int> = emptyMap(),
        chunkSectionCount: Int? = null,
    ) : this(
        registries = indexRegistries(registries),
        blockStates = blockStates,
        registrySizeOverrides = registrySizeOverrides,
        chunkSectionCount = chunkSectionCount,
    )

    init {
        require(registries.all { (id, protocolRegistry) -> id == protocolRegistry.id }) {
            "Protocol registry context keys must match their registry identifiers"
        }
        require(blockStates.withIndex().all { (index, protocolBlockState) -> protocolBlockState.id == index }) {
            "Protocol block-state IDs must be contiguous and ordered"
        }
        require(registrySizeOverrides.values.all { it > 0 }) {
            "Registry size overrides must be positive"
        }
        require(chunkSectionCount == null || chunkSectionCount >= 0) {
            "Chunk section count must be non-negative"
        }
    }

    val blockStateRegistrySize: Int
        get() = blockStates.size

    val biomeRegistrySize: Int?
        get() = registrySize(BIOME_REGISTRY)

    fun registry(id: Identifier): ProtocolRegistry? = registries[id]

    fun requireRegistry(id: Identifier): ProtocolRegistry =
        registry(id) ?: throw IllegalArgumentException(
            "Protocol registry $id is not installed",
        )

    fun requireRegistryEntry(
        registry: Identifier,
        entry: Identifier,
    ): ProtocolRegistryEntry = requireRegistry(registry).entry(entry)
        ?: throw IllegalArgumentException(
            "$entry is not present in protocol registry $registry",
        )

    fun blockStates(block: Identifier): List<ProtocolBlockState> {
        val resolved = registry(StaticRegistrySchema.BLOCK_REGISTRY)
            ?.entry(block)
            ?.id
            ?: block
        return blockStates.filter { protocolBlockState -> protocolBlockState.block == resolved }
    }

    fun defaultBlockState(block: Identifier): ProtocolBlockState? =
        blockStates(block).singleOrNull(ProtocolBlockState::isDefault)

    fun requireDefaultBlockState(block: Identifier): ProtocolBlockState =
        defaultBlockState(block) ?: throw IllegalArgumentException(
            "$block does not have exactly one default state in the installed block registry",
        )

    fun blockState(
        block: Identifier,
        properties: Map<String, String>,
    ): ProtocolBlockState? = blockStates(block).firstOrNull { protocolBlockState ->
        protocolBlockState.properties == properties
    }

    fun registrySize(id: Identifier): Int? =
        registrySizeOverrides[id] ?: registries[id]?.size

    /**
     * Overlays complete raw-ID mappings while retaining the current block
     * states, size overrides, and active-dimension data by reference.
     */
    fun withRegistries(
        registries: List<ProtocolRegistry>,
    ): ProtocolRegistryContext {
        val additions = indexRegistries(registries)
        return ProtocolRegistryContext(
            registries = this.registries + additions,
            blockStates = blockStates,
            registrySizeOverrides = registrySizeOverrides,
            chunkSectionCount = chunkSectionCount,
        )
    }

    /**
     * Installs a resolved static/loader mapping without discarding unrelated
     * dynamic registries already synchronized during Configuration.
     */
    fun withStaticRegistryResolution(
        resolvedStaticRegistryContext: ProtocolRegistryContext,
    ): ProtocolRegistryContext = ProtocolRegistryContext(
        registries = registries + resolvedStaticRegistryContext.registries,
        blockStates = resolvedStaticRegistryContext.blockStates,
        registrySizeOverrides = registrySizeOverrides + resolvedStaticRegistryContext.registrySizeOverrides,
        chunkSectionCount = resolvedStaticRegistryContext.chunkSectionCount ?: chunkSectionCount,
    )

    fun withRegistrySize(id: Identifier, size: Int): ProtocolRegistryContext {
        return withRegistrySizes(mapOf(id to size))
    }

    fun withRegistrySizes(
        sizes: Map<Identifier, Int>,
    ): ProtocolRegistryContext {
        require(sizes.values.all { it > 0 }) {
            "Registry sizes must be positive"
        }
        return ProtocolRegistryContext(
            registries = registries,
            blockStates = blockStates,
            registrySizeOverrides = registrySizeOverrides + sizes,
            chunkSectionCount = chunkSectionCount,
        )
    }

    fun withChunkSectionCount(sectionCount: Int): ProtocolRegistryContext =
        ProtocolRegistryContext(
            registries = registries,
            blockStates = blockStates,
            registrySizeOverrides = registrySizeOverrides,
            chunkSectionCount = sectionCount,
        )

    companion object {
        val Empty: ProtocolRegistryContext = ProtocolRegistryContext(
            emptyList(),
            emptyList(),
        )

        val BIOME_REGISTRY: Identifier = Identifier("worldgen/biome")

        val ENTITY_TYPE_REGISTRY: Identifier = Identifier("entity_type")

        private fun indexRegistries(
            registries: List<ProtocolRegistry>,
        ): Map<Identifier, ProtocolRegistry> {
            val registriesById = registries.associateBy(ProtocolRegistry::id)
            require(registriesById.size == registries.size) {
                "Protocol registry context contains duplicate registry identifiers"
            }
            return registriesById
        }
    }
}
