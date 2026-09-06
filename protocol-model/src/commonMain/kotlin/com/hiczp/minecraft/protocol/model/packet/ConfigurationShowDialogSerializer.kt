package com.hiczp.minecraft.protocol.model.packet

import com.hiczp.minecraft.nbt.NbtTag
import com.hiczp.minecraft.protocol.model.type.DialogHolder
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Official CONFIGURATION_STREAM_CODEC uses direct NBT; the Play STREAM_CODEC uses a registry-aware holder. */
internal object ConfigurationShowDialogSerializer : KSerializer<ClientboundShowDialogPacket> {
    private val delegate = NbtTag.serializer()
    override val descriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ClientboundShowDialogPacket) {
        val dialog = value.dialog
        require(dialog is DialogHolder.Direct) { "Configuration dialogs cannot reference the Play dialog registry" }
        delegate.serialize(encoder, dialog.dialog)
    }

    override fun deserialize(decoder: Decoder): ClientboundShowDialogPacket =
        ClientboundShowDialogPacket(DialogHolder.Direct(delegate.deserialize(decoder)))
}
