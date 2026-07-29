package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.io.path.readText

private const val WIKI_TITLE = "Java Edition protocol/Packets"
private const val WIKI_PAGE_URL =
    "https://minecraft.wiki/w/Java_Edition_protocol/Packets"
private const val WIKI_API_URL = "https://minecraft.wiki/api.php"

abstract class RefreshProtocolSpecificationTask :
    MinecraftProtocolToolTask() {
    @get:Input
    abstract val checkOnly: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val target: Property<String>

    init {
        checkOnly.convention(false)
    }

    @TaskAction
    fun refresh() {
        val requestedTarget = target.orNull
        val wiki = fetchWiki(requestedTarget)
        val outputs = buildOutputs(wiki, requestedTarget)
        val sourceCache = repository.resolve(
            "build/protocol-reference/wiki/${wiki.revisionId}/" +
                    "packets.wikitext",
        )
        compareOrWrite(sourceCache, wiki.source, false)
        val references = repository.resolve("protocol-specification")
        val results = listOf(
            compareOrWrite(
                references.resolve("wiki-protocol-snapshot.json"),
                outputs.metadataText,
                checkOnly.get(),
            ),
            compareOrWrite(
                references.resolve("packet-inventory.csv"),
                outputs.inventoryText,
                checkOnly.get(),
            ),
        )
        logger.lifecycle(
            "Wiki target: Minecraft ${outputs.minecraftVersion}, " +
                    "protocol ${outputs.protocolVersion}, revision " +
                    "${wiki.revisionId}, ${outputs.packetCount} packet forms.",
        )
        outputs.warnings.forEach {
            logger.warn("WARNING: $it")
        }
        check(results.all { it }) {
            "Checked-in protocol specification is stale"
        }
    }

    private fun fetchWiki(target: String?): WikiRevision {
        val current = fetchCurrentWiki()
        if (target == null || targetMatches(current.source, target)) {
            return current
        }
        return fetchHistoricalWiki(target)
    }

    private fun fetchCurrentWiki(): WikiRevision {
        val result = ProtocolHttp.getJson(
            WIKI_API_URL,
            linkedMapOf(
                "action" to "query",
                "prop" to "revisions",
                "rvprop" to "ids|timestamp|content",
                "rvslots" to "main",
                "titles" to WIKI_TITLE,
                "format" to "json",
                "formatversion" to 2,
            ),
        )
        return parseWikiRevision(result, "current Wiki page")
    }

    private fun fetchHistoricalWiki(target: String): WikiRevision {
        val start: OffsetDateTime?
        val end: OffsetDateTime?
        val maximumRevisions: Int
        if (target.startsWith("protocol:")) {
            val protocol = target.removePrefix("protocol:")
            require(protocol.all(Char::isDigit) && protocol.isNotEmpty()) {
                "Protocol target must use protocol:<decimal-id>"
            }
            start = null
            end = null
            maximumRevisions = 5_000
        } else {
            require(target.matches(Regex("[0-9A-Za-z._-]+"))) {
                "Unsafe Minecraft target: $target"
            }
            val releaseTime = mojangReleaseTime(target)
            start = releaseTime.plusDays(180)
            end = releaseTime.minusDays(180)
            maximumRevisions = 2_000
        }
        var continuation: String? = null
        var inspected = 0
        while (inspected < maximumRevisions) {
            val parameters = linkedMapOf<String, Any>(
                "action" to "query",
                "prop" to "revisions",
                "rvprop" to "ids|timestamp|content",
                "rvslots" to "main",
                "rvlimit" to 20,
                "rvdir" to "older",
                "titles" to WIKI_TITLE,
                "format" to "json",
                "formatversion" to 2,
            )
            if (continuation != null) {
                parameters["rvcontinue"] = continuation
            } else if (start != null && end != null) {
                parameters["rvstart"] = apiTimestamp(start)
                parameters["rvend"] = apiTimestamp(end)
            }
            val result = ProtocolHttp.getJson(
                WIKI_API_URL,
                parameters,
            )
            val revisions = result.requiredObject("query")
                .requiredArray("pages")[0]
                .jsonObject
                .requiredArray("revisions")
            revisions.forEach { element ->
                inspected++
                val revision = parseRevisionElement(element.jsonObject)
                if (targetMatches(revision.source, target)) {
                    return revision
                }
            }
            continuation = result["continue"]
                ?.jsonObject
                ?.optionalString("rvcontinue")
            if (continuation == null) break
        }
        error(
            "No revision of '$WIKI_TITLE' declares target '$target'; " +
                    "inspected $inspected revisions",
        )
    }

    private fun parseWikiRevision(
        result: JsonObject,
        context: String,
    ): WikiRevision {
        val page = result.requiredObject("query")
            .requiredArray("pages")
            .firstOrNull()
            ?.jsonObject
            ?: error("Minecraft Wiki $context response has no page")
        val revision = page.requiredArray("revisions")
            .firstOrNull()
            ?.jsonObject
            ?: error("Minecraft Wiki $context response has no revision")
        return parseRevisionElement(revision)
    }

    private fun parseRevisionElement(revision: JsonObject): WikiRevision {
        val source = revision.requiredObject("slots")
            .requiredObject("main")
            .requiredString("content")
        check(source.length >= 50_000) {
            "Minecraft Wiki packet source is unexpectedly small"
        }
        return WikiRevision(
            source = source,
            revisionId = revision.requiredInt("revid"),
            timestamp = revision.requiredString("timestamp"),
        )
    }

    private fun mojangReleaseTime(version: String): OffsetDateTime {
        val manifest = ProtocolHttp.getJson(VERSION_MANIFEST_URL)
        val entry = manifest.requiredArray("versions")
            .map { it.jsonObject }
            .firstOrNull {
                it.requiredString("id") == version &&
                        it.requiredString("type") == "release"
            }
            ?: error("'$version' is not a Mojang release version")
        return OffsetDateTime.parse(entry.requiredString("releaseTime"))
            .withOffsetSameInstant(ZoneOffset.UTC)
    }

    private fun targetMatches(source: String, target: String): Boolean =
        runCatching {
            val declared = parseDeclaredVersion(source)
            if (target.startsWith("protocol:")) {
                declared.protocolVersion.toString() ==
                        target.removePrefix("protocol:")
            } else {
                declared.minecraftVersion == target
            }
        }.getOrDefault(false)

    private fun buildOutputs(
        wiki: WikiRevision,
        requestedTarget: String?,
    ): SpecificationOutputs {
        val declared = parseDeclaredVersion(wiki.source)
        val packets = parsePacketInventory(wiki.source)
        val packetBlock = packetListBlock(wiki.source)
        val listNoteProtocol = listNotePattern.find(packetBlock)
            ?.groups
            ?.get("protocolVersion")
            ?.value
            ?.toInt()
        val releaseInfo = fetchMojangReleaseInfo(
            declared.minecraftVersion,
        )
        val warnings = mutableListOf<String>()
        if (listNoteProtocol == null) {
            warnings +=
                "Wiki packet-list note does not state its protocol version"
        } else if (listNoteProtocol != declared.protocolVersion) {
            warnings += "Wiki page is internally inconsistent: the " +
                    "introduction declares protocol " +
                    "${declared.protocolVersion}, while the packet-list note " +
                    "says $listNoteProtocol"
        }
        if (releaseInfo.latestRelease != declared.minecraftVersion) {
            warnings += "Wiki documents Minecraft " +
                    "${declared.minecraftVersion}, while Mojang's latest " +
                    "release is ${releaseInfo.latestRelease}"
        }
        val metadata = jsonObjectOf(
            "schema_version" to jsonNumber(1),
            "minecraft_version" to
                    jsonString(declared.minecraftVersion),
            "protocol_version" to
                    jsonNumber(declared.protocolVersion),
            "packet_count" to jsonNumber(packets.size),
            "normal_packet_count" to
                    jsonNumber(packets.count { it.framing == "normal" }),
            "selection" to jsonObjectOf(
                "mode" to jsonString(
                    if (requestedTarget == null) "latest" else "pinned",
                ),
                "requested_target" to (
                        requestedTarget?.let(::jsonString) ?: JsonNull
                        ),
            ),
            "source" to jsonObjectOf(
                "title" to jsonString(WIKI_TITLE),
                "url" to jsonString(WIKI_PAGE_URL),
                "revision_id" to jsonNumber(wiki.revisionId),
                "revision_timestamp" to jsonString(wiki.timestamp),
                "sha256" to jsonString(
                    wiki.source.toByteArray(StandardCharsets.UTF_8)
                        .sha256(),
                ),
            ),
            "cross_checks" to jsonObjectOf(
                "java_major_version" to
                        jsonNumber(releaseInfo.javaMajorVersion),
                "packet_list_note_protocol" to (
                        listNoteProtocol?.let(::jsonNumber) ?: JsonNull
                        ),
                "mojang_latest_release" to
                        jsonString(releaseInfo.latestRelease),
                "status" to jsonString(
                    if (warnings.isEmpty()) "consistent" else "warning",
                ),
                "warnings" to JsonArray(warnings.map(::jsonString)),
            ),
        )
        val csv = StringBuilder()
        CSVPrinter(
            csv,
            CSVFormat.DEFAULT.builder()
                .setHeader(
                    "state",
                    "direction",
                    "id",
                    "wiki_name",
                    "official_name",
                    "framing",
                )
                .setRecordSeparator("\n")
                .get(),
        ).use { printer ->
            packets.sortedWith(
                compareBy(
                    { it.state },
                    { it.direction },
                    { it.packetId },
                    { it.framing },
                ),
            ).forEach {
                printer.printRecord(
                    it.state,
                    it.direction,
                    "0x${
                        it.packetId.toString(16).uppercase().padStart(2, '0')
                    }",
                    it.wikiName,
                    it.officialName,
                    it.framing,
                )
            }
        }
        return SpecificationOutputs(
            metadataText = renderJson(metadata, sortKeys = true) + "\n",
            inventoryText = csv.toString(),
            minecraftVersion = declared.minecraftVersion,
            protocolVersion = declared.protocolVersion,
            packetCount = packets.size,
            warnings = warnings,
        )
    }

    private fun parseDeclaredVersion(source: String): DeclaredVersion {
        val match = versionPattern.find(source)
            ?: error(
                "Could not find the Wiki's declared " +
                        "Minecraft/protocol version",
            )
        return DeclaredVersion(
            minecraftVersion = match.groups["minecraftVersion"]!!
                .value
                .trim(),
            protocolVersion = match.groups["protocolVersion"]!!
                .value
                .toInt(),
        )
    }

    private fun packetListBlock(source: String): String {
        val startMarker = "== List of packets =="
        val start = source.indexOf(startMarker)
        check(start >= 0) {
            "Could not find the Wiki packet-list heading"
        }
        val remainder = source.substring(start)
        val end = Regex(
            """^== Handshaking ==\s*$""",
            RegexOption.MULTILINE,
        ).find(remainder)
            ?: error("Could not find the end of the Wiki packet list")
        return remainder.substring(0, end.range.first)
    }

    private fun parsePacketInventory(source: String): List<WikiPacket> {
        val block = packetListBlock(source)
        val packets = mutableListOf<WikiPacket>()
        val seenTables = mutableSetOf<Pair<String, String>>()
        var currentTable: Pair<String, String>? = null
        var nextId = 0
        block.lines().forEachIndexed { index, line ->
            val begin = beginPattern.matchEntire(line.trim())
            if (begin != null) {
                val wikiState = begin.groups["state"]!!.value.trim()
                val state = stateNames[wikiState]
                    ?: error("Unknown packet-list state '$wikiState'")
                val table = state to
                        begin.groups["direction"]!!.value.uppercase()
                check(seenTables.add(table)) {
                    "Duplicate Wiki packet table $table"
                }
                currentTable = table
                nextId = 0
                return@forEachIndexed
            }
            val packet = packetPattern.matchEntire(line.trim())
                ?: return@forEachIndexed
            val table = currentTable
                ?: error(
                    "Packet row appears before a packet-list header at " +
                            "list line ${index + 1}",
                )
            val options = packet.groups["options"]!!.value
            check(
                !Regex(
                    """\bchange\s*=""",
                    RegexOption.IGNORE_CASE,
                ).containsMatchIn(options),
            ) {
                "Stable Wiki packet list contains development-version " +
                        "change markers"
            }
            packets += WikiPacket(
                state = table.first,
                direction = table.second,
                packetId = nextId++,
                wikiName = packet.groups["wikiName"]!!.value.trim(),
                officialName = packet.groups["officialName"]!!
                    .value
                    .trim(),
            )
        }
        val missingTables = expectedTables - seenTables
        val extraTables = seenTables - expectedTables
        check(missingTables.isEmpty() && extraTables.isEmpty()) {
            "Unexpected Wiki packet tables; missing=$missingTables, " +
                    "extra=$extraTables"
        }
        check(packets.size >= 200) {
            "Only ${packets.size} normal packets were parsed"
        }
        val legacyStart = source.indexOf(
            "==== Legacy Server List Ping ====",
        )
        val legacyEnd = source.indexOf("== Status ==", legacyStart)
        check(legacyStart >= 0 && legacyEnd > legacyStart) {
            "Legacy server-list ping section was not found"
        }
        val legacy = source.substring(legacyStart, legacyEnd)
        check(
            Regex("""\|\s*0xFE\s*(?:\n|\r\n)""")
                .containsMatchIn(legacy),
        ) {
            "Legacy server-list ping ID 0xFE was not found"
        }
        packets += WikiPacket(
            state = "HANDSHAKE",
            direction = "SERVERBOUND",
            packetId = 0xFE,
            wikiName = "Legacy Server List Ping",
            officialName = "legacy_server_list_ping",
            framing = "legacy-unframed",
        )
        check(
            packets.map { Triple(it.state, it.direction, it.packetId) }
                .toSet()
                .size == packets.size,
        ) {
            "Parsed Wiki packet inventory contains duplicate IDs"
        }
        return packets
    }

    private fun fetchMojangReleaseInfo(
        version: String,
    ): ReleaseInfo {
        val manifest = ProtocolHttp.getJson(VERSION_MANIFEST_URL)
        val latestRelease = manifest.requiredObject("latest")
            .requiredString("release")
        val entry = manifest.requiredArray("versions")
            .map { it.jsonObject }
            .firstOrNull {
                it.requiredString("id") == version &&
                        it.requiredString("type") == "release"
            }
            ?: error("Mojang has no stable release $version")
        val metadata = ProtocolHttp.getJson(entry.requiredString("url"))
        val javaMajor = metadata.requiredObject("javaVersion")
            .requiredInt("majorVersion")
        check(javaMajor > 0) {
            "Mojang metadata for $version has no Java version"
        }
        return ReleaseInfo(latestRelease, javaMajor)
    }

    private fun compareOrWrite(
        path: java.nio.file.Path,
        content: String,
        check: Boolean,
    ): Boolean {
        if (path.toFile().isFile && path.readText() == content) {
            logger.lifecycle("unchanged: $path")
            return true
        }
        if (check) {
            logger.error("stale or missing: $path")
            return false
        }
        path.atomicWriteText(content)
        logger.lifecycle("updated: $path")
        return true
    }

    private fun apiTimestamp(value: OffsetDateTime): String =
        value.withOffsetSameInstant(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            .replace("+00:00", "Z")

    private data class WikiRevision(
        val source: String,
        val revisionId: Int,
        val timestamp: String,
    )

    private data class DeclaredVersion(
        val minecraftVersion: String,
        val protocolVersion: Int,
    )

    private data class WikiPacket(
        val state: String,
        val direction: String,
        val packetId: Int,
        val wikiName: String,
        val officialName: String,
        val framing: String = "normal",
    )

    private data class ReleaseInfo(
        val latestRelease: String,
        val javaMajorVersion: Int,
    )

    private data class SpecificationOutputs(
        val metadataText: String,
        val inventoryText: String,
        val minecraftVersion: String,
        val protocolVersion: Int,
        val packetCount: Int,
        val warnings: List<String>,
    )

    private companion object {
        val expectedTables = setOf(
            "HANDSHAKE" to "SERVERBOUND",
            "STATUS" to "CLIENTBOUND",
            "STATUS" to "SERVERBOUND",
            "LOGIN" to "CLIENTBOUND",
            "LOGIN" to "SERVERBOUND",
            "CONFIGURATION" to "CLIENTBOUND",
            "CONFIGURATION" to "SERVERBOUND",
            "PLAY" to "CLIENTBOUND",
            "PLAY" to "SERVERBOUND",
        )
        val stateNames = mapOf(
            "Handshaking" to "HANDSHAKE",
            "Status" to "STATUS",
            "Login" to "LOGIN",
            "Configuration" to "CONFIGURATION",
            "Play" to "PLAY",
        )
        val beginPattern = Regex(
            """^\{\{packet list/begin\|(?<state>[^|}]+)\|""" +
                    """(?<direction>clientbound|serverbound)\}\}\s*$""",
            RegexOption.IGNORE_CASE,
        )
        val packetPattern = Regex(
            """^\{\{packet list\|(?<wikiName>[^|}]+)\|""" +
                    """(?<officialName>[^|}]+)""" +
                    """(?<options>(?:\|[^}]*)?)\}\}\s*$""",
            RegexOption.IGNORE_CASE,
        )
        val versionPattern = Regex(
            """This article presents.*?""" +
                    """\|(?<minecraftVersion>[^,|\]]+),\s*protocol\s+""" +
                    """(?<protocolVersion>\d+)\]\]""",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL,
            ),
        )
        val listNotePattern = Regex(
            """packet IDs listed here.*?currently\s+""" +
                    """(?<protocolVersion>\d+)""",
            setOf(
                RegexOption.IGNORE_CASE,
                RegexOption.DOT_MATCHES_ALL,
            ),
        )
    }
}

abstract class PrepareWikiProtocolReferencesTask :
    MinecraftProtocolToolTask() {
    @TaskAction
    fun prepare() {
        val snapshot = repository.resolve(
            "protocol-specification/wiki-protocol-snapshot.json",
        ).readJsonObject()
        val sourceMetadata = snapshot.requiredObject("source")
        val packetRevision = sourceMetadata.requiredInt("revision_id")
        val packetTimestamp = sourceMetadata.requiredString(
            "revision_timestamp",
        )
        val packetSourcePath = repository.resolve(
            "build/protocol-reference/wiki/$packetRevision/" +
                    "packets.wikitext",
        )
        val packetSource = packetSourcePath.readText()
        val titles = linkPattern.findAll(packetSource)
            .map { match ->
                match.groupValues[1]
                    .substringBefore('|')
                    .substringBefore('#')
                    .trim()
            }
            .filter(String::isNotEmpty)
            .map { "Java Edition protocol/$it" }
            .toSortedSet()
        val outputDirectory = packetSourcePath.parent.resolve("references")
        val entries = titles.map { requestedTitle ->
            val revision = fetchRevision(
                requestedTitle,
                packetTimestamp,
            )
            val fileName = safeName(requestedTitle) + ".wikitext"
            outputDirectory.resolve(fileName)
                .atomicWriteText(revision.source)
            logger.lifecycle(
                "cached: $requestedTitle @ ${revision.revisionId}",
            )
            jsonObjectOf(
                "requested_title" to jsonString(requestedTitle),
                "canonical_title" to
                        jsonString(revision.canonicalTitle),
                "revision_id" to jsonNumber(revision.revisionId),
                "revision_timestamp" to
                        jsonString(revision.timestamp),
                "sha256" to jsonString(
                    revision.source
                        .toByteArray(StandardCharsets.UTF_8)
                        .sha256(),
                ),
                "path" to jsonString(fileName),
            )
        }
        val index = jsonObjectOf(
            "schema_version" to jsonNumber(1),
            "packet_revision_id" to jsonNumber(packetRevision),
            "packet_revision_timestamp" to
                    jsonString(packetTimestamp),
            "references" to JsonArray(entries),
        )
        outputDirectory.resolve("index.json")
            .writeJson(index, sortKeys = true)
        logger.lifecycle(
            "Cached ${entries.size} linked protocol references.",
        )
    }

    private fun fetchRevision(
        title: String,
        timestamp: String,
    ): ReferenceRevision {
        val result = ProtocolHttp.getJson(
            WIKI_API_URL,
            linkedMapOf(
                "action" to "query",
                "prop" to "revisions",
                "rvprop" to "ids|timestamp|content",
                "rvslots" to "main",
                "rvlimit" to 1,
                "rvdir" to "older",
                "rvstart" to timestamp,
                "titles" to title,
                "redirects" to 1,
                "format" to "json",
                "formatversion" to 2,
            ),
        )
        val page = result.requiredObject("query")
            .requiredArray("pages")[0]
            .jsonObject
        check(page.optionalBoolean("missing") != true) {
            "Minecraft Wiki page is missing: $title"
        }
        val revision = page.requiredArray("revisions")[0].jsonObject
        val source = revision.requiredObject("slots")
            .requiredObject("main")
            .requiredString("content")
        check(source.isNotBlank()) {
            "Empty Wiki source for '$title'"
        }
        return ReferenceRevision(
            canonicalTitle = page.requiredString("title"),
            source = source,
            revisionId = revision.requiredInt("revid"),
            timestamp = revision.requiredString("timestamp"),
        )
    }

    private fun safeName(title: String): String =
        title.removePrefix("Java Edition protocol/")
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

    private data class ReferenceRevision(
        val canonicalTitle: String,
        val source: String,
        val revisionId: Int,
        val timestamp: String,
    )

    private companion object {
        val linkPattern = Regex(
            """\[\[Java Edition protocol/([^\]]+)""",
        )
    }
}
