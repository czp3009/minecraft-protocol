package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.datapack.DataPackConfigurationSnapshot
import com.hiczp.minecraft.protocol.datapack.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.datapack.resolveSynchronizedRegistryContext
import com.hiczp.minecraft.protocol.datapack.vanilla.VanillaProtocolData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.CustomPayload
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.RegistryTags
import com.hiczp.minecraft.protocol.session.VanillaClient
import io.ktor.network.selector.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Portable protocol scenario driven by a platform-owned official server. */
internal object OfficialServerClientScenario {
    private const val MAXIMUM_PACKETS_PER_STAGE = 2_048

    suspend fun run(
        host: String,
        port: Int,
        phaseChanged: (String) -> Unit = {},
    ): MinecraftClientNegotiationResult {
        phaseChanged("status query")
        return SelectorManager(Dispatchers.Default).use { selector ->
            MinecraftClientConnection.connect(
                selectorManager = selector,
                host = host,
                port = port,
            ).use { statusClient ->
                val minecraftStatusExchange = statusClient.queryStatus(
                    0x0102_0304_0506_0708,
                )
                val statusDocument = Json
                    .parseToJsonElement(minecraftStatusExchange.statusResponsePacket.jsonResponse)
                    .jsonObject
                check(
                    statusDocument.getValue("version")
                        .jsonObject
                        .getValue("protocol")
                        .jsonPrimitive
                        .int == MinecraftProtocol.PROTOCOL_VERSION,
                ) {
                    "Official status did not advertise protocol ${MinecraftProtocol.PROTOCOL_VERSION}"
                }
            }

            phaseChanged("preset login")
            val presetResult = MinecraftClientConnection.connect(
                selectorManager = selector,
                host = host,
                port = port,
            ).use { loginClient ->
                val defaults = MinecraftClientNegotiationOptions()
                val login = loginClient.negotiate(
                    MinecraftOfflineIdentity("KmpClientProbe"),
                    options = MinecraftClientNegotiationOptions(
                        clientInformation = defaults.clientInformation.copy(
                            viewDistance = 2,
                        ),
                    ),
                )
                phaseChanged("configuration verification")
                verifyVanillaConfiguration(login)
                login
            }

            phaseChanged("public API login")
            MinecraftClientConnection.connect(
                selectorManager = selector,
                host = host,
                port = port,
            ).use { loginClient ->
                val defaults = MinecraftClientNegotiationOptions()
                val login = negotiateOffline(
                    connection = loginClient,
                    identity = MinecraftOfflineIdentity("KmpProtocolProbe"),
                    options = MinecraftClientNegotiationOptions(
                        clientInformation = defaults.clientInformation.copy(viewDistance = 2),
                    ),
                )
                check(loginClient.state == ConnectionState.PLAY) {
                    "Official-server client did not reach Play"
                }
                check(loginClient.protocolRegistryContext.chunkSectionCount != null) {
                    "Official-server client did not install the active dimension"
                }
                verifyVanillaConfiguration(login)
            }
            presetResult
        }
    }

    private suspend fun negotiateOffline(
        connection: MinecraftClientConnection,
        identity: MinecraftOfflineIdentity,
        options: MinecraftClientNegotiationOptions,
    ): MinecraftClientNegotiationResult {
        val profile = VanillaClient
        profile.begin(connection)
        connection.outgoing.send(
            profile.prepareHandshake(
                HandshakePacket(
                    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                    serverAddress = connection.serverAddress,
                    serverPort = connection.serverPort,
                    nextState = HandshakeNextState.LOGIN,
                ),
            ),
        )
        connection.outgoing.send(LoginStartPacket(identity.name, identity.id))
        connection.requestFlush()

        var login: LoginSuccessPacket? = null
        var loginPackets = 0
        while (login == null) {
            check(++loginPackets <= MAXIMUM_PACKETS_PER_STAGE) {
                "Login packet limit exceeded"
            }
            when (val packet = connection.incoming.receive()) {
                is SetCompressionPacket -> Unit
                is LoginCookieRequestPacket -> connection.outgoing.send(
                    LoginCookieResponsePacket(packet.key, options.loginCookies[packet.key]),
                )

                is LoginSuccessPacket -> {
                    login = packet
                    connection.outgoing.send(LoginAcknowledgedPacket)
                    connection.awaitState(ConnectionState.CONFIGURATION)
                }

                is LoginDisconnectPacket -> error("Official server rejected Login: ${packet.reason.json}")
                else -> error("Unexpected Login packet ${packet::class.simpleName}")
            }
            connection.requestFlush()
        }
        val actualLogin = checkNotNull(login)

        connection.outgoing.send(ConfigurationClientInformationPacket(options.clientInformation))
        connection.requestFlush()
        var configurationClientboundKnownPacksPacket: ConfigurationClientboundKnownPacksPacket? = null
        var featureFlagsPacket: FeatureFlagsPacket? = null
        var configurationUpdateTagsPacket: ConfigurationUpdateTagsPacket? = null
        val synchronizedRegistryPackets = mutableListOf<RegistryDataPacket>()
        val storedConfigurationCookies = linkedMapOf<Identifier, ByteString>()
        var configurationFinished = false
        var configurationPackets = 0
        while (!configurationFinished) {
            check(++configurationPackets <= MAXIMUM_PACKETS_PER_STAGE) {
                "Configuration packet limit exceeded"
            }
            when (val packet = connection.incoming.receive()) {
                is ConfigurationClientboundKnownPacksPacket -> {
                    configurationClientboundKnownPacksPacket = packet
                    connection.outgoing.send(
                        ConfigurationServerboundKnownPacksPacket(
                            packet.knownPacks.filter(options.acceptedKnownPacks::contains),
                        ),
                    )
                }

                is FeatureFlagsPacket -> featureFlagsPacket = packet
                is RegistryDataPacket -> {
                    check(synchronizedRegistryPackets.none { it.registryId == packet.registryId }) {
                        "Official server sent duplicate registry ${packet.registryId}"
                    }
                    synchronizedRegistryPackets += packet
                }

                is ConfigurationUpdateTagsPacket -> configurationUpdateTagsPacket = packet
                is ConfigurationCookieRequestPacket -> connection.outgoing.send(
                    ConfigurationCookieResponsePacket(
                        packet.key,
                        options.configurationCookies[packet.key],
                    ),
                )

                is ConfigurationStoreCookiePacket -> storedConfigurationCookies[packet.key] = packet.payload
                is ConfigurationPingPacket -> connection.outgoing.send(ConfigurationPongPacket(packet.id))
                is ConfigurationAddResourcePackPacket -> connection.outgoing.send(
                    ConfigurationResourcePackResponsePacket(packet.uuid, options.resourcePackResult),
                )

                is CodeOfConductPacket -> {
                    check(options.acceptCodeOfConduct) {
                        "Official server required an unaccepted Code of Conduct"
                    }
                    connection.outgoing.send(AcceptCodeOfConductPacket)
                }

                is FinishConfigurationPacket -> {
                    val resolvedProtocolRegistryContext = options.protocolData.resolveSynchronizedRegistryContext(
                        synchronizedRegistryPackets = synchronizedRegistryPackets,
                        staticRegistrySchema = options.staticRegistrySchema,
                    )
                    val profileProtocolRegistryContext =
                        profile.resolveProtocolRegistryContext(resolvedProtocolRegistryContext)
                    connection.installProtocolRegistryContext(profileProtocolRegistryContext)
                    profile.preparePlay(connection)
                    connection.outgoing.send(AcknowledgeFinishConfigurationPacket)
                    connection.requestFlush()
                    connection.awaitState(ConnectionState.PLAY)
                    configurationFinished = true
                }

                is ConfigurationClientboundPluginMessagePacket -> check(
                    packet.payload is CustomPayload.Brand,
                ) {
                    "Unexpected official Configuration payload ${packet.payload}"
                }

                is ConfigurationRemoveResourcePackPacket,
                is ConfigurationCustomReportDetailsPacket,
                is ConfigurationServerLinksPacket,
                ConfigurationClearDialogPacket,
                is ConfigurationShowDialogPacket,
                ResetChatPacket,
                    -> Unit

                is ConfigurationDisconnectPacket -> error(
                    "Official server rejected Configuration: ${packet.reason}",
                )

                is ConfigurationTransferPacket -> error(
                    "Official server unexpectedly transferred the client to ${packet.host}:${packet.port}",
                )

                else -> error("Unexpected Configuration packet ${packet::class.simpleName}")
            }
            connection.requestFlush()
        }

        val playLoginPacket = connection.incoming.receive() as? PlayLoginPacket
            ?: error("Official server did not send Play Login first")
        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            playLoginPacket = playLoginPacket,
            synchronizedRegistryPackets = synchronizedRegistryPackets,
            protocolData = options.protocolData,
        )
        connection.installProtocolRegistryContext(
            connection.protocolRegistryContext.withChunkSectionCount(minecraftDimensionLayout.sectionCount),
        )
        val negotiationProfileResult = profile.complete(connection)
        return MinecraftClientNegotiationResult(
            loginSuccessPacket = actualLogin,
            dataPackConfigurationSnapshot = DataPackConfigurationSnapshot(
                offeredKnownPacks = configurationClientboundKnownPacksPacket?.knownPacks.orEmpty(),
                enabledFeatureFlags = featureFlagsPacket?.featureFlags.orEmpty(),
                synchronizedRegistryPackets = synchronizedRegistryPackets,
                registryTags = configurationUpdateTagsPacket?.tags.orEmpty(),
            ),
            storedConfigurationCookies = storedConfigurationCookies.toMap(),
            playLoginPacket = playLoginPacket,
            minecraftDimensionLayout = minecraftDimensionLayout,
            negotiationProfileResult = negotiationProfileResult,
        )
    }

    private fun verifyVanillaConfiguration(
        result: MinecraftClientNegotiationResult,
    ) {
        val dataPackConfigurationSnapshot = result.dataPackConfigurationSnapshot
        check(
            dataPackConfigurationSnapshot.offeredKnownPacks == VanillaProtocolData.offeredKnownPacks,
        ) {
            "Official Known Packs differ from protocol-datapack-vanilla"
        }
        check(dataPackConfigurationSnapshot.enabledFeatureFlags == VanillaProtocolData.enabledFeatureFlags) {
            "Official Feature Flags differ from protocol-datapack-vanilla"
        }
        check(
            dataPackConfigurationSnapshot.synchronizedRegistryPackets ==
                    VanillaProtocolData.synchronizedRegistryPackets(
                        VanillaProtocolData.offeredKnownPacks,
                    ),
        ) {
            "Official compact registries differ from protocol-datapack-vanilla"
        }
        check(
            tagsSemanticallyEqual(
                dataPackConfigurationSnapshot.registryTags,
                VanillaProtocolData.registryTags,
            ),
        ) {
            "Official tags differ from protocol-datapack-vanilla"
        }
    }

    private fun tagsSemanticallyEqual(
        firstRegistryTags: List<RegistryTags>,
        secondRegistryTags: List<RegistryTags>,
    ): Boolean =
        firstRegistryTags.associate { registryTags ->
            registryTags.registry to registryTags.tags.associate { tagDefinition ->
                tagDefinition.name to tagDefinition.entries.toSet()
            }
        } ==
                secondRegistryTags.associate { registryTags ->
                    registryTags.registry to registryTags.tags.associate { tagDefinition ->
                        tagDefinition.name to tagDefinition.entries.toSet()
                    }
                }
}
