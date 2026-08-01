import com.hiczp.minecraft.protocol.buildScript.GenerateVanillaConfigurationSourceTask
import com.hiczp.minecraft.protocol.buildScript.GenerateVanillaStaticDataSourceTask
import com.hiczp.minecraft.protocol.buildScript.configureAllTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
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
val officialMinecraftConfiguration = configurations.create(
    "officialMinecraftConfiguration",
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
val officialConfigurationFile = layout.file(
    officialMinecraftConfiguration.elements.map { it.single().asFile },
)

val generatedStaticDataDirectory = layout.buildDirectory.dir(
    "generated/sources/vanillaStaticData/commonMain/kotlin",
)
val generatedConfigurationDirectory = layout.buildDirectory.dir(
    "generated/sources/vanillaConfiguration/commonMain/kotlin",
)

val generateVanillaStaticDataSource =
    tasks.register<GenerateVanillaStaticDataSourceTask>(
        "generateVanillaStaticDataSource",
    ) {
        description = "Generate typed vanilla registry and block-state source."
        targetFile = officialTargetFile
        registriesFile = officialReportsDirectory.map {
            it.file("reports/registries.json")
        }
        blocksFile = officialReportsDirectory.map {
            it.file("reports/blocks.json")
        }
        outputFile =
            generatedStaticDataDirectory.map {
                it.file("com/hiczp/minecraft/protocol/data/VanillaStaticDataPayloads.kt")
            }
    }

val generateVanillaConfigurationSource =
    tasks.register<GenerateVanillaConfigurationSourceTask>(
        "generateVanillaConfigurationSource",
    ) {
        description = "Generate vanilla Configuration source from analysis data."
        targetFile = officialTargetFile
        configurationFile = officialConfigurationFile
        outputFile =
            generatedConfigurationDirectory.map {
                it.file("com/hiczp/minecraft/protocol/data/VanillaConfigurationPayloads.kt")
            }
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
    add(
        officialMinecraftConfiguration.name,
        project(
            path = ":",
            configuration = "officialMinecraftConfigurationElements",
        ),
    )
}

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
                    .builtBy(generateVanillaConfigurationSource),
            )
            dependencies {
                api(project(":protocol-model"))
                implementation(project(":protocol-serialization"))
                implementation(libs.kotlinx.serialization.json)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
