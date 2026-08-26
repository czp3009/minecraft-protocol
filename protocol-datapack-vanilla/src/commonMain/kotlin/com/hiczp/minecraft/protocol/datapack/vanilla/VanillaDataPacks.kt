package com.hiczp.minecraft.protocol.datapack.vanilla

import com.hiczp.minecraft.protocol.datapack.DataPackProtocolProjector
import com.hiczp.minecraft.protocol.datapack.DataPackRegistryProjector
import com.hiczp.minecraft.protocol.datapack.ResolvedProtocolData
import com.hiczp.minecraft.world.format.Compression
import com.hiczp.minecraft.world.format.CompressionRegistry
import com.hiczp.minecraft.world.format.datapack.*
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class VanillaDataPackPayloadDescriptor(
    val dataPackId: String,
    val dataPackIndex: Int,
    val batchCount: Int,
)

/** Programmatic official data packs matching this build's selected release. */
object VanillaDataPacks {
    val coreDataPackId: DataPackId = DataPackId("vanilla")

    val minecraftVersion: String
        get() = VanillaDataPackPayload.minecraftVersion

    val dataPackFormatVersion: DataPackFormatVersion =
        VanillaDataPackPayload.dataPackFormatVersion.let { encodedDataPackFormatVersion ->
            check(VanillaDataPackPayload.schemaVersion == PAYLOAD_SCHEMA_VERSION) {
                "Unsupported bundled vanilla data-pack schema"
            }
            check(encodedDataPackFormatVersion.size == 2) { "Bundled vanilla data-pack format is invalid" }
            DataPackFormatVersion(encodedDataPackFormatVersion[0], encodedDataPackFormatVersion[1])
        }

    val dataPackIds: Set<DataPackId>
        get() = dataPackPayloadDescriptorsById.keys

    /** Decodes one complete raw official archive for a caller-selected parser or transformation. */
    fun dataPackArchive(dataPackId: DataPackId): DataPackArchive =
        decodeDataPackArchive(requireDataPackPayloadDescriptor(dataPackId))

    /** Parses one bundled pack with caller-selected file decoders without materializing its raw archive first. */
    fun parseDataPack(
        dataPackId: DataPackId,
        dataPackFormat: DataPackFormat = DataPackFormat(),
    ): DataPack = attachCoreDataPackMetadata(
        dataPackFormat.decode(dataPackId, decodedDataPackFileBytes(requireDataPackPayloadDescriptor(dataPackId))),
    )

    /** Every bundled pack parsed through [DataPackFormat], including built-in experimental packs. */
    val dataPacks: Map<DataPackId, DataPack>
        get() = defaultDataPacksById.mapValues { (_, dataPack) -> dataPack.value }

    val coreDataPack: DataPack
        get() = defaultDataPack(coreDataPackId)

    val builtInDataPacks: Map<DataPackId, DataPack>
        get() = defaultDataPacksById.filterKeys { it != coreDataPackId }
            .mapValues { (_, dataPack) -> dataPack.value }

    val coreDataPackStack: DataPackStack by lazy(LazyThreadSafetyMode.PUBLICATION) {
        DataPackStack(coreDataPack)
    }

    fun dataPackStack(enabledBuiltInDataPackIds: Iterable<DataPackId> = emptyList()): DataPackStack {
        val selectedBuiltInDataPackIds = enabledBuiltInDataPackIds.toList()
        require(selectedBuiltInDataPackIds.distinct().size == selectedBuiltInDataPackIds.size) {
            "Enabled built-in data packs contains duplicates"
        }
        val availableBuiltInDataPackIds = dataPackPayloadDescriptorsById.keys - coreDataPackId
        require(selectedBuiltInDataPackIds.all(availableBuiltInDataPackIds::contains)) {
            val unknownDataPackId = selectedBuiltInDataPackIds.firstOrNull { it !in availableBuiltInDataPackIds }
            "Unknown built-in data pack: $unknownDataPackId"
        }
        return DataPackStack(buildList {
            add(coreDataPack)
            selectedBuiltInDataPackIds.mapTo(this, ::defaultDataPack)
        })
    }

    private val dataPackPayloadDescriptorsById: Map<DataPackId, VanillaDataPackPayloadDescriptor> by lazy(
        LazyThreadSafetyMode.PUBLICATION,
    ) {
        val dataPackPayloadDescriptors = VanillaDataPackPayload.dataPackPayloadDescriptors
            .associateBy { vanillaDataPackPayloadDescriptor -> DataPackId(vanillaDataPackPayloadDescriptor.dataPackId) }
        check(dataPackPayloadDescriptors.size == VanillaDataPackPayload.dataPackPayloadDescriptors.size) {
            "Bundled vanilla data packs contain duplicate identifiers"
        }
        check(coreDataPackId in dataPackPayloadDescriptors) { "Bundled vanilla core data pack is missing" }
        dataPackPayloadDescriptors
    }

    private val defaultDataPacksById: Map<DataPackId, Lazy<DataPack>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        dataPackPayloadDescriptorsById.mapValues { (dataPackId) ->
            lazy(LazyThreadSafetyMode.PUBLICATION) { parseDataPack(dataPackId) }
        }
    }

    private fun defaultDataPack(dataPackId: DataPackId): DataPack = defaultDataPacksById.getValue(dataPackId).value

    private fun requireDataPackPayloadDescriptor(dataPackId: DataPackId): VanillaDataPackPayloadDescriptor =
        requireNotNull(dataPackPayloadDescriptorsById[dataPackId]) {
            "Unknown bundled vanilla data pack: $dataPackId"
        }

    private fun attachCoreDataPackMetadata(dataPack: DataPack): DataPack {
        if (dataPack.dataPackId != coreDataPackId || dataPack.dataPackMetadata != null) return dataPack
        return DataPack(
            dataPackId = dataPack.dataPackId,
            dataPackMetadata = DataPackMetadata(
                description = JsonPrimitive("Vanilla"),
                supportedDataPackFormatVersionRange = DataPackFormatVersionRange.exact(dataPackFormatVersion),
            ),
            dataPackFileContentsByPath = dataPack.dataPackFileContentsByPath,
        )
    }

    private const val PAYLOAD_SCHEMA_VERSION = 3
}

@Serializable
private data class VanillaDataPackPayloadBatch(
    @SerialName("files")
    val encodedDataPackFileBytesByPath: Map<String, String>,
)

@OptIn(ExperimentalEncodingApi::class)
private fun decodedDataPackFileBytes(
    vanillaDataPackPayloadDescriptor: VanillaDataPackPayloadDescriptor,
): Sequence<Pair<DataPackFilePath, DataPackFileBytes>> = sequence {
    repeat(vanillaDataPackPayloadDescriptor.batchCount) { batchIndex ->
        val encodedBatchChunks = VanillaDataPackPayload.loadDataPackBatch(
            vanillaDataPackPayloadDescriptor.dataPackIndex,
            batchIndex,
        )
        decodeDataPackPayloadBatch(encodedBatchChunks).encodedDataPackFileBytesByPath
            .forEach { (encodedPath, encodedDataPackFileBytes) ->
                yield(DataPackFilePath(encodedPath) to DataPackFileBytes(Base64.decode(encodedDataPackFileBytes)))
            }
    }
}

private fun decodeDataPackArchive(
    vanillaDataPackPayloadDescriptor: VanillaDataPackPayloadDescriptor,
): DataPackArchive = DataPackArchive(
    dataPackId = DataPackId(vanillaDataPackPayloadDescriptor.dataPackId),
    dataPackFileBytesByPath = decodedDataPackFileBytes(vanillaDataPackPayloadDescriptor).toMap(),
)

@OptIn(ExperimentalEncodingApi::class)
private fun decodeDataPackPayloadBatch(encodedBatchChunks: List<String>): VanillaDataPackPayloadBatch {
    val compressedBatchBytes = Base64.decode(encodedBatchChunks.joinToString(separator = ""))
    val compressedBatchSource = Buffer().apply { write(compressedBatchBytes) }
    val decompressedBatchBytes = CompressionRegistry.decompressingSource(Compression.GZIP, compressedBatchSource)
        .buffered().use { decompressedBatchSource ->
            decompressedBatchSource.readByteArray()
        }
    return Json.decodeFromString<VanillaDataPackPayloadBatch>(decompressedBatchBytes.decodeToString())
}

/**
 * Projects an in-memory stack on top of the exact generated vanilla protocol defaults.
 *
 * The release-matched [vanillaDataPackRegistryProjectors] cover ordinary vanilla resources. Caller-supplied projectors
 * replace matching defaults by registry ID and add projectors for custom registries.
 */
fun DataPackStack.toVanillaProtocolData(
    dataPackRegistryProjectorOverrides: List<DataPackRegistryProjector> = emptyList(),
    dataPackFormatVersion: DataPackFormatVersion? = VanillaDataPacks.dataPackFormatVersion,
): ResolvedProtocolData = vanillaDataPackProtocolProjector(dataPackRegistryProjectorOverrides)
    .project(this, dataPackFormatVersion)

/**
 * Projects an already resolved stack on top of the exact generated vanilla protocol defaults.
 *
 * The release-matched [vanillaDataPackRegistryProjectors] cover ordinary vanilla resources. Caller-supplied projectors
 * replace matching defaults by registry ID and add projectors for custom registries.
 */
fun ResolvedDataPackStack.toVanillaProtocolData(
    dataPackRegistryProjectorOverrides: List<DataPackRegistryProjector> = emptyList(),
): ResolvedProtocolData = vanillaDataPackProtocolProjector(dataPackRegistryProjectorOverrides).project(this)

/**
 * Creates the bridge from parsed vanilla-based resources to Configuration protocol data.
 *
 * Caller-supplied projectors replace matching [vanillaDataPackRegistryProjectors] by registry ID and add custom
 * registries. Construct [DataPackProtocolProjector] directly when every default should be replaced.
 */
fun vanillaDataPackProtocolProjector(
    dataPackRegistryProjectorOverrides: List<DataPackRegistryProjector> = emptyList(),
): DataPackProtocolProjector = DataPackProtocolProjector(
    baseProtocolData = VanillaProtocolData,
    dataPackRegistryProjectors = vanillaDataPackRegistryProjectors.withOverrides(dataPackRegistryProjectorOverrides),
    preprojectedDataPackIds = setOf(VanillaDataPacks.coreDataPackId),
)

private fun List<DataPackRegistryProjector>.withOverrides(
    dataPackRegistryProjectorOverrides: List<DataPackRegistryProjector>,
): List<DataPackRegistryProjector> {
    val overrideRegistryIds = dataPackRegistryProjectorOverrides.map(DataPackRegistryProjector::registryId)
    require(overrideRegistryIds.distinct().size == overrideRegistryIds.size) {
        "Vanilla data-pack protocol projector has duplicate registry overrides"
    }
    val overridesByRegistryId = dataPackRegistryProjectorOverrides.associateBy(DataPackRegistryProjector::registryId)
    val defaultRegistryIds = mapTo(mutableSetOf(), DataPackRegistryProjector::registryId)
    return buildList {
        this@withOverrides.forEach { dataPackRegistryProjector ->
            add(overridesByRegistryId[dataPackRegistryProjector.registryId] ?: dataPackRegistryProjector)
        }
        dataPackRegistryProjectorOverrides.filterTo(this) { dataPackRegistryProjector ->
            dataPackRegistryProjector.registryId !in defaultRegistryIds
        }
    }
}
