package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.configuration.DataPackConfigurationSnapshot
import com.hiczp.minecraft.protocol.configuration.MinecraftDimensionContext
import com.hiczp.minecraft.protocol.configuration.MinecraftDimensionLayout
import com.hiczp.minecraft.protocol.configuration.resolveSynchronizedRegistryContext
import com.hiczp.minecraft.protocol.configuration.vanilla.VanillaConfigurationData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.*
import com.hiczp.minecraft.protocol.model.type.*
import com.hiczp.minecraft.protocol.session.VanillaClient
import com.hiczp.minecraft.world.format.DimensionId
import io.ktor.network.selector.*
import kotlinx.coroutines.Dispatchers

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
                check(
                    minecraftStatusExchange.clientboundStatusResponsePacket.status.version?.protocol ==
                            MinecraftProtocol.PROTOCOL_VERSION,
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
                var receivedResourcePack = false
                val login = loginClient.negotiate(
                    MinecraftOfflineIdentity("KmpClientProbe"),
                    minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(
                        clientInformation = defaults.clientInformation.copy(
                            viewDistance = 2,
                        ),
                        onResourcePack = { request, reportProgress ->
                            check(request.required) { "Official server did not mark its resource pack required" }
                            receivedResourcePack = true
                            reportProgress(ServerboundResourcePackPacket.Action.ACCEPTED)
                            reportProgress(ServerboundResourcePackPacket.Action.DOWNLOADED)
                            ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED
                        },
                    ),
                )
                check(receivedResourcePack) { "Official server did not offer its resource pack during preset negotiation" }
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
                var receivedResourcePack = false
                val login = negotiateOffline(
                    minecraftClientConnection = loginClient,
                    minecraftOfflineIdentity = MinecraftOfflineIdentity("KmpProtocolProbe"),
                    minecraftClientNegotiationOptions = MinecraftClientNegotiationOptions(
                        clientInformation = defaults.clientInformation.copy(viewDistance = 2),
                        onResourcePack = { request, reportProgress ->
                            check(request.required) { "Official server did not mark its resource pack required" }
                            receivedResourcePack = true
                            reportProgress(ServerboundResourcePackPacket.Action.ACCEPTED)
                            ServerboundResourcePackPacket.Action.FAILED_DOWNLOAD
                        },
                    ),
                )
                check(receivedResourcePack) { "Official server did not offer its resource pack during manual negotiation" }
                check(loginClient.connectionState == ConnectionState.PLAY) {
                    "Official-server client did not reach Play"
                }
                check(login.chunkLayout.sectionCount > 0) {
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
                ClientIntentionPacket(
                    protocolVersion = MinecraftProtocol.PROTOCOL_VERSION,
                    hostName = minecraftClientConnection.serverAddress,
                    port = minecraftClientConnection.serverPort,
                    intention = ClientIntent.LOGIN,
                ),
            ),
        )
        minecraftClientConnection.outgoing.send(
            ServerboundHelloPacket(
                minecraftOfflineIdentity.name,
                minecraftOfflineIdentity.id
            )
        )
        minecraftClientConnection.requestFlush()

        var clientboundLoginFinishedPacket: ClientboundLoginFinishedPacket? = null
        var loginPackets = 0
        while (clientboundLoginFinishedPacket == null) {
            check(++loginPackets <= MAXIMUM_PACKETS_PER_STAGE) {
                "Login packet limit exceeded"
            }
            when (val clientboundPacket = minecraftClientConnection.incoming.receive()) {
                is ClientboundLoginCompressionPacket -> Unit
                is ClientboundCookieRequestPacket -> minecraftClientConnection.outgoing.send(
                    ServerboundCookieResponsePacket(
                        clientboundPacket.key,
                        minecraftClientNegotiationOptions.loginCookies[clientboundPacket.key]
                    ),
                )

                is ClientboundLoginFinishedPacket -> {
                    clientboundLoginFinishedPacket = clientboundPacket
                    minecraftClientConnection.outgoing.send(ServerboundLoginAcknowledgedPacket)
                    minecraftClientConnection.awaitState(ConnectionState.CONFIGURATION)
                }

                is ClientboundLoginDisconnectPacket -> error("Official server rejected Login: ${clientboundPacket.reason.json}")
                else -> error("Unexpected Login packet ${clientboundPacket::class.simpleName}")
            }
            minecraftClientConnection.requestFlush()
        }
        val actualLogin = checkNotNull(clientboundLoginFinishedPacket)

        minecraftClientConnection.outgoing.send(ServerboundClientInformationPacket(minecraftClientNegotiationOptions.clientInformation))
        minecraftClientConnection.requestFlush()
        var clientboundSelectKnownPacks: ClientboundSelectKnownPacks? = null
        var clientboundUpdateEnabledFeaturesPacket: ClientboundUpdateEnabledFeaturesPacket? = null
        var clientboundUpdateTagsPacket: ClientboundUpdateTagsPacket? = null
        val synchronizedRegistryPackets = mutableListOf<ClientboundRegistryDataPacket>()
        val storedConfigurationCookies = linkedMapOf<Identifier, ByteString>()
        var configurationFinished = false
        var configurationPackets = 0
        while (!configurationFinished) {
            check(++configurationPackets <= MAXIMUM_PACKETS_PER_STAGE) {
                "Configuration packet limit exceeded"
            }
            when (val clientboundPacket = minecraftClientConnection.incoming.receive()) {
                is ClientboundSelectKnownPacks -> {
                    clientboundSelectKnownPacks = clientboundPacket
                    minecraftClientConnection.outgoing.send(
                        ServerboundSelectKnownPacks(
                            clientboundPacket.knownPacks.filter(minecraftClientNegotiationOptions.acceptedKnownPacks::contains),
                        ),
                    )
                }

                is ClientboundUpdateEnabledFeaturesPacket -> clientboundUpdateEnabledFeaturesPacket = clientboundPacket
                is ClientboundRegistryDataPacket -> {
                    check(synchronizedRegistryPackets.none { it.registry == clientboundPacket.registry }) {
                        "Official server sent duplicate registry ${clientboundPacket.registry}"
                    }
                    synchronizedRegistryPackets += clientboundPacket
                }

                is ClientboundUpdateTagsPacket -> clientboundUpdateTagsPacket = clientboundPacket
                is ClientboundCookieRequestPacket -> minecraftClientConnection.outgoing.send(
                    ServerboundCookieResponsePacket(
                        clientboundPacket.key,
                        minecraftClientNegotiationOptions.configurationCookies[clientboundPacket.key],
                    ),
                )

                is ClientboundStoreCookiePacket -> storedConfigurationCookies[clientboundPacket.key] =
                    clientboundPacket.payload

                is ClientboundPingPacket -> minecraftClientConnection.outgoing.send(
                    ServerboundPongPacket(
                        clientboundPacket.id
                    )
                )

                is ClientboundResourcePackPushPacket -> {
                    val action = minecraftClientNegotiationOptions.onResourcePack(clientboundPacket) { progress ->
                        minecraftClientConnection.outgoing.send(
                            ServerboundResourcePackPacket(
                                clientboundPacket.id,
                                progress
                            )
                        )
                        minecraftClientConnection.requestFlush()
                    }
                    minecraftClientConnection.outgoing.send(ServerboundResourcePackPacket(clientboundPacket.id, action))
                }

                is ClientboundCodeOfConductPacket -> {
                    check(minecraftClientNegotiationOptions.acceptCodeOfConduct) {
                        "Official server required an unaccepted Code of Conduct"
                    }
                    minecraftClientConnection.outgoing.send(ServerboundAcceptCodeOfConductPacket)
                }

                is ClientboundFinishConfigurationPacket -> {
                    val resolvedPacketCodecContext =
                        minecraftClientNegotiationOptions.configurationData.resolveSynchronizedRegistryContext(
                            synchronizedRegistryPackets = synchronizedRegistryPackets,
                            staticRegistrySchema = minecraftClientNegotiationOptions.staticRegistrySchema,
                        )
                    val profilePacketCodecContext =
                        profile.resolvePacketCodecContext(resolvedPacketCodecContext)
                    minecraftClientConnection.installPacketCodecContext(profilePacketCodecContext)
                    profile.preparePlay(minecraftClientConnection)
                    minecraftClientConnection.outgoing.send(ServerboundFinishConfigurationPacket)
                    minecraftClientConnection.requestFlush()
                    minecraftClientConnection.awaitState(ConnectionState.PLAY)
                    configurationFinished = true
                }

                is ClientboundCustomPayloadPacket -> check(
                    clientboundPacket.payload is CustomPayload.Brand,
                ) {
                    "Unexpected official Configuration payload ${clientboundPacket.payload}"
                }

                is ClientboundResourcePackPopPacket,
                is ClientboundCustomReportDetailsPacket,
                is ClientboundServerLinksPacket,
                ClientboundClearDialogPacket,
                is ClientboundShowDialogPacket,
                ClientboundResetChatPacket,
                    -> Unit

                is ClientboundDisconnectPacket -> error(
                    "Official server rejected Configuration: ${clientboundPacket.reason}",
                )

                is ClientboundTransferPacket -> error(
                    "Official server unexpectedly transferred the client to ${clientboundPacket.host}:${clientboundPacket.port}",
                )

                else -> error("Unexpected Configuration packet ${clientboundPacket::class.simpleName}")
            }
            minecraftClientConnection.requestFlush()
        }

        val clientboundLoginPacket = minecraftClientConnection.incoming.receive() as? ClientboundLoginPacket
            ?: error("Official server did not send Play Login first")
        val minecraftDimensionLayout = MinecraftDimensionLayout.from(
            dimensionTypeRawId = clientboundLoginPacket.commonPlayerSpawnInfo.dimensionTypeId,
            synchronizedRegistryPackets = synchronizedRegistryPackets,
            configurationData = minecraftClientNegotiationOptions.configurationData,
        )
        val minecraftDimensionContext = MinecraftDimensionContext(
            dimensionId = DimensionId.parse(clientboundLoginPacket.commonPlayerSpawnInfo.dimension.toString()),
            minecraftDimensionLayout = minecraftDimensionLayout,
            packetCodecContext = minecraftClientConnection.packetCodecContext,
        )
        minecraftClientConnection.installPacketCodecContext(
            minecraftDimensionContext.packetCodecContext,
        )
        val negotiationProfileResult = profile.complete(minecraftClientConnection)
        return MinecraftClientNegotiationResult(
            clientboundLoginFinishedPacket = actualLogin,
            dataPackConfigurationSnapshot = DataPackConfigurationSnapshot(
                offeredKnownPacks = clientboundSelectKnownPacks?.knownPacks.orEmpty(),
                enabledFeatureFlags = clientboundUpdateEnabledFeaturesPacket?.features.orEmpty(),
                synchronizedRegistryPackets = synchronizedRegistryPackets,
                registryTags = clientboundUpdateTagsPacket?.tags.orEmpty(),
            ),
            storedConfigurationCookies = storedConfigurationCookies.toMap(),
            clientboundLoginPacket = clientboundLoginPacket,
            minecraftDimensionContext = minecraftDimensionContext,
            negotiationProfileResult = negotiationProfileResult,
        )
    }

    private fun verifyVanillaConfiguration(
        minecraftClientNegotiationResult: MinecraftClientNegotiationResult,
    ) {
        val dataPackConfigurationSnapshot = minecraftClientNegotiationResult.dataPackConfigurationSnapshot
        check(
            dataPackConfigurationSnapshot.offeredKnownPacks == VanillaConfigurationData.offeredKnownPacks,
        ) {
            "Official Known Packs differ from protocol-configuration-vanilla"
        }
        check(dataPackConfigurationSnapshot.enabledFeatureFlags == VanillaConfigurationData.enabledFeatureFlags) {
            "Official Feature Flags differ from protocol-configuration-vanilla"
        }
        check(
            dataPackConfigurationSnapshot.synchronizedRegistryPackets ==
                    VanillaConfigurationData.synchronizedRegistryPackets(
                        VanillaConfigurationData.offeredKnownPacks,
                    ),
        ) {
            "Official compact registries differ from protocol-configuration-vanilla"
        }
        check(
            tagsSemanticallyEqual(
                dataPackConfigurationSnapshot.registryTags,
                VanillaConfigurationData.registryTags,
            ),
        ) {
            "Official tags differ from protocol-configuration-vanilla"
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
