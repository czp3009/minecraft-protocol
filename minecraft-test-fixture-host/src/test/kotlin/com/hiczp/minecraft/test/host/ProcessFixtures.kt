package com.hiczp.minecraft.test.host

import java.nio.file.Path
import kotlin.io.path.isRegularFile

internal fun configureHostedTestSupportForJvmTests() {
    val projectDirectory = Path.of(System.getProperty("user.dir"))
    val fixtureRoot = projectDirectory.resolve("build").resolve("fixture-host-tests")
    HostedMinecraftTestSupport.configure(
        MinecraftTestLayout(
            minecraftVersion = "fixture-host-test",
            officialServerRootDirectory = fixtureRoot.resolve("server"),
            headlessClientRootDirectory = fixtureRoot.resolve("client"),
            serverRuntimeDirectory = fixtureRoot.resolve("server-runtime"),
            codecClassesDirectory = fixtureRoot.resolve("codec-classes"),
            hostWorkRoot = fixtureRoot.resolve("work").resolve("hosts").resolve("test"),
        ),
    )
}

internal fun processFixtureSource(name: String): Path = Path.of(
    checkNotNull(
        Thread.currentThread().contextClassLoader.getResource(name),
    ) { "Process fixture resource is missing: $name" }.toURI(),
).also { source ->
    check(source.isRegularFile()) {
        "Process fixture source is missing: $source"
    }
}
