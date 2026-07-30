package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

internal data class PacketKey(
    val state: String,
    val direction: String,
    val packetId: Int,
) {
    fun display(): String =
        "${state.lowercase()}/${direction.lowercase()}/" +
                "0x${packetId.toString(16).uppercase().padStart(2, '0')}"
}

internal data class ExpectedPacket(
    val key: PacketKey,
    val wikiName: String,
    val officialName: String,
    val framing: String,
)

internal data class LocalPacket(
    val key: PacketKey,
    val className: String,
    val officialName: String?,
    val path: Path,
)

private val declarationPattern = Regex(
    """^[ \t]*(?:public\s+)?(?:data\s+)?(?:class|object)\s+""" +
            """(?<class>[A-Za-z_][A-Za-z0-9_]*)""",
    RegexOption.MULTILINE,
)

private val packetInfoPattern = Regex(
    """@PacketInfo\(\s*""" +
            """(?:id\s*=\s*)?(?<id>0x[0-9A-Fa-f]+|\d+)\s*,\s*""" +
            """(?:state\s*=\s*)?ConnectionState\.(?<state>[A-Z_]+)\s*,\s*""" +
            """(?:direction\s*=\s*)?PacketDirection\.(?<direction>[A-Z_]+)\s*""" +
            """(?:,\s*(?:officialName\s*=\s*)?"(?<officialName>[^"]*)"\s*)?""" +
            """,?\s*\)""",
    setOf(RegexOption.DOT_MATCHES_ALL),
)

internal fun parseInteger(value: String): Int =
    if (value.startsWith("0x", ignoreCase = true)) {
        value.substring(2).toInt(16)
    } else {
        value.toInt()
    }

internal fun readCsv(
    path: Path,
    delimiter: Char = ',',
): CSVParser {
    val format = CSVFormat.DEFAULT.builder()
        .setDelimiter(delimiter)
        .setHeader()
        .setSkipHeaderRecord(true)
        .get()
    return CSVParser.parse(path, StandardCharsets.UTF_8, format)
}

internal fun loadPacketManifest(
    specificationDirectory: Path,
): List<ExpectedPacket> {
    val inventoryPath = specificationDirectory.resolve(
        "packet-inventory.csv",
    )
    check(inventoryPath.exists()) {
        "$inventoryPath is missing; run refreshProtocolSpecification first"
    }
    val rows = readCsv(inventoryPath).use { parser ->
        parser.map { row ->
            ExpectedPacket(
                key = PacketKey(
                    row["state"].uppercase(),
                    row["direction"].uppercase(),
                    parseInteger(row["id"]),
                ),
                wikiName = row["wiki_name"],
                officialName = row["official_name"],
                framing = row["framing"],
            )
        }
    }
    val auditPath = specificationDirectory.resolve(
        "official-packet-audit.json",
    )
    check(auditPath.exists()) {
        "$auditPath is missing; run verifyProtocolReferenceSources first"
    }
    val audit = auditPath.readJsonObject()
    val corrections = audit["normalized_name_differences"]
        ?.let { element ->
            element.jsonArray.associate { differenceElement ->
                val difference = differenceElement.jsonObject
                PacketKey(
                    difference.requiredString("state").uppercase(),
                    difference.requiredString("direction").uppercase(),
                    difference.requiredInt("id"),
                ) to difference.requiredString("vanilla")
                    .removePrefix("minecraft:")
            }
        }
        .orEmpty()
    return rows.map { entry ->
        entry.copy(
            officialName = corrections[entry.key] ?: entry.officialName,
        )
    }
}

internal fun loadLocalPackets(
    repository: Path,
): Pair<List<LocalPacket>, List<String>> {
    val sourceRoot = repository.resolve(
        "protocol-model/src/commonMain/kotlin",
    )
    val packets = mutableListOf<LocalPacket>()
    val errors = mutableListOf<String>()
    kotlinSources(sourceRoot).forEach { path ->
        val text = path.readText()
        val seenDeclarations = mutableSetOf<Int>()
        packetInfoPattern.findAll(text).forEach { packetMatch ->
            val declaration = declarationPattern.find(
                text,
                packetMatch.range.last + 1,
            )
            val relative = repository.relativize(path)
            if (declaration == null) {
                errors += "$relative:${lineNumber(text, packetMatch.range.first)}: " +
                        "@PacketInfo is not followed by a packet declaration"
                return@forEach
            }
            if (!seenDeclarations.add(declaration.range.first)) {
                errors += "$relative:${lineNumber(text, declaration.range.first)}: " +
                        "${declaration.groups["class"]!!.value} has multiple " +
                        "@PacketInfo annotations"
                return@forEach
            }
            val annotationStart = packetMatch.range.first
            val serializableSearchStart = maxOf(
                text.lastIndexOf("\n\n", annotationStart)
                    .takeIf { it >= 0 }
                    ?: 0,
                text.lastIndexOf('}', annotationStart)
                    .takeIf { it >= 0 }
                    ?: 0,
            )
            val declarationPrefix = text.substring(
                serializableSearchStart,
                declaration.range.first,
            )
            if (
                !Regex("""@Serializable(?:\s|\(|$)""")
                    .containsMatchIn(declarationPrefix)
            ) {
                errors += "$relative:${lineNumber(text, declaration.range.first)}: " +
                        "${declaration.groups["class"]!!.value} has @PacketInfo " +
                        "but not @Serializable"
            }
            packets += LocalPacket(
                key = PacketKey(
                    packetMatch.groups["state"]!!.value,
                    packetMatch.groups["direction"]!!.value,
                    parseInteger(packetMatch.groups["id"]!!.value),
                ),
                className = declaration.groups["class"]!!.value,
                officialName = packetMatch.groups["officialName"]
                    ?.value
                    ?.takeIf(String::isNotEmpty),
                path = path,
            )
        }
    }
    return packets to errors
}

internal fun kotlinSources(root: Path): List<Path> {
    if (!Files.isDirectory(root)) return emptyList()
    return Files.walk(root).use { paths ->
        paths.filter {
            Files.isRegularFile(it) &&
                    it.fileName.toString().endsWith(".kt")
        }.sorted().toList()
    }
}

internal fun protocolAuditSources(repository: Path): List<Path> =
    listOf("protocol-model", "protocol-serialization")
        .flatMap { module ->
            kotlinSources(repository.resolve(module).resolve("src"))
        }
        .sorted()

internal fun lineNumber(text: String, index: Int): Int =
    text.take(index).count { it == '\n' } + 1
