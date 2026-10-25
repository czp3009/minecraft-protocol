package com.hiczp.minecraft.demo.worldmcp

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

internal suspend fun WorldEditor.readContent(
    worldSession: WorldSession,
    name: String,
    arguments: JsonObject
): JsonObject {
    val dimensionId = arguments.dimensionId()
    val kind = arguments.kind()
    val raw = arguments.raw()
    val regionPosition =
        if (name == "read_region") arguments.regionPosition() else arguments.chunkPosition().regionPosition
    return worldSession.withRegion(dimensionId, kind, regionPosition) { regionAccess ->
        if (name == "read_region") {
            val positions = regionAccess.positions()
            val offset = pageOffset(name, arguments, positions.size.toLong()).toInt()
            val end = minOf(positions.size, offset + pageSize(arguments, 1))
            val items = positions.subList(offset, end).map { chunkPosition ->
                currentCoroutineContext().ensureActive()
                val document =
                    requireNotNull(regionAccess.read(chunkPosition)) { "Indexed Chunk $chunkPosition has no readable payload" }
                buildJsonObject {
                    put("chunk", chunkPosition.json())
                    put("data", contentValue(worldSession, dimensionId, kind, raw, document))
                }
            }
            page(name, arguments, items, end.toLong(), positions.size.toLong())
        } else {
            val document = regionAccess.read(arguments.chunkPosition())
            buildJsonObject {
                put("exists", document != null)
                put("representation", if (raw) "raw" else "expanded")
                if (document != null) {
                    if (name == "read_chunk") put("data", contentValue(worldSession, dimensionId, kind, raw, document))
                    else {
                        require(kind != RegionKind.ENTITIES) { "Entity storage has no Sections" }
                        val y = arguments.int("sectionY")
                        val section = sectionTag(document, kind, y)
                        put("sectionExists", section != null)
                        put(
                            "data", when {
                            section == null -> JsonNull
                            raw -> JsonPrimitive(section.snbt())
                            kind == RegionKind.POI -> buildJsonObject { put("nbt", section.snbt()) }
                            else -> {
                                val chunk = worldSession.decoder(dimensionId).decodeDocument(document).chunk
                                TerrainContent(worldSession, dimensionId).section(
                                    section,
                                    requireNotNull(chunk.getSection(y))
                                )
                            }
                        })
                    }
                }
            }
        }
    }
}

private fun contentValue(
    worldSession: WorldSession,
    dimensionId: DimensionId,
    kind: RegionKind,
    raw: Boolean,
    document: NbtDocument
): JsonElement = when {
    raw -> JsonPrimitive(document.root.snbt())
    kind == RegionKind.TERRAIN -> TerrainContent(worldSession, dimensionId).expand(document)
    else -> buildJsonObject { put("nbt", document.root.snbt()) }
}

internal suspend fun WorldEditor.writeContent(
    worldSession: WorldSession,
    name: String,
    arguments: JsonObject
): JsonObject {
    val dimensionId = arguments.dimensionId()
    val kind = arguments.kind()
    val raw = arguments.raw()
    val prepared = linkedMapOf<ChunkPosition, NbtDocument>()
    if (name == "write_section") {
        require(kind != RegionKind.ENTITIES) { "Entity storage has no Sections" }
        val chunkPosition = arguments.chunkPosition()
        val y = arguments.int("sectionY")
        worldSession.withRegion(dimensionId, kind, chunkPosition.regionPosition) { regionAccess ->
            val old =
                requireNotNull(regionAccess.read(chunkPosition)) { "Section writes require an existing Chunk; create it with write_chunk first" }
            val section = when {
                raw -> arguments["data"]!!.jsonPrimitive.content.compoundSnbt()
                kind == RegionKind.POI -> arguments.obj("data").text("nbt").compoundSnbt()
                else -> {
                    val decoded = worldSession.decoder(dimensionId).decodeDocument(old)
                    require(decoded.chunk.isFullyGenerated) { "Expanded Section editing requires a completed Chunk" }
                    val input = arguments.obj("data")
                    require(input.int("y") == y) { "Section payload Y does not match the target" }
                    val previous = decoded.chunk.getSection(y)
                    decoded.chunk.setSection(
                        y,
                        TerrainContent(worldSession, dimensionId).decodeSection(input, decoded.chunk)
                    )
                    val changed = hashSetOf<BlockPosition>()
                    if (y in worldSession.context(dimensionId).dimensionTypeLayout.chunkLayout) {
                        for (local in MinecraftCoordinates.localBlockPositions()) {
                            val position = SectionPosition(chunkPosition.x, y, chunkPosition.z).block(local)
                            val before = previous?.terrain?.blockStates?.get(local.index)
                                ?: decoded.chunk.chunkContext.defaultBlockState
                            val after = decoded.chunk.getBlockState(position)
                            if (before != after) changed.add(position)
                            if (before.blockId != after.blockId) decoded.chunk.blockEntities.remove(position)
                        }
                    }
                    EditMaintenance.cleanTicks(decoded.chunk, changed)
                    EditMaintenance.invalidate(decoded.chunk)
                    val encoded =
                        worldSession.encoder(dimensionId, decoded.chunkNbtMetadata).encodeDocument(decoded.chunk)
                    prepared[chunkPosition] = encoded
                    null
                }
            }
            if (section != null) {
                if (kind == RegionKind.TERRAIN) require(section.integer("Y") == y) { "Section payload Y does not match the target" }
                val root = if (kind == RegionKind.TERRAIN) {
                    old.root.withFields(
                        "sections" to NbtList(
                            old.root.list("sections").filter { (it as NbtCompound).integer("Y") != y } + section))
                } else {
                    val sections = (old.root["Sections"] as? NbtCompound) ?: NbtCompound(emptyMap())
                    old.root.withFields("Sections" to sections.withFields(y.toString() to section))
                }
                prepared[chunkPosition] = NbtDocument(root)
            }
        }
    } else {
        val entries = if (name == "write_region") arguments.array("data").map { it.jsonObject }
        else listOf(buildJsonObject {
            put("chunk", arguments.obj("chunk")); put(
            "data",
            requireNotNull(arguments["data"])
        )
        })
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            val chunkPosition = entry.chunkPosition()
            if (name == "write_region") require(chunkPosition.regionPosition == arguments.regionPosition()) { "Chunk $chunkPosition is outside the target Region" }
            require(chunkPosition !in prepared) { "Duplicate Chunk $chunkPosition" }
            val data = requireNotNull(entry["data"])
            val document = when {
                raw -> NbtDocument(data.jsonPrimitive.content.compoundSnbt())
                kind == RegionKind.TERRAIN -> {
                    val decoded = TerrainContent(worldSession, dimensionId).decode(data.jsonObject)
                    EditMaintenance.invalidate(decoded.chunk)
                    worldSession.encoder(dimensionId, decoded.chunkNbtMetadata).encodeDocument(decoded.chunk)
                }

                else -> NbtDocument(data.jsonObject.text("nbt").compoundSnbt())
            }
            validateDocument(document, kind, chunkPosition)
            prepared[chunkPosition] = document
        }
    }
    val completed = mutableListOf<ChunkPosition>()
    val affected = prepared.keys.toMutableSet()
    if (!raw && kind == RegionKind.TERRAIN && name == "write_region") {
        worldSession.withRegion(dimensionId, kind, arguments.regionPosition()) { affected.addAll(it.positions()) }
    }
    var phase = "prepare"
    try {
        // All caller data has been parsed and validated before the first persistent mutation.
        if (!raw && kind == RegionKind.TERRAIN) {
            phase = "poi"
            for (chunkPosition in affected) EditMaintenance.invalidatePoi(
                worldSession,
                dimensionId,
                chunkPosition,
                null
            )
        }
        phase = "terrain_or_record"
        if (name == "write_region") {
            worldSession.withRegion(dimensionId, kind, arguments.regionPosition()) { regionAccess ->
                val compressed =
                    prepared.mapValues { (_, document) -> document.withCurrentDataVersion().toCompressedChunk() }
                regionAccess.replace {
                    compressed.forEach { (chunkPosition, compressedChunk) ->
                        writeCompressedChunk(
                            chunkPosition,
                            compressedChunk
                        )
                    }
                }
            }
            completed.addAll(prepared.keys)
        } else {
            for ((chunkPosition, document) in prepared) {
                worldSession.withRegion(dimensionId, kind, chunkPosition.regionPosition) {
                    it.write(
                        chunkPosition,
                        document
                    )
                }
                completed.add(chunkPosition)
            }
        }
        if (!raw && kind == RegionKind.TERRAIN) {
            phase = "neighbour_light"
            EditMaintenance.invalidateNeighbourLight(worldSession, dimensionId, affected)
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        throw persistenceFailure(failure, phase, completed, prepared.keys.toList())
    }
    return buildJsonObject {
        put("writtenChunks", JsonArray(completed.map(ChunkPosition::json)))
        put("replaced", name.removePrefix("write_"))
        put(
            "maintenance",
            if (raw || kind != RegionKind.TERRAIN) "supplied_nbt" else "heightmaps_light_poi_invalidated"
        )
    }
}

internal fun JsonObject.raw(): Boolean = when (text("representation", "raw")) {
    "raw" -> true
    "expanded" -> false
    else -> error("representation must be raw or expanded")
}

private fun sectionTag(document: NbtDocument, kind: RegionKind, y: Int): NbtCompound? = when (kind) {
    RegionKind.TERRAIN -> document.root.list("sections").map { it as NbtCompound }.singleOrNull { it.integer("Y") == y }
    RegionKind.POI -> (document.root["Sections"] as? NbtCompound)?.get(y.toString()) as? NbtCompound
    RegionKind.ENTITIES -> error("Entity storage has no Sections")
}

private fun validateDocument(document: NbtDocument, kind: RegionKind, chunkPosition: ChunkPosition) {
    when (kind) {
        RegionKind.TERRAIN -> {
            require(
                ChunkPosition(
                    document.root.integer("xPos"),
                    document.root.integer("zPos")
                ) == chunkPosition
            ) { "Stored Chunk position does not match the target" }
            require(document.root["Status"] is NbtString && document.root["sections"] is NbtList) { "Terrain needs Status and sections" }
            val sections = document.root.list("sections").map { (it as NbtCompound).integer("Y") }
            require(sections.distinct().size == sections.size) { "Duplicate Section Y" }
        }

        RegionKind.ENTITIES -> {
            val position = document.root["Position"] as? NbtIntArray ?: error("Entity Chunk needs Position")
            require(
                position.value.contentEquals(
                    intArrayOf(
                        chunkPosition.x,
                        chunkPosition.z
                    )
                )
            ) { "Entity Chunk position does not match the target" }
            require(document.root["Entities"] is NbtList) { "Entity Chunk needs Entities" }
        }

        RegionKind.POI -> require(document.root["Sections"] is NbtCompound) { "POI Chunk needs Sections" }
    }
}

internal fun persistenceFailure(
    failure: Exception,
    phase: String,
    completed: List<ChunkPosition>,
    requested: List<ChunkPosition>
): EditorException = EditorException(
    buildJsonObject {
        put("message", failure.message ?: "Persistence failed")
        put("phase", phase)
        put("completed", JsonArray(completed.map(ChunkPosition::json)))
        put("notConfirmed", JsonArray((requested - completed.toSet()).map(ChunkPosition::json)))
        put("partialWritesPossible", true)
    },
    failure,
)
