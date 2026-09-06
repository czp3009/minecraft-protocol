package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.nbt.NbtCompound
import com.hiczp.minecraft.nbt.NbtString
import com.hiczp.minecraft.protocol.configuration.ConfigurationData
import com.hiczp.minecraft.protocol.configuration.vanilla.VanillaConfigurationData
import com.hiczp.minecraft.protocol.model.packet.ClientboundResourcePackPushPacket
import com.hiczp.minecraft.protocol.model.type.GameMode
import com.hiczp.minecraft.protocol.model.type.Identifier
import com.hiczp.minecraft.protocol.model.type.TextComponent
import com.hiczp.minecraft.world.format.DimensionId
import kotlin.random.Random
import kotlin.uuid.Uuid

/** Values consumed while serving Status or moving one connection from Handshake through its first Play Login. */
data class MinecraftServerNegotiationOptions(
    val configurationData: ConfigurationData = VanillaConfigurationData,
    /** The optional server pack offered during Configuration, matching the official dedicated server's single pack. */
    val resourcePack: ClientboundResourcePackPushPacket? = null,
    /** Disconnect text for a declined required pack; [ClientboundResourcePackPushPacket.prompt] is the acceptance prompt. */
    val resourcePackRejectionReason: TextComponent = TextComponent(
        NbtCompound(mapOf("translate" to NbtString("multiplayer.requiredTexturePrompt.disconnect"))),
    ),
    /** World dimension entered in Play; it need not have the same identifier as its dimension type. */
    val initialDimensionId: DimensionId = DimensionId.Overworld,
    /** Dimension-type registry entry resolved from [configurationData] by the default Play Login builder. */
    val initialDimensionTypeId: Identifier = Identifier("overworld"),
    val dimensionIds: Set<DimensionId> = setOf(initialDimensionId),
    val compressionThreshold: Int? = 256,
    val sessionId: Uuid = Uuid.fromLongs(
        Random.nextLong(),
        Random.nextLong(),
    ),
    val statusEnabled: Boolean = true,
    val acceptsTransfers: Boolean = false,
    val preventProxyConnections: Boolean = false,
    val maximumPlayers: Int = 20,
    val viewDistance: Int = 10,
    val simulationDistance: Int = 10,
    val statusDescription: String = "A Minecraft Server",
    val hardcore: Boolean = false,
    val gameMode: GameMode = GameMode.SURVIVAL,
    /**
     * Requests the protocol-visible secure-chat claim. It is effective only in
     * Online authentication. Set it only when the consuming server actually
     * validates and enforces secure profiles and signed chat.
     */
    val enforcesSecureChat: Boolean = false,
) {
    init {
        require(initialDimensionId in dimensionIds) {
            "Initial dimension $initialDimensionId is absent from the advertised dimensions"
        }
    }
}
