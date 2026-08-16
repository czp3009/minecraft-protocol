package com.hiczp.minecraft.protocol.server

import com.hiczp.minecraft.protocol.model.packet.BundleDelimiterPacket
import com.hiczp.minecraft.protocol.model.packet.ClientboundPacket
import com.hiczp.minecraft.protocol.model.packet.SetEntityMetadataPacket
import com.hiczp.minecraft.protocol.model.packet.SpawnEntityPacket
import com.hiczp.minecraft.protocol.model.type.*
import kotlin.uuid.Uuid

/**
 * The client-facing state needed for an initial entity spawn.
 */
data class MinecraftEntitySnapshot(
    val entityId: Int,
    val uuid: Uuid,
    val type: Identifier,
    val position: Vector3d,
    val velocity: Vector3d = Vector3d(0.0, 0.0, 0.0),
    val pitch: Float = 0.0f,
    val yaw: Float = 0.0f,
    val headYaw: Float = yaw,
    val data: Int = 0,
    val metadata: EntityMetadata? = null,
) {
    init {
        require(entityId > 0) {
            "An entity ID must be positive"
        }
        require(position.isFinite()) {
            "An entity position must be finite"
        }
        require(velocity.isFinite()) {
            "An entity velocity must be finite"
        }
        require(pitch.isFinite() && yaw.isFinite() && headYaw.isFinite()) {
            "Entity rotations must be finite"
        }
    }

    fun typeId(registries: ProtocolRegistryContext): Int =
        registries.requireRegistryEntry(ENTITY_TYPE_REGISTRY, type).rawId

    fun packets(registries: ProtocolRegistryContext): List<ClientboundPacket> =
        packets(typeId(registries))

    fun packets(typeId: Int): List<ClientboundPacket> = buildList {
        require(typeId >= 0) { "An entity type ID must be non-negative" }
        add(BundleDelimiterPacket)
        add(
            SpawnEntityPacket(
                entityId = entityId,
                entityUuid = uuid,
                typeId = typeId,
                x = position.x,
                y = position.y,
                z = position.z,
                velocity = velocity,
                pitch = Angle.fromDegrees(pitch),
                yaw = Angle.fromDegrees(yaw),
                headYaw = Angle.fromDegrees(headYaw),
                data = data,
            ),
        )
        metadata?.let {
            add(SetEntityMetadataPacket(entityId, it))
        }
        add(BundleDelimiterPacket)
    }

    companion object {
        val ENTITY_TYPE_REGISTRY: Identifier = Identifier("entity_type")
    }
}

private fun Vector3d.isFinite(): Boolean =
    x.isFinite() && y.isFinite() && z.isFinite()
