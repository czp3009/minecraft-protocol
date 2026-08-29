package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.world.format.PlayerAdvancements
import com.hiczp.minecraft.world.format.PlayerData
import com.hiczp.minecraft.world.format.PlayerStatistics
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import okio.BufferedSink
import okio.BufferedSource
import kotlin.jvm.JvmName

/** Coordinated access to the standard files keyed by player UUID under one mutable world lease. */
class MinecraftWorldPlayers internal constructor(
    val configuration: MinecraftWorldAccessConfiguration,
    private val worldOperationLifecycle: WorldOperationLifecycle,
    private val logicalResourceCoordinator: LogicalResourceCoordinator<WorldResourceKey>,
    private val playerDataStore: PlayerDataStore,
    private val playerStatisticsStore: PlayerStatisticsStore,
    private val playerAdvancementsStore: PlayerAdvancementsStore,
) {
    /** Returns a sorted detached snapshot of UUIDs represented under `players/data`. */
    suspend fun listUuids(): List<String> = worldOperationLifecycle.withOperation { playerDataStore.listUuids() }

    suspend fun readDataDocument(playerUuid: String): NbtDocument? = readRecovering(
        WorldResourceKey.PlayerData(playerUuid),
        { playerDataStore.readDocumentForSharedAccess(playerUuid) },
        { playerDataStore.readDocument(playerUuid) },
    )

    suspend fun <T> readData(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = readRecovering(
        WorldResourceKey.PlayerData(playerUuid),
        { playerDataStore.readForSharedAccess(playerUuid, deserializationStrategy) },
        { playerDataStore.read(playerUuid, deserializationStrategy) },
    )

    suspend fun readData(playerUuid: String): PlayerData? = readData<PlayerData>(playerUuid)

    @JvmName("readTypedData")
    suspend inline fun <reified T> readData(playerUuid: String): T? = readData(
        playerUuid,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
    )

    suspend fun <T> readData(playerUuid: String, block: (BufferedSource) -> T): T? = readRecovering(
        WorldResourceKey.PlayerData(playerUuid),
        { playerDataStore.readForSharedAccess(playerUuid, block) },
        { playerDataStore.read(playerUuid, block) },
    )

    suspend fun writeDataDocument(playerUuid: String, nbtDocument: NbtDocument) =
        write(WorldResourceKey.PlayerData(playerUuid)) { playerDataStore.writeDocument(playerUuid, nbtDocument) }

    suspend fun <T> writeData(
        playerUuid: String,
        value: T,
        serializationStrategy: SerializationStrategy<T>,
    ) = write(WorldResourceKey.PlayerData(playerUuid)) {
        playerDataStore.write(playerUuid, value, serializationStrategy)
    }

    suspend fun writeData(playerUuid: String, playerData: PlayerData) = writeData<PlayerData>(playerUuid, playerData)

    suspend inline fun <reified T> writeData(playerUuid: String, value: T) = writeData(
        playerUuid,
        value,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
    )

    suspend fun writeData(playerUuid: String, block: (BufferedSink) -> Unit) =
        write(WorldResourceKey.PlayerData(playerUuid)) { playerDataStore.write(playerUuid, block) }

    suspend fun readStatisticsJson(playerUuid: String): JsonElement? =
        read(WorldResourceKey.Statistics(playerUuid)) { playerStatisticsStore.readJson(playerUuid) }

    suspend fun <T> readStatistics(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = read(WorldResourceKey.Statistics(playerUuid)) {
        playerStatisticsStore.read(playerUuid, deserializationStrategy)
    }

    suspend fun readStatistics(playerUuid: String): PlayerStatistics? = readStatistics<PlayerStatistics>(playerUuid)

    @JvmName("readTypedStatistics")
    suspend inline fun <reified T> readStatistics(playerUuid: String): T? =
        readStatistics(playerUuid, configuration.standaloneJson.serializersModule.serializer())

    suspend fun <T> readStatistics(playerUuid: String, block: (BufferedSource) -> T): T? =
        read(WorldResourceKey.Statistics(playerUuid)) { playerStatisticsStore.read(playerUuid, block) }

    suspend fun writeStatisticsJson(playerUuid: String, jsonElement: JsonElement) =
        write(WorldResourceKey.Statistics(playerUuid)) {
            playerStatisticsStore.writeJson(playerUuid, jsonElement)
        }

    suspend fun <T> writeStatistics(
        playerUuid: String,
        value: T,
        serializationStrategy: SerializationStrategy<T>,
    ) = write(WorldResourceKey.Statistics(playerUuid)) {
        playerStatisticsStore.write(playerUuid, value, serializationStrategy)
    }

    suspend fun writeStatistics(playerUuid: String, playerStatistics: PlayerStatistics) =
        writeStatistics<PlayerStatistics>(playerUuid, playerStatistics)

    suspend inline fun <reified T> writeStatistics(playerUuid: String, value: T) =
        writeStatistics(playerUuid, value, configuration.standaloneJson.serializersModule.serializer())

    suspend fun writeStatistics(playerUuid: String, block: (BufferedSink) -> Unit) =
        write(WorldResourceKey.Statistics(playerUuid)) { playerStatisticsStore.write(playerUuid, block) }

    suspend fun readAdvancementsJson(playerUuid: String): JsonElement? =
        read(WorldResourceKey.Advancements(playerUuid)) { playerAdvancementsStore.readJson(playerUuid) }

    suspend fun <T> readAdvancements(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = read(WorldResourceKey.Advancements(playerUuid)) {
        playerAdvancementsStore.read(playerUuid, deserializationStrategy)
    }

    suspend fun readAdvancements(playerUuid: String): PlayerAdvancements? =
        readAdvancements<PlayerAdvancements>(playerUuid)

    @JvmName("readTypedAdvancements")
    suspend inline fun <reified T> readAdvancements(playerUuid: String): T? =
        readAdvancements(playerUuid, configuration.standaloneJson.serializersModule.serializer())

    suspend fun <T> readAdvancements(playerUuid: String, block: (BufferedSource) -> T): T? =
        read(WorldResourceKey.Advancements(playerUuid)) { playerAdvancementsStore.read(playerUuid, block) }

    suspend fun writeAdvancementsJson(playerUuid: String, jsonElement: JsonElement) =
        write(WorldResourceKey.Advancements(playerUuid)) {
            playerAdvancementsStore.writeJson(playerUuid, jsonElement)
        }

    suspend fun <T> writeAdvancements(
        playerUuid: String,
        value: T,
        serializationStrategy: SerializationStrategy<T>,
    ) = write(WorldResourceKey.Advancements(playerUuid)) {
        playerAdvancementsStore.write(playerUuid, value, serializationStrategy)
    }

    suspend fun writeAdvancements(playerUuid: String, playerAdvancements: PlayerAdvancements) =
        writeAdvancements<PlayerAdvancements>(playerUuid, playerAdvancements)

    suspend inline fun <reified T> writeAdvancements(playerUuid: String, value: T) =
        writeAdvancements(playerUuid, value, configuration.standaloneJson.serializersModule.serializer())

    suspend fun writeAdvancements(playerUuid: String, block: (BufferedSink) -> Unit) =
        write(WorldResourceKey.Advancements(playerUuid)) { playerAdvancementsStore.write(playerUuid, block) }

    private suspend fun <T> read(worldResourceKey: WorldResourceKey, block: () -> T): T =
        worldOperationLifecycle.withOperation { logicalResourceCoordinator.read(worldResourceKey) { block() } }

    private suspend fun <T> write(worldResourceKey: WorldResourceKey, block: () -> T): T =
        worldOperationLifecycle.withOperation { logicalResourceCoordinator.write(worldResourceKey) { block() } }

    private suspend fun <T> readRecovering(
        worldResourceKey: WorldResourceKey,
        sharedRead: () -> CoordinatedRead<T>,
        exclusiveRead: () -> T,
    ): T = worldOperationLifecycle.withOperation {
        when (val coordinatedRead = logicalResourceCoordinator.read(worldResourceKey) { sharedRead() }) {
            is CoordinatedRead.Complete -> coordinatedRead.value
            CoordinatedRead.RequiresExclusive -> logicalResourceCoordinator.write(worldResourceKey) { exclusiveRead() }
        }
    }
}

/** Synchronous read-only access to the standard files keyed by player UUID in a live world. */
class LiveMinecraftWorldPlayers internal constructor(
    val configuration: LiveMinecraftWorldAccessConfiguration,
    private val playerDataStore: PlayerDataStore,
    private val playerStatisticsStore: PlayerStatisticsStore,
    private val playerAdvancementsStore: PlayerAdvancementsStore,
) {
    /** Returns a sorted detached snapshot of UUIDs represented under `players/data`. */
    fun listUuids(): List<String> = playerDataStore.listUuids()

    fun readDataDocument(playerUuid: String): NbtDocument? = playerDataStore.readDocument(playerUuid)

    fun <T> readData(playerUuid: String, deserializationStrategy: DeserializationStrategy<T>): T? =
        playerDataStore.read(playerUuid, deserializationStrategy)

    fun readData(playerUuid: String): PlayerData? = readData<PlayerData>(playerUuid)

    @JvmName("readTypedData")
    inline fun <reified T> readData(playerUuid: String): T? = readData(
        playerUuid,
        configuration.standaloneNbtFormat.serializersModule.serializer(),
    )

    fun <T> readData(playerUuid: String, block: (BufferedSource) -> T): T? = playerDataStore.read(playerUuid, block)

    fun readStatisticsJson(playerUuid: String): JsonElement? = playerStatisticsStore.readJson(playerUuid)

    fun <T> readStatistics(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = playerStatisticsStore.read(playerUuid, deserializationStrategy)

    fun readStatistics(playerUuid: String): PlayerStatistics? = readStatistics<PlayerStatistics>(playerUuid)

    @JvmName("readTypedStatistics")
    inline fun <reified T> readStatistics(playerUuid: String): T? =
        readStatistics(playerUuid, configuration.standaloneJson.serializersModule.serializer())

    fun <T> readStatistics(playerUuid: String, block: (BufferedSource) -> T): T? =
        playerStatisticsStore.read(playerUuid, block)

    fun readAdvancementsJson(playerUuid: String): JsonElement? = playerAdvancementsStore.readJson(playerUuid)

    fun <T> readAdvancements(
        playerUuid: String,
        deserializationStrategy: DeserializationStrategy<T>,
    ): T? = playerAdvancementsStore.read(playerUuid, deserializationStrategy)

    fun readAdvancements(playerUuid: String): PlayerAdvancements? = readAdvancements<PlayerAdvancements>(playerUuid)

    @JvmName("readTypedAdvancements")
    inline fun <reified T> readAdvancements(playerUuid: String): T? =
        readAdvancements(playerUuid, configuration.standaloneJson.serializersModule.serializer())

    fun <T> readAdvancements(playerUuid: String, block: (BufferedSource) -> T): T? =
        playerAdvancementsStore.read(playerUuid, block)
}
