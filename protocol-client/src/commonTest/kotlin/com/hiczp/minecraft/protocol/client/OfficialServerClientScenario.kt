package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.data.VanillaProtocolData
import com.hiczp.minecraft.protocol.model.MinecraftProtocol
import com.hiczp.minecraft.protocol.model.packet.ConfigurationUpdateTagsPacket
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

            phaseChanged("login")
            MinecraftClientConnection.connect(
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
        }
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
