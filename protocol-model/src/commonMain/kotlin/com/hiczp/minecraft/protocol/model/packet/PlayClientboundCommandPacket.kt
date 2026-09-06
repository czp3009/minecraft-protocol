@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.protocol.model.type.CommandNode
import com.hiczp.minecraft.protocol.model.type.CommandNodeSerializer
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = ClientboundCommandsPacketSerializer::class)
@PacketInfo(
    0x10,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "commands",
)
data class ClientboundCommandsPacket(
    val rootIndex: Int,
    val entries: List<CommandNode>,
) : PlayStatePacket, ClientboundPacket

/**
 * The packet-level serializer also performs the two graph-cycle checks made by
 * the vanilla packet constructor after all nodes have been decoded.
 */
internal object ClientboundCommandsPacketSerializer : KSerializer<ClientboundCommandsPacket> {
    private val nodesSerializer: KSerializer<List<CommandNode>> = ListSerializer(CommandNodeSerializer)

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.ClientboundCommandsPacket",
    ) {
        element<List<CommandNode>>("entries")
        element<Int>("rootIndex", annotations = listOf(VarInt()))
    }

    override fun serialize(encoder: Encoder, value: ClientboundCommandsPacket) {
        validateGraph(value.entries)
        val output = encoder.beginStructure(descriptor)
        output.encodeSerializableElement(
            descriptor,
            NODES,
            nodesSerializer,
            value.entries,
        )
        output.encodeIntElement(descriptor, ROOT_INDEX, value.rootIndex)
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): ClientboundCommandsPacket {
        val input = decoder.beginStructure(descriptor)
        val clientboundCommandsPacket = if (input.decodeSequentially()) {
            ClientboundCommandsPacket(
                entries = input.decodeSerializableElement(
                    descriptor,
                    NODES,
                    nodesSerializer,
                ),
                rootIndex = input.decodeIntElement(descriptor, ROOT_INDEX),
            )
        } else {
            var nodes: List<CommandNode>? = null
            var rootIndex: Int? = null
            while (true) {
                when (val index = input.decodeElementIndex(descriptor)) {
                    NODES -> nodes = input.decodeSerializableElement(
                        descriptor,
                        NODES,
                        nodesSerializer,
                    )

                    ROOT_INDEX -> rootIndex =
                        input.decodeIntElement(descriptor, ROOT_INDEX)

                    -1 -> break
                    else -> throw SerializationException(
                        "Unexpected ClientboundCommandsPacket field $index",
                    )
                }
            }
            ClientboundCommandsPacket(
                entries = nodes ?: throw SerializationException("Missing command nodes"),
                rootIndex = rootIndex ?: throw SerializationException("Missing command rootIndex"),
            )
        }
        input.endStructure(descriptor)
        validateGraph(clientboundCommandsPacket.entries)
        return clientboundCommandsPacket
    }

    private fun validateGraph(nodes: List<CommandNode>) {
        validateDependencies(nodes, "redirect") { commandNode, unresolved ->
            commandNode.redirect == null || commandNode.redirect !in unresolved
        }
        validateDependencies(nodes, "child") { commandNode, unresolved ->
            commandNode.children.none { it in unresolved }
        }
    }

    private inline fun validateDependencies(
        nodes: List<CommandNode>,
        kind: String,
        canResolve: (CommandNode, Set<Int>) -> Boolean,
    ) {
        val unresolved = nodes.indices.toMutableSet()
        while (unresolved.isNotEmpty()) {
            val resolved = unresolved.filter { index ->
                canResolve(nodes[index], unresolved)
            }
            if (resolved.isEmpty()) {
                throw SerializationException(
                    "Command graph contains an impossible $kind cycle",
                )
            }
            resolved.forEach(unresolved::remove)
        }
    }

    private const val NODES: Int = 0
    private const val ROOT_INDEX: Int = 1
}
