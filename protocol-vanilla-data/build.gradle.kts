import com.hiczp.minecraft.protocol.buildScript.GenerateVanillaStaticDataSourceTask
import com.hiczp.minecraft.protocol.buildScript.MinecraftTarget
import com.hiczp.minecraft.protocol.buildScript.configureAllTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

val generatedStaticDataDirectory = layout.buildDirectory.dir(
    "generated/sources/vanillaStaticData/commonMain/kotlin",
)
val generateVanillaStaticDataSource =
    tasks.register<GenerateVanillaStaticDataSourceTask>(
        "generateVanillaStaticDataSource",
    ) {
        dependsOn(
            rootProject.tasks.named("generateOfficialMinecraftReports"),
        )
        repositoryDirectory.set(rootProject.layout.projectDirectory)
        serverJar.set(
            rootProject.layout.buildDirectory.file(
                "protocol-reference/mojang/${MinecraftTarget.version}/server.jar",
            ),
        )
        registriesFile.set(
            rootProject.layout.buildDirectory.file(
                "protocol-reference/mojang/${MinecraftTarget.version}/" +
                        "generated/reports/registries.json",
            ),
        )
        blocksFile.set(
            rootProject.layout.buildDirectory.file(
                "protocol-reference/mojang/${MinecraftTarget.version}/" +
                        "generated/reports/blocks.json",
            ),
        )
        outputFile.set(
            generatedStaticDataDirectory.map {
                it.file(
                    "com/hiczp/minecraft/protocol/data/" +
                            "VanillaStaticDataPayloads.kt",
                )
            },
        )
    }

val generatedConfigurationDirectory =
    project(":protocol-serialization").layout.buildDirectory.dir(
        "generated/vanilla-configuration/commonMain/kotlin",
    )

kotlin {
    configureAllTargets("com.hiczp.minecraft.protocol.data")

    sourceSets {
        commonMain {
            kotlin.srcDir(
                files(generatedStaticDataDirectory)
                    .builtBy(generateVanillaStaticDataSource),
            )
            kotlin.srcDir(
                files(generatedConfigurationDirectory)
                    .builtBy(
                        ":protocol-serialization:" +
                                "generateVanillaConfigurationData",
                    ),
            )
            dependencies {
                api(project(":protocol-model"))
                implementation(project(":protocol-serialization"))
                implementation(libs.kotlinx.serialization.core)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
