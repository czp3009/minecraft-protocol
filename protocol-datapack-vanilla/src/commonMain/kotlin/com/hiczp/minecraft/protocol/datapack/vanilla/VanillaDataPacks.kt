package com.hiczp.minecraft.protocol.datapack.vanilla

import com.hiczp.minecraft.protocol.datapack.*
import com.hiczp.minecraft.protocol.model.type.KnownPack
import com.hiczp.minecraft.world.format.Compression
import com.hiczp.minecraft.world.format.CompressionRegistry
import com.hiczp.minecraft.world.format.datapack.*
import kotlinx.io.Buffer
import kotlinx.io.buffered
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class VanillaDataPackPayloadDescriptor(
    val id: String,
    val index: Int,
    val batchCount: Int,
)

/** Programmatic official data packs matching this build's selected release. */
object VanillaDataPacks {
    val coreId: DataPackId = DataPackId("vanilla")

    val minecraftVersion: String
        get() = VanillaDataPackPayload.minecraftVersion

    val formatVersion: DataPackFormatVersion = VanillaDataPackPayload.dataPackFormat.let { format ->
        check(VanillaDataPackPayload.schemaVersion == PAYLOAD_SCHEMA_VERSION) {
            "Unsupported bundled vanilla data-pack schema"
        }
        check(format.size == 2) { "Bundled vanilla data-pack format is invalid" }
        DataPackFormatVersion(format[0], format[1])
    }

    val packIds: Set<DataPackId>
        get() = descriptorsById.keys

    /** Decodes one complete raw official archive for a caller-selected parser or transformation. */
    fun archive(id: DataPackId): DataPackArchive = decodeArchive(requireDescriptor(id))

    /** Complete raw files for every bundled pack. This cache is independent of the parsed [packs] cache. */
    val archives: Map<DataPackId, DataPackArchive> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        descriptorsById.mapValues { (_, descriptor) -> decodeArchive(descriptor) }
    }

    /** Parses one bundled pack with caller-selected file decoders without materializing its raw archive first. */
    fun parsePack(
        id: DataPackId,
        format: DataPackFormat = DataPackFormat(),
    ): DataPack = attachCoreMetadata(format.decode(id, decodedFiles(requireDescriptor(id))))

    /** Every bundled pack parsed through [DataPackFormat], including built-in experimental packs. */
    val packs: Map<DataPackId, DataPack>
        get() = defaultPacksById.mapValues { (_, pack) -> pack.value }

    val core: DataPack
        get() = defaultPack(coreId)

    val builtIn: Map<DataPackId, DataPack>
        get() = defaultPacksById.filterKeys { it != coreId }.mapValues { (_, pack) -> pack.value }

    val coreStack: DataPackStack by lazy(LazyThreadSafetyMode.PUBLICATION) { DataPackStack(core) }

    val resolvedCore: ResolvedDataPackStack by lazy(LazyThreadSafetyMode.PUBLICATION) {
        coreStack.resolve(formatVersion)
    }

    /** Exact official network projection already captured from Configuration negotiation. */
    val protocolData: ProtocolDataSet
        get() = VanillaProtocolData

    /** Default bridge from parsed resources to the exact official protocol snapshot. */
    val protocolProjection: DataPackProtocolProjection by lazy(LazyThreadSafetyMode.PUBLICATION) {
        createProtocolProjection(emptyList())
    }

    /** Creates a vanilla-based projection with caller-defined codecs for changed synchronized registries. */
    fun protocolProjection(
        registryProjectors: List<DataPackSynchronizedRegistryProjector>,
    ): DataPackProtocolProjection = if (registryProjectors.isEmpty()) {
        protocolProjection
    } else {
        createProtocolProjection(registryProjectors)
    }

    val knownPack: KnownPack
        get() = VanillaProtocolData.knownPacks.single()

    /** Configuration packets seen by a vanilla client that accepts the offered Known Packs. */
    val clientConfiguration: ReceivedDataPackConfiguration by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ReceivedDataPackConfiguration(
            knownPacks = protocolData.knownPacks,
            featureFlags = protocolData.featureFlags.featureFlags,
            registries = protocolData.registryPackets(protocolData.knownPacks),
            tags = protocolData.tags.registries,
        )
    }

    /** Runtime registry, block-state, and tag view for [clientConfiguration]. */
    val clientRuntime: ClientDataPackRuntime by lazy(LazyThreadSafetyMode.PUBLICATION) {
        clientConfiguration.resolveRuntime(protocolData)
    }

    fun stack(enabledBuiltIn: Iterable<DataPackId> = emptyList()): DataPackStack {
        val selected = enabledBuiltIn.toList()
        require(selected.distinct().size == selected.size) { "Enabled built-in data packs contains duplicates" }
        val availableBuiltIn = descriptorsById.keys - coreId
        require(selected.all(availableBuiltIn::contains)) {
            "Unknown built-in data pack: ${selected.firstOrNull { it !in availableBuiltIn }}"
        }
        return DataPackStack(buildList {
            add(core)
            selected.mapTo(this, ::defaultPack)
        })
    }

    private val descriptorsById: Map<DataPackId, VanillaDataPackPayloadDescriptor> by lazy(
        LazyThreadSafetyMode.PUBLICATION,
    ) {
        val descriptors = VanillaDataPackPayload.packs.associateBy { descriptor -> DataPackId(descriptor.id) }
        check(descriptors.size == VanillaDataPackPayload.packs.size) {
            "Bundled vanilla data packs contain duplicate identifiers"
        }
        check(coreId in descriptors) { "Bundled vanilla core data pack is missing" }
        descriptors
    }

    private val defaultPacksById: Map<DataPackId, Lazy<DataPack>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        descriptorsById.mapValues { (id) ->
            lazy(LazyThreadSafetyMode.PUBLICATION) { parsePack(id) }
        }
    }

    private fun defaultPack(id: DataPackId): DataPack = defaultPacksById.getValue(id).value

    private fun requireDescriptor(id: DataPackId): VanillaDataPackPayloadDescriptor =
        requireNotNull(descriptorsById[id]) { "Unknown bundled vanilla data pack: $id" }

    private fun attachCoreMetadata(pack: DataPack): DataPack {
        if (pack.id != coreId || pack.metadata != null) return pack
        return DataPack(
            id = pack.id,
            metadata = DataPackMetadata(
                description = JsonPrimitive("Vanilla"),
                formats = DataPackFormatRange.exact(formatVersion),
            ),
            files = pack.files,
        )
    }

    private fun createProtocolProjection(
        registryProjectors: List<DataPackSynchronizedRegistryProjector>,
    ): DataPackProtocolProjection = DataPackProtocolProjection(
        base = protocolData,
        registryProjectors = registryProjectors,
        preprojectedPacks = setOf(coreId),
    )

    private const val PAYLOAD_SCHEMA_VERSION = 3
}

@Serializable
private data class VanillaDataPackPayloadBatch(
    val files: Map<String, String>,
)

@OptIn(ExperimentalEncodingApi::class)
private fun decodedFiles(
    descriptor: VanillaDataPackPayloadDescriptor,
): Sequence<Pair<DataPackPath, DataPackBinary>> = sequence {
    repeat(descriptor.batchCount) { batchIndex ->
        val chunks = VanillaDataPackPayload.loadBatch(descriptor.index, batchIndex)
        decodeBatch(chunks).files.forEach { (path, encoded) ->
            yield(DataPackPath(path) to DataPackBinary(Base64.decode(encoded)))
        }
    }
}

private fun decodeArchive(descriptor: VanillaDataPackPayloadDescriptor): DataPackArchive = DataPackArchive(
    id = DataPackId(descriptor.id),
    files = decodedFiles(descriptor).toMap(),
)

@OptIn(ExperimentalEncodingApi::class)
private fun decodeBatch(chunks: List<String>): VanillaDataPackPayloadBatch {
    val compressed = Base64.decode(chunks.joinToString(separator = ""))
    val source = Buffer().apply { write(compressed) }
    val payloadBytes = CompressionRegistry.decompressingSource(Compression.GZIP, source).buffered().use { decoded ->
        decoded.readByteArray()
    }
    return Json.decodeFromString<VanillaDataPackPayloadBatch>(payloadBytes.decodeToString())
}

/** Projects an in-memory stack on top of the exact generated vanilla protocol defaults. */
fun DataPackStack.toVanillaProtocolDataSet(
    registryProjectors: List<DataPackSynchronizedRegistryProjector> = emptyList(),
    format: DataPackFormatVersion? = VanillaDataPacks.formatVersion,
): DataPackProtocolDataSet = VanillaDataPacks.protocolProjection(registryProjectors).project(this, format)

/** Projects an already resolved stack on top of the exact generated vanilla protocol defaults. */
fun ResolvedDataPackStack.toVanillaProtocolDataSet(
    registryProjectors: List<DataPackSynchronizedRegistryProjector> = emptyList(),
): DataPackProtocolDataSet = VanillaDataPacks.protocolProjection(registryProjectors).project(this)
