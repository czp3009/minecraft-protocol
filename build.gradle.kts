import com.hiczp.minecraft.protocol.buildScript.*
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin
import org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.yarn.WasmYarnRootExtension

plugins {
    id("java-base")
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
}

group = "com.hiczp"
version = "0.0.1"

plugins.withType<YarnPlugin> {
    extensions.configure<YarnRootExtension> {
        lockFileDirectory =
            layout.buildDirectory.dir("kotlin-js-store/js").get().asFile
    }
}
plugins.withType<WasmYarnPlugin> {
    extensions.configure<WasmYarnRootExtension> {
        lockFileDirectory =
            layout.buildDirectory.dir("kotlin-js-store/wasm").get().asFile
    }
}

val minecraftVersion = MinecraftTarget.version
val officialServerDirectory = layout.buildDirectory.dir(
    "protocol-reference/mojang/$minecraftVersion",
)
val officialServerJar = officialServerDirectory.map {
    it.file("server.jar")
}
val officialServerMetadata = officialServerDirectory.map {
    it.file("download-metadata.json")
}
val officialReportsDirectory = officialServerDirectory.map {
    it.dir("generated")
}

val javaToolchains = extensions.getByType<JavaToolchainService>()
val java25Launcher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.register<PrintMinecraftVersionTask>("minecraftVersion") {
    group = "help"
    description = "Print the official Minecraft release selected in buildSrc."
    this.minecraftVersion.set(MinecraftTarget.version)
}

val downloadOfficialMinecraftServer =
    tasks.register<DownloadOfficialMinecraftServerTask>(
        "downloadOfficialMinecraftServer",
    ) {
        offline.set(gradle.startParameter.isOffline)
        serverJar.set(officialServerJar)
        metadataFile.set(officialServerMetadata)
    }

val generateOfficialMinecraftReports =
    tasks.register<GenerateOfficialMinecraftReportsTask>(
        "generateOfficialMinecraftReports",
    ) {
        javaExecutable.set(
            java25Launcher.map {
                it.executablePath.asFile.absolutePath
            },
        )
        serverJar.set(downloadOfficialMinecraftServer.flatMap {
            it.serverJar
        })
        downloadMetadata.set(downloadOfficialMinecraftServer.flatMap {
            it.metadataFile
        })
        outputDirectory.set(officialReportsDirectory)
    }

val officialServerProperties =
    tasks.register<GenerateOfficialServerPropertiesTask>(
        "generateOfficialServerProperties",
    ) {
        javaExecutable.set(
            java25Launcher.map {
                it.executablePath.asFile.absolutePath
            },
        )
        serverJar.set(downloadOfficialMinecraftServer.flatMap {
            it.serverJar
        })
        reportFile.set(
            layout.buildDirectory.file(
                "generated/protocol-specification/server-properties.json",
            ),
        )
    }

val generatedProtocolSpecification =
    tasks.register<GenerateProtocolSpecificationTask>(
        "generateProtocolSpecification",
    ) {
        dependsOn(":protocol-vanilla-data:generateVanillaConfigurationData")
        serverJar.set(downloadOfficialMinecraftServer.flatMap {
            it.serverJar
        })
        downloadMetadata.set(downloadOfficialMinecraftServer.flatMap {
            it.metadataFile
        })
        packetsReport.set(
            officialReportsDirectory.map {
                it.file("reports/packets.json")
            },
        )
        registriesReport.set(
            officialReportsDirectory.map {
                it.file("reports/registries.json")
            },
        )
        blocksReport.set(
            officialReportsDirectory.map {
                it.file("reports/blocks.json")
            },
        )
        serverPropertiesReport.set(officialServerProperties.flatMap {
            it.reportFile
        })
        configurationReport.set(
            project(":protocol-vanilla-data").layout.buildDirectory.file(
                "generated/reports/vanillaConfiguration/configuration.json",
            ),
        )
        outputDirectory.set(
            layout.buildDirectory.dir(
                "generated/protocol-specification/generated",
            ),
        )
    }

tasks.register<Sync>("refreshProtocolSpecification") {
    group = "minecraft"
    description =
        "Regenerate checked-in evidence from the selected official server."
    from(generatedProtocolSpecification.flatMap {
        it.outputDirectory
    })
    into(
        layout.projectDirectory.dir("protocol-specification/generated"),
    )
}

subprojects {
    group = rootProject.group
    version = rootProject.version
}
