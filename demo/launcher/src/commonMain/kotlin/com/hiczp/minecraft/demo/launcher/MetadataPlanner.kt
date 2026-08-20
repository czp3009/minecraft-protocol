@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.protocol.auth.MinecraftIdentity
import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.auth.MinecraftOnlineIdentity
import com.kgit2.kommand.Platform
import com.kgit2.kommand.platform
import okio.Path
import okio.Path.Companion.toPath
import kotlin.uuid.Uuid

internal data class LauncherPlatform(
    val osName: String,
    val architecture: String,
    val classpathSeparator: String,
    val platformKey: String,
    val osVersion: String? = null,
) {
    companion object {
        fun current(): LauncherPlatform = when (platform) {
            Platform.MINGW_X64 -> LauncherPlatform("windows", "x86_64", ";", "windows-x86_64")
            Platform.LINUX_X64 -> LauncherPlatform("linux", "x86_64", ":", "linux-x86_64")
            Platform.LINUX_ARM64 -> LauncherPlatform("linux", "aarch64", ":", "linux-aarch64")
            Platform.MACOS_X64 -> LauncherPlatform("osx", "x86_64", ":", "osx-x86_64")
            Platform.MACOS_ARM64 -> LauncherPlatform("osx", "aarch64", ":", "osx-aarch64")
        }
    }
}

internal object MetadataPlanner {
    fun createInstallPlan(metadata: VersionMetadata, platform: LauncherPlatform): InstallPlan {
        val versionId = validateSinglePathComponent(metadata.id, "version ID")
        require(metadata.minecraftArguments == null && metadata.arguments != null) {
            "This demo does not support single-string minecraftArguments metadata"
        }
        require(metadata.assetIndex.size >= 0L)
        val downloads = mutableListOf(
            metadata.downloads.client.toSpec("client.jar"),
        )
        val classpath = mutableListOf<String>()

        metadata.libraries.forEach { library ->
            if (!RuleEvaluator.allows(library.rules, platform)) return@forEach
            require(library.natives == null && library.extract == null) {
                "This demo does not support legacy native classifiers or extraction: ${library.name}"
            }
            val artifact = requireNotNull(library.downloads?.artifact) {
                "Applicable library has no download artifact: ${library.name}"
            }
            val artifactPath = validateRelativePath(requireNotNull(artifact.path), "library artifact path")
            val relativePath = "libraries/$artifactPath"
            downloads += artifact.toSpec(relativePath)
            classpath += relativePath
        }
        classpath += "client.jar"

        val assetIndexId = validateSinglePathComponent(metadata.assetIndex.id, "asset index ID")
        val assetIndex = DownloadSpec(
            url = metadata.assetIndex.url,
            sha1 = validateSha1(metadata.assetIndex.sha1),
            size = metadata.assetIndex.size,
            relativePath = "assets/indexes/$assetIndexId.json",
        )
        downloads += assetIndex

        val loggingFile = metadata.logging?.client?.file?.let { loggingDownload ->
            val loggingId = validateSinglePathComponent(requireNotNull(loggingDownload.id), "logging file ID")
            val relativePath = "logging/$loggingId"
            downloads += loggingDownload.toSpec(relativePath)
            relativePath
        }

        return InstallPlan(
            version = metadata,
            gameRootName = versionId,
            downloads = downloads.distinctBy(DownloadSpec::relativePath),
            assetIndex = assetIndex,
            classpath = classpath,
            loggingFile = loggingFile,
            nativeDirectory = "natives",
        )
    }

    fun createAssetDownloads(index: AssetIndex): List<DownloadSpec> {
        require(!index.virtual && !index.mapToResources) {
            "This demo does not support virtual or resource-mapped assets"
        }
        return index.objects.values.distinctBy(AssetObject::hash).map { asset ->
            val hash = validateSha1(asset.hash)
            require(asset.size >= 0L)
            DownloadSpec(
                url = "https://resources.download.minecraft.net/${hash.take(2)}/$hash",
                sha1 = hash,
                size = asset.size,
                relativePath = "assets/objects/${hash.take(2)}/$hash",
            )
        }
    }

    fun createLaunchPlan(
        installPlan: InstallPlan,
        gameRoot: Path,
        platform: LauncherPlatform,
        identity: MinecraftIdentity,
        installationId: Uuid,
    ): LaunchPlan {
        val metadata = installPlan.version
        val arguments = requireNotNull(metadata.arguments)
        val absoluteGameRoot = gameRoot.toString()
        val libraryDirectory = (gameRoot / "libraries").toString()
        val assetsRoot = (gameRoot / "assets").toString()
        val nativeDirectory = (gameRoot / installPlan.nativeDirectory).toString()
        val classpath = installPlan.classpath.joinToString(platform.classpathSeparator) { relative ->
            resolveSafe(gameRoot, relative).toString()
        }
        val accessToken = identity.createLaunchAccessToken()
        val placeholders = mapOf(
            "auth_player_name" to identity.name,
            "auth_uuid" to identity.id.toHexString(),
            "auth_access_token" to accessToken,
            "auth_xuid" to "",
            "version_name" to metadata.id,
            "version_type" to metadata.type,
            "game_directory" to absoluteGameRoot,
            "assets_root" to assetsRoot,
            "assets_index_name" to metadata.assets,
            "natives_directory" to nativeDirectory,
            "library_directory" to libraryDirectory,
            "classpath" to classpath,
            "classpath_separator" to platform.classpathSeparator,
            "launcher_name" to LAUNCHER_NAME,
            "launcher_version" to LAUNCHER_VERSION,
            "clientid" to installationId.toString(),
        )
        val javaArguments = expandArguments(arguments.jvm, platform, placeholders).toMutableList()
        metadata.logging?.client?.let { logging ->
            val loggingFile = requireNotNull(installPlan.loggingFile)
            val absoluteLoggingFile = resolveSafe(gameRoot, loggingFile).toString()
            javaArguments += logging.argument.replace($$"${path}", absoluteLoggingFile)
        }
        val gameArguments = expandArguments(arguments.game, platform, placeholders)
        return LaunchPlan(
            javaArguments = javaArguments,
            mainClass = metadata.mainClass,
            gameArguments = gameArguments,
            sensitiveAccessToken = accessToken.takeIf { identity is MinecraftOnlineIdentity },
            workingDirectory = absoluteGameRoot,
            requiredJavaMajor = metadata.javaVersion?.majorVersion,
        )
    }

    fun expandArguments(
        arguments: List<MojangArgument>,
        platform: LauncherPlatform,
        placeholders: Map<String, String>,
    ): List<String> = buildList {
        arguments.forEach { argument ->
            when (argument) {
                is MojangArgument.Literal -> add(replacePlaceholders(argument.value, placeholders))
                is MojangArgument.Conditional -> if (RuleEvaluator.allows(argument.rules, platform)) {
                    argument.values.forEach { add(replacePlaceholders(it, placeholders)) }
                }
            }
        }
    }
}

private fun MinecraftIdentity.createLaunchAccessToken(): String = when (this) {
    is MinecraftOfflineIdentity -> Uuid.random().toHexString()
    is MinecraftOnlineIdentity -> accessToken
}

internal object RuleEvaluator {
    private val knownFeatures = setOf(
        "is_demo_user",
        "has_custom_resolution",
        "has_quick_plays_support",
        "is_quick_play_singleplayer",
        "is_quick_play_multiplayer",
        "is_quick_play_realms",
    )

    fun allows(
        rules: List<MojangRule>?,
        platform: LauncherPlatform,
        enabledFeatures: Set<String> = emptySet(),
    ): Boolean {
        if (rules.isNullOrEmpty()) return true
        var allowed = false
        rules.forEach { rule ->
            require(rule.action == "allow" || rule.action == "disallow") {
                "Unknown Mojang rule action: ${rule.action}"
            }
            if (matches(rule, platform, enabledFeatures)) {
                allowed = rule.action == "allow"
            }
        }
        return allowed
    }

    private fun matches(rule: MojangRule, platform: LauncherPlatform, enabledFeatures: Set<String>): Boolean {
        val osMatches = rule.os?.let { os ->
            val expectedName = os.name?.also {
                require(it in setOf("windows", "linux", "osx")) { "Unknown Mojang OS name: $it" }
            }
            val expectedArchitecture = os.arch?.let(::normalizeArchitecture)
            val regexMatches = os.version?.let { expression ->
                val actualVersion = requireNotNull(platform.osVersion) {
                    "Cannot safely evaluate an OS version rule on this platform"
                }
                Regex(expression).containsMatchIn(actualVersion)
            } ?: true
            val rangeMatches = os.versionRange?.let { range ->
                val actualVersion = requireNotNull(platform.osVersion) {
                    "Cannot safely evaluate an OS version range on this platform"
                }
                versionInRange(actualVersion, range.min, range.max)
            } ?: true
            (expectedName == null || expectedName == platform.osName) &&
                    (expectedArchitecture == null || expectedArchitecture == platform.architecture) &&
                    regexMatches && rangeMatches
        } ?: true
        val featuresMatch = rule.features?.all { (name, expected) ->
            require(name in knownFeatures) { "Unknown Mojang feature rule: $name" }
            (name in enabledFeatures) == expected
        } ?: true
        return osMatches && featuresMatch
    }
}

internal fun validateSinglePathComponent(value: String, description: String): String {
    require(value.isNotBlank()) { "$description cannot be blank" }
    require(value != "." && value != "..") { "$description contains traversal" }
    require('/' !in value && '\\' !in value && ':' !in value) { "$description is not a single safe component" }
    return value
}

internal fun validateRelativePath(value: String, description: String): String {
    require(value.isNotBlank() && !value.startsWith('/') && !value.startsWith('\\')) {
        "$description must be relative"
    }
    require('\\' !in value && ':' !in value) { "$description contains a platform separator or drive prefix" }
    val components = value.split('/')
    require(components.all { it.isNotBlank() && it != "." && it != ".." }) {
        "$description contains traversal or an empty component"
    }
    return components.joinToString("/")
}

internal fun resolveSafe(root: Path, relative: String): Path {
    val validated = validateRelativePath(relative, "artifact path")
    return root / validated.toPath()
}

internal fun validateSha1(value: String): String {
    require(value.length == 40 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        "Invalid SHA-1 value"
    }
    return value.lowercase()
}

private fun Download.toSpec(relativePath: String): DownloadSpec {
    require(size >= 0L)
    return DownloadSpec(url, validateSha1(sha1), size, validateRelativePath(relativePath, "download target"))
}

private fun replacePlaceholders(value: String, placeholders: Map<String, String>): String {
    val expanded = PLACEHOLDER_PATTERN.replace(value) { match ->
        val name = match.groupValues[1]
        requireNotNull(placeholders[name]) { "Unknown launch placeholder: $name" }
    }
    require(!PLACEHOLDER_PATTERN.containsMatchIn(expanded)) { "Unresolved launch placeholder in argument" }
    return expanded
}

private fun normalizeArchitecture(value: String): String = when (value.lowercase()) {
    "x86", "i386", "i686" -> "x86"
    "x86_64", "x64", "amd64" -> "x86_64"
    "aarch64", "arm64" -> "aarch64"
    else -> throw IllegalArgumentException("Unknown Mojang architecture: $value")
}

private fun versionInRange(actual: String, min: String?, max: String?): Boolean {
    val actualParts = numericVersion(actual)
    val aboveMin = min?.let { compareVersions(actualParts, numericVersion(it)) >= 0 } ?: true
    val belowMax = max?.let { compareVersions(actualParts, numericVersion(it)) <= 0 } ?: true
    return aboveMin && belowMax
}

private fun numericVersion(value: String): List<Int> {
    val values = Regex("\\d+").findAll(value).map { it.value.toInt() }.toList()
    require(values.isNotEmpty()) { "Cannot safely compare OS version: $value" }
    return values
}

private fun compareVersions(left: List<Int>, right: List<Int>): Int {
    repeat(maxOf(left.size, right.size)) { index ->
        val comparison = (left.getOrNull(index) ?: 0).compareTo(right.getOrNull(index) ?: 0)
        if (comparison != 0) return comparison
    }
    return 0
}

private val PLACEHOLDER_PATTERN = Regex("""\$\{([^}]+)}""")
