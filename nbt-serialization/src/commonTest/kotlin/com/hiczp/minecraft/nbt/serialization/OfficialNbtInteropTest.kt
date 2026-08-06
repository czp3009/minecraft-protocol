package com.hiczp.minecraft.nbt.serialization

import com.hiczp.minecraft.nbt.*
import com.hiczp.minecraft.test.MinecraftTestSupport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test

class OfficialNbtInteropTest {
    @Test
    fun libraryBytesCrossReadAndWriteWithOfficialNbtIo() = runTest {
        MinecraftTestSupport.verifyOfficialNbt(officialNbtFixtures())
    }
}

private fun officialNbtFixtures() = buildJsonArray {
    fun addFixture(
        mode: String,
        sample: String,
        payload: ByteArray,
        expected: ByteArray = payload,
        exactBytes: Boolean = true,
    ) {
        add(
            buildJsonObject {
                put("mode", mode)
                put("sample", sample)
                put("payloadHex", payload.toHexString())
                put("expectedHex", expected.toHexString())
                put("exactBytes", exactBytes)
                put("reject", false)
            },
        )
    }

    fun addRejected(
        mode: String,
        sample: String,
        payload: ByteArray,
    ) {
        add(
            buildJsonObject {
                put("mode", mode)
                put("sample", sample)
                put("payloadHex", payload.toHexString())
                put("expectedHex", payload.toHexString())
                put("exactBytes", false)
                put("reject", true)
            },
        )
    }

    val everyTag = NbtCompound(
        linkedMapOf(
            "byte" to NbtByte(Byte.MIN_VALUE),
            "short" to NbtShort(Short.MIN_VALUE),
            "int" to NbtInt(Int.MIN_VALUE),
            "long" to NbtLong(Long.MIN_VALUE),
            "float" to NbtFloat(-1.25f),
            "double" to NbtDouble(2.5),
            "bytes" to NbtByteArray(byteArrayOf(-1, 0, 1)),
            "string" to NbtString("nul=\u0000, bmp=é, supplementary=😀"),
            "list" to NbtList(listOf(NbtInt(1), NbtInt(2))),
            "emptyList" to NbtList(emptyList()),
            "compound" to NbtCompound(mapOf("value" to NbtString("nested"))),
            "ints" to NbtIntArray(intArrayOf(Int.MIN_VALUE, Int.MAX_VALUE)),
            "longs" to NbtLongArray(longArrayOf(Long.MIN_VALUE, Long.MAX_VALUE)),
        ),
    )
    addFixture(
        mode = "ANY",
        sample = "end-root",
        payload = NbtFormat.encodeAnyTagToByteArray(NbtEnd),
    )
    addFixture(
        mode = "ANY",
        sample = "every-tag-and-modified-utf",
        payload = NbtFormat.encodeAnyTagToByteArray(everyTag),
        exactBytes = false,
    )
    addFixture(
        mode = "ANY",
        sample = "mixed-list-wrappers",
        payload = NbtFormat.encodeAnyTagToByteArray(
            NbtList(
                listOf(
                    NbtInt(1),
                    NbtString("two"),
                    NbtCompound(mapOf("value" to NbtLong(3))),
                ),
            ),
        ),
    )
    addFixture(
        mode = "ANY",
        sample = "wrapper-shaped-compound",
        payload = NbtFormat.encodeAnyTagToByteArray(
            NbtList(
                listOf(
                    NbtCompound(mapOf("" to NbtInt(1))),
                ),
            ),
        ),
    )
    addFixture(
        mode = "ANY",
        sample = "noncanonical-empty-list-type",
        payload = byteArrayOf(9, 13, 0, 0, 0, 0),
        expected = byteArrayOf(9, 0, 0, 0, 0, 0),
    )
    addFixture(
        mode = "ANY",
        sample = "duplicate-compound-name-last-wins",
        payload = byteArrayOf(
            10,
            3, 0, 1, 'x'.code.toByte(), 0, 0, 0, 1,
            3, 0, 1, 'x'.code.toByte(), 0, 0, 0, 2,
            0,
        ),
        expected = NbtFormat.encodeAnyTagToByteArray(
            NbtCompound(mapOf("x" to NbtInt(2))),
        ),
    )
    addFixture(
        mode = "ANY",
        sample = "raw-null-modified-utf-is-canonicalized",
        payload = byteArrayOf(8, 0, 1, 0),
        expected = NbtFormat.encodeAnyTagToByteArray(NbtString("\u0000")),
    )
    addFixture(
        mode = "ANY",
        sample = "noncanonical-float-nan-is-canonicalized",
        payload = byteArrayOf(5, 0x7F, 0xA1.toByte(), 0x23, 0x45),
        expected = NbtFormat.encodeAnyTagToByteArray(NbtFloat(Float.NaN)),
    )
    addFixture(
        mode = "ANY",
        sample = "noncanonical-double-nan-is-canonicalized",
        payload = byteArrayOf(
            6,
            0x7F,
            0xF0.toByte(),
            0,
            0,
            0,
            0,
            0,
            1,
        ),
        expected = NbtFormat.encodeAnyTagToByteArray(NbtDouble(Double.NaN)),
    )
    addFixture(
        mode = "ANY",
        sample = "unpaired-surrogate-modified-utf",
        payload = NbtFormat.encodeAnyTagToByteArray(NbtString("\uD800")),
    )
    addRejected(
        mode = "ANY",
        sample = "unknown-tag-id",
        payload = byteArrayOf(13),
    )
    addRejected(
        mode = "ANY",
        sample = "truncated-int",
        payload = byteArrayOf(3, 0, 0, 0),
    )
    addRejected(
        mode = "ANY",
        sample = "negative-byte-array-length",
        payload = byteArrayOf(7, -1, -1, -1, -1),
    )
    addRejected(
        mode = "ANY",
        sample = "nonempty-end-list",
        payload = byteArrayOf(9, 0, 0, 0, 0, 1),
    )
    addRejected(
        mode = "ANY",
        sample = "malformed-modified-utf",
        payload = byteArrayOf(8, 0, 1, 0x80.toByte()),
    )
    addRejected(
        mode = "ANY",
        sample = "depth-above-vanilla-maximum",
        payload = nestedCompoundBytes(513),
    )

    val namedInt = NamedNbtTag("root\u0000😀", NbtInt(42))
    addFixture(
        mode = "UNNAMED",
        sample = "root-name-is-discarded",
        payload = NbtFormat.encodeNamedTagToByteArray(namedInt),
        expected = NbtFormat.encodeUnnamedTagToByteArray(namedInt.tag),
    )
    addFixture(
        mode = "UNNAMED",
        sample = "end-root-has-no-name",
        payload = NbtFormat.encodeUnnamedTagToByteArray(NbtEnd),
    )

    val document = NbtDocument(everyTag)
    addFixture(
        mode = "DOCUMENT",
        sample = "compound-root-name-is-discarded",
        payload = NbtFormat.encodeNamedTagToByteArray(
            NamedNbtTag("level", document.root),
        ),
        expected = NbtFormat.encodeDocumentToByteArray(document),
        exactBytes = false,
    )
    addRejected(
        mode = "DOCUMENT",
        sample = "non-compound-root",
        payload = NbtFormat.encodeUnnamedTagToByteArray(NbtInt(1)),
    )
}

private fun nestedCompoundBytes(nestedCompounds: Int): ByteArray {
    require(nestedCompounds >= 0)
    val bytes = ByteArray(1 + nestedCompounds * 3 + nestedCompounds + 1)
    var index = 0
    bytes[index++] = 10
    repeat(nestedCompounds) {
        bytes[index++] = 10
        bytes[index++] = 0
        bytes[index++] = 0
    }
    while (index < bytes.size) bytes[index++] = 0
    return bytes
}
