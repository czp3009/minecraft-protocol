package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.*

internal fun validateTree(
    tag: NbtTag,
    configuration: NbtFormatConfiguration,
) {
    NbtTreeLimitValidator(configuration).validate(tag)
}

private class NbtTreeLimitValidator(
    private val configuration: NbtFormatConfiguration,
) {
    private var encodedBytes = 0L

    fun validate(tag: NbtTag) {
        account(1)
        visitPayload(tag, depth = 0)
    }

    private fun visitPayload(tag: NbtTag, depth: Int) {
        checkDepth(depth)
        when (tag) {
            NbtEnd -> Unit
            is NbtByte -> account(1)
            is NbtShort -> account(2)
            is NbtInt,
            is NbtFloat,
                -> account(4)

            is NbtLong,
            is NbtDouble,
                -> account(8)

            is NbtByteArray -> {
                val size = tag.size
                checkByteArraySize(size)
                account(4)
                account(size.toLong())
            }

            is NbtString -> accountString(tag.value)
            is NbtList -> visitList(tag, depth)
            is NbtCompound -> visitCompound(tag, depth)
            is NbtIntArray -> {
                val size = tag.size
                checkCollectionSize(size, "NBT int array")
                account(4)
                account(size.toLong() * Int.SIZE_BYTES)
            }

            is NbtLongArray -> {
                val size = tag.size
                checkCollectionSize(size, "NBT long array")
                account(4)
                account(size.toLong() * Long.SIZE_BYTES)
            }
        }
    }

    private fun visitList(tag: NbtList, depth: Int) {
        checkCollectionSize(tag.size, "NBT list")
        account(1 + Int.SIZE_BYTES.toLong())
        val rawType = rawListType(tag)
        tag.forEach { element ->
            if (
                rawType == TAG_COMPOUND &&
                (element !is NbtCompound || element.isListWrapper())
            ) {
                checkDepth(depth + 1)
                account(1)
                accountString("")
                visitPayload(element, depth + 2)
                account(1)
            } else {
                visitPayload(element, depth + 1)
            }
        }
    }

    private fun visitCompound(tag: NbtCompound, depth: Int) {
        checkCollectionSize(tag.size, "NBT compound")
        tag.forEachEntry { name, value ->
            account(1)
            accountString(name)
            visitPayload(value, depth + 1)
        }
        account(1)
    }

    private fun accountString(value: String) {
        val bytes = modifiedUtfLength(value)
        if (bytes > configuration.maximumStringBytes) {
            throw NbtLimitException(
                "NBT string byte length $bytes exceeds configured limit ${configuration.maximumStringBytes}",
            )
        }
        account(Short.SIZE_BYTES.toLong())
        account(bytes.toLong())
    }

    private fun checkDepth(depth: Int) {
        if (depth > configuration.maximumDepth) {
            throw NbtLimitException(
                "NBT exceeds configured depth limit ${configuration.maximumDepth}",
            )
        }
    }

    private fun checkCollectionSize(size: Int, kind: String) {
        if (size > configuration.maximumCollectionSize) {
            throw NbtLimitException(
                "$kind length $size exceeds configured limit ${configuration.maximumCollectionSize}",
            )
        }
    }

    private fun checkByteArraySize(size: Int) {
        if (size > configuration.maximumByteArraySize) {
            throw NbtLimitException(
                "NBT byte array length $size exceeds configured limit ${configuration.maximumByteArraySize}",
            )
        }
    }

    private fun account(count: Long) {
        if (
            count < 0 ||
            count > configuration.maximumEncodedBytes - encodedBytes
        ) {
            throw NbtLimitException(
                "NBT exceeds configured encoded-byte limit ${configuration.maximumEncodedBytes}",
            )
        }
        encodedBytes += count
    }
}
