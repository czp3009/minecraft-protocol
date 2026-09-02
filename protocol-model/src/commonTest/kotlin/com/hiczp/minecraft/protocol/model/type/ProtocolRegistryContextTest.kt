package com.hiczp.minecraft.protocol.model.type

import kotlin.test.*

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
    fun ordinaryRegistryModelsRetainCallerOwnedCollections() {
        val aliases = mutableSetOf(Identifier("test:alias"))
        val entries = mutableListOf(
            ProtocolRegistryEntry(
                Identifier("test:entry"),
                0,
                aliases,
            ),
        )
        val protocolRegistry = ProtocolRegistry(
            Identifier("test:registry"),
            entries,
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

        assertSame(entries, protocolRegistry.entries)
        assertSame(aliases, entries.single().aliases)
        assertSame(base.registries, dimension.registries)
        assertSame(base.blockStates, dimension.blockStates)
        assertSame(dimension.registries, sized.registries)
        assertSame(dimension.blockStates, sized.blockStates)
    }

    @Test
    fun remoteRegistrySnapshotDetachesNestedCallerCollections() {
        val registryId = Identifier("test:registry")
        val alias = Identifier("test:alias")
        val aliases = mutableSetOf(alias)
        val entries = mutableListOf(
            RemoteRegistryEntry(
                id = Identifier("test:entry"),
                rawId = 0,
                aliases = aliases,
            ),
        )
        val remoteRegistry = RemoteRegistry(registryId, entries)
        val registries = mutableListOf(remoteRegistry)

        val remoteRegistrySnapshot = RemoteRegistrySnapshot(registries)

        assertEquals(setOf(registryId), remoteRegistrySnapshot.registries.keys)
        assertNotSame(entries, remoteRegistrySnapshot.registry(registryId)?.entries)
        assertNotSame(aliases, remoteRegistrySnapshot.registry(registryId)?.entries?.single()?.aliases)
        aliases += Identifier("test:later_alias")
        entries += RemoteRegistryEntry(Identifier("test:later_entry"), 1)
        registries.clear()
        assertEquals(
            setOf(alias),
            remoteRegistrySnapshot.registry(registryId)?.entries?.single()?.aliases,
        )
    }

    @Test
    fun blockStateIdsAreAuthoritativeRegardlessOfListPosition() {
        val first = ProtocolBlockState(7, Identifier("test:first"), emptyMap(), isDefault = true)
        val second = ProtocolBlockState(2, Identifier("test:second"), emptyMap(), isDefault = true)
        val protocolRegistryContext = ProtocolRegistryContext(emptyList(), listOf(first, second))

        assertSame(first, protocolRegistryContext.blockState(7))
        assertSame(second, protocolRegistryContext.blockState(2))
        assertEquals(8, protocolRegistryContext.blockStateRegistrySize)
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
