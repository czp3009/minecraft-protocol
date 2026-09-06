package com.hiczp.minecraft.world.format.datapack.vanilla

import com.hiczp.minecraft.world.format.Compression
import com.hiczp.minecraft.world.format.CompressionRegistry
import com.hiczp.minecraft.world.format.datapack.*
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

internal data class VanillaDataPackPayloadDescriptor(
    val dataPackId: String,
    val dataPackIndex: Int,
)

/** Programmatic official data packs matching this build's selected release. */
object VanillaDataPacks {
    val coreDataPackId: DataPackId = DataPackId("vanilla")

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

    /** Parses one complete bundled pack with caller-selected file decoders. */
    fun parseDataPack(
        dataPackId: DataPackId,
        dataPackFormat: DataPackFormat = DataPackFormat(),
    ): DataPack = attachCoreDataPackMetadata(
        dataPackFormat.decode(decodeDataPackArchive(requireDataPackPayloadDescriptor(dataPackId))),
    )

    /** Every bundled pack parsed through [DataPackFormat], including built-in experimental packs. */
    val dataPacks: Map<DataPackId, DataPack>
        get() = defaultDataPacksById.mapValues { (_, dataPack) -> dataPack.value }

    /** Returns one parsed bundled pack without forcing unrelated built-in packs to be decoded. */
    fun dataPackOrNull(dataPackId: DataPackId): DataPack? = defaultDataPacksById[dataPackId]?.value

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

    private const val PAYLOAD_SCHEMA_VERSION = 4
}

@Serializable
private data class VanillaDataPackPayloadContent(
    @SerialName("files")
    val encodedDataPackFileBytesByPath: Map<String, String>,
)

@OptIn(ExperimentalEncodingApi::class)
private fun decodeDataPackArchive(
    vanillaDataPackPayloadDescriptor: VanillaDataPackPayloadDescriptor,
): DataPackArchive = DataPackArchive(
    dataPackId = DataPackId(vanillaDataPackPayloadDescriptor.dataPackId),
    dataPackFileBytesByPath = decodeDataPackPayload(
        VanillaDataPackPayload.loadDataPackPayload(vanillaDataPackPayloadDescriptor.dataPackIndex),
    ).encodedDataPackFileBytesByPath.map { (encodedPath, encodedDataPackFileBytes) ->
        DataPackFilePath(encodedPath) to DataPackFileBytes(Base64.decode(encodedDataPackFileBytes))
    }.toMap(),
)

@OptIn(ExperimentalEncodingApi::class)
private fun decodeDataPackPayload(encodedPayloadChunks: List<String>): VanillaDataPackPayloadContent {
    val compressedPayloadBytes = Base64.decode(encodedPayloadChunks.joinToString(separator = ""))
    val compressedPayloadSource = Buffer().apply { write(compressedPayloadBytes) }
    val decompressedPayloadBytes = CompressionRegistry.decompressingSource(Compression.GZIP, compressedPayloadSource)
        .buffered().use { decompressedPayloadSource ->
            decompressedPayloadSource.readByteArray()
        }
    return Json.decodeFromString<VanillaDataPackPayloadContent>(decompressedPayloadBytes.decodeToString())
}

/** Completes a world's persisted selection from its file packs and the release-matched bundled packs. */
fun WorldDataPackLoadResult.toVanillaDataPackStack(): DataPackStack {
    val selectedDataPackStack = toDataPackStack(VanillaDataPacks::dataPackOrNull)
    if (VanillaDataPacks.coreDataPackId in enabledDataPackIds) return selectedDataPackStack
    return DataPackStack(listOf(VanillaDataPacks.coreDataPack) + selectedDataPackStack.dataPacks)
}
