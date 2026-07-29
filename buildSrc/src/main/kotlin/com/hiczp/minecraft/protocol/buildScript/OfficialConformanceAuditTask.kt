package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.*
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

abstract class AuditOfficialConformanceTask :
    MinecraftProtocolToolTask() {
    @get:Input
    abstract val refresh: Property<Boolean>

    @get:Input
    abstract val reportOnly: Property<Boolean>

    @get:Internal
    abstract val reportFile: RegularFileProperty

    init {
        refresh.convention(false)
        reportOnly.convention(false)
    }

    @TaskAction
    fun audit() {
        val references = repository.resolve("protocol-specification")
        val ledgerPath = references.resolve(
            "official-conformance-ledger.json",
        )
        var errors: List<String>
        var report: JsonObject
        try {
            val target = targetMetadata(references)
            val localPackets = loadLocal()
            val officialPackets = loadOfficial(
                references.resolve("official-packet-classes.csv"),
            )
            if (refresh.get()) {
                refreshLedger(
                    ledgerPath,
                    target,
                    localPackets,
                    officialPackets,
                )
            }
            val validation = validate(
                ledgerPath,
                target,
                localPackets,
                officialPackets,
            )
            errors = validation.first
            report = validation.second
        } catch (failure: Throwable) {
            errors = listOf(failure.message ?: failure.toString())
            report = jsonObjectOf(
                "errors" to JsonArray(errors.map(::jsonString)),
            )
        }
        if (reportFile.isPresent) {
            reportFile.asFile.get().toPath().writeJson(report)
        }
        if (errors.isNotEmpty()) {
            if (reportOnly.get()) {
                logger.lifecycle(
                    "Official conformance report: ${errors.size} " +
                            "issue(s); see " +
                            (reportFile.orNull?.asFile ?: "the report output") +
                            ".",
                )
                return
            }
            error(errors.joinToString("\n") { "ERROR: $it" })
        }
        logger.lifecycle(
            "Official conformance audit passed: " +
                    "${report.requiredInt("passing_packet_count")} packet " +
                    "entries are current and passing.",
        )
    }

    private fun targetMetadata(references: Path): JsonObject {
        val snapshot = references.resolve(
            "wiki-protocol-snapshot.json",
        ).readJsonObject()
        val officialAudit = references.resolve(
            "official-packet-audit.json",
        ).readJsonObject()
        val target = jsonObjectOf(
            "minecraft_version" to
                    jsonString(snapshot.requiredString("minecraft_version")),
            "protocol_version" to
                    jsonNumber(snapshot.requiredInt("protocol_version")),
            "wiki_revision_id" to jsonNumber(
                snapshot.requiredObject("source")
                    .requiredInt("revision_id"),
            ),
            "official_server_sha1" to jsonString(
                officialAudit.requiredObject("vanilla")
                    .requiredString("server_sha1"),
            ),
        )
        listOf(
            "minecraft_version",
            "protocol_version",
            "wiki_revision_id",
        ).forEach { key ->
            check(target.getValue(key) == officialAudit.getValue(key)) {
                "Wiki snapshot and official packet audit disagree on $key"
            }
        }
        return target
    }

    private fun loadLocal(): Map<PacketKey, ConformanceLocalPacket> {
        val (packets, errors) = loadLocalPackets(repository)
        check(errors.isEmpty()) { errors.joinToString("\n") }
        val result = linkedMapOf<PacketKey, ConformanceLocalPacket>()
        packets.forEach { packet ->
            check(packet.key !in result) {
                "Duplicate local packet ${packet.key.conformanceText()}"
            }
            result[packet.key] = ConformanceLocalPacket(
                packet.key,
                packet.className,
                packet.officialName.orEmpty(),
                packet.path,
            )
        }
        return result
    }

    private fun loadOfficial(
        path: Path,
    ): Map<PacketKey, ConformanceOfficialPacket> {
        val result = linkedMapOf<PacketKey, ConformanceOfficialPacket>()
        readCsv(path).use { parser ->
            parser.forEach { row ->
                val key = PacketKey(
                    row["state"].uppercase(),
                    row["direction"].uppercase(),
                    parseInteger(row["id"]),
                )
                check(key !in result) {
                    "Duplicate official packet ${key.conformanceText()}"
                }
                result[key] = ConformanceOfficialPacket(
                    key = key,
                    officialName = row["official_name"],
                    className = row["official_class"],
                    sourcePath = row["source_path"],
                    sourceSha256 = row["source_sha256"],
                )
            }
        }
        return result
    }

    private fun currentEvidence(): JsonObject {
        val testRoot = repository.resolve(
            "protocol-serialization/src/commonTest/kotlin/com/hiczp/" +
                    "minecraft/protocol/serialization",
        )
        val registryTest = testRoot.resolve("PacketRegistryTest.kt")
        val tests = Files.list(testRoot).use { paths ->
            paths.filter {
                Files.isRegularFile(it) &&
                        it.fileName.toString().endsWith("Test.kt")
            }.sorted().toList()
        }
        val officialReport = repository.resolve(
            "build/reports/protocol-update/" +
                    "official-codec-conformance.json",
        )
        val oracleSource = repository.resolve(
            "buildSrc/src/officialCodecOracle/java/" +
                    "OfficialCodecOracle.java",
        )
        check(officialReport.isRegularFile()) {
            "Official codec report is missing; run " +
                    "checkOfficialCodecConformance"
        }
        val official = officialReport.readJsonObject()
        val results = official["results"]?.jsonArray
            ?: error("Official codec report results must be an array")
        val expected = official["expected_packet_count"]
            ?.jsonPrimitive
            ?.intOrNull
            ?: error(
                "Official codec report has no expected packet count",
            )
        val covered = official["covered_packet_count"]
            ?.jsonPrimitive
            ?.intOrNull
        val fixtureCount = official["fixture_count"]
            ?.jsonPrimitive
            ?.intOrNull
            ?: error("Official codec report has no fixture count")
        val passed = official["passed"]?.jsonPrimitive?.intOrNull
        val failed = official["failed"]?.jsonPrimitive?.intOrNull
        check(
            covered == expected &&
                    passed == fixtureCount &&
                    failed == 0 &&
                    results.all {
                        it is JsonObject &&
                                it.optionalString("status") == "pass"
                    },
        ) {
            "Official codec report is not passing and complete; run " +
                    "checkOfficialCodecConformance"
        }
        val stableKeys = listOf(
            "schema_version",
            "minecraft_version",
            "protocol_version",
            "official_server_inner_sha256",
            "fixture_sha256",
            "expected_packet_count",
            "covered_packet_count",
            "fixture_count",
            "passed",
            "failed",
        )
        val stableEntries = linkedMapOf<String, JsonElement>()
        stableKeys.forEach {
            stableEntries[it] = official[it] ?: JsonNull
        }
        stableEntries["results"] = results
        val officialFingerprint = renderCanonicalJson(
            JsonObject(stableEntries),
        ).toByteArray(StandardCharsets.UTF_8).sha256()
        return jsonObjectOf(
            REGISTRY_EVIDENCE_ID to jsonObjectOf(
                "kind" to jsonString("registry-round-trip"),
                "path" to jsonString(relative(registryTest)),
                "sha256" to jsonString(registryTest.sha256()),
                "symbol" to jsonString(
                    "every registered normal packet has an executable " +
                            "binary round trip",
                ),
            ),
            SUITE_EVIDENCE_ID to jsonObjectOf(
                "kind" to jsonString("test-suite-aggregate"),
                "root" to jsonString(relative(testRoot)),
                "file_count" to jsonNumber(tests.size),
                "sha256" to jsonString(aggregateHash(tests)),
            ),
            OFFICIAL_CODEC_EVIDENCE_ID to jsonObjectOf(
                "kind" to jsonString(
                    "exact-version-official-codec-oracle",
                ),
                "path" to jsonString(relative(officialReport)),
                "sha256_without_generation_time" to
                        jsonString(officialFingerprint),
                "oracle_source" to jsonString(relative(oracleSource)),
                "oracle_source_sha256" to
                        jsonString(oracleSource.sha256()),
                "official_server_inner_sha256" to official.getValue(
                    "official_server_inner_sha256",
                ),
                "fixture_sha256" to
                        official.getValue("fixture_sha256"),
                "covered_packet_count" to jsonNumber(
                    requireNotNull(covered),
                ),
                "fixture_count" to jsonNumber(fixtureCount),
                "validation" to jsonString(
                    "complete payload consumption and official re-encoding",
                ),
            ),
        )
    }

    private fun implementationFingerprint(): JsonObject {
        val roots = listOf(
            repository.resolve(
                "protocol-model/src/commonMain/kotlin",
            ),
            repository.resolve(
                "protocol-serialization/src/commonMain/kotlin",
            ),
        )
        val sources = roots.flatMap(::kotlinSources).sorted()
        return jsonObjectOf(
            "roots" to JsonArray(
                roots.map { jsonString(relative(it)) },
            ),
            "file_count" to jsonNumber(sources.size),
            "sha256" to jsonString(aggregateHash(sources)),
        )
    }

    private fun aggregateHash(paths: List<Path>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        paths.sortedBy { relative(it).lowercase() }.forEach { path ->
            digest.update(
                relative(path).toByteArray(StandardCharsets.UTF_8),
            )
            digest.update(0)
            digest.update(
                path.sha256().toByteArray(StandardCharsets.UTF_8),
            )
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    private fun refreshLedger(
        ledgerPath: Path,
        target: JsonObject,
        localPackets: Map<PacketKey, ConformanceLocalPacket>,
        officialPackets: Map<PacketKey, ConformanceOfficialPacket>,
    ) {
        val previous = if (ledgerPath.exists()) {
            ledgerPath.readJsonObject()
        } else {
            JsonObject(emptyMap())
        }
        val oldEntries = previous["packets"]
            ?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { entry ->
                entry.optionalString("key")?.let { it to entry }
            }
            ?.toMap()
            .orEmpty()
        val evidence = currentEvidence()
        val implementation = implementationFingerprint()
        val evidenceUnchanged =
            previous["test_evidence"] == evidence
        val implementationUnchanged =
            previous["implementation_fingerprint"] == implementation
        val targetUnchanged = previous["target"] == target
        val entries = officialPackets.keys.sortedWith(packetKeyComparator)
            .mapNotNull { key ->
                val local = localPackets[key] ?: return@mapNotNull null
                val current = buildCurrentEntry(
                    local,
                    officialPackets.getValue(key),
                )
                val old = oldEntries[key.conformanceText()]
                if (
                    old != null &&
                    targetUnchanged &&
                    evidenceUnchanged &&
                    implementationUnchanged &&
                    entryFingerprint(old) == entryFingerprint(current)
                ) {
                    JsonObject(
                        current.toMutableMap().apply {
                            this["reviews"] = old["reviews"]
                                ?: pendingReviews()
                            this["notes"] = old["notes"]
                                ?: JsonArray(emptyList())
                        },
                    )
                } else {
                    current
                }
            }
        val ledger = jsonObjectOf(
            "schema_version" to jsonNumber(1),
            "target" to target,
            "implementation_fingerprint" to implementation,
            "test_evidence" to evidence,
            "review_policy" to jsonObjectOf(
                "authority" to jsonString(
                    "exact-version-official-server-jar",
                ),
                "required_reviews" to JsonArray(
                    requiredReviews.map(::jsonString),
                ),
                "registry_round_trip_required" to jsonBoolean(true),
                "official_codec_oracle_required" to jsonBoolean(true),
            ),
            "packets" to JsonArray(entries),
        )
        ledgerPath.writeJson(ledger)
    }

    private fun validate(
        ledgerPath: Path,
        target: JsonObject,
        localPackets: Map<PacketKey, ConformanceLocalPacket>,
        officialPackets: Map<PacketKey, ConformanceOfficialPacket>,
    ): Pair<List<String>, JsonObject> {
        val errors = mutableListOf<String>()
        if (!ledgerPath.exists()) {
            errors += "${relative(ledgerPath)} is missing"
            return errors to jsonObjectOf(
                "packet_count" to jsonNumber(0),
                "passing_count" to jsonNumber(0),
                "errors" to JsonArray(emptyList()),
            )
        }
        val ledger = ledgerPath.readJsonObject()
        if (ledger.requiredInt("schema_version") != 1) {
            errors += "Conformance ledger schema_version must be 1"
        }
        if (ledger["target"] != target) {
            errors += "Conformance ledger target is stale; run " +
                    "refreshOfficialProtocolConformance"
        }
        if (
            ledger["implementation_fingerprint"] !=
            implementationFingerprint()
        ) {
            errors += "Conformance ledger implementation fingerprint is " +
                    "stale; run refreshOfficialProtocolConformance and " +
                    "re-review invalidated entries"
        }
        validateEvidence(ledger["test_evidence"], errors)
        val packetsElement = ledger["packets"]
        val packets = if (packetsElement is JsonArray) {
            packetsElement
        } else {
            errors += "Conformance ledger packets must be an array"
            JsonArray(emptyList())
        }
        val byKey = linkedMapOf<String, MutableList<JsonObject>>()
        packets.forEachIndexed { index, element ->
            val entry = element as? JsonObject
            if (entry == null) {
                errors += "Ledger packet entry $index is not an object"
                return@forEachIndexed
            }
            val key = entry.optionalString("key")
            if (key == null) {
                errors += "Ledger packet entry $index has no key"
            } else {
                byKey.getOrPut(key) { mutableListOf() } += entry
            }
        }
        byKey.forEach { (key, entries) ->
            if (entries.size > 1) {
                errors += "Duplicate conformance entry $key"
            }
        }
        var passingCount = 0
        officialPackets.keys.sortedWith(packetKeyComparator)
            .forEach { key ->
                val official = officialPackets.getValue(key)
                val local = localPackets[key]
                if (local == null) {
                    errors += "${key.conformanceText()}: no local packet model"
                    return@forEach
                }
                val expected = buildCurrentEntry(local, official)
                val entries = byKey[key.conformanceText()].orEmpty()
                if (entries.isEmpty()) {
                    errors += "${key.conformanceText()}: missing conformance entry"
                    return@forEach
                }
                val entry = entries.first()
                listOf(
                    "key",
                    "state",
                    "direction",
                    "id",
                    "kotlin",
                    "official",
                ).forEach { section ->
                    if (entry[section] != expected[section]) {
                        errors += "${key.conformanceText()}: stale or incorrect " +
                                "$section fingerprint"
                    }
                }
                validateOfficialSource(key, official, target, errors)
                var packetPasses = validateReviews(key, entry, errors)
                packetPasses = validateEvidenceReferences(
                    key,
                    entry,
                    ledger,
                    errors,
                ) && packetPasses
                val notes = entry["notes"]
                if (
                    notes !is JsonArray ||
                    notes.any {
                        it !is JsonPrimitive || !it.isString
                    }
                ) {
                    errors += "${key.conformanceText()}: notes must be an array " +
                            "of strings"
                    packetPasses = false
                }
                if (packetPasses) passingCount++
            }
        val expectedKeys = officialPackets.keys
            .map { it.conformanceText() }
            .toSet()
        (byKey.keys - expectedKeys).sorted().forEach {
            errors += "Unexpected conformance entry $it"
        }
        return errors to jsonObjectOf(
            "target" to target,
            "expected_packet_count" to
                    jsonNumber(officialPackets.size),
            "ledger_packet_count" to jsonNumber(packets.size),
            "passing_packet_count" to jsonNumber(passingCount),
            "errors" to JsonArray(errors.map(::jsonString)),
        )
    }

    private fun validateEvidence(
        actual: JsonElement?,
        errors: MutableList<String>,
    ) {
        val expected = currentEvidence()
        val actualObject = actual as? JsonObject
        if (actualObject == null) {
            errors += "Ledger test_evidence must be an object"
            return
        }
        if (actualObject != expected) {
            errors += "Ledger test evidence is stale; run " +
                    "refreshOfficialProtocolConformance and re-review " +
                    "invalidated entries"
        }
        val registry = actualObject[REGISTRY_EVIDENCE_ID]
                as? JsonObject
        val path = registry?.optionalString("path")
        val symbol = registry?.optionalString("symbol")
        if (path != null && symbol != null) {
            val source = repository.safeResolve(path)
            if (
                !source.exists() ||
                symbol !in source.readText()
            ) {
                errors += "$REGISTRY_EVIDENCE_ID does not identify an " +
                        "existing test"
            }
        }
    }

    private fun validateOfficialSource(
        key: PacketKey,
        official: ConformanceOfficialPacket,
        target: JsonObject,
        errors: MutableList<String>,
    ) {
        val source = repository.resolve(
            "build/protocol-reference/mojang/" +
                    "${target.requiredString("minecraft_version")}/" +
                    "decompiled",
        ).safeResolve(official.sourcePath)
        if (!source.exists()) {
            errors += "${key.conformanceText()}: indexed official source is " +
                    "missing: ${official.sourcePath}"
        } else if (source.sha256() != official.sourceSha256) {
            errors += "${key.conformanceText()}: official source hash no longer " +
                    "matches the index"
        }
    }

    private fun validateReviews(
        key: PacketKey,
        entry: JsonObject,
        errors: MutableList<String>,
    ): Boolean {
        val reviews = entry["reviews"] as? JsonObject
        if (reviews == null) {
            errors += "${key.conformanceText()}: reviews must be an object"
            return false
        }
        var passes = true
        val missing = requiredReviews.toSet() - reviews.keys
        val extra = reviews.keys - requiredReviews.toSet()
        if (missing.isNotEmpty()) {
            errors += "${key.conformanceText()}: missing review verdicts: " +
                    missing.sorted().joinToString()
            passes = false
        }
        if (extra.isNotEmpty()) {
            errors += "${key.conformanceText()}: unknown review verdicts: " +
                    extra.sorted().joinToString()
            passes = false
        }
        requiredReviews.forEach { category ->
            val review = reviews[category] as? JsonObject
                ?: return@forEach
            if (review.optionalString("status") != "pass") {
                errors += "${key.conformanceText()}: $category verdict is not passing"
                passes = false
            }
            if (review.optionalString("basis").isNullOrBlank()) {
                errors +=
                    "${key.conformanceText()}: $category has no review basis"
                passes = false
            }
        }
        return passes
    }

    private fun validateEvidenceReferences(
        key: PacketKey,
        entry: JsonObject,
        ledger: JsonObject,
        errors: MutableList<String>,
    ): Boolean {
        val references = entry["test_evidence"] as? JsonArray
        if (references == null) {
            errors +=
                "${key.conformanceText()}: test_evidence must be an array"
            return false
        }
        var passes = true
        val referenceNames = references.mapNotNull {
            (it as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
        }
        if (referenceNames.size != references.size) {
            errors += "${key.conformanceText()}: test_evidence entries must be strings"
            passes = false
        }
        if (referenceNames.toSet().size != referenceNames.size) {
            errors +=
                "${key.conformanceText()}: duplicate test evidence reference"
            passes = false
        }
        requiredEvidence.forEach { required ->
            if (required !in referenceNames) {
                errors += "${key.conformanceText()}: missing required test " +
                        "evidence $required"
                passes = false
            }
        }
        val catalog = ledger["test_evidence"] as? JsonObject
            ?: JsonObject(emptyMap())
        referenceNames.forEach { reference ->
            if (reference !in catalog) {
                errors += "${key.conformanceText()}: unknown test evidence " +
                        "'$reference'"
                passes = false
            }
        }
        return passes
    }

    private fun buildCurrentEntry(
        local: ConformanceLocalPacket,
        official: ConformanceOfficialPacket,
    ): JsonObject = jsonObjectOf(
        "key" to jsonString(local.key.conformanceText()),
        "state" to jsonString(local.key.state),
        "direction" to jsonString(local.key.direction),
        "id" to jsonNumber(local.key.packetId),
        "kotlin" to jsonObjectOf(
            "class" to jsonString(local.className),
            "source" to jsonString(relative(local.source)),
            "source_sha256" to jsonString(local.source.sha256()),
        ),
        "official" to jsonObjectOf(
            "name" to jsonString(official.officialName),
            "class" to jsonString(official.className),
            "source" to jsonString(official.sourcePath),
            "source_sha256" to jsonString(official.sourceSha256),
        ),
        "reviews" to pendingReviews(),
        "test_evidence" to JsonArray(
            requiredEvidence.map(::jsonString),
        ),
        "notes" to JsonArray(emptyList()),
    )

    private fun pendingReviews(): JsonObject = JsonObject(
        requiredReviews.associateWith {
            jsonObjectOf(
                "status" to jsonString("pending"),
                "basis" to jsonString(""),
            )
        },
    )

    private fun entryFingerprint(entry: JsonObject): List<JsonElement?> {
        val kotlin = entry["kotlin"] as? JsonObject
            ?: JsonObject(emptyMap())
        val official = entry["official"] as? JsonObject
            ?: JsonObject(emptyMap())
        return listOf(
            entry["key"],
            kotlin["class"],
            kotlin["source"],
            kotlin["source_sha256"],
            official["name"],
            official["class"],
            official["source"],
            official["source_sha256"],
            entry["test_evidence"],
        )
    }

    private fun relative(path: Path): String =
        repository.relativize(path.toAbsolutePath().normalize())
            .toString()
            .replace('\\', '/')

    private fun PacketKey.conformanceText(): String =
        "$state/$direction/0x${
            packetId.toString(16).uppercase().padStart(2, '0')
        }"

    private data class ConformanceLocalPacket(
        val key: PacketKey,
        val className: String,
        val officialName: String,
        val source: Path,
    )

    private data class ConformanceOfficialPacket(
        val key: PacketKey,
        val officialName: String,
        val className: String,
        val sourcePath: String,
        val sourceSha256: String,
    )

    private companion object {
        const val REGISTRY_EVIDENCE_ID =
            "registry-wide-binary-round-trip"
        const val SUITE_EVIDENCE_ID = "minecraft-format-test-suite"
        const val OFFICIAL_CODEC_EVIDENCE_ID =
            "exact-official-codec-oracle"
        val requiredReviews = listOf(
            "field_order",
            "presence_conditions",
            "wire_encoding",
            "limits",
        )
        val requiredEvidence = listOf(
            REGISTRY_EVIDENCE_ID,
            SUITE_EVIDENCE_ID,
            OFFICIAL_CODEC_EVIDENCE_ID,
        )
        val packetKeyComparator = compareBy<PacketKey>(
            { it.state },
            { it.direction },
            { it.packetId },
        )
    }
}
