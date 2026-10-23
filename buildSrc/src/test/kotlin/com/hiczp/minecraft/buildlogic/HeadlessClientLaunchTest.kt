package com.hiczp.minecraft.buildlogic

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class HeadlessClientLaunchTest {
    @Test
    fun resolvesPlatformRulesAndFabricOverridesWithoutEnablingOptionalGameFeatures() {
        val metadata = buildLogicJson.parseToJsonElement(
            """
            {
              "id": "fixture", "type": "release", "assetIndex": {"id": "assets"},
              "libraries": [
                {"name": "example:shared:1", "downloads": {"artifact": {"path": "shared-old.jar"}}},
                {"name": "example:native:1:windows", "rules": [{"action": "allow", "os": {"name": "windows"}}], "downloads": {"artifact": {"path": "windows.jar"}}},
                {"name": "example:native:1:linux", "rules": [{"action": "allow", "os": {"name": "linux"}}], "downloads": {"artifact": {"path": "linux.jar"}}}
              ],
              "arguments": {
                "jvm": ["-cp", "classpath", {"rules": [{"action": "allow"}, {"action": "disallow", "os": {"name": "windows"}}], "value": "unix-only"}],
                "game": ["--username", "player", {"rules": [{"action": "allow", "features": {"is_demo_user": true}}], "value": "--demo"}]
              }
            }
            """.trimIndent(),
        ).jsonObject
        val fabricProfile = buildLogicJson.parseToJsonElement(
            """
            {"mainClass": "fabric.Main", "libraries": [{"name": "example:shared:2"}], "arguments": {"jvm": ["-Dfabric=true"]}}
            """.trimIndent(),
        ).jsonObject
        val windows = headlessClientLaunchManifest(metadata, fabricProfile, "windows", "amd64", "10.0")
        assertEquals(
            listOf("libraries/example/shared/2/shared-2.jar", "libraries/windows.jar", "versions/fixture/fixture.jar"),
            windows.getValue("classpath").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("-cp", "classpath", "-Dfabric=true"),
            windows.getValue("jvm_arguments").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("--username", "player"),
            windows.getValue("game_arguments").jsonArray.map { it.jsonPrimitive.content })
        assertEquals("fabric.Main", windows.getValue("main_class").jsonPrimitive.content)
        val linux = headlessClientLaunchManifest(metadata, fabricProfile, "linux", "amd64", "6.0")
        assertEquals(
            listOf("libraries/example/shared/2/shared-2.jar", "libraries/linux.jar", "versions/fixture/fixture.jar"),
            linux.getValue("classpath").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            listOf("-cp", "classpath", "unix-only", "-Dfabric=true"),
            linux.getValue("jvm_arguments").jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
