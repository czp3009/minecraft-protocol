package com.hiczp.minecraft.protocol.serialization

import com.hiczp.minecraft.protocol.model.type.DataComponent
import com.hiczp.minecraft.protocol.model.type.DataComponentType
import kotlinx.serialization.KSerializer
import java.lang.reflect.Method

internal data class NamedDataComponentSample(
    val name: String,
    val type: DataComponentType,
    val value: DataComponent,
)

/**
 * Uses the production dispatch table without widening its private visibility.
 * Reflection is confined to JVM test infrastructure.
 */
internal fun dataComponentTestSamples(): List<NamedDataComponentSample> =
    buildList {
        for (type in DataComponentType.entries) {
            val serializer = serializerForDataComponentType(type)
            for (profile in ProtocolSampleProfile.entries) {
                val value = runCatching {
                    serializer.protocolValue(profile)
                }.getOrNull() ?: continue
                add(
                    NamedDataComponentSample(
                        name = "${type.wireName.substringAfter(':')}-${profile.name.lowercase()}",
                        type = type,
                        value = value,
                    ),
                )
            }
        }
    }

@Suppress("UNCHECKED_CAST")
private fun serializerForDataComponentType(
    type: DataComponentType,
): KSerializer<DataComponent> =
    serializerForTypeMethod.invoke(null, type) as KSerializer<DataComponent>

private val serializerForTypeMethod: Method by lazy {
    Class.forName(
        "com.hiczp.minecraft.protocol.model.type.ItemStackModelsKt",
    ).declaredMethods.single { method ->
        method.name == "serializerForType" &&
                method.parameterTypes.contentEquals(
                    arrayOf(DataComponentType::class.java),
                )
    }.apply {
        isAccessible = true
    }
}
