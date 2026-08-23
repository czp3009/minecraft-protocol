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
        val keyPair = fixtureKeyPair()
        val signer = MinecraftChatChainSigner(sender, session, keyPair)
        val bodies = listOf(messageBody("first", 1_000), messageBody("second", 1_000))
        val signed = signer.signAll(bodies)
        val verifier = MinecraftServerboundChatChainVerifier(sender, session, keyPair.publicKey)

        assertEquals(listOf(0, 1), signed.map { it.link.index })
        assertIs<MinecraftChatVerificationResult.Valid>(
            verifier.verifyNext(signed[0].body, signed[0].signature),
        )
        val invalid = assertIs<MinecraftChatVerificationResult.Invalid>(
            verifier.verifyNext(signed[1].body.copy(content = "tampered"), signed[1].signature),
        )
        assertEquals(MinecraftChatChainFailure.INVALID_SIGNATURE, invalid.failure)
        assertEquals(1, verifier.nextLink()?.index)
        assertIs<MinecraftChatVerificationResult.Valid>(
            verifier.verifyNext(signed[1].body, signed[1].signature),
        )

        val staleLink = assertIs<MinecraftChatVerificationResult.Invalid>(
            verifier.verifyNext(messageBody("stale", 999), signed[1].signature),
        )
        assertEquals(MinecraftChatChainFailure.OUT_OF_ORDER_TIMESTAMP, staleLink.failure)
        assertEquals(2, verifier.nextLink()?.index)
    }

    @Test
    fun serverboundVerifierRejectsMalformedSignaturesBeforeCallingCustomVerifier() = runTest {
        val sender = Uuid.parse("10000000-0000-0000-0000-000000000001")
        val session = Uuid.parse("20000000-0000-0000-0000-000000000002")
        var verifierCalled = false
        val verifier = MinecraftServerboundChatChainVerifier(
            sender = sender,
            sessionId = session,
            signatureVerifier = MinecraftChatSignatureVerifier { _, _, _ ->
                verifierCalled = true
                true
            },
        )

        val invalid = assertIs<MinecraftChatVerificationResult.Invalid>(
            verifier.verifyNext(messageBody("malformed", 1_000), ByteString(byteArrayOf(1))),
        )

        assertEquals(MinecraftChatChainFailure.INVALID_SIGNATURE, invalid.failure)
        assertTrue(!verifierCalled)
        assertEquals(0, verifier.nextLink()?.index)
    }

    @Test
    fun signerSerializesConcurrentIndicesAndRollsBackFailedBatches() = runTest {
        val sender = Uuid.parse("30000000-0000-0000-0000-000000000003")
        val session = Uuid.parse("40000000-0000-0000-0000-000000000004")
        val expectedFailure = ExpectedSigningFailure()
        val signatureSigner = MinecraftChatSignatureSigner { _, body ->
            yield()
            if (body.content == "fail") {
                throw expectedFailure
            }
            ByteString(ByteArray(256))
        }
        val signer = MinecraftChatChainSigner(sender, session, signatureSigner)

        val failure = assertFailsWith<ExpectedSigningFailure> {
            signer.signAll(listOf(messageBody("ok", 0), messageBody("fail", 0)))
        }
        assertSame(expectedFailure, failure)
        assertEquals(0, signer.nextLink()?.index)

        val messages = List(32) { index ->
            async { signer.sign(messageBody(index.toString(), index.toLong())) }
        }.awaitAll()
        assertEquals((0 until 32).toList(), messages.map { it.link.index }.sorted())
        assertEquals(32, signer.nextLink()?.index)
    }

    @Test
    fun signedCommandShortcutAdvancesOncePerArgumentAndValidatesNames() = runTest {
        val sender = Uuid.parse("50000000-0000-0000-0000-000000000005")
        val session = Uuid.parse("60000000-0000-0000-0000-000000000006")
        val keyPair = fixtureKeyPair()
        val signer = MinecraftChatChainSigner(sender, session, keyPair)
        val verifier = MinecraftServerboundChatChainVerifier(sender, session, keyPair.publicKey)
        val arguments = listOf(
            SignableCommandArgument("target", "Player"),
            SignableCommandArgument("message", "hello"),
        )
        val lastSeen = emptyList<ByteString>()
        val signedArguments = signer.signCommandArguments(
            arguments = arguments,
            timestampEpochMillis = 2_000,
            salt = 42,
            lastSeen = lastSeen,
        )
        val packet = SignedChatCommandPacket(
            command = "msg Player hello",
            timestampEpochMillis = 2_000,
            salt = 42,
            arguments = signedArguments,
            lastSeenMessages = emptyLastSeenUpdate(),
        )

        val valid = assertIs<MinecraftChatBatchVerificationResult.Valid>(
            verifier.verify(packet, arguments, lastSeen),
        )
        assertEquals(listOf(0, 1), valid.messages.map { it.link.index })
        assertEquals(2, verifier.nextLink()?.index)

        val mismatch = assertIs<MinecraftChatBatchVerificationResult.Invalid>(
            MinecraftServerboundChatChainVerifier(sender, session, keyPair.publicKey).verify(
                packet,
                listOf(SignableCommandArgument("different", "Player")),
                lastSeen,
            ),
        )
        assertEquals(MinecraftChatChainFailure.ARGUMENT_MISMATCH, mismatch.failure)
    }

    @Test
    fun clientboundVerifierAllowsGapsAndExactDuplicatesButRejectsOlderIndices() = runTest {
        val sender = Uuid.parse("70000000-0000-0000-0000-000000000007")
        val session = Uuid.parse("80000000-0000-0000-0000-000000000008")
        val keyPair = fixtureKeyPair()
        val verifier = MinecraftClientboundChatChainVerifier(sender, session, keyPair.publicKey)
        val first = signedMessage(keyPair, SignedMessageLink(0, sender, session), "first")
        val skipped = signedMessage(keyPair, SignedMessageLink(2, sender, session), "skipped")
        val older = signedMessage(keyPair, SignedMessageLink(1, sender, session), "older")

        assertIs<MinecraftChatVerificationResult.Valid>(verifier.verify(first.link, first.body, first.signature))
        assertIs<MinecraftChatVerificationResult.Valid>(verifier.verify(skipped.link, skipped.body, skipped.signature))
        assertIs<MinecraftChatVerificationResult.Valid>(verifier.verify(skipped.link, skipped.body, skipped.signature))
        val invalid = assertIs<MinecraftChatVerificationResult.Invalid>(
            verifier.verify(older.link, older.body, older.signature),
        )
        assertEquals(MinecraftChatChainFailure.OUT_OF_ORDER_INDEX, invalid.failure)
        assertEquals(skipped, verifier.lastMessage())

        val packet = skipped.toPlayerChatMessagePacket(
            globalIndex = 9,
            chatType = BoundChatType(
                chatType = ChatTypeHolder.Reference(0),
                name = TextComponent.literal("sender"),
                targetName = null,
            ),
            packedLastSeen = emptyList(),
            filterMask = FilterMask.PassThrough,
        )
        assertEquals(2, packet.index)
        assertEquals(9, packet.globalIndex)
        assertNull(packet.unsignedContent)
        assertTrue(packet.signature == skipped.signature)
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
    keyPair: MinecraftProfileKeyPair,
    link: SignedMessageLink,
    content: String,
): MinecraftSignedMessage {
    val body = messageBody(content, link.index.toLong())
    return MinecraftSignedMessage(link, body, keyPair.signChatMessage(link, body))
}

private fun emptyLastSeenUpdate(): LastSeenMessagesUpdate = LastSeenMessagesUpdate(
    offset = 0,
    acknowledged = ByteString(ByteArray(3)),
    checksum = 0,
)

private class ExpectedSigningFailure : RuntimeException()
