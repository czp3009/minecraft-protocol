package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.DimensionId
import com.hiczp.minecraft.world.format.SavedDataId
import com.hiczp.minecraft.world.format.data.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource

/** Coordinated access to one mutable `data` directory. */
open class MinecraftSavedData internal constructor(
    private val minecraftWorldAccess: MinecraftWorldAccess,
    private val savedDataScope: SavedDataScope,
) {
    val configuration: MinecraftWorldAccessConfiguration
        get() = minecraftWorldAccess.configuration

    suspend fun readDocument(savedDataId: SavedDataId): NbtDocument? =
        minecraftWorldAccess.readSavedDataDocument(savedDataId, savedDataScope)

    suspend fun <T> read(
        savedDataId: SavedDataId,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = minecraftWorldAccess.readSavedData(savedDataId, savedDataScope, deserializationStrategy)

    suspend inline fun <reified T> read(savedDataId: SavedDataId): T? = read(
        savedDataId,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
    )

    suspend fun <T> read(savedDataId: SavedDataId, block: (BufferedSource) -> T): T? =
        minecraftWorldAccess.readSavedData(savedDataId, savedDataScope, block)

    suspend fun writeDocument(savedDataId: SavedDataId, nbtDocument: NbtDocument) =
        minecraftWorldAccess.writeSavedDataDocument(savedDataId, nbtDocument, savedDataScope)

    suspend fun <T> write(
        savedDataId: SavedDataId,
        value: T,
        serializationStrategy: SerializationStrategy<T>,
    ) = minecraftWorldAccess.writeSavedData(savedDataId, value, savedDataScope, serializationStrategy)

    suspend inline fun <reified T> write(savedDataId: SavedDataId, value: T) = write(
        savedDataId,
        value,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
    )

    suspend fun write(savedDataId: SavedDataId, block: (BufferedSink) -> Unit) =
        minecraftWorldAccess.writeSavedData(savedDataId, savedDataScope, block)
}

/** Mutable saved data belonging to one selected dimension. */
class MinecraftDimensionSavedData internal constructor(
    minecraftWorldAccess: MinecraftWorldAccess,
    dimensionId: DimensionId,
) : MinecraftSavedData(minecraftWorldAccess, SavedDataScope.Dimension(dimensionId)) {
    suspend fun readWorldBorderData(): SavedDataFile<WorldBorderData>? =
        read<SavedDataFile<WorldBorderData>>(WORLD_BORDER_ID)

    suspend fun writeWorldBorderData(worldBorderData: SavedDataFile<WorldBorderData>) =
        write(WORLD_BORDER_ID, worldBorderData)

    suspend fun readChunkTicketsData(): SavedDataFile<ChunkTicketsData>? =
        read<SavedDataFile<ChunkTicketsData>>(CHUNK_TICKETS_ID)

    suspend fun writeChunkTicketsData(chunkTicketsData: SavedDataFile<ChunkTicketsData>) =
        write(CHUNK_TICKETS_ID, chunkTicketsData)

    suspend fun readRaidsData(): SavedDataFile<RaidsData>? =
        read<SavedDataFile<RaidsData>>(RAIDS_ID)

    suspend fun writeRaidsData(raidsData: SavedDataFile<RaidsData>) =
        write(RAIDS_ID, raidsData)

    suspend fun readEnderDragonFightData(): SavedDataFile<EnderDragonFightData>? =
        read<SavedDataFile<EnderDragonFightData>>(ENDER_DRAGON_FIGHT_ID)

    suspend fun writeEnderDragonFightData(enderDragonFightData: SavedDataFile<EnderDragonFightData>) =
        write(ENDER_DRAGON_FIGHT_ID, enderDragonFightData)
}

/** Synchronous read-only access to one live `data` directory. */
open class LiveMinecraftSavedData internal constructor(
    private val liveMinecraftWorldAccess: LiveMinecraftWorldAccess,
    private val savedDataScope: SavedDataScope,
) {
    val configuration: LiveMinecraftWorldAccessConfiguration
        get() = liveMinecraftWorldAccess.configuration

    fun readDocument(savedDataId: SavedDataId): NbtDocument? =
        liveMinecraftWorldAccess.readSavedDataDocument(savedDataId, savedDataScope)

    fun <T> read(
        savedDataId: SavedDataId,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = liveMinecraftWorldAccess.readSavedData(savedDataId, savedDataScope, deserializationStrategy)

    inline fun <reified T> read(savedDataId: SavedDataId): T? = read(
        savedDataId,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
    )

    fun <T> read(savedDataId: SavedDataId, block: (BufferedSource) -> T): T? =
        liveMinecraftWorldAccess.readSavedData(savedDataId, savedDataScope, block)
}

/** Live read-only saved data belonging to one selected dimension. */
class LiveMinecraftDimensionSavedData internal constructor(
    liveMinecraftWorldAccess: LiveMinecraftWorldAccess,
    dimensionId: DimensionId,
) : LiveMinecraftSavedData(liveMinecraftWorldAccess, SavedDataScope.Dimension(dimensionId)) {
    fun readWorldBorderData(): SavedDataFile<WorldBorderData>? =
        read<SavedDataFile<WorldBorderData>>(WORLD_BORDER_ID)

    fun readChunkTicketsData(): SavedDataFile<ChunkTicketsData>? =
        read<SavedDataFile<ChunkTicketsData>>(CHUNK_TICKETS_ID)

    fun readRaidsData(): SavedDataFile<RaidsData>? = read<SavedDataFile<RaidsData>>(RAIDS_ID)

    fun readEnderDragonFightData(): SavedDataFile<EnderDragonFightData>? =
        read<SavedDataFile<EnderDragonFightData>>(ENDER_DRAGON_FIGHT_ID)
}

internal val WORLD_BORDER_ID = SavedDataId("world_border")
internal val CHUNK_TICKETS_ID = SavedDataId("chunk_tickets")
internal val RAIDS_ID = SavedDataId("raids")
internal val ENDER_DRAGON_FIGHT_ID = SavedDataId("ender_dragon_fight")
