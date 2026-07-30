package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.jsonObject
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

private data class PacketKey(
    val state: String,
    val direction: String,
    val id: Int,
) {
    fun display(): String =
        "$state/$direction/0x${id.toString(16).uppercase().padStart(2, '0')}"
}

private data class OfficialPacket(
    val key: PacketKey,
    val name: String,
)

private data class LocalPacket(
    val key: PacketKey,
    val className: String,
    val officialName: String?,
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

@CacheableTask
abstract class GeneratePacketRegistrySourceTask :
    MinecraftProtocolToolTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packetsReport: RegularFileProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packetSources: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun generate() {
        val official = loadOfficialPackets(
            packetsReport.asFile.get().toPath(),
        )
        val local = loadLocalPackets(repository)
        val officialByKey = official.associateBy(OfficialPacket::key)
        val localByKey = local.associateBy(LocalPacket::key)
        check(officialByKey.size == official.size) {
            "The official packets report contains duplicate keys"
        }
        check(localByKey.size == local.size) {
            "Local packet annotations contain duplicate keys"
        }
        val missing = officialByKey.keys - localByKey.keys
        val extra = localByKey.keys - officialByKey.keys - LEGACY_PACKET_KEY
        check(missing.isEmpty() && extra.isEmpty()) {
            "Packet models do not match the official report: " +
                    "missing=${missing.sortedBy { it.display() }}, " +
                    "extra=${extra.sortedBy { it.display() }}"
        }
        officialByKey.forEach { (key, packet) ->
            val localPacket = localByKey.getValue(key)
            check(localPacket.officialName == packet.name) {
                "${localPacket.className} identifies " +
                        "'${localPacket.officialName}', but the official " +
                        "report identifies '${packet.name}'"
            }
        }
        check(
            localByKey[LEGACY_PACKET_KEY]?.officialName ==
                    "legacy_server_list_ping",
        ) {
            "The sole non-report packet must be the annotated legacy " +
                    "server-list ping"
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
                { it.key.id },
            ),
        )
        val source = buildString {
            append(REGISTRY_HEADER)
            packets.forEach { packet ->
                val framing = if (packet.key == LEGACY_PACKET_KEY) {
                    ",\n        PacketFraming.LEGACY_UNFRAMED"
                } else {
                    ""
                }
                appendLine("    generatedPacketCodec(")
                appendLine(
                    "        ConnectionState.${packet.key.state},",
                )
                appendLine(
                    "        PacketDirection.${packet.key.direction},",
                )
                appendLine(
                    "        0x${
                        packet.key.id.toString(16)
                            .uppercase()
                            .padStart(2, '0')
                    },",
                )
                append(
                    "        ${packet.className}.serializer()" +
                            "$framing,\n",
                )
                appendLine("    ),")
            }
            append(REGISTRY_FOOTER)
        }
        val output = outputFile.asFile.get().toPath()
        output.atomicWriteText(source)
        logger.lifecycle(
            "Generated ${packets.size} packet registry entries: $output",
        )
    }

    private fun loadOfficialPackets(reportPath: Path): List<OfficialPacket> =
        reportPath.readJsonObject().entries.flatMap { (state, stateElement) ->
            stateElement.jsonObject.entries.flatMap {
                    (direction, directionElement) ->
                directionElement.jsonObject.entries.map {
                        (name, packetElement) ->
                    OfficialPacket(
                        key = PacketKey(
                            state.uppercase(),
                            direction.uppercase(),
                            packetElement.jsonObject.requiredInt(
                                "protocol_id",
                            ),
                        ),
                        name = name.removePrefix("minecraft:"),
                    )
                }
            }
        }

    private fun loadLocalPackets(root: Path): List<LocalPacket> {
        val sourceRoot = root.resolve(
            "protocol-model/src/commonMain/kotlin",
        )
        return kotlinSources(sourceRoot).flatMap { path ->
            val text = path.readText()
            packetInfoPattern.findAll(text).map { packetMatch ->
                val declaration = declarationPattern.find(
                    text,
                    packetMatch.range.last + 1,
                ) ?: error(
                    "${root.relativize(path)}: @PacketInfo is not followed " +
                            "by a packet declaration",
                )
                LocalPacket(
                    key = PacketKey(
                        packetMatch.groups["state"]!!.value,
                        packetMatch.groups["direction"]!!.value,
                        parseInteger(
                            packetMatch.groups["id"]!!.value,
                        ),
                    ),
                    className = declaration.groups["class"]!!.value,
                    officialName = packetMatch.groups["officialName"]
                        ?.value
                        ?.takeIf(String::isNotEmpty),
                )
            }.toList()
        }
    }

    private fun kotlinSources(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths.filter {
                Files.isRegularFile(it) &&
                        it.fileName.toString().endsWith(".kt")
            }.sorted().toList()
        }

    private fun parseInteger(value: String): Int =
        if (value.startsWith("0x", ignoreCase = true)) {
            value.substring(2).toInt(16)
        } else {
            value.toInt()
        }

    private companion object {
        val LEGACY_PACKET_KEY = PacketKey(
            state = "HANDSHAKE",
            direction = "SERVERBOUND",
            id = 0xFE,
        )

        val REGISTRY_HEADER = """
            |// Generated from the official packets report and local packet annotations.
            |// Do not edit by hand.
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
