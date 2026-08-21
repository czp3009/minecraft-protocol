package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.data.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.data.VanillaProtocolData
import com.hiczp.minecraft.protocol.data.resolveSynchronizedRegistryContext
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.ByteString
import com.hiczp.minecraft.protocol.model.type.CustomPayload
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.session.VanillaClient
import io.ktor.network.selector.*
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Portable protocol scenario driven by a platform-owned official server. */
internal object OfficialServerClientScenario {
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
                val status = statusClient.queryStatus(
                    0x0102_0304_0506_0708,
                )
                val statusDocument = Json
                    .parseToJsonElement(status.response.jsonResponse)
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
                        information = defaults.information.copy(
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
                        information = defaults.information.copy(viewDistance = 2),
                    ),
                )
                check(loginClient.state == ConnectionState.PLAY) {
                    "Official-server client did not reach Play"
                }
                check(loginClient.registries.chunkSectionCount != null) {
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

        var login: LoginSuccessPacket? = null
        var loginPackets = 0
        while (login == null) {
            check(++loginPackets <= options.maximumPacketsPerPhase) {
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
        }
        val actualLogin = checkNotNull(login)

        connection.outgoing.send(ConfigurationClientInformationPacket(options.information))
        var knownPacks: ConfigurationClientboundKnownPacksPacket? = null
        var featureFlags: FeatureFlagsPacket? = null
        var tags: ConfigurationUpdateTagsPacket? = null
        val registries = mutableListOf<RegistryDataPacket>()
        val storedCookies = linkedMapOf<Identifier, ByteString>()
        var configurationFinished = false
        var configurationPackets = 0
        while (!configurationFinished) {
            check(++configurationPackets <= options.maximumPacketsPerPhase) {
                "Configuration packet limit exceeded"
            }
            when (val packet = connection.incoming.receive()) {
                is ConfigurationClientboundKnownPacksPacket -> {
                    knownPacks = packet
                    connection.outgoing.send(
                        ConfigurationServerboundKnownPacksPacket(
                            packet.knownPacks.filter(options.acceptedKnownPacks::contains),
                        ),
                    )
                }

                is FeatureFlagsPacket -> featureFlags = packet
                is RegistryDataPacket -> {
                    check(registries.none { it.registryId == packet.registryId }) {
                        "Official server sent duplicate registry ${packet.registryId}"
                    }
                    registries += packet
                }

                is ConfigurationUpdateTagsPacket -> tags = packet
                is ConfigurationCookieRequestPacket -> connection.outgoing.send(
                    ConfigurationCookieResponsePacket(
                        packet.key,
                        options.configurationCookies[packet.key],
                    ),
                )

                is ConfigurationStoreCookiePacket -> storedCookies[packet.key] = packet.payload
                is ConfigurationClientboundKeepAlivePacket -> connection.outgoing.send(
                    ConfigurationServerboundKeepAlivePacket(packet.id),
                )

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
                    val resolved = options.protocolData.resolveSynchronizedRegistryContext(
                        registries = registries,
                        staticRegistries = options.staticRegistries,
                    )
                    val profiled = profile.resolveRegistryContext(resolved)
                    connection.installRegistryContext(profiled)
                    profile.preparePlay(connection)
                    connection.outgoing.send(AcknowledgeFinishConfigurationPacket)
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
        }

        val playLogin = connection.incoming.receive() as? PlayLoginPacket
            ?: error("Official server did not send Play Login first")
        val dimensionLayout = MinecraftDimensionLayout.from(
            login = playLogin,
            registries = registries,
            protocolData = options.protocolData,
        )
        connection.installRegistryContext(
            connection.registries.withChunkSectionCount(dimensionLayout.sectionCount),
        )
        val profileResult = profile.complete(connection)
        return MinecraftClientNegotiationResult(
            login = actualLogin,
            configuration = MinecraftClientConfiguration(
                knownPacks = knownPacks,
                featureFlags = featureFlags,
                registries = registries.toList(),
                tags = tags,
                storedCookies = storedCookies.toMap(),
            ),
            playLogin = playLogin,
            dimensionLayout = dimensionLayout,
            profile = profileResult,
        )
    }

    private fun verifyVanillaConfiguration(
        result: MinecraftClientNegotiationResult,
    ) {
        val configuration = result.configuration
        check(
            configuration.knownPacks?.knownPacks ==
                    VanillaProtocolData.knownPacks,
        ) {
            "Official Known Packs differ from protocol-vanilla-data"
        }
        check(configuration.featureFlags == VanillaProtocolData.featureFlags) {
            "Official Feature Flags differ from protocol-vanilla-data"
        }
        check(
            configuration.registries ==
                    VanillaProtocolData.registryPackets(
                        VanillaProtocolData.knownPacks,
                    ),
        ) {
            "Official compact registries differ from protocol-vanilla-data"
        }
        check(
            configuration.tags != null &&
                    tagsSemanticallyEqual(
                        configuration.tags,
                        VanillaProtocolData.tags,
                    ),
        ) {
            "Official tags differ from protocol-vanilla-data"
        }
    }

    private fun tagsSemanticallyEqual(
        first: ConfigurationUpdateTagsPacket,
        second: ConfigurationUpdateTagsPacket,
    ): Boolean =
        first.registries.associate { registry ->
            registry.registry to registry.tags.associate { tag ->
                tag.name to tag.entries.toSet()
            }
        } ==
                second.registries.associate { registry ->
                    registry.registry to registry.tags.associate { tag ->
                        tag.name to tag.entries.toSet()
                    }
                }
}
