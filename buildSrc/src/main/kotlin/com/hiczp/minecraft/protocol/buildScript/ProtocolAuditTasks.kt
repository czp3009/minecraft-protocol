package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

abstract class AuditProtocolModelsTask : MinecraftProtocolToolTask() {
    @get:Input
    abstract val reportOnly: Property<Boolean>

    @get:Internal
    abstract val reportFile: RegularFileProperty

    init {
        reportOnly.convention(false)
    }

    @TaskAction
    fun audit() {
        val errors = auditProtocol(repository)
        if (reportFile.isPresent) {
            val report = jsonObjectOf(
                "schema_version" to jsonNumber(1),
                "issue_count" to jsonNumber(errors.size),
                "issues" to JsonArray(errors.map(::jsonString)),
            )
            val path = reportFile.asFile.get().toPath()
            path.writeJson(report)
            logger.lifecycle("Wrote protocol work queue: $path")
        }
        if (errors.isNotEmpty()) {
            val rendered = buildString {
                appendLine(
                    "Protocol audit found ${errors.size} issue(s):",
                )
                errors.forEach { appendLine("- $it") }
            }.trimEnd()
            if (reportOnly.get()) {
                logger.lifecycle(rendered)
            } else {
                error(rendered)
            }
        } else {
            logger.lifecycle(
                "Protocol audit passed: packet inventory and local " +
                        "structure are complete.",
            )
        }
    }

    private fun auditProtocol(root: Path): List<String> {
        val errors = mutableListOf<String>()
        val specification = root.resolve("protocol-specification")
        val expected = runCatching {
            loadPacketManifest(specification)
        }.getOrElse {
            return listOf(it.message ?: it.toString())
        }
        val (local, packetErrors) = loadLocalPackets(root)
        errors += packetErrors
        val expectedByKey = expected.associateBy { it.key }
        if (expectedByKey.size != expected.size) {
            errors +=
                "protocol inventory contains duplicate state/direction/ID rows"
        }
        val localByKey = local.groupBy { it.key }
        localByKey.entries.sortedWith(
            compareBy(
                { it.key.state },
                { it.key.direction },
                { it.key.packetId },
            ),
        ).forEach { (key, packets) ->
            if (packets.size > 1) {
                errors += "duplicate local packet ${key.display()}: " +
                        packets.joinToString { it.className }
            }
        }
        expectedByKey.forEach { (key, entry) ->
            if (key !in localByKey) {
                errors += "missing ${key.display()} " +
                        "(${entry.wikiName}, ${entry.framing})"
            }
        }
        localByKey.forEach { (key, packets) ->
            val expectedPacket = expectedByKey[key]
            if (expectedPacket == null) {
                errors += "unexpected ${key.display()}: " +
                        packets.joinToString { it.className }
                return@forEach
            }
            packets.forEach { packet ->
                val relative = root.relativize(packet.path)
                if (packet.officialName.isNullOrEmpty()) {
                    errors += "$relative: ${packet.className} has no " +
                            "auditable officialName; expected packet " +
                            "'${expectedPacket.wikiName}'"
                } else if (
                    packet.officialName != expectedPacket.officialName
                ) {
                    errors += "$relative: ${packet.className} at " +
                            "${key.display()} identifies " +
                            "'${packet.officialName}'; Wiki/vanilla inventory " +
                            "identifies '${expectedPacket.officialName}'"
                }
            }
        }

        val settings = root.resolve("settings.gradle.kts").readText()
        listOf(":protocol-model", ":protocol-serialization").forEach { module ->
            if ("\"$module\"" !in settings) {
                errors += "settings.gradle.kts does not include $module"
            }
        }
        if ("\":minecraft-protocol\"" in settings) {
            errors +=
                "out-of-scope :minecraft-protocol module is included"
        }

        val snapshotPath = specification.resolve(
            "wiki-protocol-snapshot.json",
        )
        if (!snapshotPath.exists()) {
            errors += "wiki-protocol-snapshot.json is missing; " +
                    "run refreshProtocolSpecification first"
            return errors
        }
        val snapshot = runCatching { snapshotPath.readJsonObject() }
            .getOrElse {
                errors += "invalid wiki-protocol-snapshot.json: " +
                        (it.message ?: it.toString())
                return errors
            }
        val minecraftVersion = snapshot.requiredString(
            "minecraft_version",
        )
        val protocolVersion = snapshot.requiredInt("protocol_version")
        val constantsPath = root.resolve(
            "protocol-model/src/commonMain/kotlin/com/hiczp/" +
                    "minecraft/protocol/model/MinecraftProtocol.kt",
        )
        if (!constantsPath.exists()) {
            errors += "${root.relativize(constantsPath)} is missing"
            return errors
        }
        val constants = constantsPath.readText()
        val versionMatch = Regex(
            """MINECRAFT_VERSION:\s*String\s*=\s*"([^"]+)"""",
        ).find(constants)
        val protocolMatch = Regex(
            """PROTOCOL_VERSION:\s*Int\s*=\s*(\d+)""",
        ).find(constants)
        if (versionMatch?.groupValues?.get(1) != minecraftVersion) {
            errors += "Minecraft version constant is " +
                    "${versionMatch?.groupValues?.get(1) ?: "missing"}; " +
                    "Wiki snapshot is $minecraftVersion"
        }
        if (
            protocolMatch?.groupValues?.get(1)?.toInt() !=
            protocolVersion
        ) {
            errors += "protocol version constant is " +
                    "${protocolMatch?.groupValues?.get(1) ?: "missing"}; " +
                    "Wiki snapshot is $protocolVersion"
        }

        val modelSources = kotlinSources(
            root.resolve("protocol-model/src"),
        )
        val nullableCustomSerializers = mutableSetOf<String>()
        val nullableSerializerPattern = Regex(
            """\b(?:class|object)\s+""" +
                    """(?<name>[A-Za-z_][A-Za-z0-9_]*)\s*:\s*""" +
                    """KSerializer<[^\n>]*\?>""",
        )
        modelSources.forEach { path ->
            nullableCustomSerializers += nullableSerializerPattern
                .findAll(path.readText())
                .map { it.groups["name"]!!.value }
        }
        val forbiddenImport = Regex(
            """^[ \t]*import[ \t]+""" +
                    """com\.hiczp\.minecraft\.protocol\.serialization(?:\.|$)""",
            RegexOption.MULTILINE,
        )
        modelSources.forEach { path ->
            if (forbiddenImport.containsMatchIn(path.readText())) {
                errors += "${root.relativize(path)}: model depends on " +
                        "Minecraft format internals"
            }
        }

        listOf("protocol-model", "protocol-serialization").forEach { module ->
            kotlinSources(root.resolve(module)).forEach { path ->
                inspectKotlinSource(
                    root,
                    path,
                    nullableCustomSerializers,
                    errors,
                )
            }
        }
        return errors
    }

    private fun inspectKotlinSource(
        root: Path,
        path: Path,
        nullableCustomSerializers: Set<String>,
        errors: MutableList<String>,
    ) {
        val text = path.readText()
        val relative = root.relativize(path)
        val lines = text.lines()
        val serializerPattern = Regex(
            """@Serializable\(\s*with\s*=\s*""" +
                    """(?<name>[A-Za-z_][A-Za-z0-9_]*)::class\s*\)""",
        )
        val propertyPattern = Regex(
            """(?:(?:public|internal|private|protected|override)\s+)*""" +
                    """(?:val|var)\s+[A-Za-z_][A-Za-z0-9_]*\s*:""" +
                    """(?<type>.*)""",
        )
        lines.forEachIndexed { index, sourceLine ->
            val serializer = serializerPattern.find(sourceLine)
                ?: return@forEachIndexed
            if (
                serializer.groups["name"]!!.value !in
                nullableCustomSerializers
            ) {
                return@forEachIndexed
            }
            var cursor = index + 1
            while (cursor < lines.size) {
                val following = lines[cursor].trim()
                if (following.isEmpty() || following.startsWith("@")) {
                    cursor++
                    continue
                }
                val property = propertyPattern.matchEntire(following)
                if (
                    property != null &&
                    "?" in property.groups["type"]!!.value
                ) {
                    errors += "$relative:${index + 1}: a KSerializer<T?> " +
                            "applied directly to a nullable property receives " +
                            "an extra generated nullable wrapper"
                }
                break
            }
        }
        Regex(
            """\b(?:Minecraft(?:\s+Java\s+Edition)?|protocol)""" +
                    """\s+(?:version\s+)?\d+(?:\.\d+)*\b""",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let {
            errors += "$relative:${lineNumber(text, it.range.first)}: " +
                    "target-specific version fact belongs in refreshed " +
                    "specification state"
        }
        if (
            path.any { it.toString() == "commonMain" } &&
            Regex("""(?m)^\s*@JvmInline\s*$""").containsMatchIn(text) &&
            !Regex(
                """(?m)^\s*import\s+kotlin\.jvm\.JvmInline\s*$""",
            ).containsMatchIn(text)
        ) {
            errors += "$relative uses @JvmInline in commonMain without " +
                    "importing kotlin.jvm.JvmInline"
        }
        if (Regex("""\b(?:TODO|FIXME)\b""").containsMatchIn(text)) {
            errors += "$relative contains TODO/FIXME"
        }
        if (
            Regex(
                """\bpublic\s+(?=(?:data\s+|sealed\s+|value\s+)?""" +
                        """(?:class|object|interface)|enum\s+class|""" +
                        """annotation\s+class|(?:const\s+)?(?:val|var)\b|""" +
                        """fun\b|typealias\b|companion\s+object\b)""",
            ).containsMatchIn(text)
        ) {
            errors += "$relative contains a redundant explicit public modifier"
        }
        Regex(
            """(?m)^(?!\s*(?:private|internal)\s+)""" +
                    """\s*(?:abstract\s+)?(?:class|object)\s+""" +
                    """(?<name>[A-Za-z_][A-Za-z0-9_]*(?:Serializer|Codec))\b""",
        ).findAll(text).forEach {
            val name = it.groups["name"]!!.value
            if (name != "PacketCodec") {
                errors += "$relative:${lineNumber(text, it.range.first)}: " +
                        "$name is codec machinery and must use internal or " +
                        "private visibility"
            }
        }
        Regex("""@UnknownNullability\b""").findAll(text).forEach { marker ->
            val end = minOf(text.length, marker.range.last + 1 + 1_000)
            val following = text.substring(marker.range.last + 1, end)
            val property = Regex(
                """\b(?:val|var)\s+[A-Za-z_][A-Za-z0-9_]*\s*:""" +
                        """(?<type>[^,)=\n]+)""",
            ).find(following)
            if (property == null || "?" !in property.groups["type"]!!.value) {
                errors += "$relative:" +
                        "${lineNumber(text, marker.range.first)}: " +
                        "@UnknownNullability must annotate a nullable property"
            }
        }
    }
}

abstract class GeneratePacketRegistryTask : MinecraftProtocolToolTask() {
    @get:Input
    abstract val checkOnly: Property<Boolean>

    init {
        checkOnly.convention(false)
    }

    @TaskAction
    fun generate() {
        val destination = repository.resolve(
            "protocol-serialization/src/commonMain/kotlin/com/hiczp/" +
                    "minecraft/protocol/serialization/" +
                    "GeneratedPacketRegistryEntries.kt",
        )
        val generated = render()
        if (checkOnly.get()) {
            check(destination.exists() && destination.readText() == generated) {
                "${repository.relativize(destination)} is stale; " +
                        "run generatePacketRegistry"
            }
            val count = generated.split("generatedPacketCodec(").size - 2
            logger.lifecycle(
                "Packet registry is current ($count entries).",
            )
        } else {
            destination.atomicWriteText(generated)
            logger.lifecycle("Wrote $destination")
        }
    }

    private fun render(): String {
        val expected = loadPacketManifest(
            repository.resolve("protocol-specification"),
        )
        val (local, errors) = loadLocalPackets(repository)
        check(errors.isEmpty()) { errors.joinToString("\n") }
        val expectedByKey = expected.associateBy { it.key }
        val localByKey = local.associateBy { it.key }
        val missing = expectedByKey.keys - localByKey.keys
        val extra = localByKey.keys - expectedByKey.keys
        check(
            missing.isEmpty() &&
                    extra.isEmpty() &&
                    localByKey.size == local.size,
        ) {
            "Packet models are not in one-to-one correspondence with the " +
                    "inventory; run reportProtocolModelGaps"
        }
        val stateOrder = mapOf(
            "HANDSHAKE" to 0,
            "STATUS" to 1,
            "LOGIN" to 2,
            "CONFIGURATION" to 3,
            "PLAY" to 4,
        )
        val directionOrder = mapOf(
            "CLIENTBOUND" to 0,
            "SERVERBOUND" to 1,
        )
        val packets = local.sortedWith(
            compareBy(
                { stateOrder.getValue(it.key.state) },
                { directionOrder.getValue(it.key.direction) },
                { it.key.packetId },
            ),
        )
        return buildString {
            append(REGISTRY_HEADER)
            packets.forEach { packet ->
                val expectedPacket = expectedByKey.getValue(packet.key)
                val framing = if (expectedPacket.framing == "normal") {
                    ""
                } else {
                    ",\n        PacketFraming.LEGACY_UNFRAMED"
                }
                append(
                    "    generatedPacketCodec(\n" +
                            "        ConnectionState.${packet.key.state},\n" +
                            "        PacketDirection.${packet.key.direction},\n" +
                            "        0x${
                                packet.key.packetId.toString(16)
                                    .uppercase()
                                    .padStart(2, '0')
                            },\n" +
                            "        ${packet.className}.serializer()" +
                            "$framing,\n" +
                            "    ),\n",
                )
            }
            append(REGISTRY_FOOTER)
        }
    }

    private companion object {
        val REGISTRY_HEADER = """
            |// Generated by generatePacketRegistry. Do not edit by hand.
            |package com.hiczp.minecraft.protocol.serialization
            |
            |import com.hiczp.minecraft.protocol.model.packet.*
            |import kotlinx.serialization.KSerializer
            |
            |internal fun generatedPacketCodecs(): List<PacketCodec<out Packet>> = listOf(
            |
        """.trimMargin()

        val REGISTRY_FOOTER = """
            |)
            |
            |private inline fun <reified T : Packet> generatedPacketCodec(
            |    state: ConnectionState,
            |    direction: PacketDirection,
            |    id: Int,
            |    serializer: KSerializer<T>,
            |    framing: PacketFraming = PacketFraming.NORMAL,
            |): PacketCodec<T> = PacketCodec(
            |    PacketKey(state, direction, id),
            |    framing,
            |    T::class,
            |    serializer,
            |)
            |
        """.trimMargin()
    }
}

abstract class AuditNetworkRegistriesTask :
    MinecraftProtocolToolTask() {
    @get:Internal
    abstract val manifestFile: RegularFileProperty

    @get:Internal
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun audit() {
        val errors = mutableListOf<String>()
        var report: JsonObject
        try {
            val target = repository.readMinecraftProtocolTarget()
            val local = loadLocal(manifestFile.asFile.get().toPath())
            val official = loadOfficial(
                target.minecraftVersion,
                local.keys,
            )
            errors += compare(local, official)
            report = jsonObjectOf(
                "schema_version" to jsonNumber(1),
                "minecraft_version" to
                        jsonString(target.minecraftVersion),
                "registry_count" to jsonNumber(local.size),
                "entry_count" to
                        jsonNumber(local.values.sumOf { it.size }),
                "status" to jsonString(
                    if (errors.isEmpty()) "pass" else "fail",
                ),
                "errors" to JsonArray(errors.map(::jsonString)),
            )
        } catch (failure: Throwable) {
            errors += failure.message ?: failure.toString()
            report = jsonObjectOf(
                "schema_version" to jsonNumber(1),
                "status" to jsonString("fail"),
                "errors" to JsonArray(errors.map(::jsonString)),
            )
        }
        if (reportFile.isPresent) {
            reportFile.asFile.get().toPath().writeJson(report)
        }
        check(errors.isEmpty()) {
            errors.joinToString("\n") { "ERROR: $it" }
        }
        logger.lifecycle(
            "Official network-registry audit passed: " +
                    "${report.requiredInt("entry_count")} entries across " +
                    "${report.requiredInt("registry_count")} registries.",
        )
    }

    private fun loadLocal(path: Path): Map<String, Map<Int, String>> {
        val result = linkedMapOf<String, MutableMap<Int, String>>()
        readCsv(path, '\t').use { parser ->
            parser.forEach { row ->
                val registry = row["registry"]
                val protocolId = row["protocol_id"].toInt()
                val entries = result.getOrPut(registry) {
                    linkedMapOf()
                }
                check(protocolId !in entries) {
                    "$path duplicates $registry protocol ID $protocolId"
                }
                entries[protocolId] = row["wire_name"]
            }
        }
        check(result.isNotEmpty()) {
            "$path contains no registry entries"
        }
        return result
    }

    private fun loadOfficial(
        version: String,
        registryNames: Set<String>,
    ): Map<String, Map<Int, String>> {
        val document = repository.resolve(
            "build/protocol-reference/mojang/$version/generated/" +
                    "reports/registries.json",
        ).readJsonObject()
        return registryNames.sorted().associateWith { registryName ->
            val registry = document[registryName]?.jsonObject
                ?: error(
                    "Vanilla registries report has no '$registryName'",
                )
            val entries = registry["entries"]?.jsonObject
                ?: error(
                    "Vanilla registry '$registryName' has no entries object",
                )
            val byId = linkedMapOf<Int, String>()
            entries.forEach { (wireName, element) ->
                val protocolId = element.jsonObject
                    .requiredInt("protocol_id")
                check(protocolId !in byId) {
                    "Vanilla registry '$registryName' duplicates " +
                            "protocol ID $protocolId"
                }
                byId[protocolId] = wireName
            }
            byId
        }
    }

    private fun compare(
        local: Map<String, Map<Int, String>>,
        official: Map<String, Map<Int, String>>,
    ): List<String> = buildList {
        local.keys.sorted().forEach { registryName ->
            val localEntries = local.getValue(registryName)
            val officialEntries = official.getValue(registryName)
            (officialEntries.keys - localEntries.keys).sorted().forEach {
                add(
                    "$registryName: missing ID $it " +
                            "(${officialEntries.getValue(it)})",
                )
            }
            (localEntries.keys - officialEntries.keys).sorted().forEach {
                add(
                    "$registryName: unexpected ID $it " +
                            "(${localEntries.getValue(it)})",
                )
            }
            (localEntries.keys intersect officialEntries.keys)
                .sorted()
                .forEach {
                    val localName = localEntries.getValue(it)
                    val officialName = officialEntries.getValue(it)
                    if (localName != officialName) {
                        add(
                            "$registryName: ID $it is '$localName'; " +
                                    "vanilla reports '$officialName'",
                        )
                    }
                }
        }
    }
}
