package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToByteArray

class ConnectionStatePacketTest {
    @Test
    fun `status packet payloads have their exact empty string and long shapes`() {
        assertPacketBytes(ServerboundStatusRequestPacket, ServerboundStatusRequestPacket.serializer(), "")
        assertPacketBytes(
            ClientboundStatusResponsePacket(ServerStatus()),
            ClientboundStatusResponsePacket.serializer(),
            "027b7d",
        )
        assertPacketBytes(
            ServerboundPingRequestPacket(0x0102030405060708),
            ServerboundPingRequestPacket.serializer(),
            "0102030405060708",
        )
        assertPacketBytes(
            ClientboundPongResponsePacket(0x0102030405060708),
            ClientboundPongResponsePacket.serializer(),
            "0102030405060708",
        )
    }

    @Test
    fun `status response JSON preserves every logical field`() {
        val serverStatus = ServerStatus(
            description = JsonTextComponent("""{"text":"test"}"""),
            players = ServerStatus.Players(
                max = 20,
                online = 1,
                sample = listOf(
                    ServerStatus.NameAndId(
                        id = Uuid.fromLongs(1, 2),
                        name = "player",
                    ),
                ),
            ),
            version = ServerStatus.Version("test", 1),
            favicon = ServerStatus.Favicon(ByteString(byteArrayOf(1, 2, 3))),
            enforcesSecureChat = true,
        )
        val encodedJson =
            """{"description":{"text":"test"},"players":{"max":20,"online":1,"sample":[{"id":"00000000-0000-0001-0000-000000000002","name":"player"}]},"version":{"name":"test","protocol":1},"favicon":"data:image/png;base64,AQID","enforcesSecureChat":true}"""
        val expected = encodeProtocolString(encodedJson)

        assertContentEquals(
            expected,
            MinecraftPacketPayloadFormat.encodeToByteArray(ClientboundStatusResponsePacket(serverStatus)),
        )
        assertEquals(
            ClientboundStatusResponsePacket(serverStatus),
            MinecraftPacketPayloadFormat.decodeFromByteArray(ClientboundStatusResponsePacket.serializer(), expected),
        )
    }

    @Test
    fun `status response ignores extensions and rejects malformed structured JSON`() {
        assertEquals(
            ClientboundStatusResponsePacket(ServerStatus()),
            MinecraftPacketPayloadFormat.decodeFromByteArray(
                ClientboundStatusResponsePacket.serializer(),
                encodeProtocolString("""{"extension":true}"""),
            ),
        )
        assertFailsWith<MinecraftSerializationException> {
            MinecraftPacketPayloadFormat.decodeFromByteArray(
                ClientboundStatusResponsePacket.serializer(),
                encodeProtocolString("""{"players":{}}"""),
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftPacketPayloadFormat.decodeFromByteArray(
                ClientboundStatusResponsePacket.serializer(),
                encodeProtocolString("""{"favicon":"not-a-data-url"}"""),
            )
        }
    }

    @Test
    fun `login profile and transition packets follow vanilla field order`() {
        assertPacketBytes(
            ClientboundLoginDisconnectPacket(JsonTextComponent("{}")),
            ClientboundLoginDisconnectPacket.serializer(),
            "027b7d",
        )
        assertPacketBytes(
            ClientboundLoginCompressionPacket(300),
            ClientboundLoginCompressionPacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            ServerboundHelloPacket("a", Uuid.fromLongs(1, 2)),
            ServerboundHelloPacket.serializer(),
            "016100000000000000010000000000000002",
        )
        assertPacketBytes(
            ClientboundLoginFinishedPacket(
                GameProfile(Uuid.fromLongs(1, 2), "a", emptyList()),
                Uuid.fromLongs(3, 4),
            ),
            ClientboundLoginFinishedPacket.serializer(),
            "0000000000000001000000000000000201610000000000000000030000000000000004",
        )
        assertPacketBytes(
            ServerboundLoginAcknowledgedPacket,
            ServerboundLoginAcknowledgedPacket.serializer(),
            "",
        )
        assertPacketBytes(
            ClientboundCookieRequestPacket(Identifier("test")),
            ClientboundCookieRequestPacket.serializer(),
            "0e6d696e6563726166743a74657374",
        )
        assertPacketBytes(
            ServerboundCookieResponsePacket(Identifier("test"), null),
            ServerboundCookieResponsePacket.serializer(),
            "0e6d696e6563726166743a7465737400",
        )
    }

    @Test
    fun `login encryption and custom query payload boundaries match official writes`() {
        assertPacketBytes(
            ClientboundHelloPacket(
                serverId = "",
                publicKey = ByteString(byteArrayOf(1, 2)),
                challenge = ByteString(byteArrayOf(3)),
                shouldAuthenticate = true,
            ),
            ClientboundHelloPacket.serializer(),
            "00020102010301",
        )
        assertPacketBytes(
            ServerboundKeyPacket(
                keybytes = ByteString(byteArrayOf(1)),
                encryptedChallenge = ByteString(byteArrayOf(2, 3)),
            ),
            ServerboundKeyPacket.serializer(),
            "0101020203",
        )
        assertPacketBytes(
            ClientboundCustomQueryPacket(
                transactionId = 300,
                channel = Identifier("test"),
                data = ByteString(byteArrayOf(1, 2)),
            ),
            ClientboundCustomQueryPacket.serializer(),
            "ac020e6d696e6563726166743a746573740102",
        )
        assertPacketBytes(
            ServerboundCustomQueryAnswerPacket(1, null),
            ServerboundCustomQueryAnswerPacket.serializer(),
            "0100",
        )
        assertPacketBytes(
            ServerboundCustomQueryAnswerPacket(1, ByteString(byteArrayOf(2, 3))),
            ServerboundCustomQueryAnswerPacket.serializer(),
            "01010203",
        )
    }

    @Test
    fun `configuration primitive and terminal packets retain fixed widths`() {
        assertPacketBytes(
            ClientboundKeepAlivePacket(1),
            ClientboundKeepAlivePacket.serializer(),
            "0000000000000001",
        )
        assertPacketBytes(
            ServerboundKeepAlivePacket(2),
            ServerboundKeepAlivePacket.serializer(),
            "0000000000000002",
        )
        assertPacketBytes(
            ClientboundPingPacket(3),
            ClientboundPingPacket.serializer(),
            "00000003",
        )
        assertPacketBytes(
            ServerboundPongPacket(4),
            ServerboundPongPacket.serializer(),
            "00000004",
        )
        assertPacketBytes(
            ClientboundTransferPacket("a", 255),
            ClientboundTransferPacket.serializer(),
            "0161ff01",
        )
        assertPacketBytes(
            ClientboundCodeOfConductPacket("a"),
            ClientboundCodeOfConductPacket.serializer(),
            "0161",
        )
        assertPacketBytes(
            ClientboundFinishConfigurationPacket,
            ClientboundFinishConfigurationPacket.serializer(),
            "",
        )
        assertPacketBytes(ClientboundResetChatPacket, ClientboundResetChatPacket.serializer(), "")
        assertPacketBytes(
            ClientboundClearDialogPacket,
            ClientboundClearDialogPacket.serializer(),
            "",
        )
        assertPacketBytes(
            ServerboundFinishConfigurationPacket,
            ServerboundFinishConfigurationPacket.serializer(),
            "",
        )
        assertPacketBytes(
            ServerboundAcceptCodeOfConductPacket,
            ServerboundAcceptCodeOfConductPacket.serializer(),
            "",
        )
    }

    @Test
    fun `configuration registry pack and tag collections use nested VarInt counts`() {
        assertPacketBytes(
            ClientboundRegistryDataPacket(
                Identifier("test"),
                listOf(RegistryEntry(Identifier("entry"), null)),
            ),
            ClientboundRegistryDataPacket.serializer(),
            "0e6d696e6563726166743a74657374010f6d696e6563726166743a656e74727900",
        )
        assertPacketBytes(
            ClientboundUpdateEnabledFeaturesPacket(setOf(Identifier("test"))),
            ClientboundUpdateEnabledFeaturesPacket.serializer(),
            "010e6d696e6563726166743a74657374",
        )
        assertPacketBytes(
            ClientboundUpdateTagsPacket(
                listOf(
                    RegistryTags(
                        Identifier("block"),
                        listOf(
                            TagDefinition(
                                Identifier("test"),
                                listOf(1, 300),
                            ),
                        ),
                    ),
                ),
            ),
            ClientboundUpdateTagsPacket.serializer(),
            "010f6d696e6563726166743a626c6f636b010e6d696e6563726166743a746573740201ac02",
        )
        val knownPack = KnownPack("m", "c", "1")
        assertPacketBytes(
            ClientboundSelectKnownPacks(listOf(knownPack)),
            ClientboundSelectKnownPacks.serializer(),
            "01016d01630131",
        )
        assertPacketBytes(
            ServerboundSelectKnownPacks(listOf(knownPack)),
            ServerboundSelectKnownPacks.serializer(),
            "01016d01630131",
        )
    }

    @Test
    fun `configuration cookies and resource packs preserve every optional marker`() {
        val identifierBytes = "0e6d696e6563726166743a74657374"
        assertPacketBytes(
            ClientboundCookieRequestPacket(Identifier("test")),
            ClientboundCookieRequestPacket.serializer(),
            identifierBytes,
        )
        assertPacketBytes(
            ClientboundStoreCookiePacket(
                Identifier("test"),
                ByteString(byteArrayOf(1, 2)),
            ),
            ClientboundStoreCookiePacket.serializer(),
            "${identifierBytes}020102",
        )
        assertPacketBytes(
            ServerboundCookieResponsePacket(Identifier("test"), null),
            ServerboundCookieResponsePacket.serializer(),
            "${identifierBytes}00",
        )
        assertPacketBytes(
            ServerboundCookieResponsePacket(
                Identifier("test"),
                ByteString(byteArrayOf(1, 2)),
            ),
            ServerboundCookieResponsePacket.serializer(),
            "${identifierBytes}01020102",
        )
        assertPacketBytes(
            ClientboundResourcePackPopPacket(null),
            ClientboundResourcePackPopPacket.serializer(),
            "00",
        )
        assertPacketBytes(
            ClientboundResourcePackPushPacket(
                id = Uuid.fromLongs(0, 0),
                url = "u",
                hash = "h",
                required = true,
                prompt = null,
            ),
            ClientboundResourcePackPushPacket.serializer(),
            "00000000000000000000000000000000017501680100",
        )
        assertPacketBytes(
            ServerboundResourcePackPacket(
                Uuid.fromLongs(0, 0),
                ServerboundResourcePackPacket.Action.ACCEPTED,
            ),
            ServerboundResourcePackPacket.serializer(),
            "0000000000000000000000000000000003",
        )
    }

    @Test
    fun `configuration shared structures cover client info reports links and NBT`() {
        assertPacketBytes(
            ServerboundClientInformationPacket(
                ClientInformation(
                    locale = "en_us",
                    viewDistance = 8,
                    chatMode = ChatMode.COMMANDS_ONLY,
                    chatColors = true,
                    displayedSkinParts = 255,
                    mainHand = MainHand.RIGHT,
                    enableTextFiltering = false,
                    allowServerListings = true,
                    particleStatus = ParticleStatus.MINIMAL,
                ),
            ),
            ServerboundClientInformationPacket.serializer(),
            "05656e5f7573080101ff01000102",
        )
        assertPacketBytes(
            ClientboundCustomReportDetailsPacket(
                listOf(ReportDetail("t", "d")),
            ),
            ClientboundCustomReportDetailsPacket.serializer(),
            "0101740164",
        )
        assertPacketBytes(
            ClientboundServerLinksPacket(
                listOf(
                    ServerLink(
                        ServerLinkLabel.BuiltIn(
                            BuiltInServerLinkLabel.BUG_REPORT,
                        ),
                        "u",
                    ),
                ),
            ),
            ClientboundServerLinksPacket.serializer(),
            "0101000175",
        )
        assertPacketBytes(
            ClientboundCustomPayloadPacket(
                CustomPayload.Brand("x"),
            ),
            ClientboundCustomPayloadPacket.serializer(),
            "0f6d696e6563726166743a6272616e640178",
        )
        assertPacketBytes(
            ServerboundCustomPayloadPacket(
                CustomPayload.Brand("x"),
            ),
            ServerboundCustomPayloadPacket.serializer(),
            "0f6d696e6563726166743a6272616e640178",
        )
    }

    @Test
    fun `configuration and login packet-specific limits reject oversized values`() {
        assertFailsWith<MinecraftSerializationException> {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ServerboundHelloPacket("x".repeat(17), Uuid.fromLongs(0, 0)),
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ClientboundStoreCookiePacket(
                    Identifier("test"),
                    ByteString(ByteArray(5_121)),
                ),
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ClientboundCustomReportDetailsPacket(
                    List(33) { ReportDetail("t", "d") },
                ),
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftPacketPayloadFormat.encodeToByteArray(
                ServerboundSelectKnownPacks(
                    List(65) { KnownPack("m", "c", "1") },
                ),
            )
        }
    }

    private fun <T> assertPacketBytes(
        value: T,
        kSerializer: KSerializer<T>,
        expectedHex: String,
    ) {
        val expected = expectedHex.hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftPacketPayloadFormat.encodeToByteArray(kSerializer, value),
        )
        assertEquals(
            value,
            MinecraftPacketPayloadFormat.decodeFromByteArray(kSerializer, expected),
        )
    }

    private fun encodeProtocolString(value: String): ByteArray {
        val bytes = value.encodeToByteArray()
        val length = mutableListOf<Byte>()
        var remaining = bytes.size
        do {
            var next = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) next = next or 0x80
            length += next.toByte()
        } while (remaining != 0)
        return length.toByteArray() + bytes
    }
}
