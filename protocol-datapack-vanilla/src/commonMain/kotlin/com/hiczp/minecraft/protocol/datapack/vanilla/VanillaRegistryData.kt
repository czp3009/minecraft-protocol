@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.datapack.vanilla

import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.io.Buffer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.io.decodeFromSource
import kotlin.io.encoding.Base64

/**
 * A vanilla static registry whose list position is its protocol ID.
 */
data class VanillaRegistry(
    val registryId: Identifier,
    val registryEntryIds: List<Identifier>,
) {
    init {
        require(registryEntryIds.distinct().size == registryEntryIds.size) {
            "$registryId contains duplicate vanilla registry entries"
        }
    }

    private val rawIdsByRegistryEntryId: Map<Identifier, Int> =
        this.registryEntryIds.withIndex().associate { (rawId, registryEntryId) -> registryEntryId to rawId }

    val size: Int
        get() = registryEntryIds.size

    operator fun get(rawId: Int): Identifier? =
        registryEntryIds.getOrNull(rawId)

    fun rawId(registryEntryId: Identifier): Int? = rawIdsByRegistryEntryId[registryEntryId]

    fun requireRawId(registryEntryId: Identifier): Int =
        rawId(registryEntryId)
            ?: error("$registryEntryId is not present in the $registryId vanilla registry")
}

/**
 * One entry in the global vanilla block-state palette.
 */
data class VanillaBlockState(
    val rawId: Int,
    val blockId: Identifier,
    val properties: Map<String, String>,
    val isDefault: Boolean,
)

data class VanillaBlockStateRegistry(
    val vanillaBlockStates: List<VanillaBlockState>,
) {
    init {
        require(vanillaBlockStates.withIndex().all { (rawId, vanillaBlockState) ->
            vanillaBlockState.rawId == rawId
        }) {
            "Vanilla block-state IDs must be contiguous and ordered"
        }
        require(
            vanillaBlockStates
                .groupBy(VanillaBlockState::blockId)
                .values
                .all { blockStates ->
                    blockStates.map(VanillaBlockState::properties)
                        .distinct()
                        .size == blockStates.size
                },
        ) {
            "A vanilla block has duplicate property combinations"
        }
    }

    private val vanillaBlockStatesByBlockId: Map<Identifier, List<VanillaBlockState>> =
        this.vanillaBlockStates.groupBy(VanillaBlockState::blockId)
    private val defaultBlockStatesByBlockId: Map<Identifier, VanillaBlockState> =
        vanillaBlockStatesByBlockId.mapValues { (blockId, blockStates) ->
            blockStates.singleOrNull(VanillaBlockState::isDefault)
                ?: error("$blockId does not have exactly one default state")
        }

    val size: Int
        get() = vanillaBlockStates.size

    operator fun get(rawId: Int): VanillaBlockState? =
        vanillaBlockStates.getOrNull(rawId)

    fun require(rawId: Int): VanillaBlockState =
        get(rawId) ?: error("Vanilla block-state ID $rawId does not exist")

    fun blockStates(blockId: Identifier): List<VanillaBlockState> =
        vanillaBlockStatesByBlockId[blockId].orEmpty()

    fun default(blockId: Identifier): VanillaBlockState =
        defaultBlockStatesByBlockId[blockId] ?: error("$blockId is not a vanilla block")

    fun find(
        blockId: Identifier,
        properties: Map<String, String>,
    ): VanillaBlockState? =
        vanillaBlockStatesByBlockId[blockId]?.firstOrNull { it.properties == properties }
}

/**
 * Version-matched static registries and the complete global block-state
 * palette emitted by the official vanilla data generator.
 */
object VanillaRegistryData {
    private val vanillaRegistryDataSnapshot: VanillaRegistryDataSnapshot by lazy(
        LazyThreadSafetyMode.PUBLICATION,
        ::decodeVanillaRegistryData,
    )

    val vanillaRegistries: Map<Identifier, VanillaRegistry>
        get() = vanillaRegistryDataSnapshot.vanillaRegistries

    val vanillaBlockStateRegistry: VanillaBlockStateRegistry
        get() = vanillaRegistryDataSnapshot.vanillaBlockStateRegistry

    fun registry(registryId: Identifier): VanillaRegistry? = vanillaRegistries[registryId]

    fun requireRegistry(registryId: Identifier): VanillaRegistry =
        registry(registryId) ?: error("Vanilla registry $registryId does not exist")

    /** Vanilla implementation of the loader-neutral local registry schema. */
    val staticRegistrySchema: StaticRegistrySchema by lazy(
        LazyThreadSafetyMode.PUBLICATION,
    ) {
        StaticRegistrySchema(
            registries = vanillaRegistries.mapValues { (_, vanillaRegistry) ->
                vanillaRegistry.registryEntryIds
            },
            blocks = requireRegistry(StaticRegistrySchema.BLOCK_REGISTRY)
                .registryEntryIds
                .map { blockId ->
                    StaticBlockSchema(
                        blockId,
                        vanillaBlockStateRegistry.blockStates(blockId).map { vanillaBlockState ->
                            StaticBlockState(
                                vanillaBlockState.properties,
                                vanillaBlockState.isDefault,
                            )
                        },
                    )
                },
        )
    }

    /** Default resolved context before an active dimension is selected. */
    val protocolRegistryContext: ProtocolRegistryContext by lazy(
        LazyThreadSafetyMode.PUBLICATION,
        staticRegistrySchema::resolve,
    )
}

private data class VanillaRegistryDataSnapshot(
    val vanillaRegistries: Map<Identifier, VanillaRegistry>,
    val vanillaBlockStateRegistry: VanillaBlockStateRegistry,
)

@Serializable
private data class VanillaRegistryDataPayload(
    @SerialName("format")
    val registryDataFormat: String,
    @SerialName("registries")
    val vanillaRegistries: List<VanillaRegistryPayload>,
    @SerialName("blockStates")
    val vanillaBlockStates: List<VanillaBlockStatePayload>,
)

@Serializable
private data class VanillaRegistryPayload(
    @SerialName("id")
    val registryId: String,
    @SerialName("entries")
    val registryEntryIds: List<String>,
)

@Serializable
private data class VanillaBlockStatePayload(
    @SerialName("id")
    val rawId: Int,
    @SerialName("block")
    val blockId: String,
    val properties: Map<String, String>,
    val isDefault: Boolean,
)

private fun decodeVanillaRegistryData(): VanillaRegistryDataSnapshot {
    val registryDataSource = Buffer().apply {
        write(
            Base64.Default.decode(
                VanillaRegistryDataPayloads.payloadChunks.joinToString(separator = ""),
            ),
        )
    }
    val vanillaRegistryDataPayload = Json.decodeFromSource<VanillaRegistryDataPayload>(registryDataSource)
    check(vanillaRegistryDataPayload.registryDataFormat == REGISTRY_DATA_FORMAT) {
        "Unsupported vanilla registry-data payload"
    }
    val vanillaRegistries = linkedMapOf<Identifier, VanillaRegistry>()
    vanillaRegistryDataPayload.vanillaRegistries.forEach { vanillaRegistryPayload ->
        val registryId = Identifier(vanillaRegistryPayload.registryId)
        check(
            vanillaRegistries.put(
                registryId,
                VanillaRegistry(
                    registryId,
                    vanillaRegistryPayload.registryEntryIds.map { Identifier(it) },
                ),
            ) == null,
        ) {
            "Duplicate vanilla registry $registryId"
        }
    }
    val vanillaBlockStates =
        vanillaRegistryDataPayload.vanillaBlockStates.mapIndexed { expectedRawId, vanillaBlockStatePayload ->
            check(vanillaBlockStatePayload.rawId == expectedRawId) {
                "Expected vanilla block-state ID $expectedRawId, got ${vanillaBlockStatePayload.rawId}"
            }
            VanillaBlockState(
                rawId = vanillaBlockStatePayload.rawId,
                blockId = Identifier(vanillaBlockStatePayload.blockId),
                properties = vanillaBlockStatePayload.properties,
                isDefault = vanillaBlockStatePayload.isDefault,
            )
        }
    check(vanillaRegistries.isNotEmpty()) {
        "Vanilla registry-data payload has no registries"
    }
    check(vanillaBlockStates.isNotEmpty()) {
        "Vanilla registry-data payload has no block states"
    }
    return VanillaRegistryDataSnapshot(
        vanillaRegistries = vanillaRegistries,
        vanillaBlockStateRegistry = VanillaBlockStateRegistry(vanillaBlockStates),
    )
}

private const val REGISTRY_DATA_FORMAT: String = "minecraft-registry-data-v2"
