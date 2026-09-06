package com.hiczp.minecraft.world.format

import com.hiczp.minecraft.nbt.*

internal fun readEntityAttributes(nbtTag: NbtTag, mappings: NbtPropertyReadMappings): EntityAttributes {
    val entries = linkedMapOf<AttributeId, AttributeInstance>()
    nbtTag.list().forEach { tag ->
        val attribute = tag.compound()
        val id = AttributeId.parse(attribute.string("id"))
        val modifiers = linkedMapOf<ModifierId, AttributeModifier>()
        attribute.optionalTag<NbtList>("modifiers")?.forEach { modifierTag ->
            val modifier = modifierTag.compound()
            val modifierId = ModifierId.parse(modifier.string("id"))
            val operation = when (modifier.string("operation")) {
                "add_value" -> AttributeOperation.ADD_VALUE
                "add_multiplied_base" -> AttributeOperation.ADD_MULTIPLIED_BASE
                "add_multiplied_total" -> AttributeOperation.ADD_MULTIPLIED_TOTAL
                else -> throw NbtPropertyFormatException("Unknown attribute modifier operation")
            }
            require(
                modifiers.put(
                    modifierId, AttributeModifier(
                        modifier.requiredTag<NbtDouble>("amount").value, operation, true,
                        mappings.readProperties(modifier, NbtPropertyScope("attribute_modifier"), MODIFIER_FIELDS)
                    )
                ) == null
            ) { "Duplicate attribute modifier $modifierId" }
        }
        val instance = AttributeInstance(
            attribute.optionalTag<NbtDouble>("base")?.value ?: 0.0, modifiers,
            mappings.readProperties(attribute, NbtPropertyScope("attribute", id.toString()), ATTRIBUTE_FIELDS)
        )
        require(entries.put(id, instance) == null) { "Duplicate attribute $id" }
    }
    return EntityAttributes(entries)
}

internal fun writeEntityAttributes(value: EntityAttributes, mappings: NbtPropertyWriteMappings): NbtList = NbtList(
    value.entries.map { (id, attribute) ->
        val fields = mappings.writeProperties(
            attribute.properties,
            NbtPropertyScope("attribute", id.toString()),
            ATTRIBUTE_FIELDS
        )
        fields["id"] = NbtString(id.toString())
        fields["base"] = NbtDouble(attribute.baseValue)
        val modifiers = attribute.modifiers.filterValues { it.permanent }.map { (modifierId, modifier) ->
            val modifierFields =
                mappings.writeProperties(modifier.properties, NbtPropertyScope("attribute_modifier"), MODIFIER_FIELDS)
            modifierFields["id"] = NbtString(modifierId.toString())
            modifierFields["amount"] = NbtDouble(modifier.amount)
            modifierFields["operation"] = NbtString(
                when (modifier.operation) {
                    AttributeOperation.ADD_VALUE -> "add_value"
                    AttributeOperation.ADD_MULTIPLIED_BASE -> "add_multiplied_base"
                    AttributeOperation.ADD_MULTIPLIED_TOTAL -> "add_multiplied_total"
                }
            )
            NbtCompound(modifierFields)
        }
        if (modifiers.isNotEmpty()) fields["modifiers"] = NbtList(modifiers)
        NbtCompound(fields)
    },
)

internal fun readEntityEffects(nbtTag: NbtTag, mappings: NbtPropertyReadMappings): EntityEffects {
    val entries = linkedMapOf<MobEffectId, EffectState>()
    nbtTag.list().forEach { tag ->
        val compound = tag.compound()
        val id = MobEffectId.parse(compound.string("id"))
        require(entries.put(id, readEffectState(compound, mappings)) == null) { "Duplicate effect $id" }
    }
    return EntityEffects(entries)
}

private fun readEffectState(nbtCompound: NbtCompound, mappings: NbtPropertyReadMappings): EffectState {
    val amplifier = when (val tag = nbtCompound["amplifier"]) {
        null -> 0
        is NbtByte -> tag.value.toInt() and 255
        is NbtInt -> tag.value
        else -> throw NbtPropertyFormatException("Effect amplifier must be an unsigned Byte")
    }
    require(amplifier in 0..255) { "Effect amplifier must be in 0..255" }
    val visible = nbtCompound.boolean("show_particles", true)
    return EffectState(
        amplifier,
        nbtCompound.int("duration", 0),
        nbtCompound.boolean("ambient", false),
        visible,
        nbtCompound.boolean("show_icon", visible),
        nbtCompound.optionalTag<NbtCompound>("hidden_effect")?.let { readEffectState(it, mappings) },
        mappings.readProperties(nbtCompound, NbtPropertyScope("effect"), EFFECT_FIELDS)
    )
}

internal fun writeEntityEffects(value: EntityEffects, mappings: NbtPropertyWriteMappings): NbtList =
    NbtList(value.entries.map { (id, effect) ->
        NbtCompound(writeEffectState(effect, mappings, emptyList()).value + ("id" to NbtString(id.toString())))
    })

private fun writeEffectState(
    value: EffectState,
    mappings: NbtPropertyWriteMappings,
    ancestors: List<EffectState>
): NbtCompound {
    require(ancestors.none { it === value }) { "An effect's hidden-effect chain contains a cycle" }
    require(value.amplifier in 0..255) { "Effect amplifier must be in 0..255" }
    val fields = mappings.writeProperties(value.properties, NbtPropertyScope("effect"), EFFECT_FIELDS)
    if (value.amplifier != 0) fields["amplifier"] = NbtByte(value.amplifier.toByte())
    if (value.duration != 0) fields["duration"] = NbtInt(value.duration)
    if (value.ambient) fields["ambient"] = NbtByte(1)
    if (!value.visible) fields["show_particles"] = NbtByte(0)
    fields["show_icon"] = NbtByte(if (value.showIcon) 1 else 0)
    value.hiddenEffect?.let { fields["hidden_effect"] = writeEffectState(it, mappings, ancestors + value) }
    return NbtCompound(fields)
}

private val ATTRIBUTE_FIELDS = setOf("id", "base", "modifiers")
private val MODIFIER_FIELDS = setOf("id", "amount", "operation")
private val EFFECT_FIELDS =
    setOf("id", "amplifier", "duration", "ambient", "show_particles", "show_icon", "hidden_effect")
