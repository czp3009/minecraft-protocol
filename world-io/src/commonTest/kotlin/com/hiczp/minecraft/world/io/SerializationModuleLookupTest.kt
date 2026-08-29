package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.serialization.NbtFormat
import com.hiczp.minecraft.nbt.serialization.NbtFormatConfiguration
import com.hiczp.minecraft.nbt.serialization.NbtRootEncoding
import com.hiczp.minecraft.world.format.CompressedNbtFormat
import com.hiczp.minecraft.world.format.Compression
import com.hiczp.minecraft.world.format.LocalChunkPosition
import com.hiczp.minecraft.world.format.RegionPosition
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class SerializationModuleLookupTest {
    @Test
    fun reifiedWorldApisResolveContextualSerializersFromTheExecutingFormat() = runTest {
        val serializersModule = contextualIoValueSerializersModule()
        val standaloneNbtFormat = minecraftWorldNbtFormat(serializersModule)
        val chunkNbtFormat = CompressedNbtFormat(
            NbtFormat(
                NbtFormatConfiguration(
                    serializersModule = serializersModule,
                    nbtRootEncoding = NbtRootEncoding.UNNAMED,
                ),
            ),
        )
        val json = Json {
            this.serializersModule = serializersModule
        }
        val configuration = MinecraftWorldAccessConfiguration(
            chunkNbtFormat = chunkNbtFormat,
            standaloneNbtFormat = standaloneNbtFormat,
            standaloneJson = json,
        )
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val directPath = minecraftWorldPaths.root / "mod/player.dat"
        val directJsonPath = minecraftWorldPaths.root / "mod/player.json"
        val playerUuid = "00000000-0000-0000-0000-000000000000"
        val savedDataId = SavedDataId("contextual", namespace = "example")
        val dimensionSavedDataId = SavedDataId("dimension_contextual", namespace = "example")
        val regionPosition = RegionPosition(0, 0)
        val localChunkPosition = LocalChunkPosition(1, 2)
        val contextualIoValue = ContextualIoValue(7)
        val minecraftWorldAccess = MinecraftWorldAccess.create(
            minecraftWorldPaths,
            fakeFileSystem,
            configuration,
        )

        minecraftWorldAccess.directFiles.writeNbt(directPath, contextualIoValue, Compression.NONE)
        minecraftWorldAccess.directFiles.writeJson(directJsonPath, contextualIoValue)
        minecraftWorldAccess.writeLevelData(contextualIoValue)
        minecraftWorldAccess.data.write(savedDataId, contextualIoValue)
        minecraftWorldAccess.dimensions.overworld.data.write(dimensionSavedDataId, contextualIoValue)
        minecraftWorldAccess.players.writeData(playerUuid, contextualIoValue)
        minecraftWorldAccess.players.writeStatistics(playerUuid, contextualIoValue)
        minecraftWorldAccess.players.writeAdvancements(playerUuid, contextualIoValue)
        minecraftWorldAccess.dimensions.overworld.openRegion(regionPosition).use { regionHandle ->
            regionHandle.writeChunkNbt(localChunkPosition, contextualIoValue, Compression.NONE)
            assertEquals(contextualIoValue, regionHandle.readChunkNbt<ContextualIoValue>(localChunkPosition))
        }

        assertEquals(
            contextualIoValue,
            minecraftWorldAccess.directFiles.readNbt<ContextualIoValue>(directPath, Compression.NONE),
        )
        assertEquals(
            contextualIoValue,
            minecraftWorldAccess.directFiles.readJson<ContextualIoValue>(directJsonPath),
        )
        assertEquals(contextualIoValue, minecraftWorldAccess.readLevelData<ContextualIoValue>())
        assertEquals(
            contextualIoValue,
            minecraftWorldAccess.data.read<ContextualIoValue>(savedDataId),
        )
        assertEquals(
            contextualIoValue,
            minecraftWorldAccess.dimensions.overworld.data.read<ContextualIoValue>(dimensionSavedDataId),
        )
        assertEquals(contextualIoValue, minecraftWorldAccess.players.readData<ContextualIoValue>(playerUuid))
        assertEquals(
            contextualIoValue,
            minecraftWorldAccess.players.readStatistics<ContextualIoValue>(playerUuid),
        )
        assertEquals(
            contextualIoValue,
            minecraftWorldAccess.players.readAdvancements<ContextualIoValue>(playerUuid),
        )
        minecraftWorldAccess.close()

        val liveConfiguration = LiveMinecraftWorldAccessConfiguration(
            chunkNbtFormat = chunkNbtFormat,
            standaloneNbtFormat = standaloneNbtFormat,
            standaloneJson = json,
        )
        val liveMinecraftWorldAccess = LiveMinecraftWorldAccess.open(
            minecraftWorldPaths.root,
            fakeFileSystem,
            liveConfiguration,
        )
        assertEquals(
            contextualIoValue,
            liveMinecraftWorldAccess.directFiles.readNbt<ContextualIoValue>(directPath, Compression.NONE),
        )
        assertEquals(
            contextualIoValue,
            liveMinecraftWorldAccess.directFiles.readJson<ContextualIoValue>(directJsonPath),
        )
        assertEquals(contextualIoValue, liveMinecraftWorldAccess.readLevelData<ContextualIoValue>())
        assertEquals(
            contextualIoValue,
            liveMinecraftWorldAccess.data.read<ContextualIoValue>(savedDataId),
        )
        assertEquals(
            contextualIoValue,
            liveMinecraftWorldAccess.dimensions.overworld.data.read<ContextualIoValue>(dimensionSavedDataId),
        )
        assertEquals(contextualIoValue, liveMinecraftWorldAccess.players.readData<ContextualIoValue>(playerUuid))
        assertEquals(
            contextualIoValue,
            liveMinecraftWorldAccess.players.readStatistics<ContextualIoValue>(playerUuid),
        )
        assertEquals(
            contextualIoValue,
            liveMinecraftWorldAccess.players.readAdvancements<ContextualIoValue>(playerUuid),
        )
        liveMinecraftWorldAccess.dimensions.overworld.openRegion(regionPosition).use { liveRegionHandle ->
            assertEquals(
                contextualIoValue,
                liveRegionHandle.readChunkNbt<ContextualIoValue>(localChunkPosition),
            )
        }
        fakeFileSystem.checkNoOpenFiles()
    }

    @Test
    fun reifiedJsonStoresResolveContextualSerializersFromTheirConfiguredFormat() {
        val fakeFileSystem = FakeFileSystem()
        val minecraftWorldPaths = MinecraftWorldPaths("/world".toPath())
        val json = Json {
            serializersModule = contextualIoValueSerializersModule()
        }
        val utf8JsonFileStore = Utf8JsonFileStore(fakeFileSystem, json)
        val directPath = minecraftWorldPaths.root / "mod/player.json"
        val playerUuid = "00000000-0000-0000-0000-000000000000"
        val contextualIoValue = ContextualIoValue(7)

        utf8JsonFileStore.writeJson(directPath, contextualIoValue)
        assertEquals(contextualIoValue, utf8JsonFileStore.readJson<ContextualIoValue>(directPath))

        val playerStatisticsStore = PlayerStatisticsStore(minecraftWorldPaths, utf8JsonFileStore)
        playerStatisticsStore.write(playerUuid, contextualIoValue)
        assertEquals(contextualIoValue, playerStatisticsStore.read<ContextualIoValue>(playerUuid))

        val playerAdvancementsStore = PlayerAdvancementsStore(minecraftWorldPaths, utf8JsonFileStore)
        playerAdvancementsStore.write(playerUuid, contextualIoValue)
        assertEquals(contextualIoValue, playerAdvancementsStore.read<ContextualIoValue>(playerUuid))
        fakeFileSystem.checkNoOpenFiles()
    }
}

private data class ContextualIoValue(val value: Int)

@Serializable
private data class ContextualIoValueSurrogate(val value: Int)

private object ContextualIoValueSerializer : KSerializer<ContextualIoValue> {
    override val descriptor = ContextualIoValueSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: ContextualIoValue) {
        encoder.encodeSerializableValue(
            ContextualIoValueSurrogate.serializer(),
            ContextualIoValueSurrogate(value.value),
        )
    }

    override fun deserialize(decoder: Decoder): ContextualIoValue =
        ContextualIoValue(
            decoder.decodeSerializableValue(ContextualIoValueSurrogate.serializer()).value,
        )
}

private fun contextualIoValueSerializersModule(): SerializersModule = SerializersModule {
    contextual(ContextualIoValue::class, ContextualIoValueSerializer)
}
