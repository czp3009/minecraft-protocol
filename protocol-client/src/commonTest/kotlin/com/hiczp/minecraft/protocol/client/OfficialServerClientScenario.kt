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
        return SelectorManager(Dispatchers.Default).use { selectorManager ->
            MinecraftClientConnection.connect(
                selectorManager = selectorManager,
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
                selectorManager = selectorManager,
                host = host,
                port = port,
            ).use { loginClient ->
                val defaults = MinecraftClientNegotiationOptions()
                val login = loginClient.negotiate(
                    MinecraftOfflineIdentity("KmpClientProbe"),
                    minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(
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
                selectorManager = selectorManager,
                host = host,
                port = port,
            ).use { loginClient ->
                val defaults = MinecraftClientNegotiationOptions()
                val login = negotiateOffline(
                    minecraftClientConnection = loginClient,
                    minecraftOfflineIdentity = MinecraftOfflineIdentity("KmpProtocolProbe"),
                    minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(
                        clientInformation = defaults.clientInformation.copy(viewDistance = 2),
                    ),
                )
                check(loginClient.connectionState == ConnectionState.PLAY) {
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
        minecraftClientConnection: MinecraftClientConnection,
        minecraftOfflineIdentity: MinecraftOfflineIdentity,
        minecraftClientNegotiationOptions: MinecraftClientNegotiationOptions,
    ): MinecraftClientNegotiationResult {
        val profile = VanillaClient
        profile.begin(minecraftClientConnection)
        minecraftClientConnection.outgoing.send(
            profile.prepareHandshake(
                HandshakePacket(
                    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                    serverAddress = minecraftClientConnection.serverAddress,
                    serverPort = minecraftClientConnection.serverPort,
                    nextState = HandshakeNextState.LOGIN,
                ),
            ),
        )
        minecraftClientConnection.outgoing.send(
            LoginStartPacket(
                minecraftOfflineIdentity.name,
                minecraftOfflineIdentity.id
            )
        )
        minecraftClientConnection.requestFlush()

        var loginSuccessPacket: LoginSuccessPacket? = null
        var loginPackets = 0
        while (loginSuccessPacket == null) {
            check(++loginPackets <= MAXIMUM_PACKETS_PER_STAGE) {
                "Login packet limit exceeded"
            }
            when (val clientboundPacket = minecraftClientConnection.incoming.receive()) {
                is SetCompressionPacket -> Unit
                is LoginCookieRequestPacket -> minecraftClientConnection.outgoing.send(
                    LoginCookieResponsePacket(
                        clientboundPacket.key,
                        minecraftClientNegotiationOptions.loginCookies[clientboundPacket.key]
                    ),
                )

                is LoginSuccessPacket -> {
                    loginSuccessPacket = clientboundPacket
                    minecraftClientConnection.outgoing.send(LoginAcknowledgedPacket)
                    minecraftClientConnection.awaitState(ConnectionState.CONFIGURATION)
                }

                is LoginDisconnectPacket -> error("Official server rejected Login: ${clientboundPacket.reason.json}")
                else -> error("Unexpected Login packet ${clientboundPacket::class.simpleName}")
            }
            minecraftClientConnection.requestFlush()
        }
        val actualLogin = checkNotNull(loginSuccessPacket)

        minecraftClientConnection.outgoing.send(ConfigurationClientInformationPacket(minecraftClientNegotiationOptions.clientInformation))
        minecraftClientConnection.requestFlush()
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
            when (val clientboundPacket = minecraftClientConnection.incoming.receive()) {
                is ConfigurationClientboundKnownPacksPacket -> {
                    configurationClientboundKnownPacksPacket = clientboundPacket
                    minecraftClientConnection.outgoing.send(
                        ConfigurationServerboundKnownPacksPacket(
                            clientboundPacket.knownPacks.filter(minecraftClientNegotiationOptions.acceptedKnownPacks::contains),
                        ),
                    )
                }

                is FeatureFlagsPacket -> featureFlagsPacket = clientboundPacket
                is RegistryDataPacket -> {
                    check(synchronizedRegistryPackets.none { it.registryId == clientboundPacket.registryId }) {
                        "Official server sent duplicate registry ${clientboundPacket.registryId}"
                    }
                    synchronizedRegistryPackets += clientboundPacket
                }

                is ConfigurationUpdateTagsPacket -> configurationUpdateTagsPacket = clientboundPacket
                is ConfigurationCookieRequestPacket -> minecraftClientConnection.outgoing.send(
                    ConfigurationCookieResponsePacket(
                        clientboundPacket.key,
                        minecraftClientNegotiationOptions.configurationCookies[clientboundPacket.key],
                    ),
                )

                is ConfigurationStoreCookiePacket -> storedConfigurationCookies[clientboundPacket.key] =
                    clientboundPacket.payload

                is ConfigurationPingPacket -> minecraftClientConnection.outgoing.send(
                    ConfigurationPongPacket(
                        clientboundPacket.id
                    )
                )

                is ConfigurationAddResourcePackPacket -> minecraftClientConnection.outgoing.send(
                    ConfigurationResourcePackResponsePacket(
                        clientboundPacket.uuid,
                        minecraftClientNegotiationOptions.resourcePackResult
                    ),
                )

                is CodeOfConductPacket -> {
                    check(minecraftClientNegotiationOptions.acceptCodeOfConduct) {
                        "Official server required an unaccepted Code of Conduct"
                    }
                    minecraftClientConnection.outgoing.send(AcceptCodeOfConductPacket)
                }

                is FinishConfigurationPacket -> {
                    val resolvedProtocolRegistryContext =
                        minecraftClientNegotiationOptions.protocolData.resolveSynchronizedRegistryContext(
                            synchronizedRegistryPackets = synchronizedRegistryPackets,
                            staticRegistrySchema = minecraftClientNegotiationOptions.staticRegistrySchema,
                        )
                    val profileProtocolRegistryContext =
                        profile.resolveProtocolRegistryContext(resolvedProtocolRegistryContext)
                    minecraftClientConnection.installProtocolRegistryContext(profileProtocolRegistryContext)
                    profile.preparePlay(minecraftClientConnection)
                    minecraftClientConnection.outgoing.send(AcknowledgeFinishConfigurationPacket)
                    minecraftClientConnection.requestFlush()
                    minecraftClientConnection.awaitState(ConnectionState.PLAY)
                    configurationFinished = true
                }

                is ConfigurationClientboundPluginMessagePacket -> check(
                    clientboundPacket.payload is CustomPayload.Brand,
                ) {
                    "Unexpected official Configuration payload ${clientboundPacket.payload}"
                }

                is ConfigurationRemoveResourcePackPacket,
                is ConfigurationCustomReportDetailsPacket,
                is ConfigurationServerLinksPacket,
                ConfigurationClearDialogPacket,
                is ConfigurationShowDialogPacket,
                ResetChatPacket,
                    -> Unit

                is ConfigurationDisconnectPacket -> error(
                    "Official server rejected Configuration: ${clientboundPacket.reason}",
                )

                is ConfigurationTransferPacket -> error(
                    "Official server unexpectedly transferred the client to ${clientboundPacket.host}:${clientboundPacket.port}",
                )

                else -> error("Unexpected Configuration packet ${clientboundPacket::class.simpleName}")
            }
            minecraftClientConnection.requestFlush()
        }

        val playLoginPacket = minecraftClientConnection.incoming.receive() as? PlayLoginPacket
            ?: error("Official server did not send Play Login first")
        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            playLoginPacket = playLoginPacket,
            synchronizedRegistryPackets = synchronizedRegistryPackets,
            protocolData = minecraftClientNegotiationOptions.protocolData,
        )
        minecraftClientConnection.installProtocolRegistryContext(
            minecraftClientConnection.protocolRegistryContext.withChunkSectionCount(minecraftDimensionLayout.sectionCount),
        )
        val negotiationProfileResult = profile.complete(minecraftClientConnection)
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
        minecraftClientNegotiationResult: MinecraftClientNegotiationResult,
    ) {
        val dataPackConfigurationSnapshot = minecraftClientNegotiationResult.dataPackConfigurationSnapshot
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
