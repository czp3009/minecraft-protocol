@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.hiczp.minecraft.demo.launcher

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
    fun argumentSerializerPreservesElementBoundaries() {
        val fixture = buildJsonObject {
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
        val arguments = launcherJson.decodeFromString<MojangArguments>(fixture.toString())

        assertEquals(2, arguments.game.size)
        val conditional = arguments.jvm.single() as MojangArgument.Conditional
        assertEquals(listOf("-Dfirst=true", "-Dpath=${'$'}{game_directory}"), conditional.values)
    }

    @Test
    fun orderedRulesUseLastMatchingAction() {
        val rules = listOf(
            MojangRule("allow", os = MojangRule.Os(name = "windows")),
            MojangRule("disallow", os = MojangRule.Os(name = "windows", arch = "amd64")),
        )

        assertFalse(RuleEvaluator.allows(rules, windows))
        val defaultUserRule = listOf(MojangRule("allow", features = mapOf("is_demo_user" to false)))
        assertTrue(RuleEvaluator.allows(defaultUserRule, windows))
    }

    @Test
    fun modernMetadataProducesIsolatedOrderedPlan() {
        val metadata = metadata(
            libraries = listOf(
                library("first", "a/first.jar", 'a'),
                MojangLibrary(
                    name = "linux-only",
                    downloads = MojangLibrary.Downloads(artifact = download("b/linux.jar", 'b')),
                    rules = listOf(MojangRule("allow", MojangRule.Os(name = "linux"))),
                ),
                library("second", "c/second.jar", 'c'),
            ),
        )

        val plan = MetadataPlanner.createInstallPlan(metadata, windows)

        assertEquals(listOf("libraries/a/first.jar", "libraries/c/second.jar", "client.jar"), plan.classpath)
        assertTrue(plan.downloads.any { it.relativePath == "assets/indexes/assets-id.json" })
        assertFalse(plan.downloads.any { "linux.jar" in it.relativePath })
    }

    @Test
    fun launchArgumentsExpandWithoutMergingValues() {
        val metadata = metadata(
            arguments = MojangArguments(
                jvm = listOf(
                    MojangArgument.Literal("-Djava.library.path=${'$'}{natives_directory}"),
                    MojangArgument.Literal("-cp"),
                    MojangArgument.Literal("${'$'}{classpath}"),
                ),
                game = listOf(
                    MojangArgument.Literal("--username"),
                    MojangArgument.Literal("${'$'}{auth_player_name}"),
                    MojangArgument.Literal("--accessToken"),
                    MojangArgument.Literal("${'$'}{auth_access_token}"),
                ),
            ),
        )
        val install = MetadataPlanner.createInstallPlan(metadata, windows)

        val launch = MetadataPlanner.createLaunchPlan(
            install,
            "C:/Launcher Root/minecraft/demo".toPath(),
            windows,
            MinecraftOnlineIdentity(Uuid.parse("0123456789abcdef0123456789abcdef"), "Player", "secret"),
            Uuid.parse("11111111111111111111111111111111"),
        )

        assertEquals("Player", launch.gameArguments[1])
        assertEquals("secret", launch.gameArguments[3])
        assertEquals("secret", launch.sensitiveAccessToken)
        assertTrue(launch.javaArguments[2].contains(";"))
        assertTrue(launch.workingDirectory.contains("Launcher Root"))
    }

    @Test
    fun offlineLaunchUsesFreshCompactAccessToken() {
        val metadata = metadata(
            arguments = MojangArguments(
                jvm = emptyList(),
                game = listOf(
                    MojangArgument.Literal("--accessToken"),
                    MojangArgument.Literal("${'$'}{auth_access_token}"),
                ),
            ),
        )
        val install = MetadataPlanner.createInstallPlan(metadata, windows)
        val identity = MinecraftOfflineIdentity("Player")
        fun createPlan() = MetadataPlanner.createLaunchPlan(
            install,
            "C:/Launcher Root/minecraft/demo".toPath(),
            windows,
            identity,
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
    fun unsafeAndLegacyMetadataAreRejected() {
        assertFailsWith<IllegalArgumentException> { validateRelativePath("../client.jar", "test") }
        assertFailsWith<IllegalArgumentException> { validateSinglePathComponent("../demo", "test") }
        assertFailsWith<IllegalArgumentException> {
            MetadataPlanner.createInstallPlan(
                metadata(
                    libraries = listOf(
                        MojangLibrary(
                            name = "legacy",
                            downloads = MojangLibrary.Downloads(artifact = download("legacy.jar", 'd')),
                            natives = mapOf("windows" to "natives-windows"),
                        ),
                    ),
                ),
                windows,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MetadataPlanner.createAssetDownloads(AssetIndex(emptyMap(), virtual = true))
        }
    }

    private fun metadata(
        libraries: List<MojangLibrary> = listOf(library("library", "lib/library.jar", 'b')),
        arguments: MojangArguments = MojangArguments(
            game = listOf(MojangArgument.Literal("--username"), MojangArgument.Literal("${'$'}{auth_player_name}")),
            jvm = listOf(MojangArgument.Literal("-cp"), MojangArgument.Literal("${'$'}{classpath}")),
        ),
    ) = VersionMetadata(
        id = "demo",
        type = "release",
        mainClass = "example.Main",
        assets = "assets-id",
        assetIndex = AssetIndexDownload("assets-id", "https://test/assets", sha('d'), 2),
        downloads = VersionDownloads(Download("https://test/client", sha('e'), 2)),
        libraries = libraries,
        arguments = arguments,
        javaVersion = VersionMetadata.JavaVersion("java-runtime", 21),
    )

    private fun library(name: String, path: String, hash: Char) = MojangLibrary(
        name = name,
        downloads = MojangLibrary.Downloads(artifact = download(path, hash)),
    )

    private fun download(path: String, hash: Char) = Download("https://test/$path", sha(hash), 2, path)
}

internal fun sha(character: Char): String = character.toString().repeat(40)
