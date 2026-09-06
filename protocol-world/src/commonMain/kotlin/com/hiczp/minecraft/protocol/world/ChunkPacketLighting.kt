package com.hiczp.minecraft.protocol.world

import com.hiczp.minecraft.protocol.model.type.BitSet
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.ClientboundLightUpdatePacketData
import com.hiczp.minecraft.protocol.model.type.LightDataLayer
import com.hiczp.minecraft.world.format.*

internal fun encodePacketLight(
    chunk: Chunk,
    chunkLayout: ChunkLayout,
    hasSkyLight: Boolean,
    required: ChunkPacketRequiredDataProvider,
): ClientboundLightUpdatePacketData {
    val bitCount = chunkLayout.sectionCount + 2
    val block = LightAccumulator(bitCount)
    val sky = LightAccumulator(bitCount)
    repeat(bitCount) { index ->
        val sectionY = MinecraftCoordinates.offsetSectionCoordinate(chunkLayout.minSectionY, index - 1)
        val lighting = chunk.sections[sectionY]?.lighting
        block.add(index, lighting?.blockLight ?: required.lightLayer(chunk, sectionY, LightKind.BLOCK))
        if (hasSkyLight) {
            sky.add(index, lighting?.skyLight ?: required.lightLayer(chunk, sectionY, LightKind.SKY))
        }
    }
    return ClientboundLightUpdatePacketData(
        sky.updateMask(), block.updateMask(), sky.emptyMask(), block.emptyMask(), sky.updates, block.updates,
    )
}

internal fun decodePacketLight(
    updateMask: BitSet,
    emptyMask: BitSet,
    updates: List<LightDataLayer>,
    chunkLayout: ChunkLayout,
): Map<Int, LightLayer> = buildMap {
    var updateIndex = 0
    repeat(chunkLayout.sectionCount + 2) { index ->
        val sectionY = MinecraftCoordinates.offsetSectionCoordinate(chunkLayout.minSectionY, index - 1)
        if (updateMask[index]) {
            val bytes = updates.getOrNull(updateIndex++)?.bytes?.toByteArray()
                ?: error("Light mask has more updates than available payloads")
            require(bytes.size == SECTION_LIGHT_BYTE_COUNT) { "A light update needs $SECTION_LIGHT_BYTE_COUNT bytes" }
            put(sectionY, LightLayer(List(SECTION_BLOCK_COUNT) { entry ->
                bytes[entry / 2].toInt().ushr((entry % 2) * 4) and 15
            }))
        } else if (emptyMask[index]) {
            put(sectionY, LightLayer(0))
        }
    }
}

private class LightAccumulator(bitCount: Int) {
    private val updateWords = LongArray((bitCount + 63) / 64)
    private val emptyWords = LongArray(updateWords.size)
    val updates = mutableListOf<LightDataLayer>()

    fun add(index: Int, lightLayer: LightLayer?) {
        if (lightLayer == null) return
        if (lightLayer.all { it == 0 }) {
            set(emptyWords, index)
        } else {
            set(updateWords, index)
            val bytes = ByteArray(SECTION_LIGHT_BYTE_COUNT) { offset ->
                (lightLayer[offset * 2] or (lightLayer[offset * 2 + 1] shl 4)).toByte()
            }
            updates.add(LightDataLayer(ByteString(bytes)))
        }
    }

    fun updateMask(): BitSet = canonicalMask(updateWords)
    fun emptyMask(): BitSet = canonicalMask(emptyWords)

    private fun set(words: LongArray, index: Int) {
        words[index / 64] = words[index / 64] or (1L shl (index % 64))
    }

    private fun canonicalMask(words: LongArray): BitSet = BitSet(words.copyOf(words.indexOfLast { it != 0L } + 1))
}
