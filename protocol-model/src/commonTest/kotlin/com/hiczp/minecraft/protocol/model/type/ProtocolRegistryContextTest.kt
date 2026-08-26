package com.hiczp.minecraft.protocol.model.type

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class ProtocolRegistryContextTest {
    @Test
    fun remoteBlockOrderDefinesGlobalStateIds() {
        val first = Identifier("test:first")
        val second = Identifier("test:second")
        val staticRegistrySchema = StaticRegistrySchema(
            registries = mapOf(
                StaticRegistrySchema.BLOCK_REGISTRY to listOf(first, second),
            ),
            blocks = listOf(
                StaticBlockSchema(
                    first,
                    listOf(
                        StaticBlockState(mapOf("kind" to "a"), true),
                        StaticBlockState(mapOf("kind" to "b"), false),
                    ),
                ),
                StaticBlockSchema(
                    second,
                    listOf(StaticBlockState(emptyMap(), true)),
                ),
            ),
        )
        val protocolRegistryContext = staticRegistrySchema.resolve(
            RemoteRegistrySnapshot(
                listOf(
                    RemoteRegistry(
                        StaticRegistrySchema.BLOCK_REGISTRY,
                        listOf(
                            RemoteRegistryEntry(second, 0),
                            RemoteRegistryEntry(first, 1),
                        ),
                    ),
                ),
            ),
        )

        assertEquals(listOf(second, first, first), protocolRegistryContext.blockStates.map { it.block })
        assertEquals(listOf(0, 1, 2), protocolRegistryContext.blockStates.map { it.id })
        assertEquals(
            mapOf("kind" to "a"),
            protocolRegistryContext.blockStates[1].properties,
        )
    }

    @Test
    fun missingModBlockSchemasAreReportedTogether() {
        val staticRegistrySchema = StaticRegistrySchema(emptyMap(), emptyList())
        val missingStaticBlockSchemas = assertFailsWith<MissingStaticBlockSchemas> {
            staticRegistrySchema.resolve(
                RemoteRegistrySnapshot(
                    listOf(
                        RemoteRegistry(
                            StaticRegistrySchema.BLOCK_REGISTRY,
                            listOf(
                                RemoteRegistryEntry(Identifier("mod:first"), 0),
                                RemoteRegistryEntry(Identifier("mod:second"), 1),
                            ),
                        ),
                    ),
                ),
            )
        }

        assertEquals(
            listOf(Identifier("mod:first"), Identifier("mod:second")),
            missingStaticBlockSchemas.blockIds,
        )
    }

    @Test
    fun derivedContextsShareCallerOwnedLargeSnapshots() {
        val protocolRegistry = ProtocolRegistry(
            Identifier("test:registry"),
            listOf(
                ProtocolRegistryEntry(Identifier("test:entry"), 0),
            ),
        )
        val blockStates = listOf(
            ProtocolBlockState(
                id = 0,
                block = Identifier("test:block"),
                properties = emptyMap(),
                isDefault = true,
            ),
        )
        val base = ProtocolRegistryContext(listOf(protocolRegistry), blockStates)
        val dimension = base.withChunkSectionCount(24)
        val sized = dimension.withRegistrySize(
            ProtocolRegistryContext.BIOME_REGISTRY,
            10,
        )

        assertSame(base.registries, dimension.registries)
        assertSame(base.blockStates, dimension.blockStates)
        assertSame(dimension.registries, sized.registries)
        assertSame(dimension.blockStates, sized.blockStates)
    }

    @Test
    fun staticResolutionRetainsUnrelatedDynamicRegistriesByReference() {
        val dynamicRegistry = ProtocolRegistry(
            Identifier("test:dynamic"),
            listOf(
                ProtocolRegistryEntry(Identifier("test:dynamic_entry"), 0),
            ),
        )
        val base = ProtocolRegistryContext(
            listOf(dynamicRegistry),
            emptyList(),
        )
        val resolved = StaticRegistrySchema(
            registries = mapOf(
                StaticRegistrySchema.BLOCK_REGISTRY to
                        listOf(Identifier("test:block")),
            ),
            blocks = listOf(
                StaticBlockSchema(
                    Identifier("test:block"),
                    listOf(StaticBlockState(emptyMap(), true)),
                ),
            ),
        ).resolve()

        val combined = base.withStaticRegistryResolution(resolved)

        assertSame(dynamicRegistry, combined.requireRegistry(dynamicRegistry.id))
        assertSame(resolved.blockStates, combined.blockStates)
        assertEquals(
            Identifier("test:block"),
            combined.requireDefaultBlockState(Identifier("test:block")).block,
        )
    }

    @Test
    fun registryAliasesCannotShadowCanonicalIds() {
        val canonical = Identifier("test:canonical")
        assertFailsWith<IllegalArgumentException> {
            ProtocolRegistry(
                Identifier("test:registry"),
                listOf(
                    ProtocolRegistryEntry(
                        Identifier("test:first"),
                        0,
                        aliases = setOf(canonical),
                    ),
                    ProtocolRegistryEntry(canonical, 1),
                ),
            )
        }
    }

    @Test
    fun lookupHelpersResolveAliasesAndDynamicRawIds() {
        val canonicalBlock = Identifier("mod:canonical")
        val aliasBlock = Identifier("mod:alias")
        val biome = Identifier("mod:biome")
        val protocolRegistryContext = StaticRegistrySchema(
            registries = mapOf(
                StaticRegistrySchema.BLOCK_REGISTRY to listOf(canonicalBlock),
                ProtocolRegistryContext.BIOME_REGISTRY to listOf(biome),
            ),
            blocks = listOf(
                StaticBlockSchema(
                    canonicalBlock,
                    listOf(StaticBlockState(emptyMap(), true)),
                ),
            ),
        ).resolve(
            RemoteRegistrySnapshot(
                listOf(
                    RemoteRegistry(
                        StaticRegistrySchema.BLOCK_REGISTRY,
                        listOf(
                            RemoteRegistryEntry(
                                canonicalBlock,
                                rawId = 4,
                                aliases = setOf(aliasBlock),
                            ),
                        ),
                    ),
                    RemoteRegistry(
                        ProtocolRegistryContext.BIOME_REGISTRY,
                        listOf(RemoteRegistryEntry(biome, rawId = 7)),
                    ),
                ),
            ),
        )

        assertEquals(
            canonicalBlock,
            protocolRegistryContext.requireDefaultBlockState(aliasBlock).block,
        )
        assertEquals(
            7,
            protocolRegistryContext.requireRegistryEntry(
                ProtocolRegistryContext.BIOME_REGISTRY,
                biome,
            ).rawId,
        )
    }
}
