package com.hiczp.minecraft.demo.worldmcp

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.world.format.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Offline edit maintenance. Official loading rebuilds omitted heightmaps, untrusted light and invalid POI Sections. */
object EditMaintenance {
    fun invalidate(chunk: Chunk) {
        chunk.heightmaps.maps.clear()
        invalidateLight(chunk)
    }

    fun invalidateLight(chunk: Chunk) {
        chunk.lighting = ChunkLighting(false, null)
        chunk.sectionEntries().forEach { (_, chunkSection) -> chunkSection.lighting = SectionLighting() }
    }

    /** Spatial changes preserve a Block Entity while the block identity stays the same; new entities use vanilla lazy creation on load. */
    fun replace(chunk: Chunk, blockPosition: BlockPosition, blockState: BlockState): Boolean {
        val previous = chunk.getBlockState(blockPosition)
        if (previous == blockState) return false
        chunk.setBlockState(blockPosition, blockState)
        if (previous.blockId != blockState.blockId) chunk.blockEntities.remove(blockPosition)
        return true
    }

    fun cleanTicks(chunk: Chunk, changed: Set<BlockPosition>) {
        chunk.blockTicks.removeAll { it.blockPosition in changed }
        chunk.fluidTicks.removeAll { it.blockPosition in changed }
    }

    /** Clear invalidated positions before official POI refresh so old ticket/type records cannot survive a changed block. */
    suspend fun invalidatePoi(
        worldSession: WorldSession,
        dimensionId: DimensionId,
        chunkPosition: ChunkPosition,
        changed: Set<BlockPosition>?,
    ) {
        worldSession.withRegion(dimensionId, RegionKind.POI, chunkPosition.regionPosition) { regionAccess ->
            val document = regionAccess.read(chunkPosition) ?: return@withRegion
            val sections = document.root["Sections"] as? NbtCompound ?: return@withRegion
            val modified = sections.toMap().mapValues { (name, nbtTag) ->
                val section = nbtTag as NbtCompound
                val y = name.toInt()
                if (changed != null && changed.none { it.sectionPosition.y == y }) section else {
                    val records = section.list("Records").filter { record ->
                        if (changed == null) false else {
                            val pos = (record as NbtCompound)["pos"] as NbtIntArray
                            BlockPosition(pos.value[0], pos.value[1], pos.value[2]) !in changed
                        }
                    }
                    section.withFields("Valid" to NbtByte(0), "Records" to NbtList(records))
                }
            }
            regionAccess.write(
                chunkPosition,
                NbtDocument(document.root.withFields("Sections" to NbtCompound(modified)))
            )
        }
    }

    /** Neighbour light can depend on blocks across a Chunk edge; invalidate the existing one-Chunk halo. */
    suspend fun invalidateNeighbourLight(
        worldSession: WorldSession,
        dimensionId: DimensionId,
        changed: Set<ChunkPosition>
    ) {
        val neighbours = changed.flatMap { chunkPosition ->
            (-1..1).flatMap { dz ->
                (-1..1).map { dx ->
                    ChunkPosition(
                        MinecraftCoordinates.offsetChunkCoordinate(
                            chunkPosition.x,
                            dx
                        ), MinecraftCoordinates.offsetChunkCoordinate(chunkPosition.z, dz)
                    )
                }
            }
        }.toSet() - changed
        for (chunkPosition in neighbours) {
            currentCoroutineContext().ensureActive()
            worldSession.withRegion(dimensionId, RegionKind.TERRAIN, chunkPosition.regionPosition) { regionAccess ->
                val document = regionAccess.read(chunkPosition) ?: return@withRegion
                // Preserve raw neighbour content, including unfinished generation fields.
                val sections =
                    document.root.list("sections").map { (it as NbtCompound).without("BlockLight", "SkyLight") }
                val root = document.root.withFields("isLightOn" to NbtByte(0), "sections" to NbtList(sections))
                regionAccess.write(chunkPosition, NbtDocument(root))
            }
        }
    }
}
