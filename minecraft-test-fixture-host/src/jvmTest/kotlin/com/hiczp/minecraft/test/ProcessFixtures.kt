package com.hiczp.minecraft.test

import kotlinx.io.files.Path

internal fun configureHostedTestSupportForJvmTests() {
    val projectDirectory = Path(System.getProperty("user.dir"))
    val fixtureRoot = Path(projectDirectory, "build", "fixture-host-tests")
    HostedMinecraftTestSupport.configure(
        MinecraftTestLayout(
            minecraftVersion = "fixture-host-test",
            serverCacheDirectory = Path(fixtureRoot, "server"),
            clientCacheDirectory = Path(fixtureRoot, "client"),
            versionMetadataFile = Path(fixtureRoot, "version.json"),
            headlessLauncherFile = Path(fixtureRoot, "headlessmc.jar"),
            serverRuntimeDirectory = Path(fixtureRoot, "server-runtime"),
            codecClassesDirectory = Path(fixtureRoot, "codec-classes"),
            fixtureWorkRoot = Path(fixtureRoot, "work"),
            javaExecutable = Path("java"),
        ),
    )
}

internal fun processFixtureSource(name: String): Path = Path(
    java.nio.file.Path.of(
        checkNotNull(
            Thread.currentThread().contextClassLoader.getResource(name),
        ) { "Process fixture resource is missing: $name" }.toURI(),
    ).toString(),
).also { source ->
    check(source.isRegularFile()) {
        "Process fixture source is missing: $source"
    }
}
