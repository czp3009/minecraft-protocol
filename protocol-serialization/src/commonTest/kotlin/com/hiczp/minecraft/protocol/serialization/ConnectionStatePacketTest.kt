package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.uuid.Uuid

class ConnectionStatePacketTest {
    @Test
    fun `status packet payloads have their exact empty string and long shapes`() {
        assertPacketBytes(StatusRequestPacket, StatusRequestPacket.serializer(), "")
        assertPacketBytes(
            StatusResponsePacket("{}"),
            StatusResponsePacket.serializer(),
            "027b7d",
        )
        assertPacketBytes(
            StatusPingRequestPacket(0x0102030405060708),
            StatusPingRequestPacket.serializer(),
            "0102030405060708",
        )
        assertPacketBytes(
            StatusPongResponsePacket(0x0102030405060708),
            StatusPongResponsePacket.serializer(),
            "0102030405060708",
        )
    }

    @Test
    fun `login profile and transition packets follow vanilla field order`() {
        assertPacketBytes(
            LoginDisconnectPacket(JsonTextComponent("{}")),
            LoginDisconnectPacket.serializer(),
            "027b7d",
        )
        assertPacketBytes(
            SetCompressionPacket(300),
            SetCompressionPacket.serializer(),
            "ac02",
        )
        assertPacketBytes(
            LoginStartPacket("a", Uuid.fromLongs(1, 2)),
            LoginStartPacket.serializer(),
            "0161" +
                    "0000000000000001" +
                    "0000000000000002",
        )
        assertPacketBytes(
            LoginSuccessPacket(
                GameProfile(Uuid.fromLongs(1, 2), "a", emptyList()),
                Uuid.fromLongs(3, 4),
            ),
            LoginSuccessPacket.serializer(),
            "0000000000000001" +
                    "0000000000000002" +
                    "0161" +
                    "00" +
                    "0000000000000003" +
                    "0000000000000004",
        )
        assertPacketBytes(
            LoginAcknowledgedPacket,
            LoginAcknowledgedPacket.serializer(),
            "",
        )
        assertPacketBytes(
            LoginCookieRequestPacket(Identifier("test")),
            LoginCookieRequestPacket.serializer(),
            "0e6d696e6563726166743a74657374",
        )
        assertPacketBytes(
            LoginCookieResponsePacket(Identifier("test"), null),
            LoginCookieResponsePacket.serializer(),
            "0e6d696e6563726166743a7465737400",
        )
    }

    @Test
    fun `login encryption and custom query payload boundaries match official writes`() {
        assertPacketBytes(
            EncryptionRequestPacket(
                serverId = "",
                publicKey = ByteString(byteArrayOf(1, 2)),
                verifyToken = ByteString(byteArrayOf(3)),
                shouldAuthenticate = true,
            ),
            EncryptionRequestPacket.serializer(),
            "00020102010301",
        )
        assertPacketBytes(
            EncryptionResponsePacket(
                sharedSecret = ByteString(byteArrayOf(1)),
                verifyToken = ByteString(byteArrayOf(2, 3)),
            ),
            EncryptionResponsePacket.serializer(),
            "0101020203",
        )
        assertPacketBytes(
            LoginPluginRequestPacket(
                messageId = 300,
                channel = Identifier("test"),
                data = ByteString(byteArrayOf(1, 2)),
            ),
            LoginPluginRequestPacket.serializer(),
            "ac020e6d696e6563726166743a746573740102",
        )
        assertPacketBytes(
            LoginPluginResponsePacket(1, null),
            LoginPluginResponsePacket.serializer(),
            "0100",
        )
        assertPacketBytes(
            LoginPluginResponsePacket(1, ByteString(byteArrayOf(2, 3))),
            LoginPluginResponsePacket.serializer(),
            "01010203",
        )
    }

    @Test
    fun `configuration primitive and terminal packets retain fixed widths`() {
        assertPacketBytes(
            ConfigurationClientboundKeepAlivePacket(1),
            ConfigurationClientboundKeepAlivePacket.serializer(),
            "0000000000000001",
        )
        assertPacketBytes(
            ConfigurationServerboundKeepAlivePacket(2),
            ConfigurationServerboundKeepAlivePacket.serializer(),
            "0000000000000002",
        )
        assertPacketBytes(
            ConfigurationPingPacket(3),
            ConfigurationPingPacket.serializer(),
            "00000003",
        )
        assertPacketBytes(
            ConfigurationPongPacket(4),
            ConfigurationPongPacket.serializer(),
            "00000004",
        )
        assertPacketBytes(
            ConfigurationTransferPacket("a", 255),
            ConfigurationTransferPacket.serializer(),
            "0161ff01",
        )
        assertPacketBytes(
            CodeOfConductPacket("a"),
            CodeOfConductPacket.serializer(),
            "0161",
        )
        assertPacketBytes(
            FinishConfigurationPacket,
            FinishConfigurationPacket.serializer(),
            "",
        )
        assertPacketBytes(ResetChatPacket, ResetChatPacket.serializer(), "")
        assertPacketBytes(
            ConfigurationClearDialogPacket,
            ConfigurationClearDialogPacket.serializer(),
            "",
        )
        assertPacketBytes(
            AcknowledgeFinishConfigurationPacket,
            AcknowledgeFinishConfigurationPacket.serializer(),
            "",
        )
        assertPacketBytes(
            AcceptCodeOfConductPacket,
            AcceptCodeOfConductPacket.serializer(),
            "",
        )
    }

    @Test
    fun `configuration registry pack and tag collections use nested VarInt counts`() {
        assertPacketBytes(
            RegistryDataPacket(
                Identifier("test"),
                listOf(RegistryEntry(Identifier("entry"), null)),
            ),
            RegistryDataPacket.serializer(),
            "0e6d696e6563726166743a74657374" +
                    "01" +
                    "0f6d696e6563726166743a656e747279" +
                    "00",
        )
        assertPacketBytes(
            FeatureFlagsPacket(setOf(Identifier("test"))),
            FeatureFlagsPacket.serializer(),
            "010e6d696e6563726166743a74657374",
        )
        assertPacketBytes(
            ConfigurationUpdateTagsPacket(
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
            ConfigurationUpdateTagsPacket.serializer(),
            "01" +
                    "0f6d696e6563726166743a626c6f636b" +
                    "01" +
                    "0e6d696e6563726166743a74657374" +
                    "0201ac02",
        )
        val pack = KnownPack("m", "c", "1")
        assertPacketBytes(
            ConfigurationClientboundKnownPacksPacket(listOf(pack)),
            ConfigurationClientboundKnownPacksPacket.serializer(),
            "01016d01630131",
        )
        assertPacketBytes(
            ConfigurationServerboundKnownPacksPacket(listOf(pack)),
            ConfigurationServerboundKnownPacksPacket.serializer(),
            "01016d01630131",
        )
    }

    @Test
    fun `configuration cookies and resource packs preserve every optional marker`() {
        val identifierBytes = "0e6d696e6563726166743a74657374"
        assertPacketBytes(
            ConfigurationCookieRequestPacket(Identifier("test")),
            ConfigurationCookieRequestPacket.serializer(),
            identifierBytes,
        )
        assertPacketBytes(
            ConfigurationStoreCookiePacket(
                Identifier("test"),
                ByteString(byteArrayOf(1, 2)),
            ),
            ConfigurationStoreCookiePacket.serializer(),
            identifierBytes + "020102",
        )
        assertPacketBytes(
            ConfigurationCookieResponsePacket(Identifier("test"), null),
            ConfigurationCookieResponsePacket.serializer(),
            identifierBytes + "00",
        )
        assertPacketBytes(
            ConfigurationCookieResponsePacket(
                Identifier("test"),
                ByteString(byteArrayOf(1, 2)),
            ),
            ConfigurationCookieResponsePacket.serializer(),
            identifierBytes + "01020102",
        )
        assertPacketBytes(
            ConfigurationRemoveResourcePackPacket(null),
            ConfigurationRemoveResourcePackPacket.serializer(),
            "00",
        )
        assertPacketBytes(
            ConfigurationAddResourcePackPacket(
                uuid = Uuid.fromLongs(0, 0),
                url = "u",
                hash = "h",
                forced = true,
                promptMessage = null,
            ),
            ConfigurationAddResourcePackPacket.serializer(),
            "00000000000000000000000000000000" +
                    "0175" +
                    "0168" +
                    "01" +
                    "00",
        )
        assertPacketBytes(
            ConfigurationResourcePackResponsePacket(
                Uuid.fromLongs(0, 0),
                ResourcePackResult.ACCEPTED,
            ),
            ConfigurationResourcePackResponsePacket.serializer(),
            "00000000000000000000000000000000" + "03",
        )
    }

    @Test
    fun `configuration shared structures cover client info reports links and NBT`() {
        assertPacketBytes(
            ConfigurationClientInformationPacket(
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
            ConfigurationClientInformationPacket.serializer(),
            "05656e5f7573" +
                    "08" +
                    "01" +
                    "01" +
                    "ff" +
                    "01" +
                    "00" +
                    "01" +
                    "02",
        )
        assertPacketBytes(
            ConfigurationCustomReportDetailsPacket(
                listOf(ReportDetail("t", "d")),
            ),
            ConfigurationCustomReportDetailsPacket.serializer(),
            "0101740164",
        )
        assertPacketBytes(
            ConfigurationServerLinksPacket(
                listOf(
                    ServerLink(
                        ServerLinkLabel.BuiltIn(
                            BuiltInServerLinkLabel.BUG_REPORT,
                        ),
                        "u",
                    ),
                ),
            ),
            ConfigurationServerLinksPacket.serializer(),
            "0101000175",
        )
        assertPacketBytes(
            ConfigurationShowDialogPacket(NbtString("x")),
            ConfigurationShowDialogPacket.serializer(),
            "08000178",
        )
        assertPacketBytes(
            ConfigurationClientboundPluginMessagePacket(
                CustomPayload.Brand("x"),
            ),
            ConfigurationClientboundPluginMessagePacket.serializer(),
            "0f6d696e6563726166743a6272616e640178",
        )
        assertPacketBytes(
            ConfigurationServerboundPluginMessagePacket(
                CustomPayload.Brand("x"),
            ),
            ConfigurationServerboundPluginMessagePacket.serializer(),
            "0f6d696e6563726166743a6272616e640178",
        )
    }

    @Test
    fun `configuration and login packet-specific limits reject oversized values`() {
        assertFailsWith<MinecraftSerializationException> {
            MinecraftFormat.encodeToByteArray(
                LoginStartPacket.serializer(),
                LoginStartPacket("x".repeat(17), Uuid.fromLongs(0, 0)),
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftFormat.encodeToByteArray(
                ConfigurationStoreCookiePacket.serializer(),
                ConfigurationStoreCookiePacket(
                    Identifier("test"),
                    ByteString(ByteArray(5_121)),
                ),
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftFormat.encodeToByteArray(
                ConfigurationCustomReportDetailsPacket.serializer(),
                ConfigurationCustomReportDetailsPacket(
                    List(33) { ReportDetail("t", "d") },
                ),
            )
        }
        assertFailsWith<MinecraftSerializationException> {
            MinecraftFormat.encodeToByteArray(
                ConfigurationServerboundKnownPacksPacket.serializer(),
                ConfigurationServerboundKnownPacksPacket(
                    List(65) { KnownPack("m", "c", "1") },
                ),
            )
        }
    }

    private fun <T> assertPacketBytes(
        value: T,
        serializer: KSerializer<T>,
        expectedHex: String,
    ) {
        val expected = expectedHex.hexToByteArray()
        assertContentEquals(
            expected,
            MinecraftFormat.encodeToByteArray(serializer, value),
        )
        assertEquals(
            value,
            MinecraftFormat.decodeFromByteArray(serializer, expected),
        )
    }
}
