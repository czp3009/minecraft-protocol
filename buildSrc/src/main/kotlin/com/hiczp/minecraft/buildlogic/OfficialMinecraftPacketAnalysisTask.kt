package com.hiczp.minecraft.buildlogic

import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.classfile.ClassModel
import java.lang.classfile.Opcode
import java.lang.classfile.instruction.ConstantInstruction
import java.lang.classfile.instruction.FieldInstruction
import java.lang.classfile.instruction.InvokeInstruction
import java.lang.reflect.AccessFlag
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.readText
import kotlinx.serialization.json.*
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.*

/** PacketType registration and class-file evidence, without loading or initializing Minecraft classes. */
@CacheableTask
abstract class AnalyzeOfficialMinecraftPacketsTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val implementationJar: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val packetsReport: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun analyze() {
        val packets = buildLogicJson.parseToJsonElement(packetsReport.get().asFile.toPath().readText()).jsonObject
        val evidence = analyzeOfficialPacketClasses(implementationJar.get().asFile.toPath(), packets)
        outputFile.get().asFile.toPath().writeJson(evidence, sortKeys = true)
    }
}

internal fun analyzeOfficialPacketClasses(implementationJar: Path, packetsReport: JsonObject): JsonObject =
    ZipFile(implementationJar.toFile()).use { zipFile ->
        val classFile = ClassFile.of()
        val classes = zipFile.entries().asSequence()
            .filter { it.name.startsWith("net/minecraft/network/protocol/") && it.name.endsWith(".class") }
            .associate { entry ->
                entry.name.removeSuffix(".class") to zipFile.getInputStream(entry).use {
                    classFile.parse(it.readBytes())
                }
            }
        val registrations = classes.values.filter { it.thisClass().asInternalName().endsWith("PacketTypes") }
            .flatMap(::packetTypeRegistrations)
            .groupBy { "${it.direction}/${it.name}" }
            .mapValues { (identity, values) ->
                check(values.size == 1) { "Ambiguous official PacketType registration for $identity" }
                values.single()
            }
        val packets = buildJsonArray {
            packetsReport.forEach { (state, directions) ->
                directions.jsonObject.forEach { (direction, entries) ->
                    entries.jsonObject.forEach { (name, value) ->
                        val registration = checkNotNull(registrations["$direction/$name"]) {
                            "No official PacketType registration for $state/$direction/$name"
                        }
                        check(registration.className in classes) { "Missing packet class ${registration.className}" }
                        add(buildJsonObject {
                            put("state", state)
                            put("direction", direction)
                            put("name", name)
                            put("protocol_id", value.jsonObject.getValue("protocol_id"))
                            put("class_name", registration.className.replace('/', '.'))
                            put("registration", registration.field)
                        })
                    }
                }
            }
        }
        buildJsonObject {
            put("schema_version", 1)
            put("packets", packets)
            // Include packet-owned nested values and protocol shared values, even when they are not Packet types.
            put("classes", buildJsonObject {
                classes.toSortedMap().forEach { (name, classModel) ->
                    put(name.replace('/', '.'), classModel.packetMemberEvidence())
                }
            })
        }
    }

private data class PacketTypeRegistration(
    val direction: String,
    val name: String,
    val className: String,
    val field: String,
)

private fun packetTypeRegistrations(classModel: ClassModel): List<PacketTypeRegistration> {
    val owner = classModel.thisClass().asInternalName()
    val fields = classModel.fields().associateBy { it.fieldName().stringValue() }
    val result = mutableListOf<PacketTypeRegistration>()
    var name: String? = null
    var direction: String? = null
    classModel.methods().single { it.methodName().stringValue() == "<clinit>" }.code().orElseThrow()
        .forEach { element ->
            when (element) {
                is ConstantInstruction -> {
                    val constant: Any = element.constantValue()
                    if (constant is String) {
                        check(name == null) { "Ambiguous PacketType name in $owner" }
                        name = constant
                    }
                }
                is InvokeInstruction -> when (element.name().stringValue()) {
                    "createClientbound" -> direction = "clientbound"
                    "createServerbound" -> direction = "serverbound"
                    else -> Unit
                }
                is FieldInstruction -> if (element.opcode() == Opcode.PUTSTATIC) {
                    check(element.owner().asInternalName() == owner && element.type().stringValue() == PACKET_TYPE) {
                        "Unsupported PacketType initializer in $owner"
                    }
                    val fieldName = element.name().stringValue()
                    val signature = fields.getValue(fieldName).findAttribute(Attributes.signature()).orElseThrow()
                        .signature().stringValue()
                    val className = PACKET_SIGNATURE.matchEntire(signature)?.groupValues?.get(1)
                        ?: error("Unsupported PacketType signature $owner.$fieldName: $signature")
                    result += PacketTypeRegistration(
                        checkNotNull(direction), "minecraft:${checkNotNull(name)}", className, "$owner.$fieldName",
                    )
                    name = null
                    direction = null
                }
                else -> Unit
            }
        }
    check(name == null && direction == null) { "Incomplete PacketType initializer in $owner" }
    return result
}

private fun ClassModel.packetMemberEvidence(): JsonObject = buildJsonObject {
    put("superclass", superclass().map { it.asInternalName().replace('/', '.') }.orElse(null))
    put("fields", buildJsonArray {
        fields().filterNot { it.flags().has(AccessFlag.STATIC) || it.flags().has(AccessFlag.SYNTHETIC) }
            .forEach { fieldModel ->
                add(buildJsonObject {
                    put("name", fieldModel.fieldName().stringValue())
                    put("descriptor", fieldModel.fieldType().stringValue())
                    fieldModel.findAttribute(Attributes.signature()).ifPresent {
                        put("signature", it.signature().stringValue())
                    }
                })
            }
    })
    findAttribute(Attributes.record()).ifPresent { recordAttribute ->
        put("record_components", buildJsonArray {
            recordAttribute.components().forEach { add(it.name().stringValue()) }
        })
    }
    put("methods", buildJsonArray {
        methods().forEach { methodModel ->
            add(buildJsonObject {
                put("name", methodModel.methodName().stringValue())
                put("descriptor", methodModel.methodType().stringValue())
                put("references", buildJsonArray {
                    methodModel.code().ifPresent { codeModel ->
                        codeModel.forEach { element ->
                            when (element) {
                                is FieldInstruction -> add(buildJsonObject {
                                    put("opcode", element.opcode().name)
                                    put("owner", element.owner().asInternalName().replace('/', '.'))
                                    put("name", element.name().stringValue())
                                    put("descriptor", element.type().stringValue())
                                })
                                is InvokeInstruction -> add(buildJsonObject {
                                    put("opcode", element.opcode().name)
                                    put("owner", element.owner().asInternalName().replace('/', '.'))
                                    put("name", element.name().stringValue())
                                    put("descriptor", element.type().stringValue())
                                })
                                else -> Unit
                            }
                        }
                    }
                })
            })
        }
    })
}

private const val PACKET_TYPE = "Lnet/minecraft/network/protocol/PacketType;"
private val PACKET_SIGNATURE = Regex("Lnet/minecraft/network/protocol/PacketType<L([^;]+);>;")
