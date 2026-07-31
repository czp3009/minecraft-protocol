package com.hiczp.minecraft.protocol.serialization.internal

import com.hiczp.minecraft.protocol.model.wire.MaxByteLength
import com.hiczp.minecraft.protocol.model.wire.MaxCollectionSize
import com.hiczp.minecraft.protocol.serialization.MinecraftSerializationException

internal fun validateCollectionHints(size: Int, hints: List<Annotation>) {
    hints.filterIsInstance<MaxCollectionSize>().singleOrNull()?.let { maximum ->
        if (size > maximum.elements) {
            throw MinecraftSerializationException(
                "Collection size $size exceeds protocol limit ${maximum.elements}",
            )
        }
    }
    hints.filterIsInstance<MaxByteLength>().singleOrNull()?.let { maximum ->
        if (size > maximum.bytes) {
            throw MinecraftSerializationException(
                "Byte-array size $size exceeds protocol limit ${maximum.bytes}",
            )
        }
    }
}
