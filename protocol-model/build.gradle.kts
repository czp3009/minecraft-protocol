import com.google.devtools.ksp.gradle.KspAATask
import com.hiczp.minecraft.protocol.buildScript.GenerateMinecraftProtocolSourceTask
import com.hiczp.minecraft.protocol.buildScript.configureAllTargets
import org.gradle.jvm.tasks.Jar

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

val officialMinecraftTarget = configurations.create(
    "officialMinecraftTarget",
) {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val officialMinecraftReports = configurations.create(
    "officialMinecraftReports",
) {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
val officialTargetFile = layout.file(
    officialMinecraftTarget.elements.map { it.single().asFile },
)
val officialReportsDirectory = layout.dir(
    officialMinecraftReports.elements.map { it.single().asFile },
)

val generatedProtocolSourceDirectory = layout.buildDirectory.dir(
    "generated/sources/minecraftProtocol/commonMain/kotlin",
)
val generatedPacketDefinitionsDirectory = layout.buildDirectory.dir(
    "generated/ksp/metadata/commonMain/kotlin",
)
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
    add(
        officialMinecraftTarget.name,
        project(
            path = ":",
            configuration = "officialMinecraftTargetElements",
        ),
    )
    add(
        officialMinecraftReports.name,
        project(
            path = ":",
            configuration = "officialMinecraftReportsElements",
        ),
    )
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

    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                dependsOn(generatePacketDefinitions)
            }
        }
    }
}

generatePacketDefinitions.configureEach {
    inputs.file(packetsReport)
        .withPathSensitivity(PathSensitivity.NONE)
}

tasks.withType<Jar>().configureEach {
    dependsOn(generatePacketDefinitions)
}
