package com.hiczp.minecraft.demo.worldmcp

import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal suspend fun WorldEditor.readBlocks(worldSession: WorldSession, arguments: JsonObject): JsonObject {
    val dimensionId = arguments.dimensionId()
    val box = arguments.blockBox()
    validateBounds(worldSession, dimensionId, box)
    val offset = pageOffset("read_blocks", arguments, box.volume)
    val end = minOf(box.volume, offset + pageSize(arguments, 256))
    val positions = (offset until end).map(box::position)
    val results = hashMapOf<BlockPosition, JsonElement>()
    for ((regionPosition, regionPositions) in positions.groupBy { it.regionPosition }) {
        worldSession.withRegion(dimensionId, RegionKind.TERRAIN, regionPosition) { regionAccess ->
            for ((chunkPosition, chunkPositions) in regionPositions.groupBy { it.chunkPosition }) {
                currentCoroutineContext().ensureActive()
                val document = regionAccess.read(chunkPosition)
                val chunk = document?.let { worldSession.decoder(dimensionId).decodeDocument(it).chunk }
                for (position in chunkPositions) results[position] = buildJsonObject {
                    put("position", position.json())
                    put(
                        "status", when {
                            chunk == null -> "missing_chunk"; !chunk.isFullyGenerated -> "incomplete_chunk"; else -> "present"
                        }
                    )
                    put("state", chunk?.takeIf { it.isFullyGenerated }?.getBlockState(position)?.json() ?: JsonNull)
                }
            }
        }
    }
    return page("read_blocks", arguments, positions.map { results.getValue(it) }, end, box.volume)
}

internal suspend fun WorldEditor.writeBlocks(
    worldSession: WorldSession,
    name: String,
    arguments: JsonObject
): JsonObject {
    val dimensionId = arguments.dimensionId()
    val explicit = arguments["blocks"]?.jsonArray
    require(explicit == null || name == "write_blocks") { "fill_blocks accepts bounds, not a blocks list" }
    require(
        explicit == null || listOf(
            "min",
            "max",
            "values"
        ).none { it in arguments }) { "Choose either explicit blocks or min/max/values" }
    val box = if (explicit == null) arguments.blockBox() else null
    box?.let { validateBounds(worldSession, dimensionId, it) }
    val replacements = linkedMapOf<BlockPosition, BlockState>()
    val fill = if (name == "fill_blocks") worldSession.blockState(arguments.obj("state")) else null
    if (fill == null) {
        if (explicit != null) {
            for (entry in explicit) {
                val value = entry.jsonObject
                val position = value.array("position").blockPosition()
                require(worldSession.context(dimensionId).dimensionTypeLayout.chunkLayout.containsBlockY(position.y)) { "Block $position is outside the dimension height" }
                require(
                    replacements.put(
                        position,
                        worldSession.blockState(value.obj("state"))
                    ) == null
                ) { "Duplicate position $position" }
            }
        } else {
            val values = arguments.array("values")
            require(values.size.toLong() == box!!.volume) { "values must cover the complete inclusive box in X/Z/Y order" }
            values.forEachIndexed { index, value ->
                replacements[box.position(index.toLong())] = worldSession.blockState(value)
            }
        }
    }
    val requested = (box?.chunkRange?.toList() ?: replacements.keys.map { it.chunkPosition }.distinct())
    val missingMode = arguments.text("missingChunks", "error")
    require(missingMode == "error" || missingMode == "create_empty") { "missingChunks must be error or create_empty" }
    val biome = if (missingMode == "create_empty") BiomeId.parse(arguments.text("biome")) else null
    val byRegion = requested.groupBy { it.regionPosition }
    // Preflight every target before starting writes: missing and incomplete terrain must not produce a half-filled road.
    for ((regionPosition, chunkPositions) in byRegion) worldSession.withRegion(
        dimensionId,
        RegionKind.TERRAIN,
        regionPosition
    ) { regionAccess ->
        for (chunkPosition in chunkPositions) {
            currentCoroutineContext().ensureActive()
            val inspection = regionAccess.inspectTerrain(chunkPosition)
            require(inspection != null || biome != null) { "Missing Chunk $chunkPosition; explicitly choose missingChunks=create_empty and biome to create empty terrain" }
            require(inspection == null || inspection.status == "minecraft:full") { "Chunk $chunkPosition is not fully generated; use raw editing for generation data" }
        }
    }
    val completed = mutableListOf<ChunkPosition>()
    val changedChunks = linkedSetOf<ChunkPosition>()
    var changedBlocks = 0L
    var phase = "edit"
    val match = arguments["match"]?.jsonObject
    val matchName = match?.get("name")?.jsonPrimitive?.content?.let { BlockId.parse(it) }
    val matchProperties = match?.get("properties")?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
    val byChunk = replacements.entries.groupBy { it.key.chunkPosition }
    try {
        for ((regionPosition, chunkPositions) in byRegion) {
            worldSession.withRegion(dimensionId, RegionKind.TERRAIN, regionPosition) { regionAccess ->
                for (chunkPosition in chunkPositions) {
                    currentCoroutineContext().ensureActive()
                    val document = regionAccess.read(chunkPosition)
                    val decoded = document?.let { worldSession.decoder(dimensionId).decodeDocument(it) }
                    val context = worldSession.context(dimensionId)
                    val chunk = decoded?.chunk ?: Chunk(chunkPosition, context).also { empty ->
                        for (y in context.dimensionTypeLayout.chunkLayout.sectionYRange) {
                            empty.setSection(y, ChunkSection(context.defaultBlockState, requireNotNull(biome)))
                        }
                    }
                    val changed = hashSetOf<BlockPosition>()
                    fun replace(position: BlockPosition, state: BlockState) {
                        val old = chunk.getBlockState(position)
                        if (matchName != null && old.blockId != matchName) return
                        if (matchProperties.any { (key, value) -> old.properties.asMap()[key] != value }) return
                        if (EditMaintenance.replace(chunk, position, state)) changed.add(position)
                    }
                    if (fill != null) {
                        val intersection = box!!.intersect(chunkPosition)
                        for (y in intersection.min.y..intersection.max.y) {
                            currentCoroutineContext().ensureActive()
                            for (z in intersection.min.z..intersection.max.z) for (x in intersection.min.x..intersection.max.x) replace(
                                BlockPosition(x, y, z),
                                fill
                            )
                        }
                    } else byChunk[chunkPosition].orEmpty().forEach { replace(it.key, it.value) }
                    if (changed.isNotEmpty() || document == null) {
                        EditMaintenance.cleanTicks(chunk, changed)
                        EditMaintenance.invalidate(chunk)
                        phase = "poi"
                        EditMaintenance.invalidatePoi(worldSession, dimensionId, chunkPosition, changed)
                        phase = "terrain"
                        val metadata = decoded?.chunkNbtMetadata ?: ChunkNbtMetadata(
                            MinecraftWorldFormat.WORLD_VERSION,
                            worldSession.levelDat.data.time
                        )
                        val encoded = worldSession.encoder(dimensionId, metadata).encodeDocument(chunk)
                        regionAccess.write(chunkPosition, encoded)
                        changedChunks.add(chunkPosition)
                        changedBlocks += changed.size
                    }
                }
            }
            completed.addAll(chunkPositions)
        }
        phase = "neighbour_light"
        EditMaintenance.invalidateNeighbourLight(worldSession, dimensionId, changedChunks)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        throw persistenceFailure(failure, phase, completed, requested)
    }
    return buildJsonObject {
        put("changedBlocks", changedBlocks)
        put("writtenChunks", JsonArray(changedChunks.map(ChunkPosition::json)))
        put(
            "maintenance",
            "heightmaps_light_poi_invalidated; changed-position ticks removed; replaced block entities removed; new block entities use vanilla defaults on access"
        )
    }
}

private fun validateBounds(worldSession: WorldSession, dimensionId: DimensionId, box: BlockBox) {
    val layout = worldSession.context(dimensionId).dimensionTypeLayout.chunkLayout
    require(layout.containsBlockY(box.min.y) && layout.containsBlockY(box.max.y)) { "Requested Y range is outside ${layout.minBlockY}..${layout.maxBlockY}" }
}
