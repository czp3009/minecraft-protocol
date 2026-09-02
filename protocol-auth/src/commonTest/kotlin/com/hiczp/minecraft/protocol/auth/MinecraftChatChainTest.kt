package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.packet.SignedChatCommandPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.*
import kotlin.uuid.Uuid

class MinecraftChatChainTest {
    @Test
    fun logicalSignatureValuesEnforceTheirIntrinsicContracts() {
        assertFailsWith<IllegalArgumentException> {
            SignedMessageLink(-1, Uuid.fromLongs(0, 0), Uuid.fromLongs(0, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            SignedMessageBody(
                content = "message",
                timestampEpochMillis = 0,
                salt = 0,
                lastSeen = listOf(ByteString(ByteArray(255))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SignedMessageBody(
                content = "message",
                timestampEpochMillis = 0,
                salt = 0,
                lastSeen = List(21) { ByteString(ByteArray(256)) },
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SignableCommandArgument("x".repeat(17), "value")
        }
        val rootLink = SignedMessageLink(0, Uuid.fromLongs(1, 2), Uuid.fromLongs(3, 4))
        assertEquals(1, rootLink.advance()?.index)
        assertTrue(rootLink.advance()?.isDescendantOf(rootLink) == true)
        assertNull(SignedMessageLink(Int.MAX_VALUE, rootLink.sender, rootLink.sessionId).advance())
    }

    @Test
    fun serverboundVerifierDerivesImplicitIndicesAndCommitsOnlyValidMessages() = runTest {
        val sender = Uuid.parse("10000000-0000-0000-0000-000000000001")
        val session = Uuid.parse("20000000-0000-0000-0000-000000000002")
        val minecraftProfileKeyPair = fixtureKeyPair()
        val minecraftChatChainSigner = MinecraftChatChainSigner(sender, session, minecraftProfileKeyPair)
        val bodies = listOf(messageBody("first", 1_000), messageBody("second", 1_000))
        val signed = minecraftChatChainSigner.signAll(bodies)
        val minecraftServerboundChatChainVerifier =
            MinecraftServerboundChatChainVerifier(sender, session, minecraftProfileKeyPair.minecraftProfilePublicKey)

        assertEquals(listOf(0, 1), signed.map { it.signedMessageLink.index })
        assertIs<MinecraftChatVerificationResult.Valid>(
            minecraftServerboundChatChainVerifier.verifyNext(signed[0].signedMessageBody, signed[0].signature),
        )
        val invalid = assertIs<MinecraftChatVerificationResult.Invalid>(
            minecraftServerboundChatChainVerifier.verifyNext(
                signed[1].signedMessageBody.copy(content = "tampered"),
                signed[1].signature
            ),
        )
        assertEquals(MinecraftChatChainFailure.INVALID_SIGNATURE, invalid.minecraftChatChainFailure)
        assertEquals(1, minecraftServerboundChatChainVerifier.nextLink()?.index)
        assertIs<MinecraftChatVerificationResult.Valid>(
            minecraftServerboundChatChainVerifier.verifyNext(signed[1].signedMessageBody, signed[1].signature),
        )

        val staleLink = assertIs<MinecraftChatVerificationResult.Invalid>(
            minecraftServerboundChatChainVerifier.verifyNext(messageBody("stale", 999), signed[1].signature),
        )
        assertEquals(MinecraftChatChainFailure.OUT_OF_ORDER_TIMESTAMP, staleLink.minecraftChatChainFailure)
        assertEquals(2, minecraftServerboundChatChainVerifier.nextLink()?.index)
    }

    @Test
    fun serverboundVerifierRejectsMalformedSignaturesBeforeCallingCustomVerifier() = runTest {
        val sender = Uuid.parse("10000000-0000-0000-0000-000000000001")
        val session = Uuid.parse("20000000-0000-0000-0000-000000000002")
        var verifierCalled = false
        val minecraftServerboundChatChainVerifier = MinecraftServerboundChatChainVerifier(
            sender = sender,
            sessionId = session,
            minecraftChatSignatureVerifier = MinecraftChatSignatureVerifier { _, _, _ ->
                verifierCalled = true
                true
            },
        )

        val invalid = assertIs<MinecraftChatVerificationResult.Invalid>(
            minecraftServerboundChatChainVerifier.verifyNext(
                messageBody("malformed", 1_000),
                ByteString(byteArrayOf(1))
            ),
        )

        assertEquals(MinecraftChatChainFailure.INVALID_SIGNATURE, invalid.minecraftChatChainFailure)
        assertTrue(!verifierCalled)
        assertEquals(0, minecraftServerboundChatChainVerifier.nextLink()?.index)
    }

    @Test
    fun signerSerializesConcurrentIndicesAndRollsBackFailedBatches() = runTest {
        val sender = Uuid.parse("30000000-0000-0000-0000-000000000003")
        val session = Uuid.parse("40000000-0000-0000-0000-000000000004")
        val expectedSigningFailure = ExpectedSigningFailure()
        val minecraftChatSignatureSigner = MinecraftChatSignatureSigner { _, signedMessageBody ->
            yield()
            if (signedMessageBody.content == "fail") {
                throw expectedSigningFailure
            }
            ByteString(ByteArray(256))
        }
        val minecraftChatChainSigner = MinecraftChatChainSigner(sender, session, minecraftChatSignatureSigner)

        val thrownSigningFailure = assertFailsWith<ExpectedSigningFailure> {
            minecraftChatChainSigner.signAll(listOf(messageBody("ok", 0), messageBody("fail", 0)))
        }
        assertSame(expectedSigningFailure, thrownSigningFailure)
        assertEquals(0, minecraftChatChainSigner.nextLink()?.index)

        val messages = List(32) { index ->
            async { minecraftChatChainSigner.sign(messageBody(index.toString(), index.toLong())) }
        }.awaitAll()
        assertEquals((0 until 32).toList(), messages.map { it.signedMessageLink.index }.sorted())
        assertEquals(32, minecraftChatChainSigner.nextLink()?.index)
    }

    @Test
    fun signedCommandShortcutAdvancesOncePerArgumentAndValidatesNames() = runTest {
        val sender = Uuid.parse("50000000-0000-0000-0000-000000000005")
        val session = Uuid.parse("60000000-0000-0000-0000-000000000006")
        val minecraftProfileKeyPair = fixtureKeyPair()
        val minecraftChatChainSigner = MinecraftChatChainSigner(sender, session, minecraftProfileKeyPair)
        val minecraftServerboundChatChainVerifier =
            MinecraftServerboundChatChainVerifier(sender, session, minecraftProfileKeyPair.minecraftProfilePublicKey)
        val arguments = listOf(
            SignableCommandArgument("target", "Player"),
            SignableCommandArgument("message", "hello"),
        )
        val lastSeen = emptyList<ByteString>()
        val signedCommandArguments = minecraftChatChainSigner.signCommandArguments(
            arguments = arguments,
            timestampEpochMillis = 2_000,
            salt = 42,
            lastSeen = lastSeen,
        )
        val signedChatCommandPacket = SignedChatCommandPacket(
            command = "msg Player hello",
            timestampEpochMillis = 2_000,
            salt = 42,
            arguments = signedCommandArguments,
            lastSeenMessages = emptyLastSeenUpdate(),
        )

        val valid = assertIs<MinecraftChatBatchVerificationResult.Valid>(
            minecraftServerboundChatChainVerifier.verify(signedChatCommandPacket, arguments, lastSeen),
        )
        assertEquals(listOf(0, 1), valid.messages.map { it.signedMessageLink.index })
        assertEquals(2, minecraftServerboundChatChainVerifier.nextLink()?.index)

        val mismatch = assertIs<MinecraftChatBatchVerificationResult.Invalid>(
            MinecraftServerboundChatChainVerifier(
                sender,
                session,
                minecraftProfileKeyPair.minecraftProfilePublicKey
            ).verify(
                signedChatCommandPacket,
                listOf(SignableCommandArgument("different", "Player")),
                lastSeen,
            ),
        )
        assertEquals(MinecraftChatChainFailure.ARGUMENT_MISMATCH, mismatch.minecraftChatChainFailure)
    }

    @Test
    fun clientboundVerifierAllowsGapsAndExactDuplicatesButRejectsOlderIndices() = runTest {
        val sender = Uuid.parse("70000000-0000-0000-0000-000000000007")
        val session = Uuid.parse("80000000-0000-0000-0000-000000000008")
        val minecraftProfileKeyPair = fixtureKeyPair()
        val minecraftClientboundChatChainVerifier =
            MinecraftClientboundChatChainVerifier(sender, session, minecraftProfileKeyPair.minecraftProfilePublicKey)
        val first = signedMessage(minecraftProfileKeyPair, SignedMessageLink(0, sender, session), "first")
        val skipped = signedMessage(minecraftProfileKeyPair, SignedMessageLink(2, sender, session), "skipped")
        val older = signedMessage(minecraftProfileKeyPair, SignedMessageLink(1, sender, session), "older")

        assertIs<MinecraftChatVerificationResult.Valid>(
            minecraftClientboundChatChainVerifier.verify(
                first.signedMessageLink,
                first.signedMessageBody,
                first.signature
            )
        )
        assertIs<MinecraftChatVerificationResult.Valid>(
            minecraftClientboundChatChainVerifier.verify(
                skipped.signedMessageLink,
                skipped.signedMessageBody,
                skipped.signature
            )
        )
        assertIs<MinecraftChatVerificationResult.Valid>(
            minecraftClientboundChatChainVerifier.verify(
                skipped.signedMessageLink,
                skipped.signedMessageBody,
                skipped.signature
            )
        )
        val invalid = assertIs<MinecraftChatVerificationResult.Invalid>(
            minecraftClientboundChatChainVerifier.verify(
                older.signedMessageLink,
                older.signedMessageBody,
                older.signature
            ),
        )
        assertEquals(MinecraftChatChainFailure.OUT_OF_ORDER_INDEX, invalid.minecraftChatChainFailure)
        assertEquals(skipped, minecraftClientboundChatChainVerifier.lastMessage())

        val packedLastSeen = listOf(PackedMessageSignature.Cached(3))
        val playerChatMessagePacket = skipped.toPlayerChatMessagePacket(
            globalIndex = 9,
            boundChatType = BoundChatType(
                chatType = ChatTypeHolder.Reference(0),
                name = TextComponent.literal("sender"),
                targetName = null,
            ),
            packedLastSeen = packedLastSeen,
            filterMask = FilterMask.PassThrough,
        )
        assertEquals(2, playerChatMessagePacket.index)
        assertEquals(9, playerChatMessagePacket.globalIndex)
        assertEquals(packedLastSeen, playerChatMessagePacket.body.lastSeen)
        assertNull(playerChatMessagePacket.unsignedContent)
        assertTrue(playerChatMessagePacket.signature == skipped.signature)
    }
}

private fun messageBody(
    content: String,
    timestampEpochMillis: Long,
): SignedMessageBody = SignedMessageBody(
    content = content,
    timestampEpochMillis = timestampEpochMillis,
    salt = 1,
    lastSeen = emptyList(),
)

private fun signedMessage(
    minecraftProfileKeyPair: MinecraftProfileKeyPair,
    signedMessageLink: SignedMessageLink,
    content: String,
): MinecraftSignedMessage {
    val signedMessageBody = messageBody(content, signedMessageLink.index.toLong())
    return MinecraftSignedMessage(
        signedMessageLink,
        signedMessageBody,
        minecraftProfileKeyPair.signChatMessage(signedMessageLink, signedMessageBody)
    )
}

private fun emptyLastSeenUpdate(): LastSeenMessagesUpdate = LastSeenMessagesUpdate(
    offset = 0,
    acknowledged = ByteString(ByteArray(3)),
    checksum = 0,
)

private class ExpectedSigningFailure : RuntimeException()
