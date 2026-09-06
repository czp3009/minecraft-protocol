package com.hiczp.minecraft.protocol.model.type

/** Structured failure produced when a negotiated block mapping has no local state schema. */
class MissingStaticBlockSchemas(
    val blockIds: List<Identifier>,
) : IllegalArgumentException(
    "Missing local block-state schemas for ${blockIds.joinToString()}",
) {
    init {
        require(blockIds.isNotEmpty()) { "At least one missing block schema is required" }
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
        require(blocksById.size == blocks.size) {
            "Static block schemas contain duplicate identifiers"
        }
    }

    fun resolve(
        remoteRegistrySnapshot: RemoteRegistrySnapshot = RemoteRegistrySnapshot.Empty,
    ): PacketCodecContext {
        val resolvedRegistries = linkedMapOf<Identifier, RegistryIdMap>()
        val registryIds = LinkedHashSet<Identifier>().apply {
            addAll(registries.keys)
            addAll(remoteRegistrySnapshot.registries.keys)
        }
        registryIds.forEach { registryId ->
            val remoteRegistry = remoteRegistrySnapshot.registry(registryId)
            val entries = if (remoteRegistry == null) {
                registries[registryId].orEmpty().mapIndexed { rawId, id ->
                    RegistryIdMapping(id, rawId)
                }
            } else {
                remoteRegistry.entries
                    .filterNot(RemoteRegistryEntry::blocked)
                    .map { remoteRegistryEntry ->
                        RegistryIdMapping(
                            id = remoteRegistryEntry.overrideTarget ?: remoteRegistryEntry.id,
                            rawId = remoteRegistryEntry.rawId,
                            aliases = remoteRegistryEntry.aliases,
                        )
                    }
            }
            resolvedRegistries[registryId] = RegistryIdMap(registryId, entries)
        }

        val blockRegistry = resolvedRegistries[BLOCK_REGISTRY]
        val resolvedBlocks = blockRegistry?.entries
            ?.sortedBy(RegistryIdMapping::rawId)
            ?.map { registryIdMapping ->
                val staticBlockSchema =
                    blocksById[registryIdMapping.id] ?: registryIdMapping.aliases.firstNotNullOfOrNull(
                        blocksById::get
                    )
                registryIdMapping to staticBlockSchema
            }
            .orEmpty()
        val missingBlocks = resolvedBlocks.mapNotNull { (registryIdMapping, staticBlockSchema) ->
            if (staticBlockSchema == null) registryIdMapping.id else null
        }
        if (missingBlocks.isNotEmpty()) throw MissingStaticBlockSchemas(missingBlocks)
        val blockStates = resolvedBlocks
            .flatMap { (registryIdMapping, staticBlockSchema) ->
                requireNotNull(staticBlockSchema).states.map { staticBlockState ->
                    registryIdMapping.id to staticBlockState
                }
            }
            .mapIndexed { globalId, (block, staticBlockState) ->
                BlockStateIdMapping(
                    id = globalId,
                    block = block,
                    properties = staticBlockState.properties,
                    isDefault = staticBlockState.isDefault,
                )
            }

        return PacketCodecContext(
            registries = resolvedRegistries.values.toList(),
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
)

data class RemoteRegistry(
    val id: Identifier,
    val entries: List<RemoteRegistryEntry>,
)

/** Detached loader-provided mappings, kept distinct from the local static schema. */
class RemoteRegistrySnapshot(
    registries: List<RemoteRegistry>,
) {
    val registries: Map<Identifier, RemoteRegistry> = registries.associate { remoteRegistry ->
        remoteRegistry.id to remoteRegistry.copy(
            entries = remoteRegistry.entries.map { remoteRegistryEntry ->
                remoteRegistryEntry.copy(aliases = remoteRegistryEntry.aliases.toSet())
            },
        )
    }

    init {
        require(this.registries.size == registries.size) {
            "Remote registry snapshot contains duplicate registry identifiers"
        }
    }

    fun registry(id: Identifier): RemoteRegistry? = registries[id]

    override fun equals(other: Any?): Boolean =
        other is RemoteRegistrySnapshot && registries == other.registries

    override fun hashCode(): Int = registries.hashCode()

    override fun toString(): String = "RemoteRegistrySnapshot(registries=$registries)"

    companion object {
        val Empty: RemoteRegistrySnapshot = RemoteRegistrySnapshot(emptyList())
    }
}

data class RegistryIdMapping(
    val id: Identifier,
    val rawId: Int,
    val aliases: Set<Identifier> = emptySet(),
) {
    init {
        require(rawId >= 0) { "Protocol registry IDs must be non-negative" }
    }
}

data class RegistryIdMap(
    val id: Identifier,
    val entries: List<RegistryIdMapping>,
) {
    private val byRawId: Map<Int, RegistryIdMapping> = entries.associateBy(RegistryIdMapping::rawId)
    private val byIdentifier: Map<Identifier, RegistryIdMapping> =
        buildMap {
            this@RegistryIdMap.entries.forEach { registryIdMapping ->
                put(registryIdMapping.id, registryIdMapping)
                registryIdMapping.aliases.forEach { alias -> put(alias, registryIdMapping) }
            }
        }

    init {
        require(byRawId.size == entries.size) {
            "$id has duplicate resolved raw IDs"
        }
        val identifiers = entries.flatMap { registryIdMapping ->
            listOf(registryIdMapping.id) + registryIdMapping.aliases
        }
        require(identifiers.distinct().size == identifiers.size) {
            "$id has colliding resolved identifiers or aliases"
        }
    }

    val size: Int
        get() {
            val maximumRawId = entries.maxOfOrNull(RegistryIdMapping::rawId) ?: return 0
            check(maximumRawId < Int.MAX_VALUE) { "Protocol registry $id is too large to expose an Int size" }
            return maximumRawId + 1
        }

    operator fun get(rawId: Int): RegistryIdMapping? = byRawId[rawId]

    fun entry(id: Identifier): RegistryIdMapping? = byIdentifier[id]
}

data class BlockStateIdMapping(
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
class PacketCodecContext private constructor(
    val registries: Map<Identifier, RegistryIdMap>,
    val blockStates: List<BlockStateIdMapping>,
    val registrySizeOverrides: Map<Identifier, Int> = emptyMap(),
) {
    constructor(
        registries: List<RegistryIdMap>,
        blockStates: List<BlockStateIdMapping>,
        registrySizeOverrides: Map<Identifier, Int> = emptyMap(),
    ) : this(
        registries = indexRegistries(registries),
        blockStates = blockStates,
        registrySizeOverrides = registrySizeOverrides,
    )

    private val blockStatesById: Map<Int, BlockStateIdMapping> = blockStates.associateBy(BlockStateIdMapping::id)

    init {
        require(blockStatesById.size == blockStates.size) {
            "Protocol block-state IDs must be distinct"
        }
        require(registrySizeOverrides.values.all { it > 0 }) {
            "Registry size overrides must be positive"
        }
    }

    val blockStateRegistrySize: Int
        get() {
            val maximumBlockStateId = blockStates.maxOfOrNull(BlockStateIdMapping::id) ?: return 0
            check(maximumBlockStateId < Int.MAX_VALUE) {
                "The block-state registry is too large to expose an Int size"
            }
            return maximumBlockStateId + 1
        }

    val biomeRegistrySize: Int?
        get() = registrySize(BIOME_REGISTRY)

    fun registry(id: Identifier): RegistryIdMap? = registries[id]

    fun requireRegistry(id: Identifier): RegistryIdMap =
        registry(id) ?: throw IllegalArgumentException(
            "Protocol registry $id is not installed",
        )

    fun requireRegistryEntry(
        registry: Identifier,
        entry: Identifier,
    ): RegistryIdMapping = requireRegistry(registry).entry(entry)
        ?: throw IllegalArgumentException(
            "$entry is not present in protocol registry $registry",
        )

    fun blockState(id: Int): BlockStateIdMapping? = blockStatesById[id]

    fun blockStates(block: Identifier): List<BlockStateIdMapping> {
        val resolved = registry(StaticRegistrySchema.BLOCK_REGISTRY)
            ?.entry(block)
            ?.id
            ?: block
        return blockStates.filter { blockStateIdMapping -> blockStateIdMapping.block == resolved }
    }

    fun defaultBlockState(block: Identifier): BlockStateIdMapping? =
        blockStates(block).singleOrNull(BlockStateIdMapping::isDefault)

    fun requireDefaultBlockState(block: Identifier): BlockStateIdMapping =
        defaultBlockState(block) ?: throw IllegalArgumentException(
            "$block does not have exactly one default state in the installed block registry",
        )

    fun blockState(
        block: Identifier,
        properties: Map<String, String>,
    ): BlockStateIdMapping? = blockStates(block).firstOrNull { blockStateIdMapping ->
        blockStateIdMapping.properties == properties
    }

    fun registrySize(id: Identifier): Int? =
        registrySizeOverrides[id] ?: registries[id]?.size

    /**
     * Overlays complete raw-ID mappings while retaining the current block
     * states and size overrides by reference. Unchanged mappings reuse this context.
     */
    fun withRegistries(
        registries: List<RegistryIdMap>,
    ): PacketCodecContext {
        val additions = indexRegistries(registries)
        if (additions.all { (id, registryIdMap) -> this.registries[id] == registryIdMap }) return this
        return PacketCodecContext(
            registries = this.registries + additions,
            blockStates = blockStates,
            registrySizeOverrides = registrySizeOverrides,
        )
    }

    /**
     * Installs a resolved static/loader mapping without discarding unrelated
     * dynamic registries already synchronized during Configuration.
     */
    fun withStaticRegistryResolution(
        resolvedStaticRegistryContext: PacketCodecContext,
    ): PacketCodecContext = PacketCodecContext(
        registries = registries + resolvedStaticRegistryContext.registries,
        blockStates = resolvedStaticRegistryContext.blockStates,
        registrySizeOverrides = registrySizeOverrides + resolvedStaticRegistryContext.registrySizeOverrides,
    )

    fun withRegistrySize(id: Identifier, size: Int): PacketCodecContext {
        return withRegistrySizes(mapOf(id to size))
    }

    fun withRegistrySizes(
        sizes: Map<Identifier, Int>,
    ): PacketCodecContext {
        return PacketCodecContext(
            registries = registries,
            blockStates = blockStates,
            registrySizeOverrides = registrySizeOverrides + sizes,
        )
    }

    override fun equals(other: Any?): Boolean =
        other is PacketCodecContext &&
                registries == other.registries &&
                blockStates == other.blockStates &&
                registrySizeOverrides == other.registrySizeOverrides

    override fun hashCode(): Int {
        var result = registries.hashCode()
        result = 31 * result + blockStates.hashCode()
        result = 31 * result + registrySizeOverrides.hashCode()
        return result
    }

    override fun toString(): String =
        "PacketCodecContext(registries=$registries, blockStates=$blockStates, registrySizeOverrides=$registrySizeOverrides)"

    companion object {
        val Empty: PacketCodecContext = PacketCodecContext(
            emptyList(),
            emptyList(),
        )

        val BIOME_REGISTRY: Identifier = Identifier("worldgen/biome")

        val ENTITY_TYPE_REGISTRY: Identifier = Identifier("entity_type")

        private fun indexRegistries(
            registries: List<RegistryIdMap>,
        ): Map<Identifier, RegistryIdMap> {
            val registriesById = registries.associateBy(RegistryIdMap::id)
            require(registriesById.size == registries.size) {
                "Protocol registry context contains duplicate registry identifiers"
            }
            return registriesById
        }
    }
}
