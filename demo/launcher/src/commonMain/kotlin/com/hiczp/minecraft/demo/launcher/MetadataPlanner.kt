package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.distribution.metadata.*
import com.hiczp.minecraft.protocol.auth.MinecraftIdentity
import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.auth.MinecraftOnlineIdentity
import okio.Path
import okio.Path.Companion.toPath
import kotlin.uuid.Uuid

internal object MetadataPlanner {
    fun createInstallPlan(
        minecraftVersionMetadata: MinecraftVersionMetadata,
        launcherPlatform: LauncherPlatform,
    ): InstallPlan {
        val downloads = mutableListOf(
            DownloadSpec(minecraftVersionMetadata.downloads.client, "client.jar"),
        )
        val classpath = mutableListOf<String>()

        minecraftVersionMetadata.libraries.forEach { minecraftLibrary ->
            if (!RuleEvaluator.allows(minecraftLibrary.rules, launcherPlatform)) return@forEach
            val artifact = minecraftLibrary.downloads.artifact
            val relativePath = "libraries/${artifact.path}"
            downloads += DownloadSpec(artifact.toDownload(), relativePath)
            classpath += relativePath
        }
        classpath += "client.jar"

        val assetIndexId = validateSinglePathComponent(minecraftVersionMetadata.assetIndex.id, "asset index ID")
        val minecraftLoggingFile = minecraftVersionMetadata.logging.client.file
        val loggingId = validateSinglePathComponent(minecraftLoggingFile.id, "logging file ID")
        val loggingFile = "logging/$loggingId"
        downloads += DownloadSpec(minecraftLoggingFile.toDownload(), loggingFile)

        return InstallPlan(
            minecraftVersionMetadata = minecraftVersionMetadata,
            downloads = downloads.distinctBy(DownloadSpec::relativePath),
            assetIndexPath = "assets/indexes/$assetIndexId.json",
            classpath = classpath,
            loggingFile = loggingFile,
            nativeDirectory = "natives",
        )
    }

    fun createAssetDownloads(minecraftAssetIndex: MinecraftAssetIndex): List<DownloadSpec> =
        minecraftAssetIndex.objects.values.distinctBy { it.hash.lowercase() }.map { minecraftAssetObject ->
            DownloadSpec(
                minecraftAssetObject.toDownload(),
                "assets/objects/${minecraftAssetPath(minecraftAssetObject.hash)}",
            )
        }

    fun createLaunchPlan(
        installPlan: InstallPlan,
        gameRoot: Path,
        launcherPlatform: LauncherPlatform,
        minecraftIdentity: MinecraftIdentity,
        installationId: Uuid,
    ): LaunchPlan {
        val minecraftVersionMetadata = installPlan.minecraftVersionMetadata
        val minecraftArguments = minecraftVersionMetadata.arguments
        val absoluteGameRoot = gameRoot.toString()
        val libraryDirectory = (gameRoot / "libraries").toString()
        val assetsRoot = (gameRoot / "assets").toString()
        val nativeDirectory = (gameRoot / installPlan.nativeDirectory).toString()
        val classpath = installPlan.classpath.joinToString(launcherPlatform.classpathSeparator) { relative ->
            resolveSafe(gameRoot, relative).toString()
        }
        val accessToken = minecraftIdentity.createLaunchAccessToken()
        val placeholders = mapOf(
            "auth_player_name" to minecraftIdentity.name,
            "auth_uuid" to minecraftIdentity.id.toHexString(),
            "auth_access_token" to accessToken,
            "auth_xuid" to "",
            "version_name" to minecraftVersionMetadata.id,
            "version_type" to minecraftVersionMetadata.type,
            "game_directory" to absoluteGameRoot,
            "assets_root" to assetsRoot,
            "assets_index_name" to minecraftVersionMetadata.assets,
            "natives_directory" to nativeDirectory,
            "library_directory" to libraryDirectory,
            "classpath" to classpath,
            "classpath_separator" to launcherPlatform.classpathSeparator,
            "launcher_name" to LAUNCHER_NAME,
            "launcher_version" to LAUNCHER_VERSION,
            "clientid" to installationId.toString(),
        )
        val javaArguments = buildList {
            addAll(expandArguments(minecraftArguments.defaultUserJvm, launcherPlatform, placeholders))
            addAll(expandArguments(minecraftArguments.jvm, launcherPlatform, placeholders))
            val absoluteLoggingFile = resolveSafe(gameRoot, installPlan.loggingFile).toString()
            add(minecraftVersionMetadata.logging.client.argument.replace($$"${path}", absoluteLoggingFile))
        }
        val gameArguments = expandArguments(minecraftArguments.game, launcherPlatform, placeholders)
        return LaunchPlan(
            javaArguments = javaArguments,
            mainClass = minecraftVersionMetadata.mainClass,
            gameArguments = gameArguments,
            sensitiveAccessToken = accessToken.takeIf { minecraftIdentity is MinecraftOnlineIdentity },
            workingDirectory = absoluteGameRoot,
            requiredJavaMajor = minecraftVersionMetadata.javaVersion.majorVersion,
        )
    }

    fun expandArguments(
        arguments: List<MinecraftArgument>,
        launcherPlatform: LauncherPlatform,
        placeholders: Map<String, String>,
    ): List<String> = buildList {
        arguments.forEach { minecraftArgument ->
            when (minecraftArgument) {
                is MinecraftArgument.Literal -> add(replacePlaceholders(minecraftArgument.value, placeholders))
                is MinecraftArgument.Expanded -> if (RuleEvaluator.allows(minecraftArgument.rules, launcherPlatform)) {
                    when (val minecraftArgumentValue = minecraftArgument.value) {
                        is MinecraftArgumentValue.Single ->
                            add(replacePlaceholders(minecraftArgumentValue.value, placeholders))

                        is MinecraftArgumentValue.Multiple ->
                            minecraftArgumentValue.values.forEach { add(replacePlaceholders(it, placeholders)) }
                    }
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
        rules: List<MinecraftRule>,
        launcherPlatform: LauncherPlatform,
        enabledFeatures: Set<String> = emptySet(),
    ): Boolean {
        if (rules.isEmpty()) return true
        var allowed = false
        rules.forEach { minecraftRule ->
            require(minecraftRule.action == "allow" || minecraftRule.action == "disallow") {
                "Unknown Mojang rule action: ${minecraftRule.action}"
            }
            if (matches(minecraftRule, launcherPlatform, enabledFeatures)) {
                allowed = minecraftRule.action == "allow"
            }
        }
        return allowed
    }

    private fun matches(
        minecraftRule: MinecraftRule,
        launcherPlatform: LauncherPlatform,
        enabledFeatures: Set<String>
    ): Boolean {
        val osMatches = minecraftRule.os?.let { os ->
            val expectedName = os.name?.also {
                require(it in setOf("windows", "linux", "osx")) { "Unknown Mojang OS name: $it" }
            }
            val expectedArchitecture = os.arch?.let(::normalizeArchitecture)
            (expectedName == null || expectedName == launcherPlatform.osName) &&
                    (expectedArchitecture == null || expectedArchitecture == launcherPlatform.architecture) &&
                    (os.versionRange?.let { versionRange ->
                        versionInRange(launcherPlatform.osVersion, versionRange.min, versionRange.max)
                    } ?: true)
        } ?: true
        val featuresMatch = minecraftRule.features?.all { (name, expected) ->
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

internal fun validateSha1(value: String) {
    require(value.length == 40 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
        "Invalid SHA-1 value"
    }
}

private fun replacePlaceholders(value: String, placeholders: Map<String, String>): String {
    val expanded = PLACEHOLDER_PATTERN.replace(value) { matchResult ->
        val name = matchResult.groupValues[1]
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
