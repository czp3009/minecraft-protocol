import com.hiczp.minecraft.protocol.buildScript.*

plugins {
    id("java-base")
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidKotlinMultiplatformLibrary) apply false
}

group = "com.hiczp"
version = "0.0.1"

val vineflower = configurations.create("vineflower")

dependencies {
    vineflower(libs.vineflower)
}

val protocolTarget = providers.gradleProperty("protocolTarget")

tasks.register<RefreshProtocolSpecificationTask>(
    "refreshProtocolSpecification",
) {
    group = "minecraft protocol"
    description = "Refresh the normative packet inventory from the current stable Minecraft Wiki page."
    repositoryDirectory.set(layout.projectDirectory)
    checkOnly.set(false)
    if (protocolTarget.isPresent) {
        target.set(protocolTarget)
    }
}

tasks.register<RefreshProtocolSpecificationTask>(
    "checkProtocolSpecification",
) {
    group = "minecraft protocol"
    description = "Check that the checked-in Wiki snapshot is the current Wiki revision."
    repositoryDirectory.set(layout.projectDirectory)
    checkOnly.set(true)
    if (protocolTarget.isPresent) {
        target.set(protocolTarget)
    }
}

val protocolSnapshot = layout.projectDirectory.file(
    "protocol-specification/wiki-protocol-snapshot.json",
)
val protocolSnapshotText = protocolSnapshot.asFile.readText()
val protocolJavaVersion = Regex("\"java_major_version\"\\s*:\\s*(\\d+)")
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
val javaToolchains = extensions.getByType<JavaToolchainService>()
val protocolJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(protocolJavaVersion))
}
val protocolJavaCompiler = javaToolchains.compilerFor {
    languageVersion.set(JavaLanguageVersion.of(protocolJavaVersion))
}
val protocolJavaExecutable = providers.gradleProperty("protocolJavaExecutable").orElse(
    protocolJavaLauncher.map { it.executablePath.asFile.absolutePath },
)

val downloadOfficialMinecraftServer =
    tasks.register<DownloadOfficialMinecraftServerTask>(
        "downloadOfficialMinecraftServer",
    ) {
        group = "minecraft protocol"
        description = "Download and SHA-1 verify the Mojang server for the Wiki target."
        repositoryDirectory.set(layout.projectDirectory)
        mustRunAfter("refreshProtocolSpecification")
    }

val minecraftClientDirectoryOverride =
    providers.gradleProperty("minecraftClientDirectory")
val prepareOfficialMinecraftClient =
    tasks.register<PrepareOfficialMinecraftClientTask>(
        "prepareOfficialMinecraftClient",
    ) {
        group = "minecraft protocol"
        description =
            "Prepare a complete hash-verified Mojang client under build/."
        repositoryDirectory.set(layout.projectDirectory)
        offline.set(false)
        enabled = !minecraftClientDirectoryOverride.isPresent
        mustRunAfter("refreshProtocolSpecification")
    }

val verifyPreparedOfficialMinecraftClient =
    tasks.register<PrepareOfficialMinecraftClientTask>(
        "verifyPreparedOfficialMinecraftClient",
    ) {
        group = "verification"
        description =
            "Verify the complete build-local Mojang client without network access."
        repositoryDirectory.set(layout.projectDirectory)
        offline.set(true)
        enabled = !minecraftClientDirectoryOverride.isPresent
        mustRunAfter(prepareOfficialMinecraftClient)
    }

val headlessMinecraftLauncherVersion = "2.10.0"
extra["headlessMinecraftLauncherVersion"] = headlessMinecraftLauncherVersion
val downloadHeadlessMinecraftLauncher =
    tasks.register<DownloadHeadlessMinecraftLauncherTask>(
        "downloadHeadlessMinecraftLauncher",
    ) {
        group = "minecraft protocol"
        description =
            "Download and SHA-256 verify the build-local HeadlessMC launcher."
        version.set(headlessMinecraftLauncherVersion)
        downloadUrl.set(
            "https://github.com/headlesshq/headlessmc/releases/download/" +
                    "$headlessMinecraftLauncherVersion/" +
                    "headlessmc-launcher-$headlessMinecraftLauncherVersion.jar",
        )
        expectedSize.set(13_010_386L)
        expectedSha256.set(
            "52bd5006f478377b3893011d458562977d38c65ead6d2b31089beb4d614f13cd",
        )
        offline.set(gradle.startParameter.isOffline)
        launcherJar.set(
            layout.buildDirectory.file(
                "protocol-reference/headlessmc/" +
                        "$headlessMinecraftLauncherVersion/" +
                        "headlessmc-launcher-$headlessMinecraftLauncherVersion.jar",
            ),
        )
        outputs.upToDateWhen { false }
    }

val generateOfficialMinecraftReports =
    tasks.register<GenerateOfficialMinecraftReportsTask>(
        "generateOfficialMinecraftReports",
    ) {
        group = "minecraft protocol"
        description = "Run the vanilla data generator and produce reports/packets.json."
        dependsOn(downloadOfficialMinecraftServer)
        repositoryDirectory.set(layout.projectDirectory)
        javaExecutable.set(protocolJavaExecutable)
    }

val unpackOfficialMinecraftServer =
    tasks.register<UnpackOfficialMinecraftServerTask>(
        "unpackOfficialMinecraftServer",
    ) {
        group = "minecraft protocol"
        description = "Extract and SHA-256 verify the implementation JAR inside the Mojang bundle."
        dependsOn(downloadOfficialMinecraftServer)
        repositoryDirectory.set(layout.projectDirectory)
    }

val decompileOfficialMinecraftServer = tasks.register<JavaExec>("decompileOfficialMinecraftServer") {
    group = "minecraft protocol"
    description = "Decompile the verified vanilla implementation with Vineflower."
    dependsOn(unpackOfficialMinecraftServer)
    javaLauncher.set(protocolJavaLauncher)
    classpath = vineflower
    mainClass.set("org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler")
    val implementationJar = layout.projectDirectory.file(
        "build/protocol-reference/mojang/$protocolMinecraftVersion/server-inner.jar",
    ).asFile
    val sourcesDirectory = layout.projectDirectory.dir(
        "build/protocol-reference/mojang/$protocolMinecraftVersion/decompiled",
    ).asFile
    inputs.file(implementationJar)
    outputs.dir(sourcesDirectory)
    args(
        "-asc=1",
        "-dgs=1",
        "-log=WARN",
        "-rsy=1",
        implementationJar.absolutePath,
        sourcesDirectory.absolutePath,
    )
}

val verifyProtocolReferenceSources =
    tasks.register<CompareWikiWithOfficialTask>(
        "verifyProtocolReferenceSources",
    ) {
        group = "verification"
        description = "Require the Wiki packet inventory to match vanilla reports/packets.json."
        dependsOn(generateOfficialMinecraftReports)
        repositoryDirectory.set(layout.projectDirectory)
    }

val checkOfficialNetworkRegistries =
    tasks.register<AuditNetworkRegistriesTask>(
        "checkOfficialNetworkRegistries",
    ) {
        group = "verification"
        description =
            "Require executable Kotlin mappings to match finite vanilla registries."
        dependsOn(
            generateOfficialMinecraftReports,
            ":protocol-serialization:generateNetworkRegistryManifest",
        )
        repositoryDirectory.set(layout.projectDirectory)
        manifestFile.set(
            layout.projectDirectory.file(
                "protocol-serialization/build/reports/protocol-update/" +
                        "network-registries.tsv",
            ),
        )
        reportFile.set(
            layout.projectDirectory.file(
                "build/reports/protocol-update/network-registries.json",
            ),
        )
    }

val updateVanillaStaticData =
    tasks.register<VanillaStaticDataTask>("updateVanillaStaticData") {
        group = "minecraft protocol"
        description =
            "Update committed static registries and block states from vanilla reports."
        dependsOn(generateOfficialMinecraftReports)
        repositoryDirectory.set(layout.projectDirectory)
        checkOnly.set(false)
    }

val checkVanillaStaticData =
    tasks.register<VanillaStaticDataTask>("checkVanillaStaticData") {
        group = "verification"
        description =
            "Require committed static registries and block states to match vanilla."
        dependsOn(generateOfficialMinecraftReports)
        repositoryDirectory.set(layout.projectDirectory)
        checkOnly.set(true)
    }

val indexOfficialMinecraftSources =
    tasks.register<IndexOfficialMinecraftSourcesTask>(
        "indexOfficialMinecraftSources",
    ) {
        group = "minecraft protocol"
        description = "Map every Wiki packet row to its decompiled vanilla packet class."
        dependsOn(decompileOfficialMinecraftServer, verifyProtocolReferenceSources)
        repositoryDirectory.set(layout.projectDirectory)
        decompilerVersion.set(libs.versions.vineflower)
    }

val prepareAuxiliaryProtocolSources =
    tasks.register<PrepareAuxiliaryProtocolSourcesTask>(
        "prepareAuxiliaryProtocolSources",
    ) {
        group = "minecraft protocol"
        description = "Acquire exact-version MCProtocolLib and Minestom references where available."
        repositoryDirectory.set(layout.projectDirectory)
    }

val prepareWikiProtocolReferences =
    tasks.register<PrepareWikiProtocolReferencesTask>(
        "prepareWikiProtocolReferences",
    ) {
        group = "minecraft protocol"
        description =
            "Cache linked Minecraft Wiki protocol pages at the selected packet revision timestamp."
        repositoryDirectory.set(layout.projectDirectory)
    }

tasks.register("prepareOfficialMinecraftSources") {
    group = "minecraft protocol"
    description = "Prepare reports and decompiled official sources for semantic packet auditing."
    dependsOn(indexOfficialMinecraftSources)
}

val auditProtocolModels =
    tasks.register<AuditProtocolModelsTask>("auditProtocolModels") {
        group = "verification"
        description = "Audit packet IDs, protocol metadata, module boundaries, and source hygiene."
        repositoryDirectory.set(layout.projectDirectory)
        reportOnly.set(false)
    }

val reportProtocolModelGaps =
    tasks.register<AuditProtocolModelsTask>("reportProtocolModelGaps") {
        group = "minecraft protocol"
        description = "Write the current deterministic protocol work queue without failing the build."
        repositoryDirectory.set(layout.projectDirectory)
        reportFile.set(
            layout.projectDirectory.file("build/reports/protocol-update/work-queue.json")
        )
        reportOnly.set(true)
        mustRunAfter(verifyProtocolReferenceSources, indexOfficialMinecraftSources)
    }

val reportProtocolNullability =
    tasks.register<AuditNullabilityTask>("reportProtocolNullability") {
        group = "minecraft protocol"
        description = "Write the deterministic Kotlin model-property nullability inventory."
        repositoryDirectory.set(layout.projectDirectory)
        reportFile.set(
            layout.projectDirectory.file(
                "build/reports/protocol-update/nullability-inventory.json",
            ),
        )
        reportOnly.set(true)
    }

val checkProtocolNullability =
    tasks.register<AuditNullabilityTask>("checkProtocolNullability") {
        group = "verification"
        description = "Require current official-first evidence for every model property's nullability."
        repositoryDirectory.set(layout.projectDirectory)
        ledgerFile.set(
            layout.projectDirectory.file(
                "protocol-specification/nullability-audit.yaml",
            ),
        )
        reportOnly.set(false)
    }

val generatePacketRegistry =
    tasks.register<GeneratePacketRegistryTask>("generatePacketRegistry") {
        group = "minecraft protocol"
        description = "Regenerate the committed runtime packet registry from packet model annotations."
        repositoryDirectory.set(layout.projectDirectory)
        checkOnly.set(false)
    }

val checkPacketRegistry =
    tasks.register<GeneratePacketRegistryTask>("checkPacketRegistry") {
        group = "verification"
        description = "Require the committed runtime packet registry to match all packet models."
        repositoryDirectory.set(layout.projectDirectory)
        checkOnly.set(true)
    }

val refreshOfficialProtocolConformance =
    tasks.register<AuditOfficialConformanceTask>(
        "refreshOfficialProtocolConformance",
    ) {
        group = "minecraft protocol"
        description =
            "Refresh per-packet official/Kotlin/test fingerprints, invalidating stale reviews."
        dependsOn(indexOfficialMinecraftSources)
        repositoryDirectory.set(layout.projectDirectory)
        refresh.set(true)
        reportOnly.set(true)
        reportFile.set(
            layout.projectDirectory.file(
                "build/reports/protocol-update/official-conformance.json",
            ),
        )
    }

val reportOfficialProtocolConformance =
    tasks.register<AuditOfficialConformanceTask>(
        "reportOfficialProtocolConformance",
    ) {
        group = "minecraft protocol"
        description =
            "Report missing, stale, or non-passing official conformance records."
        dependsOn(indexOfficialMinecraftSources)
        repositoryDirectory.set(layout.projectDirectory)
        refresh.set(false)
        reportOnly.set(true)
        reportFile.set(
            layout.projectDirectory.file(
                "build/reports/protocol-update/official-conformance.json",
            ),
        )
    }

val checkOfficialProtocolConformance =
    tasks.register<AuditOfficialConformanceTask>(
        "checkOfficialProtocolConformance",
    ) {
        group = "verification"
        description =
            "Require current passing official-JAR conformance evidence for every packet."
        dependsOn(indexOfficialMinecraftSources)
        repositoryDirectory.set(layout.projectDirectory)
        refresh.set(false)
        reportOnly.set(false)
    }

val officialServerRoot = layout.projectDirectory.dir(
    "build/protocol-reference/mojang/$protocolMinecraftVersion",
)
val officialServerInnerJar = officialServerRoot.file("server-inner.jar")
val officialServerRuntimeClasspath = files(
    officialServerInnerJar,
    fileTree(officialServerRoot.dir("libraries")) {
        include("**/*.jar")
    },
)
val officialCodecOracleClasses = layout.buildDirectory.dir(
    "protocol-reference/official-codec-oracle/classes",
)
val compileOfficialCodecOracle =
    tasks.register<JavaCompile>("compileOfficialCodecOracle") {
        group = "minecraft protocol"
        description = "Compile the exact-version vanilla packet codec oracle."
        dependsOn(generateOfficialMinecraftReports, unpackOfficialMinecraftServer)
        source(
            layout.projectDirectory.dir(
                "buildSrc/src/officialCodecOracle/java",
            ).asFileTree.matching {
                include("**/*.java")
            },
        )
        classpath = officialServerRuntimeClasspath
        destinationDirectory.set(officialCodecOracleClasses)
        javaCompiler.set(protocolJavaCompiler)
        options.release.set(protocolJavaVersion)
    }

fun JavaExec.configureOfficialCodecOracle(ignoreFailure: Boolean) {
    dependsOn(
        compileOfficialCodecOracle,
        indexOfficialMinecraftSources,
        ":protocol-serialization:generateOfficialCodecFixtures",
    )
    javaLauncher.set(protocolJavaLauncher)
    workingDir(officialServerRoot.asFile)
    classpath(
        officialCodecOracleClasses,
        officialServerRuntimeClasspath,
    )
    mainClass.set("OfficialCodecOracle")
    args(
        layout.projectDirectory.file(
            "protocol-serialization/build/reports/protocol-update/" +
                    "official-codec-fixtures.tsv",
        ).asFile.absolutePath,
        layout.projectDirectory.file(
            "protocol-specification/official-packet-classes.csv",
        ).asFile.absolutePath,
        officialServerInnerJar.asFile.absolutePath,
        layout.projectDirectory.file(
            "build/reports/protocol-update/official-codec-conformance.json",
        ).asFile.absolutePath,
    )
    isIgnoreExitValue = ignoreFailure
}

tasks.register<JavaExec>("reportOfficialCodecConformance") {
    group = "minecraft protocol"
    description =
        "Run every Kotlin packet sample through the matching vanilla STREAM_CODEC."
    configureOfficialCodecOracle(ignoreFailure = true)
}

val checkOfficialCodecConformance =
    tasks.register<JavaExec>("checkOfficialCodecConformance") {
        group = "verification"
        description =
            "Require every Kotlin packet sample to conform to the vanilla codec."
        configureOfficialCodecOracle(ignoreFailure = false)
    }

refreshOfficialProtocolConformance.configure {
    dependsOn(checkOfficialCodecConformance)
}
reportOfficialProtocolConformance.configure {
    dependsOn(checkOfficialCodecConformance)
}
checkOfficialProtocolConformance.configure {
    dependsOn(checkOfficialCodecConformance)
}

tasks.register("prepareProtocolUpdate") {
    group = "minecraft protocol"
    description = "Cross-check refreshed sources and produce the local update work queue."
    dependsOn(
        indexOfficialMinecraftSources,
        prepareWikiProtocolReferences,
        prepareAuxiliaryProtocolSources,
        reportProtocolModelGaps,
        reportProtocolNullability,
        reportOfficialProtocolConformance,
    )
}

tasks.register("prepareWorldStorageUpdate") {
    group = "minecraft protocol"
    description =
        "Prepare the exact official and auxiliary sources for NBT and world-storage auditing."
    dependsOn(
        decompileOfficialMinecraftServer,
        prepareAuxiliaryProtocolSources,
    )
}

val buildLogicTest = tasks.register<GradleBuild>("buildLogicTest") {
    group = "verification"
    description = "Run deterministic buildSrc tooling tests."
    buildName = "buildLogicVerification"
    dir = file("buildSrc")
    tasks = listOf("test")
}

val protocolJvmTest = tasks.register("protocolJvmTest") {
    group = "verification"
    description = "Run every protocol module's JVM test suite."
    dependsOn(
        buildLogicTest,
        ":nbt:jvmTest",
        ":protocol-model:jvmTest",
        ":protocol-serialization:jvmTest",
        ":protocol-vanilla-data:jvmTest",
        ":protocol-transport:jvmTest",
        ":protocol-session:jvmTest",
        ":protocol-auth:jvmTest",
        ":protocol-client:jvmTest",
        ":protocol-server:jvmTest",
    )
}

val protocolLayeredTest = tasks.register("protocolLayeredTest") {
    group = "verification"
    description =
        "Run every independently named protocol layer suite."
    dependsOn(
        ":nbt:nbtLayerTest",
        ":protocol-model:modelContractLayerTest",
        ":protocol-serialization:minecraftFormatLayerTest",
        ":protocol-serialization:packetPayloadLayerTest",
        ":protocol-serialization:packetTransportLayerTest",
        ":protocol-vanilla-data:vanillaDataLayerTest",
        ":protocol-transport:transportLayerTest",
        ":protocol-session:sessionLayerTest",
        ":protocol-auth:authLayerTest",
        ":protocol-client:clientLayerTest",
        ":protocol-server:serverLayerTest",
    )
}

val officialServerInteropTest = tasks.register("officialServerInteropTest") {
    group = "verification"
    description =
        "Exercise both codec probes and the production client against vanilla."
    dependsOn(
        ":protocol-serialization:officialServerInteropTest",
        ":protocol-client:officialServerClientInteropTest",
    )
}

val officialClientToServerEndToEndTest =
    tasks.register("officialClientToServerEndToEndTest") {
        group = "verification"
        description =
            "Launch the prepared vanilla client against the production server."
        dependsOn(
            verifyPreparedOfficialMinecraftClient,
            ":protocol-server:officialClientToServerEndToEndTest",
        )
    }

val headlessOfficialClientToServerEndToEndTest =
    tasks.register("headlessOfficialClientToServerEndToEndTest") {
        group = "verification"
        description =
            "Run the matching official client against production server APIs without a display."
        dependsOn(
            verifyPreparedOfficialMinecraftClient,
            downloadHeadlessMinecraftLauncher,
            ":protocol-server:headlessOfficialClientToServerEndToEndTest",
        )
    }

val checkProtocolWorkspaceHygiene =
    tasks.register<WorkspaceHygieneTask>("checkProtocolWorkspaceHygiene") {
        group = "verification"
        description =
            "Require vanilla runtime artifacts to stay in Gradle build directories."
        dependsOn(
            checkOfficialCodecConformance,
            generateOfficialMinecraftReports,
            officialServerInteropTest,
        )
        workspaceDirectory.set(layout.projectDirectory)
        forbiddenPaths.set(
            listOf(
                "eula.txt",
                "logs",
                "server.properties",
                "world",
                "world_nether",
                "world_the_end",
            ),
        )
    }

val worldStorageJvmTest = tasks.register("worldStorageJvmTest") {
    group = "verification"
    description = "Run every world-storage module's JVM test suite."
    dependsOn(
        buildLogicTest,
        ":nbt:jvmTest",
        ":world-format:jvmTest",
        ":world-io:jvmTest",
    )
}

val worldStorageLayeredTest = tasks.register("worldStorageLayeredTest") {
    group = "verification"
    description = "Run NBT, Anvil format, compression, and filesystem suites."
    dependsOn(
        ":nbt:nbtLayerTest",
        ":world-format:worldFormatLayerTest",
        ":world-io:worldIoLayerTest",
    )
}

val officialWorldStorageInteropTest =
    tasks.register("officialWorldStorageInteropTest") {
        group = "verification"
        description =
            "Rewrite a generated world and require the exact server to reload it."
        dependsOn(":world-io:officialWorldStorageInteropTest")
    }

tasks.register("verifyProtocolUpdate") {
    group = "verification"
    description =
        "Run the complete headless-CI protocol test, audit, and interoperability gate."
    dependsOn(
        auditProtocolModels,
        checkProtocolNullability,
        checkPacketRegistry,
        checkVanillaStaticData,
        checkOfficialNetworkRegistries,
        checkOfficialProtocolConformance,
        checkOfficialCodecConformance,
        checkProtocolWorkspaceHygiene,
        ":protocol-serialization:checkVanillaProtocolData",
        officialServerInteropTest,
        headlessOfficialClientToServerEndToEndTest,
        protocolLayeredTest,
        protocolJvmTest,
        indexOfficialMinecraftSources,
        ":protocol-model:compileCommonMainKotlinMetadata",
        ":protocol-serialization:compileCommonMainKotlinMetadata",
        ":protocol-vanilla-data:compileCommonMainKotlinMetadata",
        ":protocol-transport:compileCommonMainKotlinMetadata",
        ":protocol-session:compileCommonMainKotlinMetadata",
        ":protocol-auth:compileCommonMainKotlinMetadata",
        ":protocol-client:compileCommonMainKotlinMetadata",
        ":protocol-server:compileCommonMainKotlinMetadata",
        ":nbt:compileCommonMainKotlinMetadata",
    )
}

val verifyWorldStorageUpdate = tasks.register("verifyWorldStorageUpdate") {
    group = "verification"
    description =
        "Verify NBT streams, Anvil files, filesystem adapters, and vanilla interoperability."
    dependsOn(
        worldStorageLayeredTest,
        worldStorageJvmTest,
        officialWorldStorageInteropTest,
        ":nbt:compileCommonMainKotlinMetadata",
        ":world-format:compileCommonMainKotlinMetadata",
        ":world-io:compileCommonMainKotlinMetadata",
    )
}

tasks.register("verifyMinecraftLibrary") {
    group = "verification"
    description =
        "Run complete headless-CI protocol and world-storage verification."
    dependsOn(
        "verifyProtocolUpdate",
        verifyWorldStorageUpdate,
    )
}

subprojects {
    group = rootProject.group
    version = rootProject.version
}
