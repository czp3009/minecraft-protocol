package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.distribution.metadata.*
import com.hiczp.minecraft.protocol.auth.MinecraftOfflineIdentity
import com.hiczp.minecraft.protocol.auth.MinecraftOnlineIdentity
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import kotlin.test.*
import kotlin.uuid.Uuid

class MetadataPlannerTest {
    private val windows = LauncherPlatform("windows", "x86_64", ";", "windows-x86_64", "10.0.22631")

    @Test
    fun decodedArgumentsExpandWithoutMergingElementBoundaries() {
        val fixture = buildJsonObject {
            put("default-user-jvm", buildJsonArray {})
            put(
                "game",
                buildJsonArray {
                    add(JsonPrimitive("--username"))
                    add(JsonPrimitive("${'$'}{auth_player_name}"))
                },
            )
            put(
                "jvm",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(
                                "rules",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("action", "allow")
                                            put("os", buildJsonObject { put("name", "windows") })
                                        },
                                    )
                                },
                            )
                            put(
                                "value",
                                buildJsonArray {
                                    add(JsonPrimitive("-Dfirst=true"))
                                    add(JsonPrimitive("-Dpath=${'$'}{game_directory}"))
                                },
                            )
                        },
                    )
                },
            )
        }
        val minecraftArguments = launcherJson.decodeFromString<MinecraftArguments>(fixture.toString())

        assertEquals(2, minecraftArguments.game.size)
        assertEquals(
            listOf("-Dfirst=true", "-Dpath=C:/Launcher Root"),
            MetadataPlanner.expandArguments(
                minecraftArguments.jvm,
                windows,
                mapOf("game_directory" to "C:/Launcher Root")
            ),
        )
    }

    @Test
    fun orderedRulesUseLastMatchingAction() {
        val rules = listOf(
            MinecraftRule("allow", os = MinecraftOperatingSystemRule(name = "windows")),
            MinecraftRule("disallow", os = MinecraftOperatingSystemRule(name = "windows", arch = "amd64")),
        )

        assertFalse(RuleEvaluator.allows(rules, windows))
        val defaultUserRule = listOf(MinecraftRule("allow", features = mapOf("is_demo_user" to false)))
        assertTrue(RuleEvaluator.allows(defaultUserRule, windows))
    }

    @Test
    fun osVersionRangesOnOtherPlatformsAreSkipped() {
        val rules = listOf(
            MinecraftRule("allow", os = MinecraftOperatingSystemRule(name = "linux")),
            MinecraftRule(
                "disallow",
                os = MinecraftOperatingSystemRule(
                    name = "windows",
                    versionRange = MinecraftOperatingSystemVersionRange(min = "10.0.17134"),
                ),
            ),
        )
        val linux = LauncherPlatform("linux", "x86_64", ":", "linux-x86_64", "not-used-by-this-rule")

        assertTrue(RuleEvaluator.allows(rules, linux))
        val architectureRule = MinecraftRule(
            "allow",
            os = MinecraftOperatingSystemRule(
                name = "windows",
                arch = "x86",
                versionRange = MinecraftOperatingSystemVersionRange(min = "10.0.17134"),
            ),
        )
        assertFalse(RuleEvaluator.allows(listOf(architectureRule), windows.copy(osVersion = "not-used-by-this-rule")))
    }

    @Test
    fun collectorArgumentGroupsMatchTheirWindowsVersionRangesIndependently() {
        val minecraftArguments = launcherJson.decodeFromString<MinecraftArguments>(
            """
            {
              "default-user-jvm": [
                {
                  "rules": [
                    {"action": "allow", "os": {"name": "osx"}},
                    {"action": "allow", "os": {"name": "linux"}},
                    {"action": "allow", "os": {"name": "windows", "versionRange": {"min": "10.0.17134"}}}
                  ],
                  "value": ["-XX:+UseZGC"]
                },
                {
                  "rules": [{"action": "allow", "os": {"name": "windows", "versionRange": {"max": "10.0.17134"}}}],
                  "value": ["-XX:+UseG1GC"]
                }
              ],
              "jvm": [],
              "game": []
            }
            """.trimIndent(),
        )

        for (osVersion in listOf("10.0.17133", "10.0.15063.2500")) {
            assertEquals(
                listOf("-XX:+UseG1GC"),
                MetadataPlanner.expandArguments(
                    minecraftArguments.defaultUserJvm,
                    windows.copy(osVersion = osVersion),
                    emptyMap()
                ),
            )
        }
        for (osVersion in listOf("10.0.17134", "10.0.17134.0")) {
            assertEquals(
                listOf("-XX:+UseZGC", "-XX:+UseG1GC"),
                MetadataPlanner.expandArguments(
                    minecraftArguments.defaultUserJvm,
                    windows.copy(osVersion = osVersion),
                    emptyMap()
                ),
            )
        }
        for (osVersion in listOf("10.0.17134.1", "10.0.22631.1")) {
            assertEquals(
                listOf("-XX:+UseZGC"),
                MetadataPlanner.expandArguments(
                    minecraftArguments.defaultUserJvm,
                    windows.copy(osVersion = osVersion),
                    emptyMap()
                ),
            )
        }
    }

    @Test
    fun modernMetadataProducesIsolatedOrderedPlan() {
        val minecraftVersionMetadata = metadata(
            libraries = listOf(
                library("first", "a/first.jar", 'a'),
                MinecraftLibrary(
                    name = "linux-only",
                    downloads = MinecraftLibraryDownloads(artifact = download("b/linux.jar", 'b')),
                    rules = listOf(MinecraftRule("allow", os = MinecraftOperatingSystemRule(name = "linux"))),
                ),
                library("second", "c/second.jar", 'c'),
            ),
        )

        val installPlan = MetadataPlanner.createInstallPlan(minecraftVersionMetadata, windows)

        assertEquals(listOf("libraries/a/first.jar", "libraries/c/second.jar", "client.jar"), installPlan.classpath)
        assertEquals("assets/indexes/assets-id.json", installPlan.assetIndexPath)
        assertEquals(
            listOf("client.jar", "libraries/a/first.jar", "libraries/c/second.jar", "logging/client.xml"),
            installPlan.downloads.map { it.relativePath },
        )
        assertFalse(installPlan.downloads.any { "linux.jar" in it.relativePath })
    }

    @Test
    fun launchArgumentsExpandWithoutMergingValues() {
        val minecraftVersionMetadata = metadata(
            minecraftArguments = MinecraftArguments(
                defaultUserJvm = listOf(
                    MinecraftArgument.Literal("-Xmx2G"),
                    MinecraftArgument.Expanded(
                        rules = listOf(MinecraftRule("allow", os = MinecraftOperatingSystemRule(name = "windows"))),
                        value = MinecraftArgumentValue.Multiple(
                            listOf(
                                "-XX:+UseG1GC",
                                "-Droot=${'$'}{game_directory}"
                            )
                        ),
                    ),
                    MinecraftArgument.Expanded(
                        rules = listOf(MinecraftRule("allow", os = MinecraftOperatingSystemRule(name = "linux"))),
                        value = MinecraftArgumentValue.Single("-Dlinux=true"),
                    ),
                    MinecraftArgument.Expanded(
                        rules = listOf(MinecraftRule("allow", features = mapOf("is_demo_user" to false))),
                        value = MinecraftArgumentValue.Single("-Dplayer=${'$'}{auth_player_name}"),
                    ),
                ),
                jvm = listOf(
                    MinecraftArgument.Literal("-Djava.library.path=${'$'}{natives_directory}"),
                    MinecraftArgument.Literal("-cp"),
                    MinecraftArgument.Literal("${'$'}{classpath}"),
                ),
                game = listOf(
                    MinecraftArgument.Literal("--username"),
                    MinecraftArgument.Literal("${'$'}{auth_player_name}"),
                    MinecraftArgument.Literal("--accessToken"),
                    MinecraftArgument.Literal("${'$'}{auth_access_token}"),
                ),
            ),
        )
        val installPlan = MetadataPlanner.createInstallPlan(minecraftVersionMetadata, windows)

        val launchPlan = MetadataPlanner.createLaunchPlan(
            installPlan,
            "C:/Launcher Root/minecraft/demo".toPath(),
            windows,
            MinecraftOnlineIdentity(Uuid.parse("0123456789abcdef0123456789abcdef"), "Player", "secret"),
            Uuid.parse("11111111111111111111111111111111"),
        )

        assertEquals("Player", launchPlan.gameArguments[1])
        assertEquals("secret", launchPlan.gameArguments[3])
        assertEquals("secret", launchPlan.sensitiveAccessToken)
        assertEquals(
            listOf("-Xmx2G", "-XX:+UseG1GC", "-Droot=C:/Launcher Root/minecraft/demo", "-Dplayer=Player"),
            launchPlan.javaArguments.take(4),
        )
        assertEquals("-Djava.library.path=C:/Launcher Root/minecraft/demo/natives", launchPlan.javaArguments[4])
        assertEquals("-cp", launchPlan.javaArguments[5])
        assertTrue(launchPlan.javaArguments[6].contains(";"))
        assertEquals(
            "-Dlog4j.configurationFile=C:/Launcher Root/minecraft/demo/logging/client.xml",
            launchPlan.javaArguments[7]
        )
        assertEquals(8, launchPlan.javaArguments.size)
        assertTrue(launchPlan.workingDirectory.contains("Launcher Root"))
    }

    @Test
    fun offlineLaunchUsesFreshCompactAccessToken() {
        val minecraftVersionMetadata = metadata(
            minecraftArguments = MinecraftArguments(
                defaultUserJvm = emptyList(),
                jvm = emptyList(),
                game = listOf(
                    MinecraftArgument.Literal("--accessToken"),
                    MinecraftArgument.Literal("${'$'}{auth_access_token}"),
                ),
            ),
        )
        val installPlan = MetadataPlanner.createInstallPlan(minecraftVersionMetadata, windows)
        val minecraftOfflineIdentity = MinecraftOfflineIdentity("Player")
        fun createPlan() = MetadataPlanner.createLaunchPlan(
            installPlan,
            "C:/Launcher Root/minecraft/demo".toPath(),
            windows,
            minecraftOfflineIdentity,
            Uuid.parse("11111111111111111111111111111111"),
        )

        val first = createPlan()
        val second = createPlan()
        val firstToken = first.gameArguments.last()
        val secondToken = second.gameArguments.last()

        assertEquals(32, firstToken.length)
        assertEquals(firstToken, Uuid.parse(firstToken).toHexString())
        assertNotEquals(firstToken, secondToken)
        assertNull(first.sensitiveAccessToken)
        assertNull(second.sensitiveAccessToken)
    }

    @Test
    fun unsafeInstallationPathsAreRejected() {
        assertFailsWith<IllegalArgumentException> { validateRelativePath("../client.jar", "test") }
        assertFailsWith<IllegalArgumentException> { validateSinglePathComponent("../demo", "test") }
        assertFailsWith<IllegalArgumentException> {
            MetadataPlanner.createInstallPlan(
                metadata(
                    libraries = listOf(library("unsafe", "../outside.jar", 'd')),
                ),
                windows,
            )
        }
    }

    @Test
    fun assetDownloadsUseNormalizedPublicDescriptorsAndDeduplicateObjects() {
        val downloads = MetadataPlanner.createAssetDownloads(
            MinecraftAssetIndex(
                mapOf(
                    "minecraft/first.ogg" to MinecraftAssetObject(sha('A'), 2),
                    "minecraft/second.ogg" to MinecraftAssetObject(sha('a'), 2),
                ),
            ),
        )

        assertEquals(
            listOf(
                DownloadSpec(
                    MinecraftDownload(sha('a'), 2, "https://resources.download.minecraft.net/aa/${sha('a')}"),
                    "assets/objects/aa/${sha('a')}",
                ),
            ),
            downloads,
        )
    }

    private fun metadata(
        libraries: List<MinecraftLibrary> = listOf(library("library", "lib/library.jar", 'b')),
        minecraftArguments: MinecraftArguments = MinecraftArguments(
            defaultUserJvm = emptyList(),
            game = listOf(
                MinecraftArgument.Literal("--username"),
                MinecraftArgument.Literal("${'$'}{auth_player_name}")
            ),
            jvm = listOf(MinecraftArgument.Literal("-cp"), MinecraftArgument.Literal("${'$'}{classpath}")),
        ),
    ) = MinecraftVersionMetadata(
        id = "demo",
        type = "release",
        mainClass = "example.Main",
        assets = "assets-id",
        assetIndex = MinecraftAssetIndexReference("assets-id", sha('d'), 2, 2, "https://test/assets"),
        downloads = MinecraftVersionDownloads(
            client = MinecraftDownload(sha('e'), 2, "https://test/client"),
            server = MinecraftDownload(sha('f'), 2, "https://test/server"),
        ),
        libraries = libraries,
        arguments = minecraftArguments,
        javaVersion = MinecraftJavaVersion("java-runtime", 21),
        logging = MinecraftLoggingConfiguration(
            MinecraftClientLoggingConfiguration(
                "-Dlog4j.configurationFile=${'$'}{path}",
                MinecraftLoggingFile("client.xml", sha('a'), 2, "https://test/logging"),
                "log4j2-xml",
            ),
        ),
        complianceLevel = 1,
        minimumLauncherVersion = 1,
        releaseTime = "now",
        time = "now",
    )

    private fun library(name: String, path: String, hash: Char) = MinecraftLibrary(
        name = name,
        downloads = MinecraftLibraryDownloads(artifact = download(path, hash)),
    )

    private fun download(path: String, hash: Char) = MinecraftLibraryDownload(path, sha(hash), 2, "https://test/$path")
}

internal fun sha(character: Char): String = character.toString().repeat(40)
