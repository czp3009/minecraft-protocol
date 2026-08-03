package com.hiczp.minecraft.test

import kotlinx.io.files.Path

internal fun processFixtureSource(name: String): Path = Path(
    MinecraftTestSupport.layout.repositoryRoot,
    "minecraft-test-support",
    "src",
    "hostProcessTest",
    "resources",
    name,
).also { source ->
    check(source.isRegularFile()) {
        "Process fixture source is missing: $source"
    }
}
