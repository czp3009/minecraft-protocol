package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.io.path.absolutePathString

/**
 * Captures Configuration data from the matching official server through both
 * branches of the Known Packs negotiation.
 *
 * This is a build-time oracle. Its generated Kotlin source contains exact
 * packet payloads and is consumed by protocol-vanilla-data at runtime.
 */
internal object OfficialVanillaDataGenerator {
    private const val COMPRESSION_THRESHOLD = 64
    private const val PAYLOAD_CHUNK_SIZE = 12_000

    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 5) {
            "Expected <analysis-java> <official-server.jar> <work-directory> " +
                    "<generated-kotlin> <manifest.json>"
        }
        val javaExecutable = Path.of(arguments[0]).toAbsolutePath().normalize()
        val serverJar = Path.of(arguments[1]).toAbsolutePath().normalize()
        val workDirectory = Path.of(arguments[2]).toAbsolutePath().normalize()
        val generatedKotlin = Path.of(arguments[3]).toAbsolutePath().normalize()
        val manifest = Path.of(arguments[4]).toAbsolutePath().normalize()
        require(Files.isRegularFile(javaExecutable)) {
            "Analysis Java does not exist: $javaExecutable"
        }
        require(Files.isRegularFile(serverJar)) {
            "Official server does not exist: $serverJar"
        }

        Files.createDirectories(workDirectory)
        Files.createDirectories(generatedKotlin.parent)
        Files.createDirectories(manifest.parent)
        val port = ServerSocket(0).use { it.localPort }
        writeServerConfiguration(workDirectory, port)

        val serverLog = StringBuilder()
        val process = ProcessBuilder(
            javaExecutable.absolutePathString(),
            "-Djava.awt.headless=true",
            "-jar",
            serverJar.absolutePathString(),
            "nogui",
        )
            .directory(workDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val logThread = Thread.ofVirtual().name("vanilla-data-capture-log").start {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    synchronized(serverLog) {
                        serverLog.appendLine(line)
                        if (serverLog.length > 200_000) {
                            serverLog.delete(0, serverLog.length - 150_000)
                        }
                    }
                }
            }
        }

        try {
            waitForServer(process, port, serverLog)
            val complete = captureConfiguration(
                port = port,
                name = "FullDataProbe",
                acceptKnownPacks = false,
            )
            val clientKnown = captureConfiguration(
                port = port,
                name = "KnownPackProbe",
                acceptKnownPacks = true,
            )
            validateCaptures(complete, clientKnown)
            val canonicalComplete = canonicalize(complete)
            val canonicalClientKnown = canonicalize(clientKnown)
            val snapshot = Snapshot(
                knownPacks = canonicalComplete.knownPacks,
                featureFlags = canonicalComplete.featureFlags,
                completeRegistries = canonicalComplete.registries,
                clientKnownRegistries = canonicalClientKnown.registries,
                tags = canonicalComplete.tags,
            )
            Files.writeString(generatedKotlin, renderKotlin(snapshot))
            Files.writeString(
                manifest,
                renderManifest(
                    serverJar = serverJar,
                    complete = canonicalComplete,
                    clientKnown = canonicalClientKnown,
                ),
            )
            println(
                "Captured ${snapshot.completeRegistries.size} registry packets, " +
                        "${snapshot.completeRegistries.sumOf { it.entries.size }} entries, " +
                        "and ${snapshot.tags.registries.sumOf { it.tags.size }} tags " +
                        "from official Minecraft ${MinecraftProtocol.MINECRAFT_VERSION}",
            )
        } catch (failure: Throwable) {
            val log = synchronized(serverLog) { serverLog.toString() }
            throw AssertionError(
                "Official vanilla-data capture failed.\n--- official server log ---\n$log",
                failure,
            )
        } finally {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
            logThread.join(Duration.ofSeconds(5))
        }
    }

    private fun writeServerConfiguration(workDirectory: Path, port: Int) {
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
            |motd=minecraft-protocol vanilla-data oracle
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
        serverLog: StringBuilder,
    ) {
        val deadline = System.nanoTime() + Duration.ofMinutes(2).toNanos()
        while (System.nanoTime() < deadline) {
            check(process.isAlive) {
                "Official server exited with ${process.exitValue()}: " +
                        synchronized(serverLog) { serverLog.toString() }
            }
            val ready = synchronized(serverLog) {
                serverLog.contains("[Server thread/INFO]: Done (")
            }
            if (!ready) {
                Thread.sleep(100)
                continue
            }
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), 250)
                    return
                }
            } catch (_: Exception) {
                Thread.sleep(100)
            }
        }
        error("Official server did not listen on port $port within two minutes")
    }

    private fun captureConfiguration(
        port: Int,
        name: String,
        acceptKnownPacks: Boolean,
    ): ConfigurationCapture {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 30_000
            val connection = FramedConnection(socket)
            connection.send(
                HandshakePacket(
                    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                    serverAddress = "127.0.0.1",
                    serverPort = port,
                    nextState = HandshakeNextState.LOGIN,
                ),
            )
            connection.send(
                LoginStartPacket(
                    name = name,
                    playerUuid = Uuid(0L, if (acceptKnownPacks) 2L else 1L),
                ),
            )

            var state = ConnectionState.LOGIN
            var knownPacks: ConfigurationClientboundKnownPacksPacket? = null
            var featureFlags: FeatureFlagsPacket? = null
            var tags: ConfigurationUpdateTagsPacket? = null
            val registries = mutableListOf<RegistryDataPacket>()
            val received = mutableListOf<String>()
            var playLoginReceived = false
            repeat(512) {
                if (playLoginReceived) return@repeat
                val packet = connection.receive(state)
                received += packet::class.simpleName ?: packet.toString()
                when (packet) {
                    is LoginDisconnectPacket ->
                        error("Official server rejected $name: ${packet.reason.json}")

                    is LoginCookieRequestPacket ->
                        connection.send(LoginCookieResponsePacket(packet.key, null))

                    is LoginPluginRequestPacket ->
                        connection.send(LoginPluginResponsePacket(packet.messageId, null))

                    is LoginSuccessPacket -> {
                        connection.send(LoginAcknowledgedPacket)
                        state = ConnectionState.CONFIGURATION
                        connection.send(
                            ConfigurationClientInformationPacket(
                                ClientInformation(
                                    locale = "en_us",
                                    viewDistance = 2,
                                    chatMode = ChatMode.ENABLED,
                                    chatColors = true,
                                    displayedSkinParts = 0x7F,
                                    mainHand = MainHand.RIGHT,
                                    enableTextFiltering = false,
                                    allowServerListings = true,
                                    particleStatus = ParticleStatus.ALL,
                                ),
                            ),
                        )
                    }

                    is ConfigurationDisconnectPacket ->
                        error("Official server rejected $name: ${packet.reason}")

                    is ConfigurationCookieRequestPacket ->
                        connection.send(ConfigurationCookieResponsePacket(packet.key, null))

                    is ConfigurationClientboundKeepAlivePacket ->
                        connection.send(ConfigurationServerboundKeepAlivePacket(packet.id))

                    is ConfigurationPingPacket ->
                        connection.send(ConfigurationPongPacket(packet.id))

                    is ConfigurationClientboundKnownPacksPacket -> {
                        check(knownPacks == null) {
                            "Official server sent Known Packs more than once"
                        }
                        knownPacks = packet
                        connection.send(
                            ConfigurationServerboundKnownPacksPacket(
                                if (acceptKnownPacks) packet.knownPacks else emptyList(),
                            ),
                        )
                    }

                    is FeatureFlagsPacket -> {
                        check(featureFlags == null) {
                            "Official server sent Feature Flags more than once"
                        }
                        featureFlags = packet
                    }

                    is RegistryDataPacket -> registries += packet
                    is ConfigurationUpdateTagsPacket -> {
                        check(tags == null) {
                            "Official server sent Update Tags more than once"
                        }
                        tags = packet
                    }

                    is CodeOfConductPacket ->
                        connection.send(AcceptCodeOfConductPacket)

                    is FinishConfigurationPacket -> {
                        connection.send(AcknowledgeFinishConfigurationPacket)
                        state = ConnectionState.PLAY
                    }

                    is PlayLoginPacket -> playLoginReceived = true
                    else -> Unit
                }
            }
            check(playLoginReceived) {
                "Official server never entered Play for $name; received $received"
            }
            check(connection.compressionThreshold == COMPRESSION_THRESHOLD) {
                "Official server negotiated ${connection.compressionThreshold}, " +
                        "expected $COMPRESSION_THRESHOLD"
            }
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
                receivedPackets = received,
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
        check(complete.knownPacks.knownPacks.isNotEmpty()) {
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
                    "${complete.registries.size} vs ${clientKnown.registries.size}"
        }
        complete.registries.zip(clientKnown.registries).forEach { (full, compact) ->
            check(full.registryId == compact.registryId) {
                "Registry order changed: ${full.registryId} vs ${compact.registryId}"
            }
            check(full.entries.map { it.id } == compact.entries.map { it.id }) {
                "Registry entries changed for ${full.registryId}"
            }
            check(full.entries.all { it.data != null }) {
                "Full-data capture omitted data in ${full.registryId}"
            }
            check(compact.entries.all { it.data == null }) {
                "Known-pack capture retained data in ${compact.registryId}"
            }
        }
    }

    /**
     * Vanilla registry maps can have different iteration orders across server
     * processes. Preserve all registry and entry orders because those define
     * runtime numeric IDs, while canonicalizing only order-insensitive NBT
     * compounds and tag sets before writing committed data.
     */
    private fun canonicalize(
        capture: ConfigurationCapture,
    ): ConfigurationCapture =
        capture.copy(
            featureFlags = FeatureFlagsPacket(
                capture.featureFlags.featureFlags
                    .sortedBy(Any::toString)
                    .toCollection(linkedSetOf()),
            ),
            registries = capture.registries.map { registry ->
                registry.copy(
                    entries = registry.entries.map { entry ->
                        entry.copy(data = entry.data?.canonicalize())
                    },
                )
            },
            tags = ConfigurationUpdateTagsPacket(
                capture.tags.registries
                    .sortedBy { it.registry.toString() }
                    .map { registry ->
                        registry.copy(
                            tags = registry.tags
                                .sortedBy { it.name.toString() }
                                .map { tag ->
                                    tag.copy(entries = tag.entries.sorted())
                                },
                        )
                    },
            ),
        )

    private fun NbtTag.canonicalize(): NbtTag =
        when (this) {
            is NbtCompound -> NbtCompound(
                value.entries
                    .sortedBy(Map.Entry<String, NbtTag>::key)
                    .associateTo(linkedMapOf()) { (key, tag) ->
                        key to tag.canonicalize()
                    },
            )

            is NbtList -> NbtList(value.map { it.canonicalize() })
            else -> this
        }

    private fun renderKotlin(snapshot: Snapshot): String = buildString {
        appendLine("package com.hiczp.minecraft.protocol.data")
        appendLine()
        appendLine("/** Exact official-server packet payloads; regenerated by Gradle. */")
        appendLine("internal object VanillaConfigurationPayloads {")
        appendLine(
            "    const val minecraftVersion: String = " +
                    quote(MinecraftProtocol.MINECRAFT_VERSION),
        )
        appendLine(
            "    const val protocolVersion: Int = ${MinecraftProtocol.PROTOCOL_VERSION}",
        )
        appendPayload("knownPacks", snapshot.knownPacks)
        appendPayload("featureFlags", snapshot.featureFlags)
        appendPayloadList("completeRegistries", snapshot.completeRegistries)
        appendPayloadList("clientKnownRegistries", snapshot.clientKnownRegistries)
        appendPayload("tags", snapshot.tags)
        appendLine("}")
    }

    private fun StringBuilder.appendPayload(name: String, packet: Packet) {
        val payload = MinecraftPacketRegistry.encodePayload(packet).payload
        appendLine()
        appendLine("    val $name: List<String> = listOf(")
        appendBase64Chunks(payload, "        ")
        appendLine("    )")
    }

    private fun StringBuilder.appendPayloadList(
        name: String,
        packets: List<Packet>,
    ) {
        appendLine()
        appendLine("    val $name: List<List<String>> = listOf(")
        packets.forEach { packet ->
            val payload = MinecraftPacketRegistry.encodePayload(packet).payload
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
        val encoded = Base64.getEncoder().encodeToString(payload)
        encoded.chunked(PAYLOAD_CHUNK_SIZE).forEach { chunk ->
            append(indentation)
            append(quote(chunk))
            appendLine(",")
        }
    }

    private fun renderManifest(
        serverJar: Path,
        complete: ConfigurationCapture,
        clientKnown: ConfigurationCapture,
    ): String = buildString {
        val serverSha256 = sha256(Files.readAllBytes(serverJar))
        appendLine("{")
        appendLine("  \"schema_version\": 1,")
        appendLine(
            "  \"minecraft_version\": ${quote(MinecraftProtocol.MINECRAFT_VERSION)},",
        )
        appendLine("  \"protocol_version\": ${MinecraftProtocol.PROTOCOL_VERSION},")
        appendLine("  \"official_server_sha256\": ${quote(serverSha256)},")
        appendLine("  \"known_packs\": [")
        complete.knownPacks.knownPacks.forEachIndexed { index, pack ->
            append("    {\"namespace\": ${quote(pack.namespace)}, ")
            append("\"id\": ${quote(pack.id)}, ")
            append("\"version\": ${quote(pack.version)}}")
            appendLine(if (index == complete.knownPacks.knownPacks.lastIndex) "" else ",")
        }
        appendLine("  ],")
        appendLine("  \"feature_flags\": [")
        complete.featureFlags.featureFlags.forEachIndexed { index, flag ->
            append("    ${quote(flag.toString())}")
            appendLine(
                if (index == complete.featureFlags.featureFlags.size - 1) "" else ",",
            )
        }
        appendLine("  ],")
        appendLine("  \"registries\": [")
        complete.registries.zip(clientKnown.registries)
            .forEachIndexed { index, (full, compact) ->
                val entryIdsSha256 = sha256(
                    full.entries
                        .joinToString(separator = "\n") { it.id.toString() }
                        .encodeToByteArray(),
                )
                appendLine("    {")
                appendLine("      \"id\": ${quote(full.registryId.toString())},")
                appendLine("      \"entry_count\": ${full.entries.size},")
                appendLine(
                    "      \"entry_ids_sha256\": " +
                            "${quote(entryIdsSha256)},",
                )
                appendLine(
                    "      \"full_payload_sha256\": " +
                            "${quote(packetPayloadSha256(full))},",
                )
                appendLine(
                    "      \"known_pack_payload_sha256\": " +
                            "${quote(packetPayloadSha256(compact))},",
                )
                appendLine(
                    "      \"known_pack_omits_entry_data\": " +
                            compact.entries.all { it.data == null },
                )
                append("    }")
                appendLine(if (index == complete.registries.lastIndex) "" else ",")
            }
        appendLine("  ],")
        appendLine("  \"tags\": [")
        complete.tags.registries.forEachIndexed { index, registry ->
            append("    {\"registry\": ${quote(registry.registry.toString())}, ")
            append("\"tag_count\": ${registry.tags.size}, ")
            append(
                "\"entries_count\": " +
                        "${registry.tags.sumOf { it.entries.size }}}",
            )
            appendLine(if (index == complete.tags.registries.lastIndex) "" else ",")
        }
        appendLine("  ],")
        appendLine(
            "  \"packet_sequence_full\": " +
                    renderJsonStringList(complete.receivedPackets) + ",",
        )
        appendLine(
            "  \"packet_sequence_known_packs\": " +
                    renderJsonStringList(clientKnown.receivedPackets),
        )
        appendLine("}")
    }

    private fun packetPayloadSha256(packet: Packet): String =
        sha256(MinecraftPacketRegistry.encodePayload(packet).payload)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun renderJsonStringList(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]") { quote(it) }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else ->
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
            }
        }
        append('"')
    }

    private data class ConfigurationCapture(
        val knownPacks: ConfigurationClientboundKnownPacksPacket,
        val featureFlags: FeatureFlagsPacket,
        val registries: List<RegistryDataPacket>,
        val tags: ConfigurationUpdateTagsPacket,
        val receivedPackets: List<String>,
    )

    private data class Snapshot(
        val knownPacks: ConfigurationClientboundKnownPacksPacket,
        val featureFlags: FeatureFlagsPacket,
        val completeRegistries: List<RegistryDataPacket>,
        val clientKnownRegistries: List<RegistryDataPacket>,
        val tags: ConfigurationUpdateTagsPacket,
    )

    private class FramedConnection(socket: Socket) {
        private val input = BufferedInputStream(socket.getInputStream())
        private val output = BufferedOutputStream(socket.getOutputStream())
        private val format = MinecraftFormat(
            MinecraftFormatConfiguration(chunkSectionCount = 24),
        )

        var compressionThreshold: Int? = null
            private set

        fun send(packet: Packet) {
            val encoded = MinecraftPacketRegistry.encodePayload(packet, format)
            val body = ByteArrayOutput()
            body.writeVarInt(encoded.key.id)
            body.write(encoded.payload)
            TestPacketFraming.writeFrame(output, body.toByteArray(), compressionThreshold)
            output.flush()
        }

        fun receive(state: ConnectionState): Packet {
            val frame = TestPacketFraming.readFrame(input, compressionThreshold)
            val cursor = ByteArrayInput(frame)
            val packetId = cursor.readVarInt()
            val payload = cursor.remainingBytes()
            val packet = try {
                MinecraftPacketRegistry.decodePayload(
                    state = state,
                    direction = PacketDirection.CLIENTBOUND,
                    id = packetId,
                    payload = payload,
                    format = format,
                )
            } catch (failure: Throwable) {
                throw IllegalStateException(
                    "Could not decode $state clientbound packet " +
                            "0x${packetId.toString(16)} payload=${payload.toHex()}",
                    failure,
                )
            }
            val reencoded = MinecraftPacketRegistry.encodePayload(packet, format)
            check(reencoded.key.id == packetId && reencoded.payload.contentEquals(payload)) {
                "Official $state packet 0x${packetId.toString(16)} did not " +
                        "round-trip byte-for-byte through the Kotlin codec"
            }
            if (packet is SetCompressionPacket) {
                compressionThreshold = packet.threshold
            }
            return packet
        }
    }

    private class ByteArrayOutput {
        private var bytes = ByteArray(32)
        private var size = 0

        fun writeVarInt(value: Int) {
            var remaining = value
            do {
                var current = remaining and 0x7F
                remaining = remaining ushr 7
                if (remaining != 0) current = current or 0x80
                write(current)
            } while (remaining != 0)
        }

        fun write(value: ByteArray) {
            ensure(size + value.size)
            value.copyInto(bytes, destinationOffset = size)
            size += value.size
        }

        private fun write(value: Int) {
            ensure(size + 1)
            bytes[size++] = value.toByte()
        }

        private fun ensure(required: Int) {
            if (required > bytes.size) {
                bytes = bytes.copyOf(maxOf(required, bytes.size * 2))
            }
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }

    private class ByteArrayInput(
        private val bytes: ByteArray,
    ) {
        private var position = 0

        fun readVarInt(): Int {
            var result = 0
            var shift = 0
            while (shift < 35) {
                check(position < bytes.size) { "Truncated VarInt" }
                val current = bytes[position++].toInt() and 0xFF
                result = result or ((current and 0x7F) shl shift)
                if (current and 0x80 == 0) return result
                shift += 7
            }
            error("VarInt is wider than five bytes")
        }

        fun remainingBytes(): ByteArray = bytes.copyOfRange(position, bytes.size)
    }

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
}
