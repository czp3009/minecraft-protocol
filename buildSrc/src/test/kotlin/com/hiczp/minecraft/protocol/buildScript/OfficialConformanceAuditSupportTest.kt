package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class OfficialConformanceAuditSupportTest {
    @Test
    fun officialSetReencodingOrderDoesNotDestabilizeEvidence() {
        val stableNormalization = report(
            result(
                validation =
                    "complete-decode-and-stable-official-normalization",
                extra = mapOf(
                    "normalized_payload_sha256" to jsonString("first-order"),
                    "normalized_payload_size" to jsonNumber(17),
                ),
            ),
        )
        val variedNormalization = report(
            result(
                validation =
                    "complete-decode-with-nondeterministic-official-reencoding",
            ),
        )

        assertEquals(
            officialCodecEvidenceFingerprint(stableNormalization),
            officialCodecEvidenceFingerprint(variedNormalization),
        )
    }

    @Test
    fun meaningfulOfficialCodecEvidenceChangesRemainVisible() {
        val exact = report(
            result(validation = "decode-and-byte-identical-reencode"),
        )
        val normalized = report(
            result(
                validation =
                    "complete-decode-and-stable-official-normalization",
            ),
        )
        val changedPayload = report(
            result(
                validation = "decode-and-byte-identical-reencode",
                payloadSha256 = "changed",
            ),
        )

        assertNotEquals(
            officialCodecEvidenceFingerprint(exact),
            officialCodecEvidenceFingerprint(normalized),
        )
        assertNotEquals(
            officialCodecEvidenceFingerprint(exact),
            officialCodecEvidenceFingerprint(changedPayload),
        )
    }

    @Test
    fun unrelatedNormalizedOutputRemainsSignificant() {
        val first = report(
            result(
                key = "STATUS/CLIENTBOUND/0x0",
                sample = "valid_json",
                validation =
                    "complete-decode-and-stable-official-normalization",
                extra = mapOf(
                    "normalized_payload_sha256" to jsonString("first"),
                    "normalized_payload_size" to jsonNumber(17),
                ),
            ),
        )
        val second = report(
            result(
                key = "STATUS/CLIENTBOUND/0x0",
                sample = "valid_json",
                validation =
                    "complete-decode-and-stable-official-normalization",
                extra = mapOf(
                    "normalized_payload_sha256" to jsonString("second"),
                    "normalized_payload_size" to jsonNumber(17),
                ),
            ),
        )

        assertNotEquals(
            officialCodecEvidenceFingerprint(first),
            officialCodecEvidenceFingerprint(second),
        )
    }

    private fun report(result: JsonObject): JsonObject = jsonObjectOf(
        "schema_version" to jsonNumber(1),
        "minecraft_version" to jsonString("test"),
        "protocol_version" to jsonNumber(1),
        "official_server_inner_sha256" to jsonString("server"),
        "fixture_sha256" to jsonString("fixtures"),
        "expected_packet_count" to jsonNumber(1),
        "covered_packet_count" to jsonNumber(1),
        "fixture_count" to jsonNumber(1),
        "passed" to jsonNumber(1),
        "failed" to jsonNumber(0),
        "results" to JsonArray(listOf(result)),
    )

    private fun result(
        validation: String,
        key: String = "PLAY/SERVERBOUND/0x17",
        sample: String = "all-subscriptions",
        payloadSha256: String = "payload",
        extra: Map<String, kotlinx.serialization.json.JsonElement> =
            emptyMap(),
    ): JsonObject = JsonObject(
        linkedMapOf(
            "key" to jsonString(key),
            "sample" to jsonString(sample),
            "payload_sha256" to jsonString(payloadSha256),
            "status" to jsonString("pass"),
            "validation" to jsonString(validation),
        ) + extra,
    )
}
