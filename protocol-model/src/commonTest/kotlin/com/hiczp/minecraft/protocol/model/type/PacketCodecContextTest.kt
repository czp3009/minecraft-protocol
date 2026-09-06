package com.hiczp.minecraft.protocol.model.type

import kotlin.test.*

class PacketCodecContextTest {
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
        val packetCodecContext = staticRegistrySchema.resolve(
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

        assertEquals(listOf(second, first, first), packetCodecContext.blockStates.map { it.block })
        assertEquals(listOf(0, 1, 2), packetCodecContext.blockStates.map { it.id })
        assertEquals(
            mapOf("kind" to "a"),
            packetCodecContext.blockStates[1].properties,
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
            RegistryIdMapping(
                Identifier("test:entry"),
                0,
                aliases,
            ),
        )
        val registryIdMap = RegistryIdMap(
            Identifier("test:registry"),
            entries,
        )
        val blockStates = listOf(
            BlockStateIdMapping(
                id = 0,
                block = Identifier("test:block"),
                properties = emptyMap(),
                isDefault = true,
            ),
        )
        val base = PacketCodecContext(listOf(registryIdMap), blockStates)
        val sized = base.withRegistrySize(
            PacketCodecContext.BIOME_REGISTRY,
            10,
        )

        assertSame(entries, registryIdMap.entries)
        assertSame(aliases, entries.single().aliases)
        assertSame(base.registries, sized.registries)
        assertSame(base.blockStates, sized.blockStates)
    }

    @Test
    fun registryOverlayReusesUnchangedContextAndRetainsUnrelatedState() {
        val registryIdMap = RegistryIdMap(
            Identifier("test:registry"),
            listOf(RegistryIdMapping(Identifier("test:entry"), 0)),
        )
        val packetCodecContext = PacketCodecContext(
            registries = listOf(registryIdMap),
            blockStates = listOf(BlockStateIdMapping(0, Identifier("test:block"), emptyMap(), true)),
            registrySizeOverrides = mapOf(Identifier("test:other_registry") to 5),
        )

        assertSame(packetCodecContext, packetCodecContext.withRegistries(emptyList()))
        assertSame(packetCodecContext, packetCodecContext.withRegistries(listOf(registryIdMap.copy())))
        assertFailsWith<IllegalArgumentException> {
            packetCodecContext.withRegistries(listOf(registryIdMap, registryIdMap))
        }

        val replacement = registryIdMap.copy(entries = listOf(RegistryIdMapping(Identifier("test:entry"), 7)))
        val updated = packetCodecContext.withRegistries(listOf(replacement))
        assertEquals(7, updated.requireRegistryEntry(registryIdMap.id, Identifier("test:entry")).rawId)
        assertEquals(0, packetCodecContext.requireRegistryEntry(registryIdMap.id, Identifier("test:entry")).rawId)
        assertSame(packetCodecContext.blockStates, updated.blockStates)
        assertSame(packetCodecContext.registrySizeOverrides, updated.registrySizeOverrides)
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
        val first = BlockStateIdMapping(7, Identifier("test:first"), emptyMap(), isDefault = true)
        val second = BlockStateIdMapping(2, Identifier("test:second"), emptyMap(), isDefault = true)
        val packetCodecContext = PacketCodecContext(emptyList(), listOf(first, second))

        assertSame(first, packetCodecContext.blockState(7))
        assertSame(second, packetCodecContext.blockState(2))
        assertEquals(8, packetCodecContext.blockStateRegistrySize)
    }

    @Test
    fun staticResolutionRetainsUnrelatedDynamicRegistriesByReference() {
        val dynamicRegistry = RegistryIdMap(
            Identifier("test:dynamic"),
            listOf(
                RegistryIdMapping(Identifier("test:dynamic_entry"), 0),
            ),
        )
        val base = PacketCodecContext(
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
            RegistryIdMap(
                Identifier("test:registry"),
                listOf(
                    RegistryIdMapping(
                        Identifier("test:first"),
                        0,
                        aliases = setOf(canonical),
                    ),
                    RegistryIdMapping(canonical, 1),
                ),
            )
        }
    }

    @Test
    fun lookupHelpersResolveAliasesAndDynamicRawIds() {
        val canonicalBlock = Identifier("mod:canonical")
        val aliasBlock = Identifier("mod:alias")
        val biome = Identifier("mod:biome")
        val packetCodecContext = StaticRegistrySchema(
            registries = mapOf(
                StaticRegistrySchema.BLOCK_REGISTRY to listOf(canonicalBlock),
                PacketCodecContext.BIOME_REGISTRY to listOf(biome),
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
                        PacketCodecContext.BIOME_REGISTRY,
                        listOf(RemoteRegistryEntry(biome, rawId = 7)),
                    ),
                ),
            ),
        )

        assertEquals(
            canonicalBlock,
            packetCodecContext.requireDefaultBlockState(aliasBlock).block,
        )
        assertEquals(
            7,
            packetCodecContext.requireRegistryEntry(
                PacketCodecContext.BIOME_REGISTRY,
                biome,
            ).rawId,
        )
    }
}
