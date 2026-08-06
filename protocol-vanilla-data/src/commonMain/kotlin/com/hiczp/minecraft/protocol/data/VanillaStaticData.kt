@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.data

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.type.Identifier
import kotlinx.io.Buffer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.io.decodeFromSource
import kotlin.io.encoding.Base64

/**
 * A vanilla static registry whose list position is its protocol ID.
 */
class VanillaRegistry internal constructor(
    val id: Identifier,
    entries: List<Identifier>,
) {
    val entries: List<Identifier> = entries.toList()

    init {
        require(this.entries.distinct().size == this.entries.size) {
            "$id contains duplicate vanilla registry entries"
        }
    }

    private val ids: Map<Identifier, Int> =
        this.entries.withIndex().associate { (index, entry) -> entry to index }

    val size: Int
        get() = entries.size

    operator fun get(protocolId: Int): Identifier? =
        entries.getOrNull(protocolId)

    fun protocolId(entry: Identifier): Int? = ids[entry]

    fun requireProtocolId(entry: Identifier): Int =
        protocolId(entry)
            ?: error("$entry is not present in the $id vanilla registry")
}

/**
 * One entry in the global vanilla block-state palette.
 */
data class VanillaBlockState(
    val id: Int,
    val block: Identifier,
    val properties: Map<String, String>,
    val isDefault: Boolean,
)

class VanillaBlockStateRegistry internal constructor(
    states: List<VanillaBlockState>,
) {
    val states: List<VanillaBlockState> = states.toList()

    init {
        require(this.states.withIndex().all { (index, state) ->
            state.id == index
        }) {
            "Vanilla block-state IDs must be contiguous and ordered"
        }
        require(
            this.states
                .groupBy(VanillaBlockState::block)
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

    private val byBlock: Map<Identifier, List<VanillaBlockState>> =
        this.states.groupBy(VanillaBlockState::block)
    private val defaults: Map<Identifier, VanillaBlockState> =
        byBlock.mapValues { (block, blockStates) ->
            blockStates.singleOrNull(VanillaBlockState::isDefault)
                ?: error("$block does not have exactly one default state")
        }

    val size: Int
        get() = states.size

    operator fun get(stateId: Int): VanillaBlockState? =
        states.getOrNull(stateId)

    fun require(stateId: Int): VanillaBlockState =
        get(stateId) ?: error("Vanilla block-state ID $stateId does not exist")

    fun states(block: Identifier): List<VanillaBlockState> =
        byBlock[block].orEmpty()

    fun default(block: Identifier): VanillaBlockState =
        defaults[block] ?: error("$block is not a vanilla block")

    fun find(
        block: Identifier,
        properties: Map<String, String>,
    ): VanillaBlockState? =
        byBlock[block]?.firstOrNull { it.properties == properties }
}

/**
 * Version-matched static registries and the complete global block-state
 * palette emitted by the official vanilla data generator.
 */
sealed class VanillaStaticData private constructor() {
    companion object Default : VanillaStaticData() {
        private val snapshot: VanillaStaticDataSnapshot by lazy(
            LazyThreadSafetyMode.PUBLICATION,
            ::decodeVanillaStaticData,
        )

        val minecraftVersion: String
            get() = MinecraftProtocol.MINECRAFT_VERSION

        val protocolVersion: Int
            get() = MinecraftProtocol.PROTOCOL_VERSION

        val registries: Map<Identifier, VanillaRegistry>
            get() = snapshot.registries

        val blockStates: VanillaBlockStateRegistry
            get() = snapshot.blockStates

        fun registry(id: Identifier): VanillaRegistry? = registries[id]

        fun requireRegistry(id: Identifier): VanillaRegistry =
            registry(id) ?: error("Vanilla registry $id does not exist")
    }
}

private data class VanillaStaticDataSnapshot(
    val registries: Map<Identifier, VanillaRegistry>,
    val blockStates: VanillaBlockStateRegistry,
)

@Serializable
private data class VanillaStaticDataPayload(
    val format: String,
    val registries: List<VanillaRegistryPayload>,
    val blockStates: List<VanillaBlockStatePayload>,
)

@Serializable
private data class VanillaRegistryPayload(
    val id: String,
    val entries: List<String>,
)

@Serializable
private data class VanillaBlockStatePayload(
    val id: Int,
    val block: String,
    val properties: Map<String, String>,
    val isDefault: Boolean,
)

private fun decodeVanillaStaticData(): VanillaStaticDataSnapshot {
    check(
        VanillaStaticDataPayloads.minecraftVersion ==
                MinecraftProtocol.MINECRAFT_VERSION,
    ) {
        "Vanilla static data targets ${VanillaStaticDataPayloads.minecraftVersion}, but models target ${MinecraftProtocol.MINECRAFT_VERSION}"
    }
    check(
        VanillaStaticDataPayloads.protocolVersion ==
                MinecraftProtocol.PROTOCOL_VERSION,
    ) {
        "Vanilla static data targets protocol ${VanillaStaticDataPayloads.protocolVersion}, but models target ${MinecraftProtocol.PROTOCOL_VERSION}"
    }

    val source = Buffer().apply {
        write(
            Base64.Default.decode(
                VanillaStaticDataPayloads.payload.joinToString(separator = ""),
            ),
        )
    }
    val payload = Json.decodeFromSource<VanillaStaticDataPayload>(source)
    check(payload.format == STATIC_DATA_FORMAT) {
        "Unsupported vanilla static-data payload"
    }
    val registries = linkedMapOf<Identifier, VanillaRegistry>()
    payload.registries.forEach { registry ->
        val id = Identifier(registry.id)
        check(
            registries.put(
                id,
                VanillaRegistry(
                    id,
                    registry.entries.map { Identifier(it) },
                ),
            ) == null,
        ) {
            "Duplicate vanilla registry $id"
        }
    }
    val states = payload.blockStates.mapIndexed { expectedId, state ->
        check(state.id == expectedId) {
            "Expected vanilla block-state ID $expectedId, got ${state.id}"
        }
        VanillaBlockState(
            id = state.id,
            block = Identifier(state.block),
            properties = state.properties,
            isDefault = state.isDefault,
        )
    }
    check(registries.isNotEmpty()) {
        "Vanilla static-data payload has no registries"
    }
    check(states.isNotEmpty()) {
        "Vanilla static-data payload has no block states"
    }
    return VanillaStaticDataSnapshot(
        registries = registries,
        blockStates = VanillaBlockStateRegistry(states),
    )
}

private const val STATIC_DATA_FORMAT: String = "minecraft-static-data-v2"
