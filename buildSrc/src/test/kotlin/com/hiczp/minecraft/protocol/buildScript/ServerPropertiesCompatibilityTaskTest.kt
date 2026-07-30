package com.hiczp.minecraft.protocol.buildScript

import kotlinx.serialization.json.JsonArray
import kotlin.test.Test
import kotlin.test.assertTrue

class ServerPropertiesCompatibilityTaskTest {
    @Test
    fun exactSortedManifestPassesAndEveryDriftIsReported() {
        val official = report(
            listOf(
                property("alpha", "1"),
                property("beta", "2"),
            ),
        )
        val exact = compatibility(
            listOf(
                property(
                    name = "alpha",
                    default = "1",
                    scope = "protocol",
                    support = "direct",
                ),
                property(
                    name = "beta",
                    default = "2",
                    scope = "operations",
                    support = "not-applicable",
                ),
            ),
        )
        assertTrue(
            auditServerPropertiesCompatibility(official, exact).isEmpty(),
        )

        val drifted = compatibility(
            listOf(
                property(
                    name = "extra",
                    default = "3",
                    scope = "operations",
                    support = "not-applicable",
                ),
                property(
                    name = "beta",
                    default = "changed",
                    scope = "invalid",
                    support = "invalid",
                    mapping = "",
                ),
            ),
        )
        val errors = auditServerPropertiesCompatibility(official, drifted)

        assertTrue(errors.any { it.contains("not sorted") })
        assertTrue(errors.any { it.contains("missing 'alpha'") })
        assertTrue(errors.any { it.contains("unknown property 'extra'") })
        assertTrue(errors.any { it.contains("Default for 'beta'") })
        assertTrue(errors.any { it.contains("invalid scope") })
        assertTrue(errors.any { it.contains("invalid library_support") })
        assertTrue(errors.any { it.contains("blank 'library_mapping'") })
    }

    private fun report(properties: List<kotlinx.serialization.json.JsonObject>) =
        jsonObjectOf(
            "schema_version" to jsonNumber(1),
            "minecraft_version" to jsonString("test"),
            "protocol_version" to jsonNumber(1),
            "official_server_sha1" to jsonString("hash"),
            "property_count" to jsonNumber(properties.size),
            "properties" to JsonArray(properties),
        )

    private fun compatibility(
        properties: List<kotlinx.serialization.json.JsonObject>,
    ) = report(properties)

    private fun property(
        name: String,
        default: String,
        scope: String? = null,
        support: String? = null,
        mapping: String = "mapping",
    ) = jsonObjectOf(
        "name" to jsonString(name),
        "default" to jsonString(default),
        *if (scope == null) {
            emptyArray()
        } else {
            arrayOf(
                "scope" to jsonString(scope),
                "library_support" to jsonString(assertNotNull(support)),
                "library_mapping" to jsonString(mapping),
                "consumer_responsibility" to jsonString("responsibility"),
            )
        },
    )

    private fun <T : Any> assertNotNull(value: T?): T {
        assertTrue(value != null)
        return value
    }
}
