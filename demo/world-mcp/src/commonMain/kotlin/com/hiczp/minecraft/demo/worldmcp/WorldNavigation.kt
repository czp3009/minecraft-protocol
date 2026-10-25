package com.hiczp.minecraft.demo.worldmcp

import com.hiczp.minecraft.nbt.serialization.SnbtFormat
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.world.format.*
import com.hiczp.minecraft.world.io.RegionChunkInfo
import kotlinx.serialization.json.*
import okio.FileSystem

internal suspend fun WorldEditor.listRegions(worldSession: WorldSession, arguments: JsonObject): JsonObject {
    val kinds = arguments["kind"]?.let { listOf(arguments.kind()) } ?: RegionKind.entries
    val positions = kinds.flatMap { worldSession.regionPositions(arguments.dimensionId(), it) }.distinct()
        .sortedWith(compareBy({ it.z }, { it.x }))
    return paginate("list_regions", arguments, positions.map { regionPosition ->
        buildJsonObject {
            put("region", regionPosition.json())
            put(
                "chunks",
                buildJsonObject {
                    put("min", regionPosition.chunk(LocalChunkPosition(0, 0)).json()); put(
                    "max",
                    regionPosition.chunk(LocalChunkPosition(31, 31)).json()
                )
                })
        }
    })
}

internal suspend fun WorldEditor.regionInfo(worldSession: WorldSession, arguments: JsonObject): JsonObject {
    val dimensionId = arguments.dimensionId()
    val regionPosition = arguments.regionPosition()
    val storage = buildJsonObject {
        for (kind in RegionKind.entries) {
            val file =
                worldSession.minecraftWorldAccess.minecraftWorldPaths.dimension(dimensionId) / kind.directory / "r.${regionPosition.x}.${regionPosition.z}.mca"
            val metadata = FileSystem.SYSTEM.metadataOrNull(file)
            worldSession.withRegion(dimensionId, kind, regionPosition) { regionAccess ->
                put(kind.name.lowercase(), buildJsonObject {
                    put("exists", metadata != null)
                    put("path", file.toString())
                    put("fileBytes", metadata?.size?.let(::JsonPrimitive) ?: JsonNull)
                    put("modifiedAtMillis", metadata?.lastModifiedAtMillis?.let(::JsonPrimitive) ?: JsonNull)
                    put("chunkCount", regionAccess.count())
                    if (arguments.flag("includeRecords")) {
                        put(
                            "records",
                            JsonArray(
                                regionAccess.positions()
                                    .map { position -> recordInfo(position, regionAccess.info(position), true) })
                        )
                    }
                })
            }
        }
    }
    val chunkMin = regionPosition.chunk(LocalChunkPosition(0, 0))
    val chunkMax = regionPosition.chunk(LocalChunkPosition(31, 31))
    return buildJsonObject {
        put("region", regionPosition.json())
        put("storage", storage)
        put("chunkBounds", buildJsonObject { put("min", chunkMin.json()); put("max", chunkMax.json()) })
        put(
            "blockBounds",
            BlockBox(
                worldSession.chunkBounds(dimensionId, chunkMin).min,
                worldSession.chunkBounds(dimensionId, chunkMax).max
            ).json()
        )
    }
}

internal suspend fun WorldEditor.listChunks(worldSession: WorldSession, arguments: JsonObject): JsonObject =
    worldSession.withRegion(arguments.dimensionId(), arguments.kind(), arguments.regionPosition()) { regionAccess ->
        val positions = regionAccess.positions()
        val offset = pageOffset("list_chunks", arguments, positions.size.toLong()).toInt()
        val end = minOf(positions.size, offset + pageSize(arguments))
        val items = positions.subList(offset, end).map { chunkPosition ->
            if (arguments.flag("includeRecords")) recordInfo(chunkPosition, regionAccess.info(chunkPosition), true)
            else buildJsonObject {
                put("chunk", chunkPosition.json()); put(
                "local",
                buildJsonObject {
                    put("x", chunkPosition.localChunkPosition.x); put(
                    "z",
                    chunkPosition.localChunkPosition.z
                )
                })
            }
        }
        page("list_chunks", arguments, items, end.toLong(), positions.size.toLong())
    }

internal suspend fun WorldEditor.chunkInfo(worldSession: WorldSession, arguments: JsonObject): JsonObject =
    worldSession.withRegion(
        arguments.dimensionId(),
        arguments.kind(),
        arguments.chunkPosition().regionPosition
    ) { regionAccess ->
        val chunkPosition = arguments.chunkPosition()
        val record = regionAccess.info(chunkPosition)
        val result = recordInfo(chunkPosition, record, regionAccess.hasChunk(chunkPosition)).toMutableMap()
        result["blockBounds"] = worldSession.chunkBounds(arguments.dimensionId(), chunkPosition).json()
        if (record != null && arguments.flag("includeContent")) {
            when (arguments.kind()) {
                RegionKind.TERRAIN -> regionAccess.inspectTerrain(chunkPosition)?.let { inspection ->
                    result["content"] = buildJsonObject {
                        put("dataVersion", inspection.dataVersion)
                        put("status", inspection.status)
                        put("lastUpdate", inspection.lastUpdate)
                        put("sectionYs", JsonArray(inspection.sections.map { JsonPrimitive(it.y.toInt()) }))
                    }
                }

                RegionKind.POI -> result["content"] = buildJsonObject {
                    put(
                        "sectionYs",
                        JsonArray(regionAccess.inspectPoi(chunkPosition)?.sections.orEmpty().keys.map { JsonPrimitive(it.toInt()) })
                    )
                }

                RegionKind.ENTITIES -> error("Entity content inspection is available through read_chunk")
            }
        }
        JsonObject(result)
    }

internal suspend fun WorldEditor.sections(
    worldSession: WorldSession,
    arguments: JsonObject,
    single: Boolean
): JsonObject {
    val kind = arguments.kind()
    require(kind != RegionKind.ENTITIES) { "Entity storage has no Section level" }
    return worldSession.withRegion(
        arguments.dimensionId(),
        kind,
        arguments.chunkPosition().regionPosition
    ) { regionAccess ->
        val items = when (kind) {
            RegionKind.TERRAIN -> regionAccess.inspectTerrain(arguments.chunkPosition())?.sections?.map { section ->
                buildJsonObject {
                    put("y", section.y.toInt())
                    put("minY", MinecraftCoordinates.sectionBlockCoordinate(section.y.toInt(), 0))
                    put("maxY", MinecraftCoordinates.sectionBlockCoordinate(section.y.toInt(), 15))
                    put("hasTerrain", section.blocks != null || section.biomes != null)
                    put(
                        "blockPalette",
                        section.blocks?.let {
                            JsonArray(it.palette.map { nbtTag ->
                                JsonPrimitive(
                                    SnbtFormat.encodeTagToString(nbtTag)
                                )
                            })
                        } ?: JsonNull)
                    put(
                        "biomePalette",
                        section.biomes?.let {
                            JsonArray(it.palette.map { nbtTag ->
                                JsonPrimitive(
                                    SnbtFormat.encodeTagToString(nbtTag)
                                )
                            })
                        } ?: JsonNull)
                    put("paletteIncludesUnusedEntries", true)
                }
            }

            RegionKind.POI -> regionAccess.inspectPoi(arguments.chunkPosition())?.sections?.map { (y, section) ->
                buildJsonObject {
                    put(
                        "y",
                        y.toInt()
                    ); put("valid", section.valid)
                }
            }

            RegionKind.ENTITIES -> error("Entity storage has no Sections")
        }
        if (single) buildJsonObject {
            put("chunkExists", items != null)
            put("section", items?.singleOrNull { it.int("y") == arguments.int("sectionY") } ?: JsonNull)
        } else paginate("list_sections", arguments, items.orEmpty().sortedBy { it.int("y") }).let {
            JsonObject(it + ("chunkExists" to JsonPrimitive(items != null)))
        }
    }
}

internal fun WorldEditor.coordinates(worldSession: WorldSession, arguments: JsonObject): JsonObject {
    val layout = worldSession.context(arguments.dimensionId()).dimensionTypeLayout.chunkLayout
    if (arguments.text("from") == "box") {
        val box = arguments.blockBox()
        val regions = box.chunkRange.coveringRegionRange
        return buildJsonObject {
            put("blockBounds", box.json())
            put(
                "chunkBounds",
                buildJsonObject { put("min", box.min.chunkPosition.json()); put("max", box.max.chunkPosition.json()) })
            put(
                "regionBounds",
                buildJsonObject {
                    put(
                        "min",
                        RegionPosition(regions.xRange.first, regions.zRange.first).json()
                    ); put("max", RegionPosition(regions.xRange.last, regions.zRange.last).json())
                })
            put(
                "sectionY",
                buildJsonObject { put("min", box.min.sectionPosition.y); put("max", box.max.sectionPosition.y) })
        }
    }
    val position = when (arguments.text("from")) {
        "block" -> arguments.array("position").blockPosition()
        "section" -> {
            val position = arguments.array("position").blockPosition()
            val local = arguments["local"]?.jsonArray?.blockPosition() ?: BlockPosition(0, 0, 0)
            BlockPosition(
                MinecraftCoordinates.sectionBlockCoordinate(position.x, local.x),
                MinecraftCoordinates.sectionBlockCoordinate(position.y, local.y),
                MinecraftCoordinates.sectionBlockCoordinate(position.z, local.z)
            )
        }

        "chunk" -> {
            val local = arguments["local"]?.jsonArray?.blockPosition() ?: BlockPosition(0, layout.minBlockY, 0)
            BlockPosition(
                MinecraftCoordinates.blockCoordinate(arguments.chunkPosition().x, local.x),
                local.y,
                MinecraftCoordinates.blockCoordinate(arguments.chunkPosition().z, local.z)
            )
        }

        "region" -> {
            val local = arguments["local"]?.jsonObject?.let { LocalChunkPosition(it.int("x"), it.int("z")) }
                ?: LocalChunkPosition(0, 0)
            val chunk = arguments.regionPosition().chunk(local)
            BlockPosition(
                MinecraftCoordinates.blockCoordinate(chunk.x, 0),
                layout.minBlockY,
                MinecraftCoordinates.blockCoordinate(chunk.z, 0)
            )
        }

        else -> error("from must be block, section, chunk, region or box")
    }
    return buildJsonObject {
        put("block", position.json())
        put(
            "section",
            JsonArray(
                listOf(
                    position.sectionPosition.x,
                    position.sectionPosition.y,
                    position.sectionPosition.z
                ).map(::JsonPrimitive)
            )
        )
        put("chunk", position.chunkPosition.json())
        put("region", position.regionPosition.json())
        put(
            "localBlock",
            JsonArray(
                listOf(
                    position.localInSection.x,
                    position.localInSection.y,
                    position.localInSection.z
                ).map(::JsonPrimitive)
            )
        )
        put(
            "localChunk",
            buildJsonObject {
                put("x", position.chunkPosition.localChunkPosition.x); put(
                "z",
                position.chunkPosition.localChunkPosition.z
            )
            })
    }
}

private fun WorldSession.chunkBounds(dimensionId: DimensionId, chunkPosition: ChunkPosition): BlockBox {
    val layout = context(dimensionId).dimensionTypeLayout.chunkLayout
    return BlockBox(
        BlockPosition(
            MinecraftCoordinates.blockCoordinate(chunkPosition.x, 0),
            layout.minBlockY,
            MinecraftCoordinates.blockCoordinate(chunkPosition.z, 0)
        ),
        BlockPosition(
            MinecraftCoordinates.blockCoordinate(chunkPosition.x, 15),
            layout.maxBlockY,
            MinecraftCoordinates.blockCoordinate(chunkPosition.z, 15)
        ),
    )
}

internal fun WorldEditor.describeBlocks(arguments: JsonObject): JsonObject {
    val registry = WorldSession.blockStateRegistry
    val query = arguments.text("query", "")
    val blocks =
        registry.vanillaBlockStates.filter { it.isDefault && query in it.blockId.value }.sortedBy { it.blockId.value }
    return paginate("describe_blocks", arguments, blocks.map { state ->
        buildJsonObject {
            put("name", state.blockId.value)
            put("defaultProperties", JsonObject(state.properties.mapValues { JsonPrimitive(it.value) }))
            val states = registry.blockStates(Identifier(state.blockId.value))
            put("properties", buildJsonObject {
                state.properties.keys.forEach { name ->
                    put(
                        name,
                        JsonArray(states.mapNotNull { it.properties[name] }.distinct().map(::JsonPrimitive))
                    )
                }
            })
        }
    })
}

private fun recordInfo(chunkPosition: ChunkPosition, regionChunkInfo: RegionChunkInfo?, indexed: Boolean): JsonObject =
    buildJsonObject {
        put("chunk", chunkPosition.json())
        put("region", chunkPosition.regionPosition.json())
        put("exists", indexed)
        put("payloadAvailable", regionChunkInfo != null)
        regionChunkInfo?.let {
            put("compression", it.compression.toString())
            put("compressedBytes", it.compressedByteCount)
            put("placement", it.anvilChunkPlacement.name.lowercase())
            put("timestampEpochSeconds", it.timestampEpochSeconds)
            if (it.anvilChunkPlacement == AnvilChunkPlacement.EXTERNAL) put(
                "externalFile",
                "c.${chunkPosition.x}.${chunkPosition.z}.mcc"
            )
        }
    }
