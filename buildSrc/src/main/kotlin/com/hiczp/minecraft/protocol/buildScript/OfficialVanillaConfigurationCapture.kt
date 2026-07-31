package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile

internal data class OfficialPacketKey(
    val state: String,
    val direction: String,
    val name: String,
)

internal class OfficialPacketIds private constructor(
    private val byKey: Map<OfficialPacketKey, Int>,
    private val clientboundNames: Map<Pair<String, Int>, String>,
) {
    fun id(state: String, direction: String, name: String): Int =
        byKey[OfficialPacketKey(state, direction, name)]
            ?: error(
                "Official packets report has no " +
                        "$state/$direction/$name packet",
            )

    fun clientboundName(state: String, id: Int): String =
        clientboundNames[state to id]
            ?: error(
                "Official packets report has no $state/clientbound " +
                        "packet 0x${id.toString(16)}",
            )

    companion object {
        fun fromReport(report: JsonObject): OfficialPacketIds {
            val entries = linkedMapOf<OfficialPacketKey, Int>()
            val clientbound = linkedMapOf<Pair<String, Int>, String>()
            report.forEach { (stateName, stateElement) ->
                val state = stateName.lowercase()
                stateElement.jsonObject.forEach { (directionName, directionElement) ->
                    val direction = directionName.lowercase()
                    directionElement.jsonObject.forEach { (rawName, packetElement) ->
                        val name = rawName.removePrefix("minecraft:")
                        val id = packetElement.jsonObject
                            .requiredInt("protocol_id")
                        check(id >= 0) {
                            "Official packet $state/$direction/$name has " +
                                    "a negative protocol ID"
                        }
                        val key = OfficialPacketKey(state, direction, name)
                        check(entries.put(key, id) == null) {
                            "Duplicate official packet $key"
                        }
                        if (direction == CLIENTBOUND) {
                            check(
                                clientbound.put(state to id, name) == null,
                            ) {
                                "Duplicate official packet ID " +
                                        "$state/clientbound/$id"
                            }
                        }
                    }
                }
            }
            return OfficialPacketIds(entries, clientbound)
        }
    }
}

internal object OfficialVanillaConfigurationCapture {
    private const val COMPRESSION_THRESHOLD = 64
    private const val MAXIMUM_PACKETS = 512

    fun capture(
        javaExecutable: String,
        serverJar: Path,
        workDirectory: Path,
        target: MinecraftProtocolTarget,
        packetIds: OfficialPacketIds,
    ): VanillaConfigurationCaptureResult {
        val java = Path.of(javaExecutable).toAbsolutePath().normalize()
        require(java.isRegularFile()) {
            "Analysis Java does not exist: $java"
        }
        require(serverJar.isRegularFile()) {
            "Official server does not exist: $serverJar"
        }

        workDirectory.deleteTree()
        workDirectory.createDirectories()
        val port = ServerSocket(0).use { it.localPort }
        writeServerConfiguration(workDirectory, port)

        val process = ProcessBuilder(
            java.absolutePathString(),
            "-Djava.awt.headless=true",
            "-jar",
            serverJar.absolutePathString(),
            "nogui",
        )
            .directory(workDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val log = BoundedProcessLog(process, "vanilla-data-capture-log")
        try {
            waitForServer(process, port, log)
            val complete = captureConfiguration(
                port = port,
                name = "FullDataProbe",
                acceptKnownPacks = false,
                protocolVersion = target.protocolVersion,
                packetIds = packetIds,
            ).canonicalize()
            val clientKnown = captureConfiguration(
                port = port,
                name = "KnownPackProbe",
                acceptKnownPacks = true,
                protocolVersion = target.protocolVersion,
                packetIds = packetIds,
            ).canonicalize()
            validateCaptures(complete, clientKnown)
            return VanillaConfigurationCaptureResult(
                knownPacks = complete.knownPacks,
                featureFlags = complete.featureFlags,
                completeRegistries = complete.registries,
                clientKnownRegistries = clientKnown.registries,
                tags = complete.tags,
                completePacketSequence = complete.receivedPackets,
                clientKnownPacketSequence = clientKnown.receivedPackets,
            )
        } catch (failure: Throwable) {
            throw AssertionError(
                "Official vanilla-data capture failed.\n" +
                        "--- official server log ---\n${log.content()}",
                failure,
            )
        } finally {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
            log.close()
        }
    }

    private fun captureConfiguration(
        port: Int,
        name: String,
        acceptKnownPacks: Boolean,
        protocolVersion: Int,
        packetIds: OfficialPacketIds,
    ): ConfigurationCapture {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 30_000
            val connection = CaptureConnection(socket, packetIds)
            connection.send(
                state = HANDSHAKE,
                name = "intention",
                payload = PacketOutput().apply {
                    writeVarInt(protocolVersion)
                    writeString("127.0.0.1")
                    writeUnsignedShort(port)
                    writeVarInt(2)
                }.toByteArray(),
            )
            connection.send(
                state = LOGIN,
                name = "hello",
                payload = PacketOutput().apply {
                    writeString(name)
                    writeLong(0)
                    writeLong(if (acceptKnownPacks) 2 else 1)
                }.toByteArray(),
            )

            var state = LOGIN
            var knownPacks: KnownPacksPayload? = null
            var featureFlags: FeatureFlagsPayload? = null
            var tags: TagsPayload? = null
            val registries = mutableListOf<RegistryPayload>()
            val received = mutableListOf<String>()
            repeat(MAXIMUM_PACKETS) {
                val packet = connection.receive(state)
                received += "$state/clientbound/${packet.name}"
                when (state to packet.name) {
                    LOGIN to "login_disconnect" ->
                        error(
                            "Official server rejected $name: " +
                                    packet.payload.toHex(),
                        )

                    LOGIN to "cookie_request" -> {
                        val input = packet.input()
                        val key = input.readString()
                        input.requireExhausted()
                        connection.send(
                            LOGIN,
                            "cookie_response",
                            PacketOutput().apply {
                                writeString(key)
                                writeBoolean(false)
                            }.toByteArray(),
                        )
                    }

                    LOGIN to "custom_query" -> {
                        val input = packet.input()
                        val messageId = input.readVarInt()
                        connection.send(
                            LOGIN,
                            "custom_query_answer",
                            PacketOutput().apply {
                                writeVarInt(messageId)
                                writeBoolean(false)
                            }.toByteArray(),
                        )
                    }

                    LOGIN to "login_compression" -> {
                        val input = packet.input()
                        val threshold = input.readVarInt()
                        input.requireExhausted()
                        check(threshold == COMPRESSION_THRESHOLD) {
                            "Official server negotiated compression " +
                                    "threshold $threshold, expected " +
                                    COMPRESSION_THRESHOLD
                        }
                        connection.compressionThreshold = threshold
                    }

                    LOGIN to "login_finished" -> {
                        connection.send(LOGIN, "login_acknowledged")
                        state = CONFIGURATION
                        connection.send(
                            CONFIGURATION,
                            "client_information",
                            clientInformationPayload(),
                        )
                    }

                    CONFIGURATION to "disconnect" ->
                        error(
                            "Official server rejected $name: " +
                                    packet.payload.toHex(),
                        )

                    CONFIGURATION to "cookie_request" -> {
                        val input = packet.input()
                        val key = input.readString()
                        input.requireExhausted()
                        connection.send(
                            CONFIGURATION,
                            "cookie_response",
                            PacketOutput().apply {
                                writeString(key)
                                writeBoolean(false)
                            }.toByteArray(),
                        )
                    }

                    CONFIGURATION to "keep_alive" -> {
                        val input = packet.input()
                        val id = input.readLong()
                        input.requireExhausted()
                        connection.send(
                            CONFIGURATION,
                            "keep_alive",
                            PacketOutput().apply {
                                writeLong(id)
                            }.toByteArray(),
                        )
                    }

                    CONFIGURATION to "ping" -> {
                        val input = packet.input()
                        val id = input.readInt()
                        input.requireExhausted()
                        connection.send(
                            CONFIGURATION,
                            "pong",
                            PacketOutput().apply {
                                writeInt(id)
                            }.toByteArray(),
                        )
                    }

                    CONFIGURATION to "select_known_packs" -> {
                        check(knownPacks == null) {
                            "Official server sent Known Packs more than once"
                        }
                        knownPacks = KnownPacksPayload.decode(packet.payload)
                        connection.send(
                            CONFIGURATION,
                            "select_known_packs",
                            if (acceptKnownPacks) {
                                knownPacks.encode()
                            } else {
                                PacketOutput().apply {
                                    writeVarInt(0)
                                }.toByteArray()
                            },
                        )
                    }

                    CONFIGURATION to "update_enabled_features" -> {
                        check(featureFlags == null) {
                            "Official server sent Feature Flags more than once"
                        }
                        featureFlags =
                            FeatureFlagsPayload.decode(packet.payload)
                    }

                    CONFIGURATION to "registry_data" ->
                        registries += RegistryPayload.decode(packet.payload)

                    CONFIGURATION to "update_tags" -> {
                        check(tags == null) {
                            "Official server sent Update Tags more than once"
                        }
                        tags = TagsPayload.decode(packet.payload)
                    }

                    CONFIGURATION to "code_of_conduct" ->
                        connection.send(
                            CONFIGURATION,
                            "accept_code_of_conduct",
                        )

                    CONFIGURATION to "finish_configuration" -> {
                        connection.send(
                            CONFIGURATION,
                            "finish_configuration",
                        )
                        state = PLAY
                    }

                    PLAY to "login" ->
                        return ConfigurationCapture(
                            knownPacks = checkNotNull(knownPacks) {
                                "Official server did not offer Known Packs"
                            },
                            featureFlags = checkNotNull(featureFlags) {
                                "Official server did not send Feature Flags"
                            },
                            registries = registries.toList(),
                            tags = checkNotNull(tags) {
                                "Official server did not send Update Tags"
                            },
                            receivedPackets = received.toList(),
                        )
                }
            }
            error(
                "Official server never entered Play for $name; " +
                        "received $received",
            )
        }
    }

    private fun validateCaptures(
        complete: ConfigurationCapture,
        clientKnown: ConfigurationCapture,
    ) {
        check(complete.knownPacks == clientKnown.knownPacks) {
            "Known Packs changed between official captures"
        }
        check(complete.knownPacks.values.isNotEmpty()) {
            "Official server offered no Known Packs"
        }
        check(complete.featureFlags == clientKnown.featureFlags) {
            "Feature Flags changed between official captures"
        }
        check(complete.tags == clientKnown.tags) {
            "Update Tags changed between official captures"
        }
        check(complete.registries.isNotEmpty()) {
            "Official server sent no Registry Data packets"
        }
        check(complete.registries.size == clientKnown.registries.size) {
            "Registry packet count changed between Known Packs branches: " +
                    "${complete.registries.size} vs " +
                    clientKnown.registries.size
        }
        complete.registries.zip(clientKnown.registries)
            .forEach { (full, compact) ->
                check(full.registryId == compact.registryId) {
                    "Registry order changed: ${full.registryId} vs " +
                            compact.registryId
                }
                check(
                    full.entries.map(RegistryEntryPayload::id) ==
                            compact.entries.map(RegistryEntryPayload::id),
                ) {
                    "Registry entries changed for ${full.registryId}"
                }
                check(full.entries.all { it.data != null }) {
                    "Full-data capture omitted data in ${full.registryId}"
                }
                check(compact.entries.all { it.data == null }) {
                    "Known-pack capture retained data in " +
                            compact.registryId
                }
            }
    }

    private fun clientInformationPayload(): ByteArray =
        PacketOutput().apply {
            writeString("en_us")
            writeByte(2)
            writeVarInt(0)
            writeBoolean(true)
            writeByte(0x7F)
            writeVarInt(1)
            writeBoolean(false)
            writeBoolean(true)
            writeVarInt(0)
        }.toByteArray()

    private fun writeServerConfiguration(
        workDirectory: Path,
        port: Int,
    ) {
        Files.writeString(workDirectory.resolve("eula.txt"), "eula=true\n")
        Files.writeString(
            workDirectory.resolve("server.properties"),
            """
            |accepts-transfers=false
            |enable-status=false
            |enforce-secure-profile=false
            |generate-structures=false
            |level-name=vanilla-data-world-$port
            |level-type=minecraft:flat
            |max-players=2
            |max-tick-time=-1
            |motd=minecraft-protocol vanilla-data capture
            |network-compression-threshold=$COMPRESSION_THRESHOLD
            |online-mode=false
            |pause-when-empty-seconds=-1
            |server-ip=127.0.0.1
            |server-port=$port
            |simulation-distance=2
            |spawn-protection=0
            |sync-chunk-writes=false
            |view-distance=2
            """.trimMargin(),
        )
    }

    private fun waitForServer(
        process: Process,
        port: Int,
        log: BoundedProcessLog,
    ) {
        val deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos()
        while (System.nanoTime() < deadline) {
            check(process.isAlive) {
                "Official server exited with ${process.exitValue()}: " +
                        log.content()
            }
            if (!log.contains("[Server thread/INFO]: Done (")) {
                Thread.sleep(100)
                continue
            }
            try {
                Socket().use { socket ->
                    socket.connect(
                        InetSocketAddress("127.0.0.1", port),
                        250,
                    )
                    return
                }
            } catch (_: IOException) {
                Thread.sleep(100)
            }
        }
        error("Official server did not listen on port $port within two minutes")
    }
}

internal data class VanillaConfigurationCaptureResult(
    val knownPacks: KnownPacksPayload,
    val featureFlags: FeatureFlagsPayload,
    val completeRegistries: List<RegistryPayload>,
    val clientKnownRegistries: List<RegistryPayload>,
    val tags: TagsPayload,
    val completePacketSequence: List<String>,
    val clientKnownPacketSequence: List<String>,
) {
    fun renderKotlin(target: MinecraftProtocolTarget): String =
        buildString {
            appendLine("package com.hiczp.minecraft.protocol.data")
            appendLine()
            appendLine(
                "/** Exact official-server packet payloads; " +
                        "regenerated by Gradle. */",
            )
            appendLine("internal object VanillaConfigurationPayloads {")
            appendLine(
                "    const val minecraftVersion: String = " +
                        quoteKotlin(target.minecraftVersion),
            )
            appendLine(
                "    const val protocolVersion: Int = " +
                        target.protocolVersion,
            )
            appendPayload("knownPacks", knownPacks.encode())
            appendPayload("featureFlags", featureFlags.encode())
            appendPayloadList(
                "completeRegistries",
                completeRegistries.map(RegistryPayload::encode),
            )
            appendPayloadList(
                "clientKnownRegistries",
                clientKnownRegistries.map(RegistryPayload::encode),
            )
            appendPayload("tags", tags.encode())
            appendLine("}")
        }

    fun renderManifest(
        target: MinecraftProtocolTarget,
        serverJar: Path,
    ): JsonObject {
        val registries = completeRegistries.zip(clientKnownRegistries)
            .map { (full, compact) ->
                val ids = full.entries.joinToString(
                    separator = "\n",
                    transform = RegistryEntryPayload::id,
                ).encodeToByteArray()
                jsonObjectOf(
                    "id" to jsonString(full.registryId),
                    "entry_count" to jsonNumber(full.entries.size),
                    "entry_ids_sha256" to jsonString(ids.sha256()),
                    "full_payload_sha256" to
                            jsonString(full.encode().sha256()),
                    "known_pack_payload_sha256" to
                            jsonString(compact.encode().sha256()),
                    "known_pack_omits_entry_data" to jsonBoolean(
                        compact.entries.all { it.data == null },
                    ),
                )
            }
        val tagSummaries = tags.registries.map { registry ->
            jsonObjectOf(
                "registry" to jsonString(registry.registry),
                "tag_count" to jsonNumber(registry.tags.size),
                "entries_count" to jsonNumber(
                    registry.tags.sumOf { it.entries.size },
                ),
            )
        }
        return jsonObjectOf(
            "schema_version" to jsonNumber(1),
            "minecraft_version" to jsonString(target.minecraftVersion),
            "protocol_version" to jsonNumber(target.protocolVersion),
            "official_server_sha256" to jsonString(serverJar.sha256()),
            "known_packs" to JsonArray(
                knownPacks.values.map { pack ->
                    jsonObjectOf(
                        "namespace" to jsonString(pack.namespace),
                        "id" to jsonString(pack.id),
                        "version" to jsonString(pack.version),
                    )
                },
            ),
            "feature_flags" to JsonArray(
                featureFlags.values.map(::jsonString),
            ),
            "registries" to JsonArray(registries),
            "tags" to JsonArray(tagSummaries),
            "packet_sequence_full" to JsonArray(
                completePacketSequence.map(::jsonString),
            ),
            "packet_sequence_known_packs" to JsonArray(
                clientKnownPacketSequence.map(::jsonString),
            ),
        )
    }

    private fun StringBuilder.appendPayload(
        name: String,
        payload: ByteArray,
    ) {
        appendLine()
        appendLine("    val $name: List<String> = listOf(")
        appendBase64Chunks(payload, "        ")
        appendLine("    )")
    }

    private fun StringBuilder.appendPayloadList(
        name: String,
        payloads: List<ByteArray>,
    ) {
        appendLine()
        appendLine("    val $name: List<List<String>> = listOf(")
        payloads.forEach { payload ->
            appendLine("        listOf(")
            appendBase64Chunks(payload, "            ")
            appendLine("        ),")
        }
        appendLine("    )")
    }

    private fun StringBuilder.appendBase64Chunks(
        payload: ByteArray,
        indentation: String,
    ) {
        Base64.getEncoder().encodeToString(payload)
            .chunked(PAYLOAD_CHUNK_SIZE)
            .forEach { chunk ->
                append(indentation)
                append(quoteKotlin(chunk))
                appendLine(",")
            }
    }

    private companion object {
        const val PAYLOAD_CHUNK_SIZE = 12_000
    }
}

internal data class KnownPackPayload(
    val namespace: String,
    val id: String,
    val version: String,
)

internal data class KnownPacksPayload(
    val values: List<KnownPackPayload>,
) {
    fun encode(): ByteArray = PacketOutput().apply {
        writeVarInt(values.size)
        values.forEach { pack ->
            writeString(pack.namespace)
            writeString(pack.id)
            writeString(pack.version)
        }
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): KnownPacksPayload {
            val input = PacketInput(payload)
            val values = input.readList {
                KnownPackPayload(
                    namespace = readString(),
                    id = readString(),
                    version = readString(),
                )
            }
            input.requireExhausted()
            return KnownPacksPayload(values)
        }
    }
}

internal data class FeatureFlagsPayload(
    val values: List<String>,
) {
    fun canonicalize(): FeatureFlagsPayload =
        FeatureFlagsPayload(values.distinct().sorted())

    fun encode(): ByteArray = PacketOutput().apply {
        writeVarInt(values.size)
        values.forEach(::writeString)
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): FeatureFlagsPayload {
            val input = PacketInput(payload)
            val values = input.readList(PacketInput::readString)
            input.requireExhausted()
            check(values.distinct().size == values.size) {
                "Official Feature Flags packet contains duplicates"
            }
            return FeatureFlagsPayload(values)
        }
    }
}

internal data class RegistryEntryPayload(
    val id: String,
    val data: CapturedNbt?,
)

internal data class RegistryPayload(
    val registryId: String,
    val entries: List<RegistryEntryPayload>,
) {
    fun canonicalize(): RegistryPayload =
        copy(
            entries = entries.map { entry ->
                entry.copy(data = entry.data?.canonicalize())
            },
        )

    fun encode(): ByteArray = PacketOutput().apply {
        writeString(registryId)
        writeVarInt(entries.size)
        entries.forEach { entry ->
            writeString(entry.id)
            writeNullableNbt(entry.data)
        }
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): RegistryPayload {
            val input = PacketInput(payload)
            val registry = input.readString()
            val entries = input.readList {
                RegistryEntryPayload(
                    id = readString(),
                    data = readNullableNbt(),
                )
            }
            input.requireExhausted()
            check(
                entries.map(RegistryEntryPayload::id).distinct().size ==
                        entries.size
            ) {
                "Official registry $registry contains duplicate entries"
            }
            return RegistryPayload(registry, entries)
        }
    }
}

internal data class TagPayload(
    val name: String,
    val entries: List<Int>,
)

internal data class RegistryTagsPayload(
    val registry: String,
    val tags: List<TagPayload>,
)

internal data class TagsPayload(
    val registries: List<RegistryTagsPayload>,
) {
    fun canonicalize(): TagsPayload =
        TagsPayload(
            registries.sortedBy(RegistryTagsPayload::registry)
                .map { registry ->
                    registry.copy(
                        tags = registry.tags.sortedBy(TagPayload::name)
                            .map { tag ->
                                tag.copy(entries = tag.entries.sorted())
                            },
                    )
                },
        )

    fun encode(): ByteArray = PacketOutput().apply {
        writeVarInt(registries.size)
        registries.forEach { registry ->
            writeString(registry.registry)
            writeVarInt(registry.tags.size)
            registry.tags.forEach { tag ->
                writeString(tag.name)
                writeVarInt(tag.entries.size)
                tag.entries.forEach(::writeVarInt)
            }
        }
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): TagsPayload {
            val input = PacketInput(payload)
            val registries = input.readList {
                val registry = readString()
                val tags = readList {
                    TagPayload(
                        name = readString(),
                        entries = readList(PacketInput::readVarInt),
                    )
                }
                RegistryTagsPayload(registry, tags)
            }
            input.requireExhausted()
            return TagsPayload(registries)
        }
    }
}

private data class ConfigurationCapture(
    val knownPacks: KnownPacksPayload,
    val featureFlags: FeatureFlagsPayload,
    val registries: List<RegistryPayload>,
    val tags: TagsPayload,
    val receivedPackets: List<String>,
) {
    fun canonicalize(): ConfigurationCapture =
        copy(
            featureFlags = featureFlags.canonicalize(),
            registries = registries.map(RegistryPayload::canonicalize),
            tags = tags.canonicalize(),
        )
}

private data class CapturedPacket(
    val name: String,
    val payload: ByteArray,
) {
    fun input(): PacketInput = PacketInput(payload)
}

private class CaptureConnection(
    socket: Socket,
    private val packetIds: OfficialPacketIds,
) {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = BufferedOutputStream(socket.getOutputStream())

    var compressionThreshold: Int? = null

    fun send(
        state: String,
        name: String,
        payload: ByteArray = ByteArray(0),
    ) {
        val body = PacketOutput().apply {
            writeVarInt(packetIds.id(state, SERVERBOUND, name))
            writeBytes(payload)
        }.toByteArray()
        GradlePacketFraming.writeFrame(
            output,
            body,
            compressionThreshold,
        )
        output.flush()
    }

    fun receive(state: String): CapturedPacket {
        val frame = GradlePacketFraming.readFrame(
            input,
            compressionThreshold,
        )
        val cursor = PacketInput(frame)
        val packetId = cursor.readVarInt()
        return CapturedPacket(
            name = packetIds.clientboundName(state, packetId),
            payload = cursor.remainingBytes(),
        )
    }
}

internal sealed interface CapturedNbt {
    fun canonicalize(): CapturedNbt = this
    fun writePayload(output: PacketOutput)
}

private data class NbtByteValue(val value: Byte) : CapturedNbt {
    override fun writePayload(output: PacketOutput) = output.writeByte(value)
}

private data class NbtShortValue(val value: Short) : CapturedNbt {
    override fun writePayload(output: PacketOutput) = output.writeShort(value)
}

private data class NbtIntValue(val value: Int) : CapturedNbt {
    override fun writePayload(output: PacketOutput) = output.writeInt(value)
}

private data class NbtLongValue(val value: Long) : CapturedNbt {
    override fun writePayload(output: PacketOutput) = output.writeLong(value)
}

private data class NbtFloatValue(val value: Float) : CapturedNbt {
    override fun writePayload(output: PacketOutput) = output.writeFloat(value)
}

private data class NbtDoubleValue(val value: Double) : CapturedNbt {
    override fun writePayload(output: PacketOutput) = output.writeDouble(value)
}

private data class NbtByteArrayValue(
    val value: List<Byte>,
) : CapturedNbt {
    override fun writePayload(output: PacketOutput) {
        output.writeInt(value.size)
        value.forEach(output::writeByte)
    }
}

private data class NbtStringValue(val value: String) : CapturedNbt {
    override fun writePayload(output: PacketOutput) =
        output.writeModifiedUtf(value)
}

private data class NbtListValue(
    val elementType: Int,
    val value: List<CapturedNbt>,
) : CapturedNbt {
    override fun canonicalize(): CapturedNbt =
        copy(value = value.map(CapturedNbt::canonicalize))

    override fun writePayload(output: PacketOutput) {
        output.writeByte(elementType)
        output.writeInt(value.size)
        value.forEach { it.writePayload(output) }
    }
}

private data class NbtCompoundValue(
    val value: Map<String, CapturedNbt>,
) : CapturedNbt {
    override fun canonicalize(): CapturedNbt =
        NbtCompoundValue(
            value.entries.sortedBy(Map.Entry<String, CapturedNbt>::key)
                .associateTo(linkedMapOf()) { (key, tag) ->
                    key to tag.canonicalize()
                },
        )

    override fun writePayload(output: PacketOutput) {
        value.forEach { (name, tag) ->
            output.writeByte(tag.typeId())
            output.writeModifiedUtf(name)
            tag.writePayload(output)
        }
        output.writeByte(NBT_END)
    }
}

private data class NbtIntArrayValue(
    val value: List<Int>,
) : CapturedNbt {
    override fun writePayload(output: PacketOutput) {
        output.writeInt(value.size)
        value.forEach(output::writeInt)
    }
}

private data class NbtLongArrayValue(
    val value: List<Long>,
) : CapturedNbt {
    override fun writePayload(output: PacketOutput) {
        output.writeInt(value.size)
        value.forEach(output::writeLong)
    }
}

private fun CapturedNbt.typeId(): Int =
    when (this) {
        is NbtByteValue -> 1
        is NbtShortValue -> 2
        is NbtIntValue -> 3
        is NbtLongValue -> 4
        is NbtFloatValue -> 5
        is NbtDoubleValue -> 6
        is NbtByteArrayValue -> 7
        is NbtStringValue -> 8
        is NbtListValue -> 9
        is NbtCompoundValue -> 10
        is NbtIntArrayValue -> 11
        is NbtLongArrayValue -> 12
    }

internal class PacketInput(
    private val bytes: ByteArray,
) {
    private var position = 0

    fun readByte(): Byte {
        requireAvailable(1)
        return bytes[position++]
    }

    fun readUnsignedByte(): Int = readByte().toInt() and 0xFF

    fun readShort(): Short =
        readFixed(2).short

    fun readUnsignedShort(): Int = readShort().toInt() and 0xFFFF

    fun readInt(): Int = readFixed(4).int

    fun readLong(): Long = readFixed(8).long

    fun readFloat(): Float = Float.fromBits(readInt())

    fun readDouble(): Double = Double.fromBits(readLong())

    fun readBoolean(): Boolean =
        when (val value = readUnsignedByte()) {
            0 -> false
            1 -> true
            else -> error("Invalid protocol Boolean byte $value")
        }

    fun readVarInt(): Int {
        var result = 0
        var shift = 0
        var count = 0
        while (shift < 35) {
            val current = readUnsignedByte()
            count++
            result = result or ((current and 0x7F) shl shift)
            if (current and 0x80 == 0) {
                check(count == varIntSize(result)) {
                    "Non-minimal VarInt encoding"
                }
                return result
            }
            shift += 7
        }
        error("VarInt is wider than five bytes")
    }

    fun readString(): String {
        val length = readVarInt()
        require(length in 0..MAXIMUM_STRING_BYTES) {
            "Invalid protocol string byte length $length"
        }
        val data = readBytes(length)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(data))
            .toString()
    }

    fun readModifiedUtf(): String {
        val length = readUnsignedShort()
        val encoded = readBytes(length)
        val framed = ByteArray(length + 2)
        framed[0] = (length ushr 8).toByte()
        framed[1] = length.toByte()
        encoded.copyInto(framed, destinationOffset = 2)
        return DataInputStream(ByteArrayInputStream(framed)).use {
            it.readUTF()
        }
    }

    fun <T> readList(readElement: PacketInput.() -> T): List<T> {
        val size = readVarInt()
        require(size in 0..MAXIMUM_COLLECTION_SIZE) {
            "Invalid protocol collection size $size"
        }
        return List(size) { readElement() }
    }

    fun readNullableNbt(): CapturedNbt? {
        if (!readBoolean()) return null
        val type = readUnsignedByte()
        check(type != NBT_END) {
            "A present registry-entry NBT value cannot be TAG_End"
        }
        return readNbtPayload(type, 0)
    }

    fun remainingBytes(): ByteArray =
        bytes.copyOfRange(position, bytes.size).also {
            position = bytes.size
        }

    fun requireExhausted() {
        check(position == bytes.size) {
            "Packet payload has ${bytes.size - position} unread byte(s)"
        }
    }

    private fun readNbtPayload(type: Int, depth: Int): CapturedNbt {
        require(depth <= MAXIMUM_NBT_DEPTH) {
            "NBT nesting exceeds $MAXIMUM_NBT_DEPTH"
        }
        return when (type) {
            1 -> NbtByteValue(readByte())
            2 -> NbtShortValue(readShort())
            3 -> NbtIntValue(readInt())
            4 -> NbtLongValue(readLong())
            5 -> NbtFloatValue(readFloat())
            6 -> NbtDoubleValue(readDouble())
            7 -> NbtByteArrayValue(
                List(readNbtLength("byte array")) { readByte() },
            )

            8 -> NbtStringValue(readModifiedUtf())
            9 -> {
                val elementType = readUnsignedByte()
                val size = readNbtLength("list")
                check(elementType != NBT_END || size == 0) {
                    "Non-empty NBT list has END element type"
                }
                NbtListValue(
                    elementType,
                    List(size) {
                        readNbtPayload(elementType, depth + 1)
                    },
                )
            }

            10 -> {
                val values = linkedMapOf<String, CapturedNbt>()
                while (true) {
                    val childType = readUnsignedByte()
                    if (childType == NBT_END) break
                    val name = readModifiedUtf()
                    check(
                        values.put(
                            name,
                            readNbtPayload(childType, depth + 1),
                        ) == null,
                    ) {
                        "NBT compound contains duplicate key $name"
                    }
                }
                NbtCompoundValue(values)
            }

            11 -> NbtIntArrayValue(
                List(readNbtLength("int array")) { readInt() },
            )

            12 -> NbtLongArrayValue(
                List(readNbtLength("long array")) { readLong() },
            )

            else -> error("Unknown NBT tag type $type")
        }
    }

    private fun readNbtLength(kind: String): Int =
        readInt().also { size ->
            require(size in 0..MAXIMUM_COLLECTION_SIZE) {
                "Invalid NBT $kind size $size"
            }
        }

    private fun readBytes(length: Int): ByteArray {
        requireAvailable(length)
        return bytes.copyOfRange(position, position + length).also {
            position += length
        }
    }

    private fun readFixed(length: Int): ByteBuffer =
        ByteBuffer.wrap(readBytes(length)).order(ByteOrder.BIG_ENDIAN)

    private fun requireAvailable(length: Int) {
        require(length >= 0 && bytes.size - position >= length) {
            "Truncated packet payload"
        }
    }
}

internal class PacketOutput {
    private val output = ByteArrayOutputStream()
    private val data = DataOutputStream(output)

    fun writeByte(value: Byte) {
        data.writeByte(value.toInt())
    }

    fun writeByte(value: Int) {
        data.writeByte(value)
    }

    fun writeShort(value: Short) {
        data.writeShort(value.toInt())
    }

    fun writeUnsignedShort(value: Int) {
        require(value in 0..0xFFFF)
        data.writeShort(value)
    }

    fun writeInt(value: Int) {
        data.writeInt(value)
    }

    fun writeLong(value: Long) {
        data.writeLong(value)
    }

    fun writeFloat(value: Float) {
        data.writeFloat(value)
    }

    fun writeDouble(value: Double) {
        data.writeDouble(value)
    }

    fun writeBoolean(value: Boolean) {
        data.writeByte(if (value) 1 else 0)
    }

    fun writeVarInt(value: Int) {
        var remaining = value
        do {
            var current = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) current = current or 0x80
            data.writeByte(current)
        } while (remaining != 0)
    }

    fun writeString(value: String) {
        val encoded = value.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAXIMUM_STRING_BYTES) {
            "Protocol string is too large"
        }
        writeVarInt(encoded.size)
        writeBytes(encoded)
    }

    fun writeModifiedUtf(value: String) {
        data.writeUTF(value)
    }

    fun writeBytes(value: ByteArray) {
        data.write(value)
    }

    fun writeNullableNbt(value: CapturedNbt?) {
        if (value == null) {
            writeBoolean(false)
        } else {
            writeBoolean(true)
            writeByte(value.typeId())
            value.writePayload(this)
        }
    }

    fun toByteArray(): ByteArray = output.toByteArray()
}

private object GradlePacketFraming {
    private const val MAXIMUM_FRAME_SIZE = 16 * 1_048_576

    fun writeFrame(
        output: OutputStream,
        packetData: ByteArray,
        compressionThreshold: Int?,
    ) {
        val body = if (compressionThreshold == null) {
            packetData
        } else {
            PacketOutput().apply {
                if (packetData.size < compressionThreshold) {
                    writeVarInt(0)
                    writeBytes(packetData)
                } else {
                    writeVarInt(packetData.size)
                    writeBytes(deflate(packetData))
                }
            }.toByteArray()
        }
        require(body.size in 1..MAXIMUM_FRAME_SIZE) {
            "Invalid outgoing frame size ${body.size}"
        }
        PacketOutput().apply {
            writeVarInt(body.size)
            writeBytes(body)
        }.toByteArray().let(output::write)
    }

    fun readFrame(
        input: InputStream,
        compressionThreshold: Int?,
    ): ByteArray {
        val frameLength = readVarInt(input)
        require(frameLength in 1..MAXIMUM_FRAME_SIZE) {
            "Invalid incoming frame size $frameLength"
        }
        val body = input.readExactly(frameLength)
        if (compressionThreshold == null) return body

        val cursor = PacketInput(body)
        val uncompressedLength = cursor.readVarInt()
        if (uncompressedLength == 0) {
            val packet = cursor.remainingBytes()
            require(packet.size < compressionThreshold) {
                "Uncompressed packet meets compression threshold"
            }
            return packet
        }
        require(
            uncompressedLength in compressionThreshold..MAXIMUM_FRAME_SIZE,
        ) {
            "Invalid declared uncompressed length $uncompressedLength"
        }
        return inflateExactly(
            cursor.remainingBytes(),
            uncompressedLength,
        )
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater()
        return try {
            deflater.setInput(input)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                check(count > 0) { "Deflater made no progress" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun inflateExactly(
        input: ByteArray,
        expectedLength: Int,
    ): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(input)
            val output = ByteArray(expectedLength)
            var position = 0
            while (position < output.size && !inflater.finished()) {
                val count = try {
                    inflater.inflate(
                        output,
                        position,
                        output.size - position,
                    )
                } catch (failure: DataFormatException) {
                    throw IllegalArgumentException(
                        "Invalid compressed packet",
                        failure,
                    )
                }
                if (count == 0) {
                    check(
                        !inflater.needsDictionary() &&
                                !inflater.needsInput(),
                    ) {
                        "Inflater made no progress"
                    }
                }
                position += count
            }
            check(position == expectedLength && inflater.finished()) {
                "Compressed packet produced $position bytes; expected " +
                        expectedLength
            }
            check(inflater.remaining == 0) {
                "Compressed packet has trailing bytes"
            }
            output
        } finally {
            inflater.end()
        }
    }

    private fun readVarInt(input: InputStream): Int {
        var result = 0
        var shift = 0
        var count = 0
        while (shift < 35) {
            val current = input.read()
            if (current < 0) throw EOFException("EOF while reading VarInt")
            count++
            result = result or ((current and 0x7F) shl shift)
            if (current and 0x80 == 0) {
                check(count == varIntSize(result)) {
                    "Non-minimal frame-length VarInt"
                }
                return result
            }
            shift += 7
        }
        error("VarInt is wider than five bytes")
    }

    private fun InputStream.readExactly(length: Int): ByteArray {
        val result = ByteArray(length)
        var position = 0
        while (position < length) {
            val count = read(result, position, length - position)
            if (count < 0) {
                throw EOFException(
                    "Expected $length bytes, received $position",
                )
            }
            if (count == 0) continue
            position += count
        }
        return result
    }
}

private class BoundedProcessLog(
    process: Process,
    threadName: String,
) : AutoCloseable {
    private val content = StringBuilder()
    private val thread = Thread.ofVirtual().name(threadName).start {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(content) {
                        content.appendLine(line)
                        if (content.length > 200_000) {
                            content.delete(
                                0,
                                content.length - 150_000,
                            )
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // Expected when process teardown closes the diagnostic stream.
        }
    }

    fun contains(text: String): Boolean =
        synchronized(content) { text in content }

    fun content(): String =
        synchronized(content) { content.toString() }

    override fun close() {
        thread.join(Duration.ofSeconds(5))
    }
}

private fun varIntSize(value: Int): Int {
    var remaining = value
    var size = 1
    while (remaining and 0x7F.inv() != 0) {
        size++
        remaining = remaining ushr 7
    }
    return size
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") {
        (it.toInt() and 0xFF).toString(16).padStart(2, '0')
    }

private const val HANDSHAKE = "handshake"
private const val LOGIN = "login"
private const val CONFIGURATION = "configuration"
private const val PLAY = "play"
private const val CLIENTBOUND = "clientbound"
private const val SERVERBOUND = "serverbound"
private const val NBT_END = 0
private const val MAXIMUM_STRING_BYTES = 1_048_576
private const val MAXIMUM_COLLECTION_SIZE = 1_048_576
private const val MAXIMUM_NBT_DEPTH = 512
