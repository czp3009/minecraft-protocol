package com.hiczp.minecraft.test.host

import kotlinx.io.files.Path
import java.nio.file.Path as NioPath

internal fun configureHostedTestSupportForJvmTests() {
    val projectDirectory = Path(System.getProperty("user.dir"))
    val fixtureRoot = Path(projectDirectory, "build", "fixture-host-tests")
    HostedMinecraftTestSupport.configure(
        MinecraftTestLayout(
            minecraftVersion = "fixture-host-test",
            officialServerRootDirectory = Path(fixtureRoot, "server"),
            headlessClientRootDirectory = Path(fixtureRoot, "client"),
            serverRuntimeDirectory = Path(fixtureRoot, "server-runtime"),
            codecClassesDirectory = Path(fixtureRoot, "codec-classes"),
            hostWorkRoot = Path(fixtureRoot, "work", "hosts", "test"),
        ),
    )
}

internal fun processFixtureSource(name: String): Path = Path(
    NioPath.of(
        checkNotNull(
            Thread.currentThread().contextClassLoader.getResource(name),
        ) { "Process fixture resource is missing: $name" }.toURI(),
    ).toString(),
).also { source ->
    check(source.isRegularFile()) {
        "Process fixture source is missing: $source"
    }
}
