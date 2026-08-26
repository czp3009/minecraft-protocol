package com.hiczp.minecraft.protocol.symbolprocessor

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import com.google.devtools.ksp.validate
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.AnnotationSpec.UseSiteTarget.FILE
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

class ProtocolModelProcessorProvider : SymbolProcessorProvider {
    override fun create(
        environment: SymbolProcessorEnvironment,
    ): SymbolProcessor = ProtocolModelProcessor(
        codeGenerator = environment.codeGenerator,
        kspLogger = environment.logger,
        options = environment.options,
    )
}

private class ProtocolModelProcessor(
    private val codeGenerator: CodeGenerator,
    private val kspLogger: KSPLogger,
    private val options: Map<String, String>,
) : SymbolProcessor {
    private var generated = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (generated) return emptyList()

        val packetDeclarations = resolver
            .getSymbolsWithAnnotation(PACKET_INFO)
            .filterIsInstance<KSClassDeclaration>()
            .mapNotNull { ksClassDeclaration ->
                ksClassDeclaration.annotation(PACKET_INFO)?.let { ksAnnotation ->
                    ksClassDeclaration to ksAnnotation
                }
            }
            .toList()
        val componentDeclarations = resolver
            .getSymbolsWithAnnotation(DATA_COMPONENT_INFO)
            .filterIsInstance<KSClassDeclaration>()
            .mapNotNull { ksClassDeclaration ->
                ksClassDeclaration.annotation(DATA_COMPONENT_INFO)?.let {
                    ksClassDeclaration to it
                }
            }
            .toList()
        val invalid = (
                packetDeclarations.map { it.first } +
                        componentDeclarations.map { it.first }
                )
            .filterNot(KSAnnotated::validate)
        if (invalid.isNotEmpty()) return invalid
        if (packetDeclarations.isEmpty()) {
            kspLogger.error(
                "No @$PACKET_INFO_SIMPLE_NAME declarations were visible from $PACKET_PACKAGE",
            )
            return emptyList()
        }
        if (componentDeclarations.isEmpty()) {
            kspLogger.error(
                "No @$DATA_COMPONENT_INFO_SIMPLE_NAME declarations were visible from $DATA_COMPONENT_PACKAGE",
            )
            return emptyList()
        }

        val localPackets = packetDeclarations.map { (ksClassDeclaration, ksAnnotation) ->
            ksAnnotation.toPacket(ksClassDeclaration)
        }
        val dataComponents = componentDeclarations.map { (ksClassDeclaration, ksAnnotation) ->
            ksAnnotation.toDataComponent(ksClassDeclaration)
        }
        val officialPackets = loadOfficialPackets()
        if (
            !validatePackets(localPackets, officialPackets) ||
            !validateDataComponents(dataComponents, resolver)
        ) {
            return emptyList()
        }

        val sourceFiles = (
                packetDeclarations.map { it.first } +
                        componentDeclarations.map { it.first }
                )
            .mapNotNull(KSDeclaration::containingFile)
            .distinctBy(KSFile::filePath)
            .toTypedArray()
        val dependencies = Dependencies(
            aggregating = true,
            sources = sourceFiles,
        )
        renderRegistry(localPackets).writeTo(codeGenerator, dependencies)
        renderDataComponentRegistry(dataComponents)
            .writeTo(codeGenerator, dependencies)
        generated = true
        return emptyList()
    }

    private fun loadOfficialPackets(): List<OfficialPacket> {
        val report = options[PACKETS_REPORT_OPTION]
            ?: error(
                "KSP option '$PACKETS_REPORT_OPTION' was not configured",
            )
        val path = Path.of(report)
        check(Files.isRegularFile(path)) {
            "Official packets report is missing: $path"
        }
        val root = Json.parseToJsonElement(Files.readString(path)).jsonObject
        return root.entries.flatMap { (state, stateElement) ->
            stateElement.jsonObject.entries.flatMap { (direction, directionElement) ->
                directionElement.jsonObject.entries.map { (name, packetElement) ->
                    OfficialPacket(
                        packetKey = PacketKey(
                            state = state.uppercase(),
                            direction = direction.uppercase(),
                            id = packetElement.jsonObject.getValue("protocol_id").jsonPrimitive.int,
                        ),
                        name = name.removePrefix("minecraft:"),
                    )
                }
            }
        }
    }

    private fun validatePackets(
        local: List<LocalPacket>,
        official: List<OfficialPacket>,
    ): Boolean {
        var valid = true
        val localByKey = local.groupBy(LocalPacket::packetKey)
        localByKey.filterValues { it.size > 1 }.forEach { (packetKey, packets) ->
            packets.forEach { localPacket ->
                kspLogger.error(
                    "Duplicate packet key ${packetKey.display()}",
                    localPacket.ksDeclaration,
                )
            }
            valid = false
        }
        val officialByKey = official.groupBy(OfficialPacket::packetKey)
        officialByKey.filterValues { it.size > 1 }.forEach { (packetKey) ->
            kspLogger.error(
                "Official packets report contains duplicate key ${packetKey.display()}",
            )
            valid = false
        }

        val uniqueLocal = localByKey.mapValues { (_, packets) ->
            packets.singleOrNull()
        }.filterValues { it != null }.mapValues { it.value!! }
        val uniqueOfficial = officialByKey.mapValues { (_, packets) ->
            packets.singleOrNull()
        }.filterValues { it != null }.mapValues { it.value!! }
        val missing = uniqueOfficial.keys - uniqueLocal.keys
        if (missing.isNotEmpty()) {
            kspLogger.error(
                "Packet models are missing official keys: ${
                    missing.sortedBy(PacketKey::sortKey).joinToString { it.display() }
                }",
            )
            valid = false
        }
        val extra = uniqueLocal.keys - uniqueOfficial.keys - LEGACY_PACKET_KEY
        extra.forEach { packetKey ->
            kspLogger.error(
                "Packet model has no official report entry at ${packetKey.display()}",
                uniqueLocal.getValue(packetKey).ksDeclaration,
            )
            valid = false
        }
        uniqueOfficial.forEach { (packetKey, officialPacket) ->
            val localPacket = uniqueLocal[packetKey] ?: return@forEach
            if (localPacket.officialName != officialPacket.name) {
                kspLogger.error(
                    "${localPacket.className} identifies '${localPacket.officialName}', but the official report identifies '${officialPacket.name}'",
                    localPacket.ksDeclaration,
                )
                valid = false
            }
        }
        val legacy = uniqueLocal[LEGACY_PACKET_KEY]
        if (legacy?.officialName != LEGACY_PACKET_NAME) {
            kspLogger.error(
                "The sole non-report packet must be annotated as '$LEGACY_PACKET_NAME'",
                legacy?.ksDeclaration,
            )
            valid = false
        }
        return valid
    }

    private fun renderRegistry(packets: List<LocalPacket>): FileSpec {
        val packet = ClassName(PACKET_PACKAGE, "Packet")
        val packetDefinition = ClassName(PACKET_PACKAGE, "PacketDefinition")
        val connectionState = ClassName(PACKET_PACKAGE, "ConnectionState")
        val packetDirection = ClassName(PACKET_PACKAGE, "PacketDirection")
        val packetFraming = ClassName(PACKET_PACKAGE, "PacketFraming")
        val registryApi = ClassName(PACKET_PACKAGE, "InternalPacketRegistryApi")
        val entriesInitializer = CodeBlock.builder()
            .add("%M(\n", LIST_OF)
            .indent()
            .apply {
                packets.sortedBy { it.packetKey.sortKey() }.forEach { definition ->
                    val id = "0x${
                        definition.packetKey.id.toString(16)
                            .uppercase()
                            .padStart(2, '0')
                    }"
                    add("%T(\n", packetDefinition)
                    indent()
                    add("connectionState = %T.%L,\n", connectionState, definition.packetKey.state)
                    add(
                        "packetDirection = %T.%L,\n",
                        packetDirection,
                        definition.packetKey.direction,
                    )
                    add("id = %L,\n", id)
                    add(
                        "packetFraming = %T.%L,\n",
                        packetFraming,
                        if (definition.packetKey == LEGACY_PACKET_KEY) {
                            "LEGACY_UNFRAMED"
                        } else {
                            "NORMAL"
                        },
                    )
                    add("packetClass = %T::class,\n", definition.typeName)
                    add("kSerializer = %T.serializer(),\n", definition.typeName)
                    unindent()
                    add("),\n")
                }
            }
            .unindent()
            .add(")")
            .build()
        val generatedRegistry = TypeSpec.objectBuilder(REGISTRY_FILE)
            .addAnnotation(registryApi)
            .addProperty(
                PropertySpec.builder(
                    "entries",
                    LIST.parameterizedBy(
                        packetDefinition.parameterizedBy(
                            WildcardTypeName.producerOf(packet),
                        ),
                    ),
                ).initializer(entriesInitializer)
                    .build(),
            )
            .build()
        return generatedFile(
            packageName = PACKET_PACKAGE,
            fileName = REGISTRY_FILE,
            comment = "Generated from @PacketInfo declarations by KSP. Do not edit.",
            apiAnnotation = registryApi,
            typeSpec = generatedRegistry,
        )
    }

    private fun validateDataComponents(
        components: List<LocalDataComponent>,
        resolver: Resolver,
    ): Boolean {
        var valid = true
        components.groupBy(LocalDataComponent::type)
            .filterValues { it.size > 1 }
            .forEach { (type, duplicates) ->
                duplicates.forEach { localDataComponent ->
                    kspLogger.error(
                        "Duplicate data-component type $type",
                        localDataComponent.ksDeclaration,
                    )
                }
                valid = false
            }
        val typeDeclaration = resolver.getClassDeclarationByName(
            resolver.getKSNameFromString(DATA_COMPONENT_TYPE),
        )
        if (typeDeclaration == null) {
            kspLogger.error("Could not resolve $DATA_COMPONENT_TYPE")
            return false
        }
        val declaredTypes = typeDeclaration.declarations
            .filterIsInstance<KSClassDeclaration>()
            .filter { it.classKind == ClassKind.ENUM_ENTRY }
            .map { it.simpleName.asString() }
            .toSet()
        val componentTypes = components.map(LocalDataComponent::type).toSet()
        val missing = declaredTypes - componentTypes
        if (missing.isNotEmpty()) {
            kspLogger.error(
                "Data-component models are missing types: ${missing.sorted().joinToString()}",
            )
            valid = false
        }
        val extra = componentTypes - declaredTypes
        extra.forEach { type ->
            kspLogger.error(
                "Unknown data-component type $type",
                components.first { it.type == type }.ksDeclaration,
            )
            valid = false
        }
        return valid
    }

    private fun renderDataComponentRegistry(
        components: List<LocalDataComponent>,
    ): FileSpec {
        val dataComponent = ClassName(DATA_COMPONENT_PACKAGE, "DataComponent")
        val dataComponentType = ClassName(
            DATA_COMPONENT_PACKAGE,
            "DataComponentType",
        )
        val registryApi = ClassName(
            DATA_COMPONENT_PACKAGE,
            "InternalDataComponentRegistryApi",
        )
        val serializer = ClassName("kotlinx.serialization", "KSerializer")
        val sorted = components.sortedBy(LocalDataComponent::type)
        val serializerBody = CodeBlock.builder()
            .add("return when (type) {\n")
            .indent()
            .apply {
                sorted.forEach { localDataComponent ->
                    add(
                        "%T.%L -> %T.serializer()\n",
                        dataComponentType,
                        localDataComponent.type,
                        localDataComponent.typeName,
                    )
                }
            }
            .unindent()
            .add("}\n")
            .build()
        val typeBody = CodeBlock.builder()
            .add("return when (value) {\n")
            .indent()
            .apply {
                sorted.forEach { localDataComponent ->
                    add(
                        "is %T -> %T.%L\n",
                        localDataComponent.typeName,
                        dataComponentType,
                        localDataComponent.type,
                    )
                }
            }
            .unindent()
            .add("}\n")
            .build()
        val generatedRegistry = TypeSpec.objectBuilder(DATA_COMPONENT_REGISTRY_FILE)
            .addAnnotation(registryApi)
            .addFunction(
                FunSpec.builder("serializer")
                    .addParameter("type", dataComponentType)
                    .returns(
                        serializer.parameterizedBy(
                            WildcardTypeName.producerOf(dataComponent),
                        ),
                    )
                    .addCode(serializerBody)
                    .build(),
            )
            .addFunction(
                FunSpec.builder("type")
                    .addParameter("value", dataComponent)
                    .returns(dataComponentType)
                    .addCode(typeBody)
                    .build(),
            )
            .build()
        return generatedFile(
            packageName = DATA_COMPONENT_PACKAGE,
            fileName = DATA_COMPONENT_REGISTRY_FILE,
            comment = "Generated from @DataComponentInfo declarations by KSP. Do not edit.",
            apiAnnotation = registryApi,
            typeSpec = generatedRegistry,
        )
    }

    private fun generatedFile(
        packageName: String,
        fileName: String,
        comment: String,
        apiAnnotation: ClassName,
        typeSpec: TypeSpec,
    ): FileSpec = FileSpec.builder(packageName, fileName)
        .addFileComment("%L\n", comment)
        .addAnnotation(
            AnnotationSpec.builder(ClassName("kotlin", "OptIn"))
                .useSiteTarget(FILE)
                .addMember("%T::class", apiAnnotation)
                .build(),
        )
        .addType(typeSpec)
        .build()

    private fun KSClassDeclaration.annotation(
        qualifiedName: String,
    ): KSAnnotation? =
        annotations.firstOrNull { annotation ->
            annotation.annotationType.resolve().declaration
                .qualifiedName
                ?.asString() == qualifiedName
        }

    private fun KSAnnotation.toPacket(
        ksClassDeclaration: KSClassDeclaration,
    ): LocalPacket {
        val arguments = arguments.associateBy {
            it.name?.asString()
                ?: error("@$PACKET_INFO_SIMPLE_NAME has an unnamed argument")
        }
        checkNotNull(ksClassDeclaration.qualifiedName) {
            "@$PACKET_INFO_SIMPLE_NAME requires a named packet class"
        }
        return LocalPacket(
            packetKey = PacketKey(
                state = arguments.getValue("connectionState").enumName(),
                direction = arguments.getValue("packetDirection").enumName(),
                id = arguments.getValue("id").value as Int,
            ),
            className = ksClassDeclaration.simpleName.asString(),
            typeName = ksClassDeclaration.toClassName(),
            officialName = arguments.getValue("officialName").value as String,
            ksDeclaration = ksClassDeclaration,
        )
    }

    private fun KSAnnotation.toDataComponent(
        ksClassDeclaration: KSClassDeclaration,
    ): LocalDataComponent {
        val type = arguments.singleOrNull {
            it.name?.asString() == "dataComponentType"
        }?.enumName() ?: error(
            "@$DATA_COMPONENT_INFO_SIMPLE_NAME has no type argument",
        )
        return LocalDataComponent(
            type = type,
            typeName = ksClassDeclaration.toClassName(),
            ksDeclaration = ksClassDeclaration,
        )
    }

    private fun KSValueArgument.enumName(): String {
        return when (val argument = value) {
            is KSType -> argument.declaration.simpleName.asString()
            is KSClassDeclaration -> argument.simpleName.asString()
            else -> error(
                "Expected an enum annotation argument for ${name?.asString()}; got ${argument?.javaClass?.name}: $argument",
            )
        }
    }

    private data class PacketKey(
        val state: String,
        val direction: String,
        val id: Int,
    ) {
        fun display(): String =
            "$state/$direction/0x${
                id.toString(16).uppercase().padStart(2, '0')
            }"

        fun sortKey(): String =
            "${
                STATE_ORDER.getValue(state).toString().padStart(2, '0')
            }/${DIRECTION_ORDER.getValue(direction)}/${id.toString().padStart(8, '0')}"
    }

    private data class LocalPacket(
        val packetKey: PacketKey,
        val className: String,
        val typeName: ClassName,
        val officialName: String,
        val ksDeclaration: KSDeclaration,
    )

    private data class OfficialPacket(
        val packetKey: PacketKey,
        val name: String,
    )

    private data class LocalDataComponent(
        val type: String,
        val typeName: ClassName,
        val ksDeclaration: KSDeclaration,
    )

    private companion object {
        const val PACKET_PACKAGE = "com.hiczp.minecraft.protocol.model.packet"
        const val PACKET_INFO = "$PACKET_PACKAGE.PacketInfo"
        const val PACKET_INFO_SIMPLE_NAME = "PacketInfo"
        const val REGISTRY_FILE = "GeneratedPacketDefinitions"
        const val PACKETS_REPORT_OPTION = "minecraft.packetsReport"
        const val LEGACY_PACKET_NAME = "legacy_server_list_ping"
        const val DATA_COMPONENT_PACKAGE = "com.hiczp.minecraft.protocol.model.type"
        const val DATA_COMPONENT_INFO = "$DATA_COMPONENT_PACKAGE.DataComponentInfo"
        const val DATA_COMPONENT_INFO_SIMPLE_NAME = "DataComponentInfo"
        const val DATA_COMPONENT_TYPE = "$DATA_COMPONENT_PACKAGE.DataComponentType"
        const val DATA_COMPONENT_REGISTRY_FILE = "GeneratedDataComponentSerializers"

        val STATE_ORDER = mapOf(
            "HANDSHAKE" to 0,
            "STATUS" to 1,
            "LOGIN" to 2,
            "CONFIGURATION" to 3,
            "PLAY" to 4,
        )
        val DIRECTION_ORDER = mapOf(
            "CLIENTBOUND" to 0,
            "SERVERBOUND" to 1,
        )
        val LEGACY_PACKET_KEY = PacketKey(
            state = "HANDSHAKE",
            direction = "SERVERBOUND",
            id = 0xFE,
        )
        val LIST_OF = MemberName("kotlin.collections", "listOf")
    }
}
