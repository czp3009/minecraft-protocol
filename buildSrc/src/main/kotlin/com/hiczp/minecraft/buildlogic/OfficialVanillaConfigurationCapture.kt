package com.hiczp.minecraft.buildlogic

import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.KModifier.INTERNAL
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import kotlinx.serialization.json.*
import java.io.*
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
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.concurrent.withLock
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
                "Official packets report has no $state/$direction/$name packet",
            )

    fun clientboundName(state: String, id: Int): String =
        clientboundNames[state to id]
            ?: error(
                "Official packets report has no $state/clientbound packet 0x${id.toString(16)}",
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
                        val id = packetElement.jsonObject.getValue("protocol_id").jsonPrimitive.int
                        check(id >= 0) {
                            "Official packet $state/$direction/$name has a negative protocol ID"
                        }
                        val officialPacketKey = OfficialPacketKey(state, direction, name)
                        check(entries.put(officialPacketKey, id) == null) {
                            "Duplicate official packet $officialPacketKey"
                        }
                        if (direction == CLIENTBOUND) {
                            check(
                                clientbound.put(state to id, name) == null,
                            ) {
                                "Duplicate official packet ID $state/clientbound/$id"
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
    private const val MAXIMUM_BIND_ATTEMPTS = 5

    fun capture(
        serverJar: Path,
        workDirectory: Path,
        minecraftProtocolTarget: MinecraftProtocolTarget,
        officialPacketIds: OfficialPacketIds,
    ): VanillaConfigurationCaptureResult {
        require(serverJar.isRegularFile()) {
            "Official server does not exist: $serverJar"
        }

        workDirectory.deleteTree()
        workDirectory.createDirectories()
        var lastBindFailure: Throwable? = null
        for (attempt in 1..MAXIMUM_BIND_ATTEMPTS) {
            val attemptDirectory = workDirectory.resolve("attempt-$attempt")
            attemptDirectory.createDirectories()
            val port = ServerSocket(0).use { it.localPort }
            writeServerConfiguration(attemptDirectory, port)
            try {
                return captureFromRunningServer(
                    serverJar = serverJar,
                    workDirectory = attemptDirectory,
                    port = port,
                    minecraftProtocolTarget = minecraftProtocolTarget,
                    officialPacketIds = officialPacketIds,
                )
            } catch (failure: Throwable) {
                if (
                    attempt == MAXIMUM_BIND_ATTEMPTS ||
                    !failure.isPortBindFailure()
                ) {
                    throw failure
                }
                lastBindFailure = failure
            }
        }
        throw AssertionError(
            "Official server could not acquire a loopback port after $MAXIMUM_BIND_ATTEMPTS attempts",
            lastBindFailure,
        )
    }

    private fun captureFromRunningServer(
        serverJar: Path,
        workDirectory: Path,
        port: Int,
        minecraftProtocolTarget: MinecraftProtocolTarget,
        officialPacketIds: OfficialPacketIds,
    ): VanillaConfigurationCaptureResult {
        val process = ProcessBuilder(
            "java",
            JvmProcessArguments.ENABLE_NATIVE_ACCESS_ALL_UNNAMED,
            "-Djava.awt.headless=true",
            "-Djoml.nounsafe=true",
            "-jar",
            serverJar.absolutePathString(),
            "nogui",
        )
            .directory(workDirectory.toFile())
            .redirectErrorStream(true)
            .start()
        val boundedProcessLog = BoundedProcessLog(process, "vanilla-data-capture-log")
        try {
            waitForServer(process, boundedProcessLog)
            val completeConfigurationCapture = captureConfiguration(
                port = port,
                name = "FullDataProbe",
                acceptedKnownPacksPayload = null,
                protocolVersion = minecraftProtocolTarget.protocolVersion,
                officialPacketIds = officialPacketIds,
            ).canonicalize()
            val knownPackConfigurationCapture = captureConfiguration(
                port = port,
                name = "KnownPackProbe",
                acceptedKnownPacksPayload = completeConfigurationCapture.offeredKnownPacksPayload,
                protocolVersion = minecraftProtocolTarget.protocolVersion,
                officialPacketIds = officialPacketIds,
            ).canonicalize()
            return VanillaConfigurationCaptureResult(
                offeredKnownPacksPayload = completeConfigurationCapture.offeredKnownPacksPayload,
                enabledFeatureFlagsPayload = completeConfigurationCapture.enabledFeatureFlagsPayload,
                completeSynchronizedRegistryPayloads = completeConfigurationCapture.synchronizedRegistryPayloads,
                knownPackSynchronizedRegistryPayloads = knownPackConfigurationCapture.synchronizedRegistryPayloads,
                registryTagsPayload = completeConfigurationCapture.registryTagsPayload,
                completeConfigurationPacketSequence = completeConfigurationCapture.receivedPacketRoutes,
                knownPackConfigurationPacketSequence = knownPackConfigurationCapture.receivedPacketRoutes,
            )
        } catch (failure: Throwable) {
            throw AssertionError(
                "Official vanilla-data capture failed.\n--- official server log ---\n${boundedProcessLog.content()}",
                failure,
            )
        } finally {
            process.destroy()
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(10, TimeUnit.SECONDS)
            }
            boundedProcessLog.close()
        }
    }

    private fun Throwable.isPortBindFailure(): Boolean {
        val markers = listOf(
            "failed to bind to port",
            "address already in use",
            "bindexception",
        )
        return generateSequence(this) { it.cause }
            .mapNotNull { throwable -> throwable.message }
            .any { message -> markers.any { marker -> message.contains(marker, ignoreCase = true) } }
    }

    private fun captureConfiguration(
        port: Int,
        name: String,
        acceptedKnownPacksPayload: KnownPacksPayload?,
        protocolVersion: Int,
        officialPacketIds: OfficialPacketIds,
    ): ConfigurationCapture {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 30_000
            val captureConnection = CaptureConnection(socket, officialPacketIds)
            captureConnection.send(
                state = HANDSHAKE,
                name = "intention",
                payload = PacketOutput().apply {
                    writeVarInt(protocolVersion)
                    writeString("127.0.0.1")
                    writeUnsignedShort(port)
                    writeVarInt(2)
                }.toByteArray(),
            )
            captureConnection.send(
                state = LOGIN,
                name = "hello",
                payload = PacketOutput().apply {
                    writeString(name)
                    writeLong(0)
                    writeLong(if (acceptedKnownPacksPayload == null) 1 else 2)
                }.toByteArray(),
            )

            var state = LOGIN
            var offeredKnownPacksPayload: KnownPacksPayload? = null
            var enabledFeatureFlagsPayload: FeatureFlagsPayload? = null
            var registryTagsPayload: TagsPayload? = null
            val synchronizedRegistryPayloads = mutableListOf<RegistryPayload>()
            val receivedPacketRoutes = mutableListOf<String>()
            repeat(MAXIMUM_PACKETS) {
                val capturedPacket = captureConnection.receive(state)
                receivedPacketRoutes += "$state/clientbound/${capturedPacket.name}"
                when (state to capturedPacket.name) {
                    LOGIN to "login_disconnect" ->
                        error(
                            "Official server rejected $name: ${capturedPacket.payload.toHexString()}",
                        )

                    LOGIN to "cookie_request" -> {
                        val packetInput = capturedPacket.input()
                        val key = packetInput.readString()
                        packetInput.requireExhausted()
                        captureConnection.send(
                            LOGIN,
                            "cookie_response",
                            PacketOutput().apply {
                                writeString(key)
                                writeBoolean(false)
                            }.toByteArray(),
                        )
                    }

                    LOGIN to "custom_query" -> {
                        val packetInput = capturedPacket.input()
                        val messageId = packetInput.readVarInt()
                        captureConnection.send(
                            LOGIN,
                            "custom_query_answer",
                            PacketOutput().apply {
                                writeVarInt(messageId)
                                writeBoolean(false)
                            }.toByteArray(),
                        )
                    }

                    LOGIN to "login_compression" -> {
                        val packetInput = capturedPacket.input()
                        val threshold = packetInput.readVarInt()
                        packetInput.requireExhausted()
                        captureConnection.compressionThreshold = threshold
                    }

                    LOGIN to "login_finished" -> {
                        captureConnection.send(LOGIN, "login_acknowledged")
                        state = CONFIGURATION
                        captureConnection.send(
                            CONFIGURATION,
                            "client_information",
                            clientInformationPayload(),
                        )
                    }

                    CONFIGURATION to "disconnect" ->
                        error(
                            "Official server rejected $name: ${capturedPacket.payload.toHexString()}",
                        )

                    CONFIGURATION to "cookie_request" -> {
                        val packetInput = capturedPacket.input()
                        val key = packetInput.readString()
                        packetInput.requireExhausted()
                        captureConnection.send(
                            CONFIGURATION,
                            "cookie_response",
                            PacketOutput().apply {
                                writeString(key)
                                writeBoolean(false)
                            }.toByteArray(),
                        )
                    }

                    CONFIGURATION to "keep_alive" -> {
                        val packetInput = capturedPacket.input()
                        val id = packetInput.readLong()
                        packetInput.requireExhausted()
                        captureConnection.send(
                            CONFIGURATION,
                            "keep_alive",
                            PacketOutput().apply {
                                writeLong(id)
                            }.toByteArray(),
                        )
                    }

                    CONFIGURATION to "ping" -> {
                        val packetInput = capturedPacket.input()
                        val id = packetInput.readInt()
                        packetInput.requireExhausted()
                        captureConnection.send(
                            CONFIGURATION,
                            "pong",
                            PacketOutput().apply {
                                writeInt(id)
                            }.toByteArray(),
                        )
                    }

                    CONFIGURATION to "select_known_packs" -> {
                        check(offeredKnownPacksPayload == null) {
                            "Official server sent Known Packs more than once"
                        }
                        offeredKnownPacksPayload = KnownPacksPayload.decode(capturedPacket.payload)
                        captureConnection.send(
                            CONFIGURATION,
                            "select_known_packs",
                            if (acceptedKnownPacksPayload == null) {
                                PacketOutput().apply {
                                    writeVarInt(0)
                                }.toByteArray()
                            } else {
                                acceptedKnownPacksPayload.encode()
                            },
                        )
                    }

                    CONFIGURATION to "update_enabled_features" -> {
                        check(enabledFeatureFlagsPayload == null) {
                            "Official server sent Feature Flags more than once"
                        }
                        enabledFeatureFlagsPayload = FeatureFlagsPayload.decode(capturedPacket.payload)
                    }

                    CONFIGURATION to "registry_data" ->
                        synchronizedRegistryPayloads += RegistryPayload.decode(capturedPacket.payload)

                    CONFIGURATION to "update_tags" -> {
                        check(registryTagsPayload == null) {
                            "Official server sent Update Tags more than once"
                        }
                        registryTagsPayload = TagsPayload.decode(capturedPacket.payload)
                    }

                    CONFIGURATION to "code_of_conduct" ->
                        captureConnection.send(
                            CONFIGURATION,
                            "accept_code_of_conduct",
                        )

                    CONFIGURATION to "finish_configuration" -> {
                        captureConnection.send(
                            CONFIGURATION,
                            "finish_configuration",
                        )
                        state = PLAY
                    }

                    PLAY to "login" ->
                        return ConfigurationCapture(
                            offeredKnownPacksPayload = checkNotNull(offeredKnownPacksPayload) {
                                "Official server did not offer Known Packs"
                            },
                            enabledFeatureFlagsPayload = checkNotNull(enabledFeatureFlagsPayload) {
                                "Official server did not send Feature Flags"
                            },
                            synchronizedRegistryPayloads = synchronizedRegistryPayloads.toList(),
                            registryTagsPayload = checkNotNull(registryTagsPayload) {
                                "Official server did not send Update Tags"
                            },
                            receivedPacketRoutes = receivedPacketRoutes.toList(),
                        )
                }
            }
            error(
                "Official server never entered Play for $name; received $receivedPacketRoutes",
            )
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
        boundedProcessLog: BoundedProcessLog,
    ) {
        boundedProcessLog.awaitContains(
            text = "[Server thread/INFO]: Done (",
            process = process,
            timeout = Duration.ofMinutes(2),
        )
    }
}

internal data class VanillaConfigurationCaptureResult(
    val offeredKnownPacksPayload: KnownPacksPayload,
    val enabledFeatureFlagsPayload: FeatureFlagsPayload,
    val completeSynchronizedRegistryPayloads: List<RegistryPayload>,
    val knownPackSynchronizedRegistryPayloads: List<RegistryPayload>,
    val registryTagsPayload: TagsPayload,
    val completeConfigurationPacketSequence: List<String>,
    val knownPackConfigurationPacketSequence: List<String>,
) {
    init {
        require(completeSynchronizedRegistryPayloads.all { registryPayload ->
            registryPayload.registryEntryPayloads.all { registryEntryPayload ->
                registryEntryPayload.registryEntryData != null
            }
        }) {
            "A complete Configuration registry capture must include every entry's data"
        }
    }

    fun renderKotlin(): FileSpec {
        val vanillaConfigurationPacketPayloads = TypeSpec.objectBuilder("VanillaConfigurationPacketPayloads")
            .addModifiers(INTERNAL)
            .addKdoc(
                "Exact official-server packet payloads; regenerated by Gradle.\n",
            )
            .addPayload("offeredKnownPacksPayloadChunks", offeredKnownPacksPayload.encode())
            .addPayload("enabledFeatureFlagsPayloadChunks", enabledFeatureFlagsPayload.encode())
            .addPayloadList(
                "completeSynchronizedRegistryPacketPayloadChunks",
                completeSynchronizedRegistryPayloads.map(RegistryPayload::encode),
            )
            .addPayloadList(
                "knownPackSynchronizedRegistryPacketPayloadChunks",
                knownPackSynchronizedRegistryPayloads.map(RegistryPayload::encode),
            )
            .addPayload("registryTagsPayloadChunks", registryTagsPayload.encode())
            .build()
        return FileSpec.builder(
            "com.hiczp.minecraft.protocol.datapack.vanilla",
            "VanillaConfigurationPacketPayloads",
        ).addType(vanillaConfigurationPacketPayloads)
            .build()
    }

    fun toAnalysisJson(): JsonObject = buildJsonObject {
        put("schema_version", 2)
        put(
            "complete_registries",
            JsonArray(completeSynchronizedRegistryPayloads.map(RegistryPayload::toAnalysisJson)),
        )
        put(
            "known_pack_registries",
            JsonArray(knownPackSynchronizedRegistryPayloads.map(RegistryPayload::toAnalysisJson)),
        )
        putJsonObject("payloads") {
            put("known_packs_base64", Base64.getEncoder().encodeToString(offeredKnownPacksPayload.encode()))
            put("feature_flags_base64", Base64.getEncoder().encodeToString(enabledFeatureFlagsPayload.encode()))
            put("tags_base64", Base64.getEncoder().encodeToString(registryTagsPayload.encode()))
        }
        putJsonArray("packet_sequence_full") {
            completeConfigurationPacketSequence.forEach { add(it) }
        }
        putJsonArray("packet_sequence_known_packs") {
            knownPackConfigurationPacketSequence.forEach { add(it) }
        }
    }

    companion object {
        private const val PAYLOAD_CHUNK_SIZE = 12_000
        private val LIST_OF = MemberName("kotlin.collections", "listOf")

        fun fromAnalysisJson(
            document: JsonObject,
        ): VanillaConfigurationCaptureResult {
            check(document.getValue("schema_version").jsonPrimitive.int == 2) {
                "Unsupported vanilla Configuration analysis schema"
            }
            val configurationPayloads = document.getValue("payloads").jsonObject
            val offeredKnownPacksPayloadBytes = Base64.getDecoder().decode(
                configurationPayloads.getValue("known_packs_base64").jsonPrimitive.content,
            )
            val enabledFeatureFlagsPayloadBytes = Base64.getDecoder().decode(
                configurationPayloads.getValue("feature_flags_base64").jsonPrimitive.content,
            )
            val registryTagsPayloadBytes = Base64.getDecoder().decode(
                configurationPayloads.getValue("tags_base64").jsonPrimitive.content,
            )
            val offeredKnownPacksPayload = KnownPacksPayload.decode(offeredKnownPacksPayloadBytes)
            val enabledFeatureFlagsPayload = FeatureFlagsPayload.decode(enabledFeatureFlagsPayloadBytes)

            val completeSynchronizedRegistryPayloads = decodeRegistryPayloads(
                document = document,
                field = "complete_registries",
            )
            val knownPackSynchronizedRegistryPayloads = decodeRegistryPayloads(
                document = document,
                field = "known_pack_registries",
            )

            val registryTagsPayload = TagsPayload.decode(registryTagsPayloadBytes)
            return VanillaConfigurationCaptureResult(
                offeredKnownPacksPayload = offeredKnownPacksPayload,
                enabledFeatureFlagsPayload = enabledFeatureFlagsPayload,
                completeSynchronizedRegistryPayloads = completeSynchronizedRegistryPayloads,
                knownPackSynchronizedRegistryPayloads = knownPackSynchronizedRegistryPayloads,
                registryTagsPayload = registryTagsPayload,
                completeConfigurationPacketSequence = document.getValue("packet_sequence_full").jsonArray
                    .map { it.jsonPrimitive.content },
                knownPackConfigurationPacketSequence = document.getValue("packet_sequence_known_packs").jsonArray
                    .map { it.jsonPrimitive.content },
            )
        }

    }

    private fun TypeSpec.Builder.addPayload(
        name: String,
        packetPayloadBytes: ByteArray,
    ): TypeSpec.Builder = addProperty(
        PropertySpec.builder(name, LIST.parameterizedBy(STRING))
            .initializer(packetPayloadChunksInitializer(packetPayloadBytes))
            .build(),
    )

    private fun TypeSpec.Builder.addPayloadList(
        name: String,
        packetPayloads: List<ByteArray>,
    ): TypeSpec.Builder = addProperty(
        PropertySpec.builder(
            name,
            LIST.parameterizedBy(LIST.parameterizedBy(STRING)),
        ).initializer(
            CodeBlock.builder()
                .add("%M(\n", LIST_OF)
                .indent()
                .apply {
                    packetPayloads.forEach { packetPayloadBytes ->
                        add("%L,\n", packetPayloadChunksInitializer(packetPayloadBytes))
                    }
                }
                .unindent()
                .add(")")
                .build(),
        ).build(),
    )

    private fun packetPayloadChunksInitializer(
        packetPayloadBytes: ByteArray,
    ): CodeBlock = CodeBlock.builder()
        .add("%M(\n", LIST_OF)
        .indent()
        .apply {
            Base64.getEncoder().encodeToString(packetPayloadBytes)
                .chunked(PAYLOAD_CHUNK_SIZE)
                .forEach { packetPayloadChunk -> add("%S,\n", packetPayloadChunk) }
        }
        .unindent()
        .add(")")
        .build()
}

private fun RegistryPayload.toAnalysisJson(): JsonObject = buildJsonObject {
    put("payload_base64", Base64.getEncoder().encodeToString(encode()))
}

private fun decodeRegistryPayloads(
    document: JsonObject,
    field: String,
): List<RegistryPayload> = document.getValue(field).jsonArray.map { jsonElement ->
    val registryIndexJson = jsonElement.jsonObject
    val registryPayloadBytes = Base64.getDecoder().decode(
        registryIndexJson.getValue("payload_base64").jsonPrimitive.content,
    )
    RegistryPayload.decode(registryPayloadBytes)
}

internal data class KnownPackPayload(
    val namespace: String,
    val id: String,
    val version: String,
)

internal data class KnownPacksPayload(
    val knownPacks: List<KnownPackPayload>,
) {
    fun encode(): ByteArray = PacketOutput().apply {
        writeVarInt(knownPacks.size)
        knownPacks.forEach { knownPackPayload ->
            writeString(knownPackPayload.namespace)
            writeString(knownPackPayload.id)
            writeString(knownPackPayload.version)
        }
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): KnownPacksPayload {
            val packetInput = PacketInput(payload)
            val knownPacks = packetInput.readList {
                KnownPackPayload(
                    namespace = readString(),
                    id = readString(),
                    version = readString(),
                )
            }
            packetInput.requireExhausted()
            return KnownPacksPayload(knownPacks)
        }
    }
}

internal data class FeatureFlagsPayload(
    val enabledFeatureFlags: List<String>,
) {
    fun canonicalize(): FeatureFlagsPayload =
        FeatureFlagsPayload(enabledFeatureFlags.sorted())

    fun encode(): ByteArray = PacketOutput().apply {
        writeVarInt(enabledFeatureFlags.size)
        enabledFeatureFlags.forEach(::writeString)
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): FeatureFlagsPayload {
            val packetInput = PacketInput(payload)
            val enabledFeatureFlags = packetInput.readList(PacketInput::readString)
            packetInput.requireExhausted()
            return FeatureFlagsPayload(enabledFeatureFlags)
        }
    }
}

internal data class RegistryEntryPayload(
    val registryEntryId: String,
    val registryEntryData: CapturedNbt?,
)

internal data class RegistryPayload(
    val registryId: String,
    val registryEntryPayloads: List<RegistryEntryPayload>,
) {
    fun canonicalize(): RegistryPayload =
        copy(
            registryEntryPayloads = registryEntryPayloads.map { registryEntryPayload ->
                registryEntryPayload.copy(
                    registryEntryData = registryEntryPayload.registryEntryData?.canonicalize(),
                )
            },
        )

    fun encode(): ByteArray = PacketOutput().apply {
        writeString(registryId)
        writeVarInt(registryEntryPayloads.size)
        registryEntryPayloads.forEach { registryEntryPayload ->
            writeString(registryEntryPayload.registryEntryId)
            writeNullableNbt(registryEntryPayload.registryEntryData)
        }
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): RegistryPayload {
            val packetInput = PacketInput(payload)
            val registryId = packetInput.readString()
            val registryEntryPayloads = packetInput.readList {
                RegistryEntryPayload(
                    registryEntryId = readString(),
                    registryEntryData = readNullableNbt(),
                )
            }
            packetInput.requireExhausted()
            return RegistryPayload(registryId, registryEntryPayloads)
        }
    }
}

internal data class TagPayload(
    val tagId: String,
    val rawIds: List<Int>,
)

internal data class RegistryTagsPayload(
    val registryId: String,
    val tagPayloads: List<TagPayload>,
)

internal data class TagsPayload(
    val registryTagsPayloads: List<RegistryTagsPayload>,
) {
    fun canonicalize(): TagsPayload =
        TagsPayload(
            registryTagsPayloads.sortedBy(RegistryTagsPayload::registryId)
                .map { registryTagsPayload ->
                    registryTagsPayload.copy(
                        tagPayloads = registryTagsPayload.tagPayloads.sortedBy(TagPayload::tagId)
                            .map { tagPayload ->
                                tagPayload.copy(rawIds = tagPayload.rawIds.sorted())
                            },
                    )
                },
        )

    fun encode(): ByteArray = PacketOutput().apply {
        writeVarInt(registryTagsPayloads.size)
        registryTagsPayloads.forEach { registryTagsPayload ->
            writeString(registryTagsPayload.registryId)
            writeVarInt(registryTagsPayload.tagPayloads.size)
            registryTagsPayload.tagPayloads.forEach { tagPayload ->
                writeString(tagPayload.tagId)
                writeVarInt(tagPayload.rawIds.size)
                tagPayload.rawIds.forEach(::writeVarInt)
            }
        }
    }.toByteArray()

    companion object {
        fun decode(payload: ByteArray): TagsPayload {
            val packetInput = PacketInput(payload)
            val registryTagsPayloads = packetInput.readList {
                val registryId = readString()
                val tagPayloads = readList {
                    TagPayload(
                        tagId = readString(),
                        rawIds = readList(PacketInput::readVarInt),
                    )
                }
                RegistryTagsPayload(registryId, tagPayloads)
            }
            packetInput.requireExhausted()
            return TagsPayload(registryTagsPayloads)
        }
    }
}

private data class ConfigurationCapture(
    val offeredKnownPacksPayload: KnownPacksPayload,
    val enabledFeatureFlagsPayload: FeatureFlagsPayload,
    val synchronizedRegistryPayloads: List<RegistryPayload>,
    val registryTagsPayload: TagsPayload,
    val receivedPacketRoutes: List<String>,
) {
    fun canonicalize(): ConfigurationCapture =
        copy(
            enabledFeatureFlagsPayload = enabledFeatureFlagsPayload.canonicalize(),
            synchronizedRegistryPayloads = synchronizedRegistryPayloads.map(RegistryPayload::canonicalize),
            registryTagsPayload = registryTagsPayload.canonicalize(),
        )
}

private data class CapturedPacket(
    val name: String,
    val payload: ByteArray,
) {
    fun input(): PacketInput = PacketInput(payload)

    override fun equals(other: Any?): Boolean =
        other is CapturedPacket && name == other.name && payload.contentEquals(other.payload)

    override fun hashCode(): Int = 31 * name.hashCode() + payload.contentHashCode()
}

private class CaptureConnection(
    socket: Socket,
    private val officialPacketIds: OfficialPacketIds,
) {
    private val bufferedInputStream = BufferedInputStream(socket.getInputStream())
    private val bufferedOutputStream = BufferedOutputStream(socket.getOutputStream())

    var compressionThreshold: Int? = null

    fun send(
        state: String,
        name: String,
        payload: ByteArray = ByteArray(0),
    ) {
        val body = PacketOutput().apply {
            writeVarInt(officialPacketIds.id(state, SERVERBOUND, name))
            writeBytes(payload)
        }.toByteArray()
        GradlePacketFraming.writeFrame(
            bufferedOutputStream,
            body,
            compressionThreshold,
        )
        bufferedOutputStream.flush()
    }

    fun receive(state: String): CapturedPacket {
        val frame = GradlePacketFraming.readFrame(
            bufferedInputStream,
            compressionThreshold,
        )
        val packetInput = PacketInput(frame)
        val packetId = packetInput.readVarInt()
        return CapturedPacket(
            name = officialPacketIds.clientboundName(state, packetId),
            payload = packetInput.remainingBytes(),
        )
    }
}

internal sealed interface CapturedNbt {
    fun canonicalize(): CapturedNbt = this
    fun writePayload(packetOutput: PacketOutput)
}

private data class NbtByteValue(val value: Byte) : CapturedNbt {
    override fun writePayload(packetOutput: PacketOutput) = packetOutput.writeByte(value)
}

private data class NbtShortValue(val value: Short) : CapturedNbt {
    override fun writePayload(packetOutput: PacketOutput) = packetOutput.writeShort(value)
}

private data class NbtIntValue(val value: Int) : CapturedNbt {
    override fun writePayload(packetOutput: PacketOutput) = packetOutput.writeInt(value)
}

private data class NbtLongValue(val value: Long) : CapturedNbt {
    override fun writePayload(packetOutput: PacketOutput) = packetOutput.writeLong(value)
}

private data class NbtFloatValue(val value: Float) : CapturedNbt {
    override fun writePayload(packetOutput: PacketOutput) = packetOutput.writeFloat(value)
}

private data class NbtDoubleValue(val value: Double) : CapturedNbt {
    override fun writePayload(packetOutput: PacketOutput) = packetOutput.writeDouble(value)
}

private data class NbtByteArrayValue(
    val value: List<Byte>,
) : CapturedNbt {
    override fun writePayload(packetOutput: PacketOutput) {
        packetOutput.writeInt(value.size)
        value.forEach(packetOutput::writeByte)
    }
}

private data class NbtStringValue(val value: String) : CapturedNbt {
    override fun writePayload(packetOutput: PacketOutput) =
        packetOutput.writeModifiedUtf(value)
}

private data class NbtListValue(
    val elementType: Int,
    val value: List<CapturedNbt>,
) : CapturedNbt {
    override fun canonicalize(): CapturedNbt =
        copy(value = value.map(CapturedNbt::canonicalize))

    override fun writePayload(packetOutput: PacketOutput) {
        packetOutput.writeByte(elementType)
        packetOutput.writeInt(value.size)
        value.forEach { it.writePayload(packetOutput) }
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

    override fun writePayload(packetOutput: PacketOutput) {
        value.forEach { (name, capturedNbt) ->
            packetOutput.writeByte(capturedNbt.typeId())
            packetOutput.writeModifiedUtf(name)
            capturedNbt.writePayload(packetOutput)
        }
        packetOutput.writeByte(NBT_END)
    }
}

private data class NbtIntArrayValue(
    val value: List<Int>,
) : CapturedNbt {
    override fun writePayload(packetOutput: PacketOutput) {
        packetOutput.writeInt(value.size)
        value.forEach(packetOutput::writeInt)
    }
}

private data class NbtLongArrayValue(
    val value: List<Long>,
) : CapturedNbt {
    override fun writePayload(packetOutput: PacketOutput) {
        packetOutput.writeInt(value.size)
        value.forEach(packetOutput::writeLong)
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

    fun readUnsignedByte(): Int = readByte().toUByte().toInt()

    fun readShort(): Short =
        readFixed(2).short

    fun readUnsignedShort(): Int = readShort().toUShort().toInt()

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
        val byteArray = readBytes(length)
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(byteArray))
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
    private val byteArrayOutputStream = ByteArrayOutputStream()
    private val dataOutputStream = DataOutputStream(byteArrayOutputStream)

    fun writeByte(value: Byte) {
        dataOutputStream.writeByte(value.toInt())
    }

    fun writeByte(value: Int) {
        dataOutputStream.writeByte(value)
    }

    fun writeShort(value: Short) {
        dataOutputStream.writeShort(value.toInt())
    }

    fun writeUnsignedShort(value: Int) {
        require(value in 0..0xFFFF)
        dataOutputStream.writeShort(value)
    }

    fun writeInt(value: Int) {
        dataOutputStream.writeInt(value)
    }

    fun writeLong(value: Long) {
        dataOutputStream.writeLong(value)
    }

    fun writeFloat(value: Float) {
        dataOutputStream.writeFloat(value)
    }

    fun writeDouble(value: Double) {
        dataOutputStream.writeDouble(value)
    }

    fun writeBoolean(value: Boolean) {
        dataOutputStream.writeByte(if (value) 1 else 0)
    }

    fun writeVarInt(value: Int) {
        var remaining = value
        do {
            var current = remaining and 0x7F
            remaining = remaining ushr 7
            if (remaining != 0) current = current or 0x80
            dataOutputStream.writeByte(current)
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
        dataOutputStream.writeUTF(value)
    }

    fun writeBytes(value: ByteArray) {
        dataOutputStream.write(value)
    }

    fun writeNullableNbt(capturedNbt: CapturedNbt?) {
        if (capturedNbt == null) {
            writeBoolean(false)
        } else {
            writeBoolean(true)
            writeByte(capturedNbt.typeId())
            capturedNbt.writePayload(this)
        }
    }

    fun toByteArray(): ByteArray = byteArrayOutputStream.toByteArray()
}

private object GradlePacketFraming {
    private const val MAXIMUM_FRAME_SIZE = 16 * 1_048_576

    fun writeFrame(
        outputStream: OutputStream,
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
        }.toByteArray().let(outputStream::write)
    }

    fun readFrame(
        inputStream: InputStream,
        compressionThreshold: Int?,
    ): ByteArray {
        val frameLength = readVarInt(inputStream)
        require(frameLength in 1..MAXIMUM_FRAME_SIZE) {
            "Invalid incoming frame size $frameLength"
        }
        val body = inputStream.readNBytes(frameLength)
        if (body.size != frameLength) throw EOFException("Expected $frameLength bytes, received ${body.size}")
        if (compressionThreshold == null) return body

        val packetInput = PacketInput(body)
        val uncompressedLength = packetInput.readVarInt()
        if (uncompressedLength == 0) {
            val packet = packetInput.remainingBytes()
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
            packetInput.remainingBytes(),
            uncompressedLength,
        )
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater()
        return try {
            deflater.setInput(input)
            deflater.finish()
            val byteArrayOutputStream = ByteArrayOutputStream()
            val buffer = ByteArray(8_192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                check(count > 0) { "Deflater made no progress" }
                byteArrayOutputStream.write(buffer, 0, count)
            }
            byteArrayOutputStream.toByteArray()
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
                "Compressed packet produced $position bytes; expected $expectedLength"
            }
            check(inflater.remaining == 0) {
                "Compressed packet has trailing bytes"
            }
            output
        } finally {
            inflater.end()
        }
    }

    private fun readVarInt(inputStream: InputStream): Int {
        var result = 0
        var shift = 0
        var count = 0
        while (shift < 35) {
            val current = inputStream.read()
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

}

private class BoundedProcessLog(
    process: Process,
    threadName: String,
) : AutoCloseable {
    private val reentrantLock = ReentrantLock()
    private val changed = reentrantLock.newCondition()
    private val content = StringBuilder()
    private val thread = Thread.ofVirtual().name(threadName).start {
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    reentrantLock.withLock {
                        content.appendLine(line)
                        if (content.length > 200_000) {
                            content.delete(
                                0,
                                content.length - 150_000,
                            )
                        }
                        changed.signalAll()
                    }
                }
            }
        } catch (_: IOException) {
            // Expected when process teardown closes the diagnostic stream.
        } finally {
            reentrantLock.withLock {
                changed.signalAll()
            }
        }
    }

    fun awaitContains(
        text: String,
        process: Process,
        timeout: Duration,
    ) {
        val deadline = System.nanoTime() + timeout.toNanos()
        reentrantLock.withLock {
            while (text !in content) {
                check(process.isAlive) {
                    "Official server exited with ${process.exitValue()}: $content"
                }
                val remaining = deadline - System.nanoTime()
                check(remaining > 0) {
                    "Official server did not report '$text' within $timeout: $content"
                }
                changed.awaitNanos(remaining)
            }
        }
    }

    fun content(): String =
        reentrantLock.withLock { content.toString() }

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
