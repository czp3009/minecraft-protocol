package com.hiczp.minecraft.protocol.auth

import kotlinx.serialization.json.Json
import kotlin.test.*

class OfflineAuthenticationTest {
    @Test
    fun matchesVanillaNameBasedOfflineUuids() {
        assertEquals(
            "b50ad385-829d-3141-a216-7e7d7539ba7f",
            MinecraftOfflineIdentity.minecraftOfflineUuid("Notch").toHexDashString(),
        )
        assertEquals(
            "5627dd98-e6be-3c21-b8a8-e92344183641",
            MinecraftOfflineIdentity.minecraftOfflineUuid("Steve").toHexDashString(),
        )
        assertEquals(
            "36532b5e-c442-3dbb-a24c-c7e55d0f979a",
            MinecraftOfflineIdentity.minecraftOfflineUuid("Alex").toHexDashString(),
        )
    }

    @Test
    fun identityDerivesItsUuidAndProfile() {
        val identity = MinecraftOfflineIdentity("ProtocolProbe")

        assertEquals(
            MinecraftOfflineIdentity.minecraftOfflineUuid("ProtocolProbe"),
            identity.id,
        )
        assertEquals(identity.id, identity.toGameProfile().id)
    }

    @Test
    fun rejectsAnEmptyOfflineName() {
        assertFailsWith<IllegalArgumentException> {
            MinecraftOfflineIdentity("")
        }
    }

    @Test
    fun onlineAndOfflineIdentitiesRemainExhaustivePlainData() {
        val offline: MinecraftIdentity = MinecraftOfflineIdentity("Player")
        val online: MinecraftIdentity = MinecraftOnlineIdentity(
            id = MinecraftOfflineIdentity.minecraftOfflineUuid("Player"),
            name = "Player",
            accessToken = "access-token",
        )

        assertIs<MinecraftOfflineIdentity>(offline)
        val typedOnline = assertIs<MinecraftOnlineIdentity>(online)
        assertEquals("access-token", typedOnline.copy().accessToken)
        assertEquals("Player", typedOnline.name)
    }

    @Test
    fun rejectsInvalidOnlineCredentials() {
        val id = MinecraftOfflineIdentity.minecraftOfflineUuid("Player")
        assertFailsWith<IllegalArgumentException> {
            MinecraftOnlineIdentity(id, "", "token")
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftOnlineIdentity(id, "Player", "   ")
        }
        assertFailsWith<IllegalArgumentException> {
            MinecraftOfflineIdentity.minecraftOfflineUuid("")
        }
    }

    @Test
    fun identitiesRoundTripWithoutAClassDiscriminator() {
        val offline: MinecraftIdentity = MinecraftOfflineIdentity("Player")
        val online: MinecraftIdentity = MinecraftOnlineIdentity(
            id = offline.id,
            name = offline.name,
            accessToken = "access-token",
        )

        val offlineJson = Json.encodeToString(offline)
        val onlineJson = Json.encodeToString(online)

        assertEquals("""{"name":"Player"}""", offlineJson)
        assertEquals(
            """{"id":"${online.id}","name":"Player","accessToken":"access-token"}""",
            onlineJson,
        )
        assertEquals(offline, Json.decodeFromString<MinecraftIdentity>(offlineJson))
        assertEquals(online, Json.decodeFromString<MinecraftIdentity>(onlineJson))
        assertFalse("\"id\"" in offlineJson)
    }
}
