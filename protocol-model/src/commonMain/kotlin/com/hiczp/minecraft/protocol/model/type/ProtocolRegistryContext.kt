package com.hiczp.minecraft.protocol.model.type

/** Structured failure produced when a negotiated block mapping has no local state schema. */
class MissingStaticBlockSchemas(
    blockIds: List<Identifier>,
) : IllegalArgumentException(
    "Missing local block-state schemas for ${blockIds.joinToString()}",
) {
    val blockIds: List<Identifier> = blockIds.toList()

    init {
        require(this.blockIds.isNotEmpty()) { "At least one missing block schema is required" }
        require(this.blockIds.distinct().size == this.blockIds.size) { "Missing block schema IDs must be distinct" }
    }
}

/** One locally known state of a block, in its protocol iteration order. */
class StaticBlockState(
    properties: Map<String, String>,
    val isDefault: Boolean,
) {
    val properties: Map<String, String> = properties.toMap()

    override fun equals(other: Any?): Boolean =
        other is StaticBlockState &&
                properties == other.properties &&
                isDefault == other.isDefault

    override fun hashCode(): Int = 31 * properties.hashCode() + isDefault.hashCode()

    override fun toString(): String =
        "StaticBlockState(properties=$properties, isDefault=$isDefault)"
}

/** Complete local state schema for one block. */
class StaticBlockSchema(
    val id: Identifier,
    states: List<StaticBlockState>,
) {
    val states: List<StaticBlockState> = states.toList()

    init {
        require(this.states.isNotEmpty()) { "$id has no block states" }
        require(this.states.distinctBy(StaticBlockState::properties).size == this.states.size) {
            "$id has duplicate block-state property combinations"
        }
        require(this.states.count(StaticBlockState::isDefault) == 1) {
            "$id must have exactly one default block state"
        }
    }

    val defaultState: StaticBlockState
        get() = states.single(StaticBlockState::isDefault)

    override fun equals(other: Any?): Boolean =
        other is StaticBlockSchema && id == other.id && states == other.states

    override fun hashCode(): Int = 31 * id.hashCode() + states.hashCode()

    override fun toString(): String = "StaticBlockSchema(id=$id, states=$states)"
}

/**
 * Client- or server-known static registry order and complete block schemas.
 * All supplied collections are snapshotted during construction.
 */
class StaticRegistrySchema(
    registries: Map<Identifier, List<Identifier>>,
    blocks: List<StaticBlockSchema>,
) {
    val registries: Map<Identifier, List<Identifier>> =
        registries.entries.associate { (id, entries) -> id to entries.toList() }
    val blocks: List<StaticBlockSchema> = blocks.toList()

    private val blocksById: Map<Identifier, StaticBlockSchema> = this.blocks.associateBy(StaticBlockSchema::id)

    init {
        this.registries.forEach { (id, entries) ->
            require(entries.distinct().size == entries.size) {
                "$id has duplicate static registry entries"
            }
        }
        require(blocksById.size == this.blocks.size) {
            "Static block schemas contain duplicate identifiers"
        }
        this.registries[BLOCK_REGISTRY]?.let { blockEntries ->
            require(blockEntries.all(blocksById::containsKey)) {
                "The block registry contains an entry without a local state schema"
            }
        }
    }

    fun resolve(
        remote: RemoteRegistrySnapshot = RemoteRegistrySnapshot.Empty,
    ): ProtocolRegistryContext {
        val resolvedRegistries = linkedMapOf<Identifier, ProtocolRegistry>()
        val registryIds = LinkedHashSet<Identifier>().apply {
            addAll(registries.keys)
            addAll(remote.registries.keys)
        }
        registryIds.forEach { registryId ->
            val remoteRegistry = remote.registry(registryId)
            val entries = if (remoteRegistry == null) {
                registries[registryId].orEmpty().mapIndexed { rawId, id ->
                    ProtocolRegistryEntry(id, rawId)
                }
            } else {
                remoteRegistry.entries
                    .filterNot(RemoteRegistryEntry::blocked)
                    .map { entry ->
                        ProtocolRegistryEntry(
                            id = entry.overrideTarget ?: entry.id,
                            rawId = entry.rawId,
                            aliases = entry.aliases,
                        )
                    }
            }
            resolvedRegistries[registryId] = ProtocolRegistry(registryId, entries)
        }

        val blockRegistry = resolvedRegistries[BLOCK_REGISTRY]
        val resolvedBlocks = blockRegistry?.entries
            ?.sortedBy(ProtocolRegistryEntry::rawId)
            ?.map { entry ->
                entry to (blocksById[entry.id] ?: entry.aliases.firstNotNullOfOrNull(blocksById::get))
            }
            .orEmpty()
        val missingBlocks = resolvedBlocks.mapNotNull { (entry, schema) ->
            if (schema == null) entry.id else null
        }
        if (missingBlocks.isNotEmpty()) throw MissingStaticBlockSchemas(missingBlocks)
        val blockStates = resolvedBlocks
            .flatMap { (entry, schema) ->
                requireNotNull(schema).states.map { state -> entry.id to state }
            }
            .mapIndexed { globalId, (block, state) ->
                ProtocolBlockState(
                    id = globalId,
                    block = block,
                    properties = state.properties,
                    isDefault = state.isDefault,
                )
            }

        return ProtocolRegistryContext(
            registries = resolvedRegistries.values.toList(),
            blockStates = blockStates,
        )
    }

    override fun equals(other: Any?): Boolean =
        other is StaticRegistrySchema &&
                registries == other.registries &&
                blocks == other.blocks

    override fun hashCode(): Int = 31 * registries.hashCode() + blocks.hashCode()

    override fun toString(): String =
        "StaticRegistrySchema(registries=$registries, blocks=$blocks)"

    companion object {
        val BLOCK_REGISTRY: Identifier = Identifier("block")

        val Empty: StaticRegistrySchema = StaticRegistrySchema(emptyMap(), emptyList())
    }
}

/** One wire-provided raw-ID mapping entry from a loader protocol. */
class RemoteRegistryEntry(
    val id: Identifier,
    val rawId: Int,
    aliases: Set<Identifier> = emptySet(),
    val overrideTarget: Identifier? = null,
    val blocked: Boolean = false,
) {
    val aliases: Set<Identifier> = aliases.toSet()

    init {
        require(rawId >= 0) { "Remote registry IDs must be non-negative" }
    }

    override fun equals(other: Any?): Boolean =
        other is RemoteRegistryEntry &&
                id == other.id &&
                rawId == other.rawId &&
                aliases == other.aliases &&
                overrideTarget == other.overrideTarget &&
                blocked == other.blocked

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + rawId
        result = 31 * result + aliases.hashCode()
        result = 31 * result + (overrideTarget?.hashCode() ?: 0)
        return 31 * result + blocked.hashCode()
    }

    override fun toString(): String =
        "RemoteRegistryEntry(id=$id, rawId=$rawId, aliases=$aliases, overrideTarget=$overrideTarget, blocked=$blocked)"
}

class RemoteRegistry(
    val id: Identifier,
    entries: List<RemoteRegistryEntry>,
) {
    val entries: List<RemoteRegistryEntry> = entries.toList()

    init {
        require(this.entries.map(RemoteRegistryEntry::rawId).distinct().size == this.entries.size) {
            "$id has duplicate remote raw IDs"
        }
        require(this.entries.map(RemoteRegistryEntry::id).distinct().size == this.entries.size) {
            "$id has duplicate remote identifiers"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is RemoteRegistry && id == other.id && entries == other.entries

    override fun hashCode(): Int = 31 * id.hashCode() + entries.hashCode()

    override fun toString(): String = "RemoteRegistry(id=$id, entries=$entries)"
}

/** Loader-provided mappings, kept distinct from the local static schema. */
class RemoteRegistrySnapshot(
    registries: List<RemoteRegistry>,
) {
    val registries: Map<Identifier, RemoteRegistry> = registries.associateBy(RemoteRegistry::id)

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

class ProtocolRegistryEntry(
    val id: Identifier,
    val rawId: Int,
    aliases: Set<Identifier> = emptySet(),
) {
    val aliases: Set<Identifier> = aliases.toSet()

    init {
        require(rawId >= 0) { "Protocol registry IDs must be non-negative" }
    }

    override fun equals(other: Any?): Boolean =
        other is ProtocolRegistryEntry &&
                id == other.id &&
                rawId == other.rawId &&
                aliases == other.aliases

    override fun hashCode(): Int =
        31 * (31 * id.hashCode() + rawId) + aliases.hashCode()

    override fun toString(): String =
        "ProtocolRegistryEntry(id=$id, rawId=$rawId, aliases=$aliases)"
}

class ProtocolRegistry(
    val id: Identifier,
    entries: List<ProtocolRegistryEntry>,
) {
    val entries: List<ProtocolRegistryEntry> = entries.toList()

    private val byRawId: Map<Int, ProtocolRegistryEntry> = this.entries.associateBy(ProtocolRegistryEntry::rawId)
    private val byIdentifier: Map<Identifier, ProtocolRegistryEntry> =
        buildMap {
            this@ProtocolRegistry.entries.forEach { entry ->
                put(entry.id, entry)
                entry.aliases.forEach { alias -> put(alias, entry) }
            }
        }

    init {
        require(byRawId.size == this.entries.size) {
            "$id has duplicate resolved raw IDs"
        }
        val identifiers = this.entries.flatMap { entry ->
            listOf(entry.id) + entry.aliases
        }
        require(identifiers.distinct().size == identifiers.size) {
            "$id has colliding resolved identifiers or aliases"
        }
    }

    val size: Int
        get() = (entries.maxOfOrNull(ProtocolRegistryEntry::rawId) ?: -1) + 1

    operator fun get(rawId: Int): ProtocolRegistryEntry? = byRawId[rawId]

    fun entry(id: Identifier): ProtocolRegistryEntry? = byIdentifier[id]

    override fun equals(other: Any?): Boolean =
        other is ProtocolRegistry && id == other.id && entries == other.entries

    override fun hashCode(): Int = 31 * id.hashCode() + entries.hashCode()

    override fun toString(): String = "ProtocolRegistry(id=$id, entries=$entries)"
}

class ProtocolBlockState(
    val id: Int,
    val block: Identifier,
    properties: Map<String, String>,
    val isDefault: Boolean,
) {
    val properties: Map<String, String> = properties.toMap()

    init {
        require(id >= 0) { "Block-state IDs must be non-negative" }
    }

    override fun equals(other: Any?): Boolean =
        other is ProtocolBlockState &&
                id == other.id &&
                block == other.block &&
                properties == other.properties &&
                isDefault == other.isDefault

    override fun hashCode(): Int {
        var result = id
        result = 31 * result + block.hashCode()
        result = 31 * result + properties.hashCode()
        return 31 * result + isDefault.hashCode()
    }

    override fun toString(): String =
        "ProtocolBlockState(id=$id, block=$block, properties=$properties, isDefault=$isDefault)"
}

/** Immutable registry view used by one connection's physical codecs. */
class ProtocolRegistryContext private constructor(
    val registries: Map<Identifier, ProtocolRegistry>,
    val blockStates: List<ProtocolBlockState>,
    val registrySizeOverrides: Map<Identifier, Int>,
    val chunkSectionCount: Int? = null,
) {
    constructor(
        registries: List<ProtocolRegistry>,
        blockStates: List<ProtocolBlockState>,
        registrySizeOverrides: Map<Identifier, Int> = emptyMap(),
        chunkSectionCount: Int? = null,
    ) : this(
        registries = snapshotRegistries(registries),
        blockStates = blockStates.toList(),
        registrySizeOverrides = registrySizeOverrides.toMap(),
        chunkSectionCount = chunkSectionCount,
    )

    init {
        require(registries.all { (id, registry) -> id == registry.id }) {
            "Protocol registry context keys must match their registry identifiers"
        }
        require(blockStates.withIndex().all { (index, state) -> state.id == index }) {
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
        return blockStates.filter { state -> state.block == resolved }
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
    ): ProtocolBlockState? = blockStates(block).firstOrNull { state ->
        state.properties == properties
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
        val additions = snapshotRegistries(registries)
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
        resolution: ProtocolRegistryContext,
    ): ProtocolRegistryContext = ProtocolRegistryContext(
        registries = registries + resolution.registries,
        blockStates = resolution.blockStates,
        registrySizeOverrides = registrySizeOverrides + resolution.registrySizeOverrides,
        chunkSectionCount = resolution.chunkSectionCount ?: chunkSectionCount,
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

    override fun equals(other: Any?): Boolean =
        other is ProtocolRegistryContext &&
                registries == other.registries &&
                blockStates == other.blockStates &&
                registrySizeOverrides == other.registrySizeOverrides &&
                chunkSectionCount == other.chunkSectionCount

    override fun hashCode(): Int {
        var result = registries.hashCode()
        result = 31 * result + blockStates.hashCode()
        result = 31 * result + registrySizeOverrides.hashCode()
        return 31 * result + (chunkSectionCount ?: 0)
    }

    override fun toString(): String = buildString {
        append("ProtocolRegistryContext(registries=$registries, blockStates=$blockStates, ")
        append("registrySizeOverrides=$registrySizeOverrides, chunkSectionCount=$chunkSectionCount)")
    }

    companion object {
        val Empty: ProtocolRegistryContext = ProtocolRegistryContext(
            emptyList(),
            emptyList(),
        )

        val BIOME_REGISTRY: Identifier = Identifier("worldgen/biome")

        val ENTITY_TYPE_REGISTRY: Identifier = Identifier("entity_type")

        private fun snapshotRegistries(
            registries: List<ProtocolRegistry>,
        ): Map<Identifier, ProtocolRegistry> {
            val snapshot = registries.associateBy(ProtocolRegistry::id)
            require(snapshot.size == registries.size) {
                "Protocol registry context contains duplicate registry identifiers"
            }
            return snapshot
        }
    }
}
