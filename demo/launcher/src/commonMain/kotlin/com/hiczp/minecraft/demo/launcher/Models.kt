package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.distribution.metadata.MinecraftDownload
import com.hiczp.minecraft.distribution.metadata.MinecraftVersionMetadata
import com.hiczp.minecraft.protocol.auth.MinecraftIdentity
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

internal const val MICROSOFT_CLIENT_ID = "eecdf7ef-6501-4ad6-a769-789b237ada00"
internal const val OAUTH_REDIRECT_HOST = "127.0.0.1"
internal const val OAUTH_REDIRECT_PATH = "/oauth/callback"
internal const val LAUNCHER_NAME = "minecraft-protocol-demo"
internal const val LAUNCHER_VERSION = "1"
internal const val DEFAULT_OFFLINE_PLAYER_NAME = "Player"

@Serializable
internal data class InstalledState(
    val installations: List<InstalledVersion> = emptyList(),
)

@Serializable
internal data class InstalledVersion(
    val versionId: String,
)

@Serializable
internal data class AuthState(
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
    val minecraftDownload: MinecraftDownload,
    val relativePath: String,
) {
    init {
        require(minecraftDownload.size >= 0L) { "A download size must be non-negative" }
        validateSha1(minecraftDownload.sha1)
        validateRelativePath(relativePath, "download target")
    }
}

internal data class InstallPlan(
    val minecraftVersionMetadata: MinecraftVersionMetadata,
    val downloads: List<DownloadSpec>,
    val assetIndexPath: String,
    val classpath: List<String>,
    val loggingFile: String,
    val nativeDirectory: String,
)

internal data class LaunchPlan(
    val javaArguments: List<String>,
    val mainClass: String,
    val gameArguments: List<String>,
    val sensitiveAccessToken: String?,
    val workingDirectory: String,
    val requiredJavaMajor: Int,
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
