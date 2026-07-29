package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.*
import kotlinx.serialization.KSerializer
import java.nio.file.Files
import java.nio.file.Path

/**
 * Emits registry IDs from executable Kotlin serializers. The checked-in model
 * owns names; the matching official data-generator report owns the comparison
 * target.
 */
internal object NetworkRegistryManifestGenerator {
    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "Expected one output path argument" }
        val output = Path.of(arguments.single()).toAbsolutePath().normalize()
        Files.createDirectories(output.parent)

        val rows = buildList {
            dataComponentRows()
            particleRows()
            commandParserRows()
            debugSubscriptionRows()
            consumeEffectRows()
            numberFormatRows()
            slotDisplayRows()
            recipeDisplayRows()
        }
        check(rows.distinctBy { it.registry to it.protocolId }.size == rows.size) {
            "The local registry manifest contains duplicate protocol IDs"
        }
        check(rows.distinctBy { it.registry to it.wireName }.size == rows.size) {
            "The local registry manifest contains duplicate wire names"
        }

        Files.writeString(
            output,
            buildString {
                appendLine("registry\tprotocol_id\twire_name")
                rows.sortedWith(compareBy(RegistryRow::registry, RegistryRow::protocolId))
                    .forEach { row ->
                        append(row.registry)
                        append('\t')
                        append(row.protocolId)
                        append('\t')
                        appendLine(row.wireName)
                    }
            },
        )
        println("Wrote ${rows.size} executable registry entries to $output")
    }

    private fun MutableList<RegistryRow>.dataComponentRows() {
        val samples = dataComponentTestSamples()
            .groupBy(NamedDataComponentSample::type)
        for (type in DataComponentType.entries) {
            val sample = samples[type]?.firstOrNull()
                ?: error("No executable data-component sample for ${type.wireName}")
            addEncoded(
                registry = "minecraft:data_component_type",
                wireName = type.wireName,
                serializer = DataComponent.serializer(),
                value = sample.value,
            )
        }
    }

    private fun MutableList<RegistryRow>.particleRows() {
        for (sample in particleRegistrySamples()) {
            addEncoded(
                registry = "minecraft:particle_type",
                wireName = sample.wireName,
                serializer = ParticleOptions.serializer(),
                value = sample.value,
            )
        }
    }

    private fun MutableList<RegistryRow>.commandParserRows() {
        for (sample in commandParserRegistrySamples()) {
            addEncoded(
                registry = "minecraft:command_argument_type",
                wireName = sample.wireName,
                serializer = CommandParser.serializer(),
                value = sample.value,
            )
        }
    }

    private fun MutableList<RegistryRow>.debugSubscriptionRows() {
        for (type in DebugSubscriptionType.entries) {
            add(
                RegistryRow(
                    registry = "minecraft:debug_subscription",
                    protocolId = type.ordinal,
                    wireName = type.wireName,
                ),
            )
        }
    }

    private fun MutableList<RegistryRow>.consumeEffectRows() {
        for (sample in consumeEffectRegistrySamples()) {
            addEncoded(
                registry = "minecraft:consume_effect_type",
                wireName = sample.wireName,
                serializer = ConsumeEffect.serializer(),
                value = sample.value,
            )
        }
    }

    private fun MutableList<RegistryRow>.numberFormatRows() {
        for (sample in numberFormatRegistrySamples()) {
            addEncoded(
                registry = "minecraft:number_format_type",
                wireName = sample.wireName,
                serializer = NumberFormat.serializer(),
                value = sample.value,
            )
        }
    }

    private fun MutableList<RegistryRow>.slotDisplayRows() {
        for (sample in slotDisplayRegistrySamples()) {
            addEncoded(
                registry = "minecraft:slot_display",
                wireName = sample.wireName,
                serializer = SlotDisplay.serializer(),
                value = sample.value,
            )
        }
    }

    private fun MutableList<RegistryRow>.recipeDisplayRows() {
        for (sample in recipeDisplayRegistrySamples()) {
            addEncoded(
                registry = "minecraft:recipe_display",
                wireName = sample.wireName,
                serializer = RecipeDisplay.serializer(),
                value = sample.value,
            )
        }
    }

    private fun <T> MutableList<RegistryRow>.addEncoded(
        registry: String,
        wireName: String,
        serializer: KSerializer<T>,
        value: T,
    ) {
        val bytes = MinecraftFormat.encodeToByteArray(serializer, value)
        add(
            RegistryRow(
                registry = registry,
                protocolId = decodeLeadingVarInt(bytes),
                wireName = wireName,
            ),
        )
    }

    private fun decodeLeadingVarInt(bytes: ByteArray): Int {
        var result = 0
        var shift = 0
        for (byte in bytes.take(5)) {
            val current = byte.toInt() and 0xFF
            result = result or ((current and 0x7F) shl shift)
            if (current and 0x80 == 0) {
                return result
            }
            shift += 7
        }
        error("Encoded value does not start with a valid VarInt")
    }
}

private data class RegistryRow(
    val registry: String,
    val protocolId: Int,
    val wireName: String,
)
