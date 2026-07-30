import com.hiczp.minecraft.protocol.buildScript.GeneratePacketRegistrySourceTask
import com.hiczp.minecraft.protocol.buildScript.MinecraftTarget
import com.hiczp.minecraft.protocol.buildScript.configureAllTargets

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

val generatedPacketRegistryDirectory = layout.buildDirectory.dir(
    "generated/sources/packetRegistry/commonMain/kotlin",
)
val generatePacketRegistrySource =
    tasks.register<GeneratePacketRegistrySourceTask>(
        "generatePacketRegistrySource",
    ) {
        dependsOn(
            rootProject.tasks.named("generateOfficialMinecraftReports"),
        )
        repositoryDirectory.set(rootProject.layout.projectDirectory)
        packetsReport.set(
            rootProject.layout.buildDirectory.file(
                "protocol-reference/mojang/${MinecraftTarget.version}/" +
                        "generated/reports/packets.json",
            ),
        )
        packetSources.from(
            rootProject.fileTree(
                "protocol-model/src/commonMain/kotlin/com/hiczp/" +
                        "minecraft/protocol/model/packet",
            ) {
                include("**/*.kt")
            },
        )
        outputFile.set(
            generatedPacketRegistryDirectory.map {
                it.file(
                    "com/hiczp/minecraft/protocol/serialization/" +
                            "GeneratedPacketRegistryEntries.kt",
                )
            },
        )
    }

kotlin {
    configureAllTargets("com.hiczp.minecraft.protocol.serialization")

    sourceSets {
        commonMain {
            kotlin.srcDir(
                files(generatedPacketRegistryDirectory)
                    .builtBy(generatePacketRegistrySource),
            )
            dependencies {
                api(project(":protocol-model"))
                implementation(project(":nbt"))
                api(libs.kotlinx.serialization.core)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest {
            kotlin.srcDir("src/jvmInteropSupport/kotlin")
        }
    }
}

val jvmTarget = kotlin.targets.getByName("jvm")
val jvmMainCompilation = jvmTarget.compilations.getByName("main")
val jvmToolCompilation = jvmTarget.compilations.create("tool") {
    associateWith(jvmMainCompilation)
}
kotlin.sourceSets.getByName("jvmTool").kotlin.srcDir(
    "src/jvmInteropSupport/kotlin",
)

val java25Launcher = extensions
    .getByType<JavaToolchainService>()
    .launcherFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
val officialServerJar = rootProject.layout.buildDirectory.file(
    "protocol-reference/mojang/${MinecraftTarget.version}/server.jar",
)
val generatedConfigurationDirectory = layout.buildDirectory.dir(
    "generated/vanilla-configuration",
)
val generatedConfigurationSource = generatedConfigurationDirectory.map {
    it.file(
        "commonMain/kotlin/com/hiczp/minecraft/protocol/data/" +
                "VanillaConfigurationPayloads.kt",
    )
}
val generatedConfigurationReport = generatedConfigurationDirectory.map {
    it.file("configuration.json")
}
val configurationCaptureWorkDirectory = layout.buildDirectory.dir(
    "tmp/vanilla-configuration/${MinecraftTarget.version}",
)

tasks.register<JavaExec>("generateVanillaConfigurationData") {
    dependsOn(
        jvmToolCompilation.compileTaskProvider,
        rootProject.tasks.named("downloadOfficialMinecraftServer"),
    )
    javaLauncher.set(java25Launcher)
    classpath(
        jvmToolCompilation.output.allOutputs,
        jvmToolCompilation.runtimeDependencyFiles,
    )
    mainClass.set(
        "com.hiczp.minecraft.protocol.serialization." +
                "OfficialVanillaDataGenerator",
    )
    inputs.file(officialServerJar)
        .withPathSensitivity(PathSensitivity.NONE)
    inputs.property("minecraftVersion", MinecraftTarget.version)
    outputs.files(
        generatedConfigurationSource,
        generatedConfigurationReport,
    )
    outputs.cacheIf("The official capture is canonicalized") { true }
    args(
        java25Launcher.get().executablePath.asFile.absolutePath,
        officialServerJar.get().asFile.absolutePath,
        configurationCaptureWorkDirectory.get().asFile.absolutePath,
        generatedConfigurationSource.get().asFile.absolutePath,
        generatedConfigurationReport.get().asFile.absolutePath,
    )
}

tasks.named<Test>("jvmTest") {
    dependsOn(
        rootProject.tasks.named("downloadOfficialMinecraftServer"),
        rootProject.tasks.named("compileOfficialCodecOracle"),
    )
    systemProperty(
        "minecraft.protocol.java",
        java25Launcher.get().executablePath.asFile.absolutePath,
    )
    systemProperty(
        "minecraft.protocol.serverJar",
        officialServerJar.get().asFile.absolutePath,
    )
    systemProperty(
        "minecraft.protocol.officialServerWork",
        layout.buildDirectory.dir(
            "test-runtimes/official-server/${MinecraftTarget.version}",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "minecraft.protocol.officialServerReport",
        layout.buildDirectory.file(
            "reports/tests/official-server.json",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "minecraft.protocol.codecOracleClasses",
        rootProject.layout.buildDirectory.dir(
            "generated/official-codec-oracle/classes",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "minecraft.protocol.officialRuntime",
        rootProject.layout.buildDirectory.dir(
            "protocol-reference/mojang/${MinecraftTarget.version}/runtime",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "minecraft.protocol.codecFixtures",
        layout.buildDirectory.file(
            "tmp/official-codec/fixtures.tsv",
        ).get().asFile.absolutePath,
    )
    systemProperty(
        "minecraft.protocol.codecReport",
        layout.buildDirectory.file(
            "reports/tests/official-codec.json",
        ).get().asFile.absolutePath,
    )
}
