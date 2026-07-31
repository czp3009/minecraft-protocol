import com.hiczp.minecraft.protocol.buildScript.DownloadOfficialMinecraftServerTask
import com.hiczp.minecraft.protocol.buildScript.GenerateMinecraftProtocolSourceTask
import com.hiczp.minecraft.protocol.buildScript.GenerateOfficialMinecraftReportsTask
import com.hiczp.minecraft.protocol.buildScript.configureAllTargets
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

val generatedProtocolSourceDirectory = layout.buildDirectory.dir(
    "generated/sources/minecraftProtocol/commonMain/kotlin",
)
val generatedPacketDefinitionsDirectory = layout.buildDirectory.dir(
    "generated/ksp/metadata/commonMain/kotlin",
)
// Run the aggregating processor once over commonMain. KSP does not attach the
// common-metadata output to every platform compilation or source JAR, so the
// shared output and task dependency are wired explicitly below.
val generatePacketDefinitions =
    tasks.matching { it.name == "kspCommonMainKotlinMetadata" }
val generateMinecraftProtocolSource =
    tasks.register<GenerateMinecraftProtocolSourceTask>(
        "generateMinecraftProtocolSource",
    ) {
        description = "Generate protocol constants for the selected Minecraft release."
        val download = rootProject.tasks
            .named<DownloadOfficialMinecraftServerTask>(
                "downloadOfficialMinecraftServer",
            )
        serverJar.set(download.flatMap { it.serverJar })
        outputFile.set(
            generatedProtocolSourceDirectory.map {
                it.file(
                    "com/hiczp/minecraft/protocol/model/MinecraftProtocol.kt",
                )
            },
        )
    }

val officialReports = rootProject.tasks.named<GenerateOfficialMinecraftReportsTask>(
    "generateOfficialMinecraftReports",
)
val packetsReport = officialReports.flatMap {
    it.outputDirectory.file("reports/packets.json")
}

ksp {
    arg(
        "minecraft.packetsReport",
        packetsReport.map { it.asFile.absolutePath },
    )
}

dependencies {
    add("kspCommonMainMetadata", project(":protocol-symbol-processor"))
}

kotlin {
    configureAllTargets("com.hiczp.minecraft.protocol.model")

    sourceSets {
        commonMain {
            kotlin.srcDir(
                files(generatedProtocolSourceDirectory)
                    .builtBy(generateMinecraftProtocolSource),
            )
            kotlin.srcDir(generatedPacketDefinitionsDirectory)
            dependencies {
                api(libs.kotlinx.serialization.core)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

generatePacketDefinitions.configureEach {
    inputs.file(packetsReport)
        .withPathSensitivity(PathSensitivity.NONE)
}

tasks.withType<KotlinCompilationTask<*>>()
    .configureEach {
        dependsOn(generatePacketDefinitions)
    }
tasks.matching { it.name.endsWith("SourcesJar") }.configureEach {
    dependsOn(generatePacketDefinitions)
}
