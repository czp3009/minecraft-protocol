@file:OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)

package com.hiczp.minecraft.protocol.data

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.type.Identifier
import kotlin.io.encoding.Base64

/**
 * A vanilla static registry whose list position is its protocol ID.
 */
class VanillaRegistry internal constructor(
    val id: Identifier,
    entries: List<Identifier>,
) {
    val entries: List<Identifier> = entries.toList()
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

private fun decodeVanillaStaticData(): VanillaStaticDataSnapshot {
    check(
        VanillaStaticDataPayloads.minecraftVersion ==
                MinecraftProtocol.MINECRAFT_VERSION,
    ) {
        "Vanilla static data targets " +
                "${VanillaStaticDataPayloads.minecraftVersion}, but models target " +
                MinecraftProtocol.MINECRAFT_VERSION
    }
    check(
        VanillaStaticDataPayloads.protocolVersion ==
                MinecraftProtocol.PROTOCOL_VERSION,
    ) {
        "Vanilla static data targets protocol " +
                "${VanillaStaticDataPayloads.protocolVersion}, but models target " +
                MinecraftProtocol.PROTOCOL_VERSION
    }

    val text = Base64.Default.decode(
        VanillaStaticDataPayloads.payload.joinToString(separator = ""),
    ).decodeToString()
    val lines = text.lineSequence().iterator()
    check(lines.hasNext() && lines.next() == STATIC_DATA_FORMAT) {
        "Unsupported vanilla static-data payload"
    }
    val registries = linkedMapOf<Identifier, VanillaRegistry>()
    val states = mutableListOf<VanillaBlockState>()
    lines.forEach { line ->
        if (line.isEmpty()) return@forEach
        val fields = line.split('\t')
        when (fields.first()) {
            "R" -> {
                check(fields.size == 3) {
                    "Malformed vanilla registry payload row"
                }
                val id = Identifier(fields[1])
                val entries =
                    if (fields[2].isEmpty()) {
                        emptyList()
                    } else {
                        fields[2].split(',').map { Identifier(it) }
                    }
                check(
                    registries.put(id, VanillaRegistry(id, entries)) == null,
                ) {
                    "Duplicate vanilla registry $id"
                }
            }

            "S" -> {
                check(fields.size == 5) {
                    "Malformed vanilla block-state payload row"
                }
                val id = fields[1].toInt()
                check(id == states.size) {
                    "Expected vanilla block-state ID ${states.size}, got $id"
                }
                val properties =
                    if (fields[4].isEmpty()) {
                        emptyMap()
                    } else {
                        fields[4].split(',').associate { property ->
                            val separator = property.indexOf('=')
                            check(separator > 0) {
                                "Malformed block-state property '$property'"
                            }
                            property.substring(0, separator) to
                                    property.substring(separator + 1)
                        }
                    }
                states += VanillaBlockState(
                    id = id,
                    block = Identifier(fields[2]),
                    properties = properties,
                    isDefault = when (fields[3]) {
                        "0" -> false
                        "1" -> true
                        else -> error(
                            "Malformed block-state default flag ${fields[3]}",
                        )
                    },
                )
            }

            else -> error("Unknown vanilla static-data row ${fields.first()}")
        }
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

private const val STATIC_DATA_FORMAT: String = "minecraft-static-data-v1"
