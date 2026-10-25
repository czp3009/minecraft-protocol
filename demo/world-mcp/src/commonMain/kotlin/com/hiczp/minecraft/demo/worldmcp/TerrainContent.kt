package com.hiczp.minecraft.demo.worldmcp

import com.hiczp.minecraft.nbt.NbtByte
import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtDocument
import com.hiczp.minecraft.nbt.NbtList
import com.hiczp.minecraft.world.format.*
import kotlinx.serialization.json.*

/** Bidirectional MCP terrain projection. Non-palette fields remain typed SNBT; domain codecs own palette encoding. */
class TerrainContent(private val worldSession: WorldSession, private val dimensionId: DimensionId) {
    fun expand(nbtDocument: NbtDocument): JsonObject {
        val decoded = worldSession.decoder(dimensionId).decodeDocument(nbtDocument)
        require(decoded.chunk.isFullyGenerated) { "Expanded editing requires a completed Chunk; use raw for generation data" }
        val sections = nbtDocument.root.list("sections").map { nbtTag ->
            val original = nbtTag as NbtCompound
            section(original, decoded.chunk.getSection(original.integer("Y"))!!)
        }
        return buildJsonObject {
            put("nbt", nbtDocument.root.without("sections").snbt())
            put("sections", JsonArray(sections))
        }
    }

    fun section(nbtCompound: NbtCompound, chunkSection: ChunkSection): JsonObject = buildJsonObject {
        put("y", nbtCompound.integer("Y"))
        put("nbt", nbtCompound.without("Y", "block_states", "biomes").snbt())
        put("blocks", chunkSection.terrain?.blockStates?.let { JsonArray(it.map(BlockState::json)) } ?: JsonNull)
        put(
            "biomes",
            chunkSection.terrain?.biomes?.let { JsonArray(it.map { biomeId -> JsonPrimitive(biomeId.toString()) }) }
                ?: JsonNull)
    }

    fun decode(value: JsonObject): ChunkNbtDecodeResult {
        val root = value.text("nbt").compoundSnbt()
        require(root["sections"] == null) { "Expanded root nbt must not also contain sections" }
        val sections = value.array("sections").map { it.jsonObject }
        require(sections.map { it.int("y") }.distinct().size == sections.size) { "Duplicate Section Y" }
        val skeleton = root.withFields("sections" to NbtList(sections.map(::sectionSkeleton)))
        val decoded = worldSession.decoder(dimensionId).decodeDocument(NbtDocument(skeleton))
        require(decoded.chunk.isFullyGenerated) { "Expanded editing requires a completed Chunk" }
        sections.forEach { section ->
            applyTerrain(section, requireNotNull(decoded.chunk.getSection(section.int("y"))))
        }
        return decoded
    }

    fun decodeSection(value: JsonObject, owner: Chunk): ChunkSection {
        val y = value.int("y")
        val skeleton = sectionSkeleton(value)
        val empty = Chunk(owner.chunkPosition, worldSession.context(dimensionId))
        val ownerDocument = worldSession.encoder(dimensionId, ChunkNbtMetadata(MinecraftWorldFormat.WORLD_VERSION, 0))
            .encodeDocument(empty)
        val decoded = worldSession.decoder(dimensionId).decodeDocument(
            NbtDocument(ownerDocument.root.withFields("sections" to NbtList(listOf(skeleton)))),
        )
        val section = requireNotNull(decoded.chunk.getSection(y))
        applyTerrain(value, section)
        return section
    }

    private fun applyTerrain(value: JsonObject, section: ChunkSection) {
        val y = value.int("y")
        val blocks = value["blocks"]?.takeUnless { it is JsonNull }?.jsonArray
        val biomes = value["biomes"]?.takeUnless { it is JsonNull }?.jsonArray
        require((blocks == null) == (biomes == null)) { "Provide both blocks and biomes, or null for light-only Sections" }
        if (blocks != null && biomes != null) {
            require(y in worldSession.context(dimensionId).dimensionTypeLayout.chunkLayout) { "Terrain Section is outside dimension bounds" }
            require(blocks.size == MinecraftCoordinates.SECTION_BLOCK_COUNT) { "Expanded blocks needs 4096 entries in X/Z/Y order" }
            require(biomes.size == MinecraftCoordinates.SECTION_BIOME_COUNT) { "Expanded biomes needs 64 quart-cell entries in X/Z/Y order" }
            section.terrain = SectionTerrain(
                PalettedContainer(blocks.map(worldSession::blockState)),
                PalettedContainer(biomes.map { BiomeId.parse(it.jsonPrimitive.content) }),
                SectionStatistics(null, null, null, null),
            )
        }
    }

    private fun sectionSkeleton(value: JsonObject): NbtCompound {
        val y = value.int("y")
        require(y in Byte.MIN_VALUE..Byte.MAX_VALUE) { "Section Y is outside the saved byte range" }
        val extra = value.text("nbt").compoundSnbt()
        require(
            listOf(
                "Y",
                "block_states",
                "biomes"
            ).none { extra[it] != null }) { "Expanded Section nbt must not duplicate Y or palette fields" }
        return extra.withFields("Y" to NbtByte(y.toByte()))
    }
}
