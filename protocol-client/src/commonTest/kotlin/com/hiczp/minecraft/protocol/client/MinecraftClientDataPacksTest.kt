package com.hiczp.minecraft.protocol.client

import com.hiczp.minecraft.protocol.model.packet.ConfigurationUpdateTagsPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MinecraftClientDataPacksTest {
    @Test
    fun retainedPacketsResolveAgainstTheAlreadyInstalledProfileContext() {
        val blockRegistryId = Identifier("block")
        val blockId = Identifier("mod:block")
        val tagId = Identifier("mod:selected")
        val context = ProtocolRegistryContext(
            registries = listOf(
                ProtocolRegistry(
                    blockRegistryId,
                    listOf(ProtocolRegistryEntry(blockId, 0)),
                ),
            ),
            blockStates = emptyList(),
        )
        val configuration = MinecraftClientConfiguration(
            knownPacks = null,
            featureFlags = null,
            registries = emptyList(),
            tags = ConfigurationUpdateTagsPacket(
                listOf(
                    RegistryTags(
                        blockRegistryId,
                        listOf(TagDefinition(tagId, listOf(0))),
                    ),
                ),
            ),
            storedCookies = emptyMap(),
        )

        val runtime = configuration.toDataPackRuntime(context)

        assertSame(context, runtime.registryContext)
        assertEquals(blockId, runtime.tag(blockRegistryId, tagId)?.entries?.single()?.id)
    }
}
