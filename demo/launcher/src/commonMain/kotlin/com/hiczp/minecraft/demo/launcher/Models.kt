@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.protocol.auth.MinecraftIdentity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*
import kotlin.uuid.Uuid

internal const val MICROSOFT_CLIENT_ID = "eecdf7ef-6501-4ad6-a769-789b237ada00"
internal const val OAUTH_REDIRECT_HOST = "127.0.0.1"
internal const val OAUTH_REDIRECT_PATH = "/oauth/callback"
internal const val LAUNCHER_NAME = "minecraft-protocol-demo"
internal const val LAUNCHER_VERSION = "1"
internal const val DEFAULT_OFFLINE_PLAYER_NAME = "Player"

@Serializable
internal data class VersionManifest(
    val latest: Latest,
    val versions: List<VersionEntry>,
) {
    @Serializable
    internal data class Latest(
        val release: String,
        val snapshot: String,
    )
}

@Serializable
internal data class VersionEntry(
    val id: String,
    val type: String,
    val url: String,
    val time: String,
    val releaseTime: String,
    val sha1: String? = null,
    val complianceLevel: Int? = null,
)

@Serializable
internal data class VersionMetadata(
    val id: String,
    val type: String,
    val mainClass: String,
    val assets: String,
    val assetIndex: AssetIndexDownload,
    val downloads: VersionDownloads,
    val libraries: List<MojangLibrary>,
    val arguments: MojangArguments? = null,
    val minecraftArguments: String? = null,
    val javaVersion: JavaVersion? = null,
    val logging: Logging? = null,
) {
    @Serializable
    internal data class JavaVersion(
        val component: String,
        val majorVersion: Int,
    )

    @Serializable
    internal data class Logging(
        val client: Client,
    ) {
        @Serializable
        internal data class Client(
            val argument: String,
            val file: Download,
            val type: String,
        )
    }
}

@Serializable
internal data class VersionDownloads(
    val client: Download,
)

@Serializable
internal data class Download(
    val url: String,
    val sha1: String,
    val size: Long,
    val path: String? = null,
    val id: String? = null,
)

@Serializable
internal data class AssetIndexDownload(
    val id: String,
    val url: String,
    val sha1: String,
    val size: Long,
    val totalSize: Long? = null,
)

@Serializable
internal data class AssetIndex(
    val objects: Map<String, AssetObject>,
    val virtual: Boolean = false,
    @SerialName("map_to_resources")
    val mapToResources: Boolean = false,
)

@Serializable
internal data class AssetObject(
    val hash: String,
    val size: Long,
)

@Serializable
internal data class MojangLibrary(
    val name: String,
    val downloads: Downloads? = null,
    val rules: List<MojangRule>? = null,
    val natives: Map<String, String>? = null,
    val extract: JsonObject? = null,
) {
    @Serializable
    internal data class Downloads(
        val artifact: Download? = null,
        val classifiers: Map<String, Download> = emptyMap(),
    )
}

@Serializable
internal data class MojangArguments(
    val game: List<MojangArgument>,
    val jvm: List<MojangArgument>,
)

@Serializable(with = MojangArgumentSerializer::class)
internal sealed interface MojangArgument {
    data class Literal(val value: String) : MojangArgument

    data class Conditional(
        val rules: List<MojangRule>,
        val values: List<String>,
    ) : MojangArgument
}

@Serializable
internal data class MojangRule(
    val action: String,
    val os: Os? = null,
    val features: Map<String, Boolean>? = null,
) {
    @Serializable
    internal data class Os(
        val name: String? = null,
        val arch: String? = null,
        val version: String? = null,
        val versionRange: VersionRange? = null,
    )

    @Serializable
    internal data class VersionRange(
        val min: String? = null,
        val max: String? = null,
    )
}

internal object MojangArgumentSerializer : KSerializer<MojangArgument> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("MojangArgument")

    override fun deserialize(decoder: Decoder): MojangArgument {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("Mojang arguments are JSON-only")
        return when (val jsonElement = jsonDecoder.decodeJsonElement()) {
            is JsonPrimitive -> MojangArgument.Literal(jsonElement.content)
            is JsonObject -> {
                val conditionalArgumentWire =
                    jsonDecoder.json.decodeFromJsonElement<ConditionalArgumentWire>(jsonElement)
                MojangArgument.Conditional(conditionalArgumentWire.rules, conditionalArgumentWire.value.asStrings())
            }

            else -> throw SerializationException("Mojang argument must be a string or object")
        }
    }

    override fun serialize(encoder: Encoder, value: MojangArgument) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("Mojang arguments are JSON-only")
        val jsonElement = when (value) {
            is MojangArgument.Literal -> JsonPrimitive(value.value)
            is MojangArgument.Conditional -> buildJsonObject {
                put("rules", jsonEncoder.json.encodeToJsonElement(value.rules))
                put(
                    "value",
                    if (value.values.size == 1) {
                        JsonPrimitive(value.values.single())
                    } else {
                        buildJsonArray {
                            value.values.forEach { add(JsonPrimitive(it)) }
                        }
                    },
                )
            }
        }
        jsonEncoder.encodeJsonElement(jsonElement)
    }
}

@Serializable
private data class ConditionalArgumentWire(
    val rules: List<MojangRule>,
    val value: JsonElement,
)

private fun JsonElement.asStrings(): List<String> = when (this) {
    is JsonPrimitive -> listOf(content)
    is JsonArray -> map { it.jsonPrimitive.content }

    else -> throw SerializationException("Mojang argument value must be a string or string array")
}

@Serializable
internal data class InstalledState(
    val schemaVersion: Int = 1,
    val installations: List<InstalledVersion> = emptyList(),
)

@Serializable
internal data class InstalledVersion(
    val versionId: String,
    val platformKey: String,
)

@Serializable
internal data class AuthState(
    val schemaVersion: Int = 2,
    val installationId: Uuid,
    val selectedIdentityId: Uuid? = null,
    val accounts: List<StoredAccount> = emptyList(),
)

@Serializable
internal data class StoredAccount(
    val minecraftIdentity: MinecraftIdentity,
    val microsoftRefreshToken: String? = null,
    val minecraftAccessTokenExpiresAtEpochSeconds: Long? = null,
)

internal data class DownloadSpec(
    val url: String,
    val sha1: String,
    val size: Long,
    val relativePath: String,
)

internal data class InstallPlan(
    val versionMetadata: VersionMetadata,
    val gameRootName: String,
    val downloads: List<DownloadSpec>,
    val assetIndex: DownloadSpec,
    val classpath: List<String>,
    val loggingFile: String?,
    val nativeDirectory: String,
)

internal data class LaunchPlan(
    val javaArguments: List<String>,
    val mainClass: String,
    val gameArguments: List<String>,
    val sensitiveAccessToken: String?,
    val workingDirectory: String,
    val requiredJavaMajor: Int?,
)

internal data class InstallProgress(
    val completedFiles: Int = 0,
    val totalFiles: Int = 0,
)

internal data class GameOutputLine(
    val sequence: Long,
    val outputSource: OutputSource,
    val text: String,
)

internal enum class OutputSource {
    STDOUT,
    STDERR,
    SYSTEM,
}
