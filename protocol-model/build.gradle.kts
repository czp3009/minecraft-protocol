import com.google.devtools.ksp.gradle.KspAATask
import com.hiczp.minecraft.protocol.buildScript.GenerateMinecraftProtocolSourceTask
import com.hiczp.minecraft.protocol.buildScript.configureAllTargets
import com.hiczp.minecraft.protocol.buildScript.officialMinecraftAnalysisDirectory
import com.hiczp.minecraft.protocol.buildScript.officialMinecraftAnalysisFile
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

val officialTargetFile =
    officialMinecraftAnalysisFile("officialMinecraftTarget")
val officialReportsDirectory =
    officialMinecraftAnalysisDirectory("officialMinecraftReports")

val generatedProtocolSourceDirectory = layout.buildDirectory.dir(
    "generated/sources/minecraftProtocol/commonMain/kotlin",
)
val generatedPacketDefinitionsDirectory = layout.buildDirectory.dir(
    "generated/ksp/metadata/commonMain/kotlin",
)
// KSP registers this task after this script runs; match it lazily.
val generatePacketDefinitions = tasks.withType<KspAATask>().matching {
    it.name == "kspCommonMainKotlinMetadata"
}
val generateMinecraftProtocolSource =
    tasks.register<GenerateMinecraftProtocolSourceTask>(
        "generateMinecraftProtocolSource",
    ) {
        description = "Generate protocol constants for the selected Minecraft release."
        targetFile = officialTargetFile
        outputFile =
            generatedProtocolSourceDirectory.map {
                it.file("com/hiczp/minecraft/protocol/model/MinecraftProtocol.kt")
            }
    }

val packetsReport = officialReportsDirectory.map {
    it.file("reports/packets.json")
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
            // KSP does not wire the common-metadata processor output into
            // downstream compilations. A plain srcDir plus explicit task
            // dependencies is required: builtBy here would make the metadata
            // KSP task (which reads this source set) depend on itself.
            kotlin.srcDir(generatedPacketDefinitionsDirectory)
            dependencies {
                api(libs.kotlinx.serialization.core)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }

    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                dependsOn(generatePacketDefinitions)
            }
        }
    }
}

tasks.withType<Jar>().configureEach {
    dependsOn(generatePacketDefinitions)
}

// The KSP option alone is a plain string; track the report's content so the
// processor reruns when the official analysis data changes.
generatePacketDefinitions.configureEach {
    inputs.file(packetsReport)
        .withPathSensitivity(PathSensitivity.NONE)
}
