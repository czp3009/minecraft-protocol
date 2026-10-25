package com.hiczp.minecraft.demo.worldmcp

import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.protocol.configuration.resolveWorldChunkContexts
import com.hiczp.minecraft.protocol.configuration.vanilla.VanillaConfigurationData
import com.hiczp.minecraft.protocol.configuration.vanilla.VanillaRegistryData
import com.hiczp.minecraft.protocol.configuration.vanilla.toVanillaConfigurationData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.format.data.SavedDataFile
import com.hiczp.minecraft.world.format.data.WorldGenSettingsData
import com.hiczp.minecraft.world.format.datapack.vanilla.toVanillaDataPackStack
import com.hiczp.minecraft.world.io.MinecraftWorldAccess
import com.hiczp.minecraft.world.io.MinecraftWorldAccessConfiguration
import com.hiczp.minecraft.world.io.NbtFileStore
import com.hiczp.minecraft.world.io.forWorldFiles
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okio.FileSystem
import okio.Path

/** One owned world. Codecs share dimension facts and an unchanged tick origin for this offline editing lifetime. */
class WorldSession(
    val minecraftWorldAccess: MinecraftWorldAccess,
    val levelDat: LevelDat,
    val chunkContexts: Map<DimensionId, ChunkContext>,
    val selection: Long,
) {
    val chunkNbtDecoders = chunkContexts.mapValues { (_, chunkContext) ->
        ChunkNbtDecoder(
            ChunkNbtDecoderContext(
                chunkContext,
                NbtFormat,
                NbtPropertyReadMappings(),
                levelDat.data.time,
                blockStateRegistry.createBlockStateNbtDecoder()
            )
        )
    }
    val root: Path get() = minecraftWorldAccess.minecraftWorldPaths.root

    fun context(dimensionId: DimensionId): ChunkContext = chunkContexts[dimensionId]
        ?: error("Unknown dimension $dimensionId; call list_dimensions")

    fun decoder(dimensionId: DimensionId): ChunkNbtDecoder = chunkNbtDecoders[dimensionId]
        ?: error("Unknown dimension $dimensionId; call list_dimensions")

    fun encoder(dimensionId: DimensionId, chunkNbtMetadata: ChunkNbtMetadata): ChunkNbtEncoder = ChunkNbtEncoder(
        ChunkNbtEncoderContext(
            context(dimensionId).dimensionTypeLayout.chunkLayout,
            NbtFormat,
            NbtPropertyWriteMappings(),
            levelDat.data.time,
            chunkNbtMetadata
        ),
    )

    fun blockState(value: JsonElement): BlockState {
        val blockValue = EditorJson.format.decodeFromJsonElement<BlockValue>(value)
        val blockId = Identifier(BlockId.parse(blockValue.name).toString())
        val defaults = blockStateRegistry.default(blockId)
        val properties = defaults.properties + blockValue.properties
        require(
            blockStateRegistry.find(
                blockId,
                properties
            ) != null
        ) { "Invalid properties for $blockId; call describe_blocks" }
        return BlockState(BlockId(blockId.value), StateProperties(properties))
    }

    fun worldInfo(): JsonObject = buildJsonObject {
        put("path", root.toString())
        put("name", levelDat.data.levelName)
        put("dataVersion", levelDat.data.dataVersion)
        put("version", levelDat.data.versionInfo.name)
        put("storageVersion", levelDat.data.version)
        put("library", buildJsonObject {
            put("minecraftVersion", MinecraftProtocol.MINECRAFT_VERSION)
            put("dataVersion", MinecraftWorldFormat.WORLD_VERSION)
        })
        put("dataVersionMatchesLibrary", levelDat.data.dataVersion == MinecraftWorldFormat.WORLD_VERSION)
        put("selection", selection)
        put(
            "dimensions",
            JsonArray(chunkContexts.keys.sortedBy(DimensionId::toString).map { JsonPrimitive(it.toString()) })
        )
        put("spawn", buildJsonObject {
            put("dimension", levelDat.data.spawn.dimension)
            put("position", JsonArray(levelDat.data.spawn.pos.value.map(::JsonPrimitive)))
        })
    }

    fun dimensionInfo(dimensionId: DimensionId): JsonObject {
        val chunkLayout = context(dimensionId).dimensionTypeLayout.chunkLayout
        return buildJsonObject {
            put("dimension", dimensionId.toString())
            put("path", minecraftWorldAccess.minecraftWorldPaths.dimension(dimensionId).toString())
            put("minY", chunkLayout.minBlockY)
            put("maxY", chunkLayout.maxBlockY)
            put("minSectionY", chunkLayout.minSectionY)
            put("maxSectionY", chunkLayout.maxSectionY)
        }
    }

    companion object {
        val blockStateRegistry get() = VanillaRegistryData.vanillaBlockStateRegistry

        suspend fun open(path: Path, selection: Long): WorldSession {
            val root = FileSystem.SYSTEM.canonicalize(path)
            require(FileSystem.SYSTEM.metadata(root).isDirectory) { "World path is not a directory: $root" }
            require(FileSystem.SYSTEM.metadataOrNull(root / "level.dat")?.isRegularFile == true) { "Not a Minecraft world: level.dat is missing in $root" }
            val nbtFormat = NbtFormat(NbtFormatConfiguration(ignoreUnknownKeys = true)).forWorldFiles()
            // Validate before opening the mutable lease, whose lower-level contract can create a world directory.
            val probe = NbtFileStore(nbtFormat = nbtFormat)
            probe.read<LevelDat>(root / "level.dat", Compression.GZIP)
            val minecraftWorldAccess =
                MinecraftWorldAccess.open(root, MinecraftWorldAccessConfiguration(standaloneNbtFormat = nbtFormat))
            try {
                val ownedLevelDat = minecraftWorldAccess.readLevelData()
                val worldDataPackLoadResult = minecraftWorldAccess.dataPacks.readEnabled()
                val worldGenSettingsData =
                    requireNotNull(minecraftWorldAccess.data.read<SavedDataFile<WorldGenSettingsData>>(SavedDataId("world_gen_settings"))) { "World has no world_gen_settings saved data" }.data
                val dataPackStack = worldDataPackLoadResult.toVanillaDataPackStack()
                val resolvedConfigurationData = dataPackStack.toVanillaConfigurationData(
                    enabledFeatureFlags = VanillaConfigurationData.enabledFeatureFlags + worldDataPackLoadResult.enabledFeatureFlags.map {
                        Identifier(
                            it
                        )
                    },
                )
                val worldChunkContexts = resolvedConfigurationData.resolveWorldChunkContexts(
                    worldGenSettingsData, BlockState(BlockId("minecraft:air")), BiomeId("minecraft:plains"),
                )
                return WorldSession(minecraftWorldAccess, ownedLevelDat, worldChunkContexts.dimensions, selection)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    try {
                        minecraftWorldAccess.close()
                    } catch (cleanup: Throwable) {
                        failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            }
        }
    }
}
