import com.hiczp.minecraft.protocol.buildScript.GenerateMinecraftProtocolSourceTask
import com.hiczp.minecraft.protocol.buildScript.MinecraftTarget
import com.hiczp.minecraft.protocol.buildScript.configureAllTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

val generatedProtocolSourceDirectory = layout.buildDirectory.dir(
    "generated/sources/minecraftProtocol/commonMain/kotlin",
)
val generateMinecraftProtocolSource =
    tasks.register<GenerateMinecraftProtocolSourceTask>(
        "generateMinecraftProtocolSource",
    ) {
        dependsOn(rootProject.tasks.named("downloadOfficialMinecraftServer"))
        repositoryDirectory.set(rootProject.layout.projectDirectory)
        serverJar.set(
            rootProject.layout.buildDirectory.file(
                "protocol-reference/mojang/${MinecraftTarget.version}/server.jar",
            ),
        )
        outputFile.set(
            generatedProtocolSourceDirectory.map {
                it.file(
                    "com/hiczp/minecraft/protocol/model/MinecraftProtocol.kt",
                )
            },
        )
    }

kotlin {
    configureAllTargets("com.hiczp.minecraft.protocol.model")

    sourceSets {
        commonMain {
            kotlin.srcDir(
                files(generatedProtocolSourceDirectory)
                    .builtBy(generateMinecraftProtocolSource),
            )
            dependencies {
                api(libs.kotlinx.serialization.core)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named<Test>("jvmTest") {
    dependsOn(rootProject.tasks.named("generateProtocolSpecification"))
    systemProperty(
        "minecraft.protocol.expectedSpecification",
        rootProject.layout.buildDirectory.dir(
            "generated/protocol-specification/complete",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "minecraft.protocol.checkedInSpecification",
        rootProject.layout.projectDirectory.dir(
            "protocol-specification",
        ).asFile.absolutePath,
    )
}
