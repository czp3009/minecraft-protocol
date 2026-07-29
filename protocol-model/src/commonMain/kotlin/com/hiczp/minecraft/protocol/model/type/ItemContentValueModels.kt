@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.hiczp.minecraft.protocol.model.type

import com.hiczp.minecraft.protocol.model.wire.MaxLength
import com.hiczp.minecraft.protocol.model.wire.VarInt
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
enum class MapPostProcessing {
    LOCK,
    SCALE,
}

@Serializable
data class SuspiciousStewEffect(
    @VarInt
    val effectRegistryId: Int,
    @VarInt
    val duration: Int,
)

@Serializable
data class WritableBookPage(
    @MaxLength(1024)
    val raw: String,
    @MaxLength(1024)
    val filtered: String? = null,
)

@Serializable
data class WrittenBookTitle(
    @MaxLength(32)
    val raw: String,
    @MaxLength(32)
    val filtered: String? = null,
)

@Serializable
data class WrittenBookPage(
    val raw: TextComponent,
    val filtered: TextComponent? = null,
)

@Serializable
data class TrimMaterialValue(
    val assets: TrimMaterialAssets,
    val description: TextComponent,
)

@Serializable
data class TrimMaterialAssets(
    val baseSuffix: String,
    val overrides: Map<Identifier, String>,
)

@Serializable
data class TrimPatternValue(
    val assetId: Identifier,
    val description: TextComponent,
    val decal: Boolean,
)

@Serializable(with = TrimMaterialHolderSerializer::class)
sealed interface TrimMaterialHolder {
    data class Reference(
        val registryId: Int,
    ) : TrimMaterialHolder {
        init {
            require(registryId >= 0) { "A registry ID must be non-negative" }
        }
    }

    data class Direct(
        val value: TrimMaterialValue,
    ) : TrimMaterialHolder
}

@Serializable(with = TrimPatternHolderSerializer::class)
sealed interface TrimPatternHolder {
    data class Reference(
        val registryId: Int,
    ) : TrimPatternHolder {
        init {
            require(registryId >= 0) { "A registry ID must be non-negative" }
        }
    }

    data class Direct(
        val value: TrimPatternValue,
    ) : TrimPatternHolder
}

internal object TrimMaterialHolderSerializer :
    DirectHolderSerializer<TrimMaterialValue, TrimMaterialHolder>(
        "minecraft.TrimMaterialHolder",
        TrimMaterialValue.serializer(),
    ) {
    override fun registryId(value: TrimMaterialHolder): Int? =
        (value as? TrimMaterialHolder.Reference)?.registryId

    override fun directValue(
        value: TrimMaterialHolder,
    ): TrimMaterialValue? = (value as? TrimMaterialHolder.Direct)?.value

    override fun reference(registryId: Int): TrimMaterialHolder =
        TrimMaterialHolder.Reference(registryId)

    override fun direct(value: TrimMaterialValue): TrimMaterialHolder =
        TrimMaterialHolder.Direct(value)
}

internal object TrimPatternHolderSerializer :
    DirectHolderSerializer<TrimPatternValue, TrimPatternHolder>(
        "minecraft.TrimPatternHolder",
        TrimPatternValue.serializer(),
    ) {
    override fun registryId(value: TrimPatternHolder): Int? =
        (value as? TrimPatternHolder.Reference)?.registryId

    override fun directValue(
        value: TrimPatternHolder,
    ): TrimPatternValue? = (value as? TrimPatternHolder.Direct)?.value

    override fun reference(registryId: Int): TrimPatternHolder =
        TrimPatternHolder.Reference(registryId)

    override fun direct(value: TrimPatternValue): TrimPatternHolder =
        TrimPatternHolder.Direct(value)
}

internal abstract class DirectHolderSerializer<T, H>(
    serialName: String,
    private val directSerializer: KSerializer<T>,
) : KSerializer<H> {
    final override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(serialName) {
            element<Int>("holderId", annotations = listOf(VarInt()))
            element(
                "direct",
                directSerializer.descriptor,
                isOptional = true,
            )
        }

    protected abstract fun registryId(value: H): Int?

    protected abstract fun directValue(value: H): T?

    protected abstract fun reference(registryId: Int): H

    protected abstract fun direct(value: T): H

    final override fun serialize(encoder: Encoder, value: H) {
        val output = encoder.beginStructure(descriptor)
        val registryId = registryId(value)
        if (registryId != null) {
            if (registryId == Int.MAX_VALUE) {
                throw SerializationException("Registry ID is too large")
            }
            output.encodeIntElement(
                descriptor,
                HOLDER_ID,
                registryId + 1,
            )
        } else {
            val directValue = directValue(value)
                ?: throw SerializationException(
                    "Unknown direct-holder implementation",
                )
            output.encodeIntElement(descriptor, HOLDER_ID, 0)
            output.encodeSerializableElement(
                descriptor,
                DIRECT,
                directSerializer,
                directValue,
            )
        }
        output.endStructure(descriptor)
    }

    final override fun deserialize(decoder: Decoder): H {
        val input = decoder.beginStructure(descriptor)
        if (!input.decodeSequentially()) {
            throw SerializationException(
                "Direct holder requires ordered decoding",
            )
        }
        val holderId = input.decodeIntElement(descriptor, HOLDER_ID)
        val result = when {
            holderId == 0 -> direct(
                input.decodeSerializableElement(
                    descriptor,
                    DIRECT,
                    directSerializer,
                ),
            )

            holderId > 0 -> reference(holderId - 1)
            else -> throw SerializationException(
                "Invalid holder ID $holderId",
            )
        }
        input.endStructure(descriptor)
        return result
    }

    private companion object {
        const val HOLDER_ID: Int = 0
        const val DIRECT: Int = 1
    }
}
