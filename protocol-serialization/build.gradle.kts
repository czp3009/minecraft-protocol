import com.hiczp.minecraft.protocol.buildScript.CompareFilesTask
import com.hiczp.minecraft.protocol.buildScript.CopyFileTask
import com.hiczp.minecraft.protocol.buildScript.configureAllTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    configureAllTargets("com.hiczp.minecraft.protocol.serialization")

    sourceSets {
        commonMain.dependencies {
            api(project(":protocol-model"))
            implementation(project(":nbt"))
            api(libs.kotlinx.serialization.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

val jvmTestCompilation = kotlin.targets
    .getByName("jvm")
    .compilations
    .getByName("test")

fun registerJvmLayerTest(
    name: String,
    description: String,
    vararg testPatterns: String,
) = tasks.register<Test>(name) {
    group = "verification"
    this.description = description
    dependsOn(jvmTestCompilation.compileTaskProvider)
    testClassesDirs = jvmTestCompilation.output.classesDirs
    classpath = files(
        jvmTestCompilation.output.allOutputs,
        jvmTestCompilation.runtimeDependencyFiles,
    )
    filter {
        testPatterns.forEach(::includeTestsMatching)
    }
}

registerJvmLayerTest(
    "minecraftFormatLayerTest",
    "Test primitive, annotation, composite-value, boundary, and malformed codecs.",
    "com.hiczp.minecraft.protocol.serialization.MinecraftFormatTest",
    "com.hiczp.minecraft.protocol.serialization.*SerializationTest",
)

registerJvmLayerTest(
    "packetPayloadLayerTest",
    "Test packet-specific branches, golden payloads, and registry-wide round trips.",
    "com.hiczp.minecraft.protocol.serialization.*PacketTest",
    "com.hiczp.minecraft.protocol.serialization.PacketRegistryTest",
)

registerJvmLayerTest(
    "packetTransportLayerTest",
    "Test test-only packet framing, partial reads, limits, and compression branches.",
    "com.hiczp.minecraft.protocol.serialization.PacketFramingTest",
)

tasks.register<JavaExec>("generateOfficialCodecFixtures") {
    group = "minecraft protocol"
    description = "Emit one protocol-valid payload per packet for the vanilla codec oracle."
    dependsOn(jvmTestCompilation.compileTaskProvider)
    classpath(
        jvmTestCompilation.output.allOutputs,
        jvmTestCompilation.runtimeDependencyFiles,
    )
    mainClass.set(
        "com.hiczp.minecraft.protocol.serialization.OfficialCodecFixtureGenerator",
    )
    args(
        layout.buildDirectory.file(
            "reports/protocol-update/official-codec-fixtures.tsv",
        ).get().asFile.absolutePath,
    )
}

tasks.register<JavaExec>("generateNetworkRegistryManifest") {
    group = "minecraft protocol"
    description =
        "Emit executable local IDs and names for finite protocol registries."
    dependsOn(jvmTestCompilation.compileTaskProvider)
    classpath(
        jvmTestCompilation.output.allOutputs,
        jvmTestCompilation.runtimeDependencyFiles,
    )
    mainClass.set(
        "com.hiczp.minecraft.protocol.serialization.NetworkRegistryManifestGenerator",
    )
    args(
        layout.buildDirectory.file(
            "reports/protocol-update/network-registries.tsv",
        ).get().asFile.absolutePath,
    )
}

val protocolSnapshotText = rootProject.file(
    "protocol-specification/wiki-protocol-snapshot.json",
).readText()
val analysisJavaVersion = Regex("\"java_major_version\"\\s*:\\s*(\\d+)")
    .find(protocolSnapshotText)
    ?.groupValues
    ?.get(1)
    ?.toInt()
    ?: error("Wiki protocol snapshot does not declare java_major_version")
val protocolMinecraftVersion = Regex("\"minecraft_version\"\\s*:\\s*\"([^\"]+)\"")
    .find(protocolSnapshotText)
    ?.groupValues
    ?.get(1)
    ?: error("Wiki protocol snapshot does not declare minecraft_version")
val analysisJavaLauncher = extensions
    .getByType<JavaToolchainService>()
    .launcherFor {
        languageVersion.set(JavaLanguageVersion.of(analysisJavaVersion))
    }

tasks.register<JavaExec>("officialServerInteropTest") {
    group = "verification"
    description =
        "Run Status and offline Login/Configuration against the exact official server."
    dependsOn(
        jvmTestCompilation.compileTaskProvider,
        rootProject.tasks.named("downloadOfficialMinecraftServer"),
    )
    classpath(
        jvmTestCompilation.output.allOutputs,
        jvmTestCompilation.runtimeDependencyFiles,
    )
    mainClass.set(
        "com.hiczp.minecraft.protocol.serialization.OfficialServerInteropRunner",
    )
    args(
        analysisJavaLauncher.get().executablePath.asFile.absolutePath,
        rootProject.file(
            "build/protocol-reference/mojang/$protocolMinecraftVersion/server.jar",
        ).absolutePath,
        rootProject.file(
            "build/protocol-reference/official-server-interop/" +
                    protocolMinecraftVersion,
        ).absolutePath,
        rootProject.file(
            "build/reports/protocol-update/official-server-interop.json",
        ).absolutePath,
    )
    outputs.upToDateWhen { false }
}

val capturedVanillaDataSource = layout.buildDirectory.file(
    "generated/vanilla-data/com/hiczp/minecraft/protocol/data/" +
            "VanillaConfigurationPayloads.kt",
)
val capturedVanillaDataManifest = layout.buildDirectory.file(
    "reports/protocol-update/vanilla-configuration-data.json",
)

val captureOfficialVanillaData =
    tasks.register<JavaExec>("captureOfficialVanillaData") {
        group = "minecraft protocol"
        description =
            "Capture both Known Packs branches from the exact official server."
        dependsOn(
            jvmTestCompilation.compileTaskProvider,
            rootProject.tasks.named("downloadOfficialMinecraftServer"),
        )
        classpath(
            jvmTestCompilation.output.allOutputs,
            jvmTestCompilation.runtimeDependencyFiles,
        )
        mainClass.set(
            "com.hiczp.minecraft.protocol.serialization.OfficialVanillaDataGenerator",
        )
        args(
            analysisJavaLauncher.get().executablePath.asFile.absolutePath,
            rootProject.file(
                "build/protocol-reference/mojang/$protocolMinecraftVersion/server.jar",
            ).absolutePath,
            rootProject.file(
                "build/protocol-reference/vanilla-data-capture/" +
                        protocolMinecraftVersion,
            ).absolutePath,
            capturedVanillaDataSource.get().asFile.absolutePath,
            capturedVanillaDataManifest.get().asFile.absolutePath,
        )
        outputs.files(capturedVanillaDataSource, capturedVanillaDataManifest)
        outputs.upToDateWhen { false }
    }

val updateVanillaProtocolDataSource =
    tasks.register<CopyFileTask>("updateVanillaProtocolDataSource") {
        group = "minecraft protocol"
        description = "Commit the latest exact official vanilla Kotlin data."
        dependsOn(captureOfficialVanillaData)
        sourceFile.set(capturedVanillaDataSource)
        destinationFile.set(
            rootProject.layout.projectDirectory.file(
                "protocol-vanilla-data/src/commonMain/kotlin/" +
                        "com/hiczp/minecraft/protocol/data/" +
                        "VanillaConfigurationPayloads.kt",
            ),
        )
    }

val updateVanillaProtocolDataManifest =
    tasks.register<CopyFileTask>("updateVanillaProtocolDataManifest") {
        group = "minecraft protocol"
        description = "Commit the latest exact official vanilla-data manifest."
        dependsOn(captureOfficialVanillaData)
        sourceFile.set(capturedVanillaDataManifest)
        destinationFile.set(
            rootProject.layout.projectDirectory.file(
                "protocol-specification/vanilla-configuration-data.json",
            ),
        )
    }

tasks.register("updateVanillaProtocolData") {
    group = "minecraft protocol"
    description = "Commit the latest exact official vanilla-data snapshot."
    dependsOn(
        updateVanillaProtocolDataSource,
        updateVanillaProtocolDataManifest,
    )
}

val checkVanillaProtocolDataSource =
    tasks.register<CompareFilesTask>("checkVanillaProtocolDataSource") {
        group = "verification"
        description =
            "Require committed vanilla Kotlin data to equal a fresh capture."
        dependsOn(captureOfficialVanillaData)
        expectedFile.set(capturedVanillaDataSource)
        actualFile.set(
            rootProject.layout.projectDirectory.file(
                "protocol-vanilla-data/src/commonMain/kotlin/" +
                        "com/hiczp/minecraft/protocol/data/" +
                        "VanillaConfigurationPayloads.kt",
            ),
        )
    }

val checkVanillaProtocolDataManifest =
    tasks.register<CompareFilesTask>("checkVanillaProtocolDataManifest") {
        group = "verification"
        description =
            "Require committed vanilla-data manifest to equal a fresh capture."
        dependsOn(captureOfficialVanillaData)
        expectedFile.set(capturedVanillaDataManifest)
        compareJsonSemantically.set(true)
        actualFile.set(
            rootProject.layout.projectDirectory.file(
                "protocol-specification/vanilla-configuration-data.json",
            ),
        )
    }

tasks.register("checkVanillaProtocolData") {
    group = "verification"
    description =
        "Require the committed vanilla-data snapshot to equal a fresh official capture."
    dependsOn(
        checkVanillaProtocolDataSource,
        checkVanillaProtocolDataManifest,
    )
}
