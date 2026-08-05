package com.hiczp.minecraft.protocol.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OfflineAuthenticationTest {
    @Test
    fun matchesVanillaNameBasedOfflineUuids() {
        assertEquals(
            "b50ad385-829d-3141-a216-7e7d7539ba7f",
            offlineUuid("Notch").toDashedString(),
        )
        assertEquals(
            "5627dd98-e6be-3c21-b8a8-e92344183641",
            offlineUuid("Steve").toDashedString(),
        )
        assertEquals(
            "36532b5e-c442-3dbb-a24c-c7e55d0f979a",
            offlineUuid("Alex").toDashedString(),
        )
    }

    @Test
    fun uuidTextRoundTripsBothMinecraftRepresentations() {
        val uuid = offlineUuid("ProtocolProbe")

        assertEquals(uuid, parseMinecraftUuid(uuid.toUndashedString()))
        assertEquals(uuid, parseMinecraftUuid(uuid.toDashedString()))
    }

    @Test
    fun formatsSha1AsJavasSignedBigInteger() {
        assertEquals(
            "4ed1f46bbe04bc756bcb17c0c7ce3e4632f06a48",
            minecraftServerHash("Notch", byteArrayOf(), byteArrayOf()),
        )
        assertEquals(
            "-7c9d5b0044c130109a5d7b5fb5c317c02b4e28c1",
            minecraftServerHash("jeb_", byteArrayOf(), byteArrayOf()),
        )
        assertEquals(
            "88e16a1019277b15d58faf0541e11910eb756f6",
            minecraftServerHash("simon", byteArrayOf(), byteArrayOf()),
        )
    }

    @Test
    fun digestImplementationsMatchStandardBoundaryVectors() {
        assertEquals(
            "d41d8cd98f00b204e9800998ecf8427e",
            Md5.digest(byteArrayOf()).hex(),
        )
        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72",
            Md5.digest("abc".encodeToByteArray()).hex(),
        )
        assertEquals(
            "da39a3ee5e6b4b0d3255bfef95601890afd80709",
            Sha1.digest(byteArrayOf()).hex(),
        )
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            Sha1.digest(
                (
                        "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
                        ).encodeToByteArray(),
            ).hex(),
        )
    }

    @Test
    fun uuidParserRejectsEveryMalformedMinecraftRepresentation() {
        assertFailsWith<IllegalArgumentException> {
            offlineUuid("")
        }
        for (
        value in listOf(
            "",
            "0".repeat(31),
            "0".repeat(33),
            "g".repeat(32),
            "00000000-0000-0000-0000-00000000000g",
            "000000000000-0000-0000-000000000000",
        )
        ) {
            assertFailsWith<IllegalArgumentException>(value) {
                parseMinecraftUuid(value)
            }
        }
    }

    private fun ByteArray.hex(): String =
        joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
