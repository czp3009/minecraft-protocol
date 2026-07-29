package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

abstract class CompareWikiWithOfficialTask :
    MinecraftProtocolToolTask() {
    @TaskAction
    fun compare() {
        val references = repository.resolve("protocol-specification")
        val snapshot = references.resolve("wiki-protocol-snapshot.json")
            .readJsonObject()
        val version = snapshot.requiredString("minecraft_version")
        val protocolVersion = snapshot.requiredInt("protocol_version")
        val wikiRevision = snapshot.requiredObject("source")
            .requiredInt("revision_id")
        val reportPath = repository.resolve(
            "build/protocol-reference/mojang/$version/generated/" +
                    "reports/packets.json",
        )
        val downloadMetadataPath = repository.resolve(
            "build/protocol-reference/mojang/$version/" +
                    "download-metadata.json",
        )
        val report = reportPath.readJsonObject()
        val downloadMetadata = downloadMetadataPath.readJsonObject()

        val problems = mutableListOf<String>()
        val officialByKey = linkedMapOf<OfficialKey, String>()
        report.forEach { (state, directionsElement) ->
            directionsElement.jsonObject.forEach { (direction, packetsElement) ->
                packetsElement.jsonObject.forEach { (officialName, metadataElement) ->
                    val key = OfficialKey(
                        state,
                        direction,
                        metadataElement.jsonObject.requiredInt(
                            "protocol_id",
                        ),
                    )
                    if (officialByKey.put(key, officialName) != null) {
                        problems += "duplicate vanilla report key $key"
                    }
                }
            }
        }

        val stateToOfficial = mapOf(
            "HANDSHAKE" to "handshake",
            "STATUS" to "status",
            "LOGIN" to "login",
            "CONFIGURATION" to "configuration",
            "PLAY" to "play",
        )
        val wikiByKey = linkedMapOf<OfficialKey, String>()
        readCsv(references.resolve("packet-inventory.csv")).use { parser ->
            parser.forEach { row ->
                if (row["framing"] != "normal") return@forEach
                val key = OfficialKey(
                    stateToOfficial.getValue(row["state"]),
                    row["direction"].lowercase(),
                    parseInteger(row["id"]),
                )
                val rawName = row["official_name"]
                val name = if (':' in rawName) {
                    rawName
                } else {
                    "minecraft:$rawName"
                }
                if (wikiByKey.put(key, name) != null) {
                    problems += "duplicate Wiki inventory key $key"
                }
            }
        }

        val normalizedDifferences = mutableListOf<JsonObjectEntry>()
        wikiByKey.entries.sortedWith(officialEntryComparator)
            .forEach { (key, wikiName) ->
                val officialName = officialByKey[key]
                if (officialName == null) {
                    problems += "Wiki-only packet $key: $wikiName"
                } else if (officialName != wikiName) {
                    if (
                        normalizeOfficialName(officialName) !=
                        normalizeOfficialName(wikiName)
                    ) {
                        problems += "packet name mismatch $key: " +
                                "Wiki=$wikiName, vanilla=$officialName"
                    } else {
                        normalizedDifferences += JsonObjectEntry(
                            key,
                            wikiName,
                            officialName,
                        )
                    }
                }
            }
        officialByKey.entries.sortedWith(officialEntryComparator)
            .forEach { (key, officialName) ->
                if (key !in wikiByKey) {
                    problems += "vanilla-only packet $key: $officialName"
                }
            }
        check(problems.isEmpty()) {
            buildString {
                appendLine(
                    "Wiki/vanilla packet comparison found " +
                            "${problems.size} issue(s):",
                )
                problems.forEach { appendLine("- $it") }
            }
        }
        val result = jsonObjectOf(
            "schema_version" to jsonNumber(1),
            "minecraft_version" to jsonString(version),
            "protocol_version" to jsonNumber(protocolVersion),
            "wiki_revision_id" to jsonNumber(wikiRevision),
            "packet_count" to jsonNumber(wikiByKey.size),
            "result" to
                    jsonString("exact-id-and-normalized-name-match"),
            "normalized_name_differences" to JsonArray(
                normalizedDifferences.map { it.toJson() },
            ),
            "vanilla" to jsonObjectOf(
                "server_sha1" to jsonString(
                    downloadMetadata.requiredString("server_sha1"),
                ),
                "packets_report_sha256" to
                        jsonString(reportPath.sha256()),
            ),
        )
        val output = references.resolve("official-packet-audit.json")
        val content = renderJson(result, sortKeys = true) + "\n"
        val changed = output.writeIfChanged(content)
        logger.lifecycle(
            "${if (changed) "updated" else "unchanged"}: $output",
        )
        logger.lifecycle(
            "Wiki and vanilla packet registries match by ID and normalized " +
                    "name for $version / protocol $protocolVersion: " +
                    "${wikiByKey.size} normal packets, " +
                    "${normalizedDifferences.size} spelling difference(s).",
        )
    }

    private fun normalizeOfficialName(value: String): String =
        value.removePrefix("minecraft:").replace("/", "_")

    private data class OfficialKey(
        val state: String,
        val direction: String,
        val packetId: Int,
    ) {
        override fun toString(): String =
            "($state, $direction, $packetId)"
    }

    private data class JsonObjectEntry(
        val key: OfficialKey,
        val wiki: String,
        val vanilla: String,
    ) {
        fun toJson() = jsonObjectOf(
            "state" to jsonString(key.state),
            "direction" to jsonString(key.direction),
            "id" to jsonNumber(key.packetId),
            "wiki" to jsonString(wiki),
            "vanilla" to jsonString(vanilla),
        )
    }

    private companion object {
        val officialEntryComparator =
            compareBy<Map.Entry<OfficialKey, String>>(
                { it.key.state },
                { it.key.direction },
                { it.key.packetId },
            )
    }
}

abstract class IndexOfficialMinecraftSourcesTask :
    MinecraftProtocolToolTask() {
    @get:Input
    abstract val decompilerVersion: Property<String>

    @TaskAction
    fun index() {
        val references = repository.resolve("protocol-specification")
        val snapshot = references.resolve("wiki-protocol-snapshot.json")
            .readJsonObject()
        val officialAudit = references.resolve(
            "official-packet-audit.json",
        ).readJsonObject()
        val version = snapshot.requiredString("minecraft_version")
        val protocolVersion = snapshot.requiredInt("protocol_version")
        val revisionId = snapshot.requiredObject("source")
            .requiredInt("revision_id")
        val serverSha1 = officialAudit.requiredObject("vanilla")
            .requiredString("server_sha1")
        check(
            officialAudit.requiredString("minecraft_version") == version &&
                    officialAudit.requiredInt("protocol_version") ==
                    protocolVersion &&
                    officialAudit.requiredInt("wiki_revision_id") == revisionId,
        ) {
            "official-packet-audit.json is stale for the Wiki snapshot"
        }

        val sourceRoot = repository.resolve(
            "build/protocol-reference/mojang/$version/decompiled",
        )
        val typeRoot = sourceRoot.resolve(
            "net/minecraft/network/protocol",
        )
        val typeFiles = Files.walk(typeRoot).use { paths ->
            paths.filter {
                Files.isRegularFile(it) &&
                        it.fileName.toString().endsWith("PacketTypes.java")
            }.sorted().toList()
        }
        check(typeFiles.size >= 5) {
            "Decompiled vanilla PacketTypes sources are missing; run " +
                    "decompileOfficialMinecraftServer first"
        }
        val packetTypes = linkedMapOf<Pair<String, String>, PacketType>()
        typeFiles.forEach { typeFile ->
            val text = typeFile.readText()
            val packageName = packagePattern.find(text)
                ?.groups
                ?.get("package")
                ?.value
                ?: error("No package declaration in $typeFile")
            packetTypePattern.findAll(text).forEach { match ->
                val direction = match.groups["direction"]!!.value
                    .uppercase()
                val officialName = match.groups["name"]!!.value
                val relativeClass = match.groups["class"]!!.value.trim()
                val outerClass = relativeClass.substringBefore('.')
                val sourcePath = packageName.replace('.', '/') +
                        "/$outerClass.java"
                val absoluteSource = sourceRoot.safeResolve(sourcePath)
                check(absoluteSource.exists()) {
                    "Vanilla packet source is missing: $absoluteSource"
                }
                val key = direction to officialName
                check(key !in packetTypes) {
                    "Duplicate vanilla PacketType $key"
                }
                packetTypes[key] = PacketType(
                    officialClass = "$packageName.$relativeClass",
                    sourcePath = sourcePath,
                    sourceSha256 = absoluteSource.sha256(),
                )
            }
        }

        val corrections = officialAudit["normalized_name_differences"]
            ?.jsonArray
            ?.associate { element ->
                val row = element.jsonObject
                PacketKey(
                    row.requiredString("state").uppercase(),
                    row.requiredString("direction").uppercase(),
                    row.requiredInt("id"),
                ) to row.requiredString("vanilla")
                    .removePrefix("minecraft:")
            }
            .orEmpty()
        val rows = mutableListOf<IndexedPacket>()
        readCsv(references.resolve("packet-inventory.csv")).use { parser ->
            parser.forEach { row ->
                if (row["framing"] != "normal") return@forEach
                val packetId = parseInteger(row["id"])
                val key = PacketKey(
                    row["state"],
                    row["direction"],
                    packetId,
                )
                val officialName = corrections[key] ?: row["official_name"]
                val packetType = packetTypes[
                    row["direction"] to officialName
                ] ?: error(
                    "No decompiled PacketType for " +
                            "${row["state"]}/${row["direction"]}/" +
                            "${row["id"]} $officialName",
                )
                rows += IndexedPacket(
                    state = row["state"],
                    direction = row["direction"],
                    id = "0x${
                        packetId.toString(16).uppercase().padStart(2, '0')
                    }",
                    officialName = officialName,
                    officialClass = packetType.officialClass,
                    sourcePath = packetType.sourcePath,
                    sourceSha256 = packetType.sourceSha256,
                )
            }
        }
        val expectedCount = snapshot.requiredInt("normal_packet_count")
        check(rows.size == expectedCount) {
            "Indexed ${rows.size} packet rows, expected $expectedCount"
        }
        val csv = StringBuilder()
        CSVPrinter(
            csv,
            CSVFormat.DEFAULT.builder()
                .setHeader(
                    "state",
                    "direction",
                    "id",
                    "official_name",
                    "official_class",
                    "source_path",
                    "source_sha256",
                )
                .setRecordSeparator("\n")
                .get(),
        ).use { printer ->
            rows.forEach { row ->
                printer.printRecord(
                    row.state,
                    row.direction,
                    row.id,
                    row.officialName,
                    row.officialClass,
                    row.sourcePath,
                    row.sourceSha256,
                )
            }
        }
        writeWithStatus(
            references.resolve("official-packet-classes.csv"),
            csv.toString(),
        )
        val packetSourceCount = rows.map { it.sourcePath }.toSet().size
        val metadata = jsonObjectOf(
            "schema_version" to jsonNumber(1),
            "minecraft_version" to jsonString(version),
            "protocol_version" to jsonNumber(protocolVersion),
            "wiki_revision_id" to jsonNumber(revisionId),
            "server_sha1" to jsonString(serverSha1),
            "decompiler" to jsonObjectOf(
                "name" to jsonString("Vineflower"),
                "version" to jsonString(decompilerVersion.get()),
            ),
            "packet_count" to jsonNumber(rows.size),
            "packet_source_count" to jsonNumber(packetSourceCount),
        )
        writeWithStatus(
            references.resolve("official-source-index.json"),
            renderJson(metadata, sortKeys = true) + "\n",
        )
        logger.lifecycle(
            "Indexed ${rows.size} vanilla packet registrations across " +
                    "$packetSourceCount source files.",
        )
    }

    private fun writeWithStatus(path: Path, content: String) {
        logger.lifecycle(
            "${if (path.writeIfChanged(content)) "updated" else "unchanged"}" +
                    ": $path",
        )
    }

    private data class PacketType(
        val officialClass: String,
        val sourcePath: String,
        val sourceSha256: String,
    )

    private data class IndexedPacket(
        val state: String,
        val direction: String,
        val id: String,
        val officialName: String,
        val officialClass: String,
        val sourcePath: String,
        val sourceSha256: String,
    )

    private companion object {
        val packetTypePattern = Regex(
            """public\s+static\s+final\s+PacketType<(?<class>.*?)>\s+""" +
                    """(?<constant>[A-Z0-9_]+)\s*=\s*""" +
                    """create(?<direction>Clientbound|Serverbound)\(\s*""" +
                    """"(?<name>[^"]+)"\s*\);""",
            RegexOption.DOT_MATCHES_ALL,
        )
        val packagePattern = Regex(
            """^package\s+(?<package>[\w.]+);""",
            RegexOption.MULTILINE,
        )
    }
}
