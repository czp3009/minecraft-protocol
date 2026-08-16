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

@Serializable(with = CommandsPacketSerializer::class)
@PacketInfo(
    0x10,
    ConnectionState.PLAY,
    PacketDirection.CLIENTBOUND,
    officialName = "commands",
)
data class CommandsPacket(
    val nodes: List<CommandNode>,
    val rootIndex: Int,
) : PlayStatePacket, ClientboundPacket

/**
 * The packet-level serializer also performs the two graph-cycle checks made by
 * the vanilla packet constructor after all nodes have been decoded.
 */
internal object CommandsPacketSerializer : KSerializer<CommandsPacket> {
    private val nodesSerializer: KSerializer<List<CommandNode>> = ListSerializer(CommandNodeSerializer)

    override val descriptor: SerialDescriptor = buildClassSerialDescriptor(
        "minecraft.CommandsPacket",
    ) {
        element<List<CommandNode>>("nodes")
        element<Int>("rootIndex", annotations = listOf(VarInt()))
    }

    override fun serialize(encoder: Encoder, value: CommandsPacket) {
        validateGraph(value.nodes)
        val output = encoder.beginStructure(descriptor)
        output.encodeSerializableElement(
            descriptor,
            NODES,
            nodesSerializer,
            value.nodes,
        )
        output.encodeIntElement(descriptor, ROOT_INDEX, value.rootIndex)
        output.endStructure(descriptor)
    }

    override fun deserialize(decoder: Decoder): CommandsPacket {
        val input = decoder.beginStructure(descriptor)
        val result = if (input.decodeSequentially()) {
            CommandsPacket(
                nodes = input.decodeSerializableElement(
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
                        "Unexpected CommandsPacket field $index",
                    )
                }
            }
            CommandsPacket(
                nodes ?: throw SerializationException("Missing command nodes"),
                rootIndex ?: throw SerializationException("Missing command rootIndex"),
            )
        }
        input.endStructure(descriptor)
        validateGraph(result.nodes)
        return result
    }

    private fun validateGraph(nodes: List<CommandNode>) {
        validateDependencies(nodes, "redirect") { node, unresolved ->
            node.redirect == null || node.redirect !in unresolved
        }
        validateDependencies(nodes, "child") { node, unresolved ->
            node.children.none { it in unresolved }
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
            unresolved.removeAll(resolved.toSet())
        }
    }

    private const val NODES: Int = 0
    private const val ROOT_INDEX: Int = 1
}
