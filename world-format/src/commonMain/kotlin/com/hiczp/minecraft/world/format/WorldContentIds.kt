package com.hiczp.minecraft.world.format

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/** Domain identities contain canonical namespaced text, never connection-local numeric registry IDs. */
@Serializable
@JvmInline
value class BlockId(val value: String) {
    init {
        validateContentId(value)
    }

    override fun toString(): String = value

    companion object {
        fun parse(value: String): BlockId = BlockId(canonicalContentId(value))
    }
}

@Serializable
@JvmInline
value class FluidId(val value: String) {
    init {
        validateContentId(value)
    }

    override fun toString(): String = value

    companion object {
        fun parse(value: String): FluidId = FluidId(canonicalContentId(value))
    }
}

@Serializable
@JvmInline
value class BiomeId(val value: String) {
    init {
        validateContentId(value)
    }

    override fun toString(): String = value

    companion object {
        fun parse(value: String): BiomeId = BiomeId(canonicalContentId(value))
    }
}

@Serializable
@JvmInline
value class BlockEntityTypeId(val value: String) {
    init {
        validateContentId(value)
    }

    override fun toString(): String = value

    companion object {
        fun parse(value: String): BlockEntityTypeId = BlockEntityTypeId(canonicalContentId(value))
    }
}

@Serializable
@JvmInline
value class ComponentId(val value: String) {
    init {
        validateContentId(value)
    }

    override fun toString(): String = value

    companion object {
        fun parse(value: String): ComponentId = ComponentId(canonicalContentId(value))
    }
}

@Serializable
@JvmInline
value class ItemId(val value: String) {
    init {
        validateContentId(value)
    }

    override fun toString(): String = value

    companion object {
        fun parse(value: String): ItemId = ItemId(canonicalContentId(value))
    }
}

@Serializable
@JvmInline
value class StructureId(val value: String) {
    init {
        validateContentId(value)
    }

    override fun toString(): String = value

    companion object {
        fun parse(value: String): StructureId = StructureId(canonicalContentId(value))
    }
}

@Serializable
@JvmInline
value class StructurePieceTypeId(val value: String) {
    init {
        validateContentId(value)
    }

    override fun toString(): String = value

    companion object {
        fun parse(value: String): StructurePieceTypeId = StructurePieceTypeId(canonicalContentId(value))
    }
}

@Serializable
@JvmInline
value class EntityTypeId(val value: String) {
    init {
        validateContentId(value)
    }

    override fun toString(): String = value

    companion object {
        fun parse(value: String): EntityTypeId = EntityTypeId(canonicalContentId(value))
    }
}

@Serializable
@JvmInline
value class PoiTypeId(val value: String) {
    init {
        validateContentId(value)
    }

    override fun toString(): String = value

    companion object {
        fun parse(value: String): PoiTypeId = PoiTypeId(canonicalContentId(value))
    }
}

@Serializable
@JvmInline
value class AttributeId(val value: String) {
    init {
        validateContentId(value)
    }

    override fun toString(): String = value

    companion object {
        fun parse(value: String): AttributeId = AttributeId(canonicalContentId(value))
    }
}

@Serializable
@JvmInline
value class ModifierId(val value: String) {
    init {
        validateContentId(value)
    }

    override fun toString(): String = value

    companion object {
        fun parse(value: String): ModifierId = ModifierId(canonicalContentId(value))
    }
}

@Serializable
@JvmInline
value class MobEffectId(val value: String) {
    init {
        validateContentId(value)
    }

    override fun toString(): String = value

    companion object {
        fun parse(value: String): MobEffectId = MobEffectId(canonicalContentId(value))
    }
}

/** Equipment slot names are schema names, not a namespaced registry. */
@Serializable
@JvmInline
value class EquipmentSlotId(val value: String) {
    init {
        require(value.isNotBlank()) { "An equipment slot name must not be blank" }
    }

    override fun toString(): String = value
}

private fun canonicalContentId(value: String): String =
    if (':' in value) value else "$DEFAULT_MINECRAFT_NAMESPACE:$value"

private fun validateContentId(value: String) {
    val separator = value.indexOf(':')
    require(separator > 0) { "A content identity must contain a namespace: $value" }
    validateNamespacedValue(value.substring(0, separator), value.substring(separator + 1), "content")
}
