package com.hiczp.minecraft.distribution.metadata

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MinecraftDistributionMetadataModelsTest {
    @Test
    fun manifestV2RequiresItsIntegrityAndComplianceFields() {
        val minecraftVersionManifest = MODEL_JSON.decodeFromString<MinecraftVersionManifest>(VERSION_MANIFEST_V2_JSON)

        assertEquals("modern-release", minecraftVersionManifest.latest.release)
        assertEquals(testModelSha1('a'), minecraftVersionManifest.versions.single().sha1)
        assertEquals(1, minecraftVersionManifest.versions.single().complianceLevel)
        assertFailsWith<SerializationException> {
            MODEL_JSON.decodeFromString<MinecraftVersionManifest>(VERSION_MANIFEST_V1_SHAPE_JSON)
        }
    }

    @Test
    fun argumentSerializerPreservesEveryModernValueShape() {
        val minecraftArguments = MODEL_JSON.decodeFromString<MinecraftArguments>(MINECRAFT_ARGUMENTS_JSON)

        val defaultUserJvmArgument =
            assertIs<MinecraftArgument.Expanded>(minecraftArguments.defaultUserJvm.single())
        assertEquals(emptyList(), defaultUserJvmArgument.rules)
        assertEquals(
            listOf("-Xms2G"),
            assertIs<MinecraftArgumentValue.Multiple>(defaultUserJvmArgument.value).values,
        )
        assertEquals(
            "--username",
            assertIs<MinecraftArgument.Literal>(minecraftArguments.game.first()).value,
        )
        assertEquals(
            "-Xss1M",
            assertIs<MinecraftArgumentValue.Single>(
                assertIs<MinecraftArgument.Expanded>(minecraftArguments.jvm.single()).value,
            ).value,
        )

        val encodedArguments = Json.parseToJsonElement(MODEL_JSON.encodeToString(minecraftArguments)).jsonObject
        val encodedDefaultUserJvm = encodedArguments.getValue("default-user-jvm").jsonArray.single().jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("-Xms2G"))), encodedDefaultUserJvm.getValue("value"))
        assertEquals(
            JsonPrimitive("-Xss1M"),
            encodedArguments.getValue("jvm").jsonArray.single().jsonObject.getValue("value"),
        )

        assertFailsWith<SerializationException> {
            MODEL_JSON.decodeFromString<MinecraftArgument>("1")
        }
        assertFailsWith<SerializationException> {
            MODEL_JSON.decodeFromString<MinecraftArgument>("""{"value":["valid",1]}""")
        }
    }

    @Test
    fun modernArgumentsRequireDefaultUserJvm() {
        assertFailsWith<SerializationException> {
            MODEL_JSON.decodeFromString<MinecraftArguments>("""{"game":[],"jvm":[]}""")
        }
    }

    @Test
    fun runtimeCatalogKeepsDynamicPlatformsAndComponents() {
        val minecraftJavaRuntimeCatalog =
            MODEL_JSON.decodeFromString<MinecraftJavaRuntimeCatalog>(JAVA_RUNTIME_CATALOG_JSON)
        val minecraftJavaRuntimeEntry = minecraftJavaRuntimeCatalog.platforms
            .getValue("future-platform")
            .getValue("future-component")
            .single()

        assertEquals(100, minecraftJavaRuntimeEntry.availability.progress)
        assertEquals("25.0.1", minecraftJavaRuntimeEntry.version.name)
        assertEquals(
            emptyList(),
            minecraftJavaRuntimeCatalog.platforms.getValue("future-platform").getValue("empty-component"),
        )
        assertEquals(
            Json.parseToJsonElement(JAVA_RUNTIME_CATALOG_JSON),
            Json.parseToJsonElement(MODEL_JSON.encodeToString(minecraftJavaRuntimeCatalog)),
        )
    }

    @Test
    fun runtimeManifestPreservesFileDirectoryAndLinkVariants() {
        val minecraftJavaRuntimeManifest =
            MODEL_JSON.decodeFromString<MinecraftJavaRuntimeManifest>(JAVA_RUNTIME_MANIFEST_JSON)

        val file = assertIs<MinecraftJavaRuntimeFile.File>(
            minecraftJavaRuntimeManifest.files.getValue("bin/java"),
        )
        assertEquals(true, file.executable)
        assertEquals(7, file.downloads.raw.size)
        assertEquals(3, file.downloads.lzma?.size)
        assertEquals(
            MinecraftJavaRuntimeFile.Directory,
            minecraftJavaRuntimeManifest.files.getValue("lib"),
        )
        assertEquals(
            "../target",
            assertIs<MinecraftJavaRuntimeFile.Link>(
                minecraftJavaRuntimeManifest.files.getValue("link"),
            ).target,
        )
        assertEquals(
            Json.parseToJsonElement(JAVA_RUNTIME_MANIFEST_JSON),
            Json.parseToJsonElement(MODEL_JSON.encodeToString(minecraftJavaRuntimeManifest)),
        )

        assertFailsWith<SerializationException> {
            MODEL_JSON.decodeFromString<MinecraftJavaRuntimeManifest>("""{"files":{"entry":{"type":"device"}}}""")
        }
        assertFailsWith<SerializationException> {
            MODEL_JSON.decodeFromString<MinecraftJavaRuntimeManifest>("""{"files":{"entry":{"type":"file","executable":true}}}""")
        }
    }

    @Test
    fun assetObjectsResolveToDownloadDescriptorsWithoutChangingWireJson() {
        val minecraftAssetObject = MinecraftAssetObject(
            hash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01",
            size = 19,
        )

        val minecraftDownload = listOf(minecraftAssetObject)
            .map(MinecraftAssetObject::toDownload)
            .single()

        assertEquals("abcdef0123456789abcdef0123456789abcdef01", minecraftDownload.sha1)
        assertEquals(19, minecraftDownload.size)
        assertEquals(
            "https://resources.download.minecraft.net/ab/abcdef0123456789abcdef0123456789abcdef01",
            minecraftDownload.url,
        )
        assertEquals(
            JsonObject(
                mapOf(
                    "hash" to JsonPrimitive("ABCDEF0123456789ABCDEF0123456789ABCDEF01"),
                    "size" to JsonPrimitive(19),
                ),
            ),
            Json.parseToJsonElement(MODEL_JSON.encodeToString(minecraftAssetObject)),
        )

    }
}

private fun testModelSha1(character: Char): String = character.toString().repeat(40)

private val MODEL_JSON = Json {
    ignoreUnknownKeys = true
}

private val VERSION_MANIFEST_V2_JSON = """
    {
      "latest": {
        "release": "modern-release",
        "snapshot": "modern-snapshot"
      },
      "versions": [
        {
          "id": "modern-release",
          "type": "release",
          "url": "https://piston-meta.mojang.com/v1/packages/hash/version.json",
          "time": "2026-01-01T00:00:00+00:00",
          "releaseTime": "2026-01-01T00:00:00+00:00",
          "sha1": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
          "complianceLevel": 1
        }
      ]
    }
""".trimIndent()

private val VERSION_MANIFEST_V1_SHAPE_JSON = """
    {
      "latest": {
        "release": "old-release",
        "snapshot": "old-snapshot"
      },
      "versions": [
        {
          "id": "old-release",
          "type": "release",
          "url": "https://piston-meta.mojang.com/v1/packages/hash/version.json",
          "time": "2011-01-01T00:00:00+00:00",
          "releaseTime": "2011-01-01T00:00:00+00:00"
        }
      ]
    }
""".trimIndent()

private val MINECRAFT_ARGUMENTS_JSON = """
    {
      "default-user-jvm": [
        {
          "value": [
            "-Xms2G"
          ]
        }
      ],
      "game": [
        "--username",
        {
          "rules": [
            {
              "action": "allow",
              "features": {
                "is_demo_user": true
              }
            }
          ],
          "value": "--demo"
        }
      ],
      "jvm": [
        {
          "rules": [
            {
              "action": "allow",
              "os": {
                "arch": "x86"
              }
            }
          ],
          "value": "-Xss1M"
        }
      ]
    }
""".trimIndent()

private val JAVA_RUNTIME_CATALOG_JSON = """
    {
      "future-platform": {
        "future-component": [
          {
            "availability": {
              "group": 1,
              "progress": 100
            },
            "manifest": {
              "sha1": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "size": 123,
              "url": "https://piston-meta.mojang.com/v1/packages/hash/manifest.json"
            },
            "version": {
              "name": "25.0.1",
              "released": "2026-01-01T00:00:00+00:00"
            }
          }
        ],
        "empty-component": []
      }
    }
""".trimIndent()

private val JAVA_RUNTIME_MANIFEST_JSON = """
    {
      "files": {
        "bin/java": {
          "downloads": {
            "raw": {
              "sha1": "cccccccccccccccccccccccccccccccccccccccc",
              "size": 7,
              "url": "https://piston-data.mojang.com/raw"
            },
            "lzma": {
              "sha1": "dddddddddddddddddddddddddddddddddddddddd",
              "size": 3,
              "url": "https://piston-data.mojang.com/lzma"
            }
          },
          "executable": true,
          "type": "file"
        },
        "lib": {
          "type": "directory"
        },
        "link": {
          "target": "../target",
          "type": "link"
        }
      }
    }
""".trimIndent()
