package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.FixedLength
import com.hiczp.minecraft.protocol.model.wire.ZeroFallbackEnum
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** Four display lines and their filtered equivalents. The wire omits filtered lines when they equal the originals. */
@Serializable(with = SignTextSerializer::class)
data class SignText(
    val messages: List<TextComponent>,
    val filteredMessages: List<TextComponent> = messages,
    val color: DyeColor = DyeColor.BLACK,
    val hasGlowingText: Boolean = false,
) {
    init {
        require(messages.size == 4 && filteredMessages.size == 4) { "Sign text requires four original and four filtered lines" }
    }
}

@Serializable
private data class SignTextSurrogate(
    @FixedLength(4) val messages: List<TextComponent>,
    @FixedLength(4) val filteredMessages: List<TextComponent>?,
    @ZeroFallbackEnum val color: DyeColor,
    val hasGlowingText: Boolean,
)

internal object SignTextSerializer : KSerializer<SignText> {
    override val descriptor = SignTextSurrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: SignText) = encoder.encodeSerializableValue(
        SignTextSurrogate.serializer(),
        SignTextSurrogate(
            value.messages,
            value.filteredMessages.takeUnless { it == value.messages },
            value.color,
            value.hasGlowingText,
        ),
    )

    override fun deserialize(decoder: Decoder): SignText {
        val surrogate = decoder.decodeSerializableValue(SignTextSurrogate.serializer())
        return SignText(
            surrogate.messages,
            surrogate.filteredMessages ?: surrogate.messages,
            surrogate.color,
            surrogate.hasGlowingText
        )
    }
}
