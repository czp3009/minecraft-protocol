package com.hiczp.minecraft.world.io

import com.hiczp.minecraft.world.format.datapack.*
import okio.BufferedSource

/** Read-only access to the `datapacks` directory under one mutable world lease. */
class MinecraftWorldDataPacks internal constructor(
    private val minecraftWorldAccess: MinecraftWorldAccess,
) {
    suspend fun inspect(dataPackId: DataPackId): DataPackInspection =
        minecraftWorldAccess.inspectDataPack(dataPackId)

    suspend fun read(dataPackId: DataPackId): DataPack = minecraftWorldAccess.readDataPack(dataPackId)

    suspend fun read(dataPackInspection: DataPackInspection): DataPack =
        minecraftWorldAccess.readDataPack(dataPackInspection)

    suspend fun readArchive(dataPackId: DataPackId): DataPackArchive =
        minecraftWorldAccess.readDataPackArchive(dataPackId)

    suspend fun readArchive(dataPackInspection: DataPackInspection): DataPackArchive =
        minecraftWorldAccess.readDataPackArchive(dataPackInspection)

    suspend fun readFile(
        dataPackId: DataPackId,
        dataPackFilePath: DataPackFilePath,
    ): DataPackFileBytes = minecraftWorldAccess.readDataPackFile(dataPackId, dataPackFilePath)

    suspend fun <T> readFile(
        dataPackId: DataPackId,
        dataPackFilePath: DataPackFilePath,
        block: (BufferedSource) -> T,
    ): T = minecraftWorldAccess.readDataPackFile(dataPackId, dataPackFilePath, block)

    suspend fun readFile(
        dataPackInspection: DataPackInspection,
        dataPackFilePath: DataPackFilePath,
    ): DataPackFileBytes = minecraftWorldAccess.readDataPackFile(dataPackInspection, dataPackFilePath)

    suspend fun <T> readFile(
        dataPackInspection: DataPackInspection,
        dataPackFilePath: DataPackFilePath,
        block: (BufferedSource) -> T,
    ): T = minecraftWorldAccess.readDataPackFile(dataPackInspection, dataPackFilePath, block)

    suspend fun inspectEnabledFiles(enabledDataPackIds: List<DataPackId>): List<DataPackInspection> =
        minecraftWorldAccess.inspectEnabledFileDataPacks(enabledDataPackIds)

    suspend fun inspectEnabledFiles(): List<DataPackInspection> =
        minecraftWorldAccess.inspectEnabledFileDataPacks()

    suspend fun readEnabled(enabledDataPackIds: List<DataPackId>): WorldDataPackLoadResult =
        minecraftWorldAccess.readEnabledDataPacks(enabledDataPackIds)

    suspend fun readEnabled(): WorldDataPackLoadResult = minecraftWorldAccess.readEnabledDataPacks()
}

/** Synchronous read-only access to the `datapacks` directory in a live world. */
class LiveMinecraftWorldDataPacks internal constructor(
    private val liveMinecraftWorldAccess: LiveMinecraftWorldAccess,
) {
    fun inspect(dataPackId: DataPackId): DataPackInspection =
        liveMinecraftWorldAccess.inspectDataPack(dataPackId)

    fun read(dataPackId: DataPackId): DataPack = liveMinecraftWorldAccess.readDataPack(dataPackId)

    fun read(dataPackInspection: DataPackInspection): DataPack =
        liveMinecraftWorldAccess.readDataPack(dataPackInspection)

    fun readArchive(dataPackId: DataPackId): DataPackArchive =
        liveMinecraftWorldAccess.readDataPackArchive(dataPackId)

    fun readArchive(dataPackInspection: DataPackInspection): DataPackArchive =
        liveMinecraftWorldAccess.readDataPackArchive(dataPackInspection)

    fun readFile(
        dataPackId: DataPackId,
        dataPackFilePath: DataPackFilePath,
    ): DataPackFileBytes = liveMinecraftWorldAccess.readDataPackFile(dataPackId, dataPackFilePath)

    fun <T> readFile(
        dataPackId: DataPackId,
        dataPackFilePath: DataPackFilePath,
        block: (BufferedSource) -> T,
    ): T = liveMinecraftWorldAccess.readDataPackFile(dataPackId, dataPackFilePath, block)

    fun readFile(
        dataPackInspection: DataPackInspection,
        dataPackFilePath: DataPackFilePath,
    ): DataPackFileBytes = liveMinecraftWorldAccess.readDataPackFile(dataPackInspection, dataPackFilePath)

    fun <T> readFile(
        dataPackInspection: DataPackInspection,
        dataPackFilePath: DataPackFilePath,
        block: (BufferedSource) -> T,
    ): T = liveMinecraftWorldAccess.readDataPackFile(dataPackInspection, dataPackFilePath, block)

    fun inspectEnabledFiles(enabledDataPackIds: List<DataPackId>): List<DataPackInspection> =
        liveMinecraftWorldAccess.inspectEnabledFileDataPacks(enabledDataPackIds)

    fun inspectEnabledFiles(): List<DataPackInspection> =
        liveMinecraftWorldAccess.inspectEnabledFileDataPacks()

    fun readEnabled(enabledDataPackIds: List<DataPackId>): WorldDataPackLoadResult =
        liveMinecraftWorldAccess.readEnabledDataPacks(enabledDataPackIds)

    fun readEnabled(): WorldDataPackLoadResult = liveMinecraftWorldAccess.readEnabledDataPacks()
}
