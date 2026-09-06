package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.model.packet.ClientboundBundlePacket
import com.hiczp.minecraft.protocol.world.EntityPacketEncoder
import com.hiczp.minecraft.protocol.world.EntityPairingData
import com.hiczp.minecraft.world.format.Entity

/** An explicitly configured finite batch; all Entity references remain ordinary caller-owned data. */
data class MinecraftEntityBatch(
    val entities: List<Entity>,
    val entityPacketEncoder: EntityPacketEncoder,
    val entityPairingData: (Entity) -> EntityPairingData,
)

internal fun encodeEntityBundle(minecraftEntityBatch: MinecraftEntityBatch): ClientboundBundlePacket =
    ClientboundBundlePacket(buildList {
        minecraftEntityBatch.entities.forEach { entity ->
            addAll(
                minecraftEntityBatch.entityPacketEncoder.encode(
                    entity,
                    minecraftEntityBatch.entityPairingData(entity)
                )
            )
        }
    })

/** Builds one logical bundle for callers choosing their own enqueue operation. */
fun MinecraftEntityBatch.toBundle(): ClientboundBundlePacket = encodeEntityBundle(this)

/** One outgoing operation keeps all delimiters and contents together in the session's packet pump. */
suspend fun MinecraftServerConnection.sendEntities(minecraftEntityBatch: MinecraftEntityBatch) {
    outgoing.send(encodeEntityBundle(minecraftEntityBatch))
}

suspend fun MinecraftServerConnection.sendEntity(
    entity: Entity,
    entityPairingData: EntityPairingData,
    entityPacketEncoder: EntityPacketEncoder,
) {
    outgoing.send(ClientboundBundlePacket(entityPacketEncoder.encode(entity, entityPairingData)))
}
