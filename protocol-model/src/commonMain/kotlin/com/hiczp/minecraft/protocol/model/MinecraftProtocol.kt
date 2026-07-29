package com.hiczp.minecraft.protocol.model

/**
 * The single protocol revision implemented by this source tree.
 *
 * Packet models deliberately do not contain runtime version branches. Supporting
 * another protocol revision means adding another registry/model set.
 */
object MinecraftProtocol {
    const val MINECRAFT_VERSION: String = "26.2"
    const val PROTOCOL_VERSION: Int = 776
}
