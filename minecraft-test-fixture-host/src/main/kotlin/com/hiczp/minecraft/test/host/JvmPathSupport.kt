package com.hiczp.minecraft.test.host

import kotlinx.io.files.Path
import java.nio.file.Path as NioPath

internal fun fixtureJavaCommand(vararg arguments: String): List<String> =
    buildList {
        add("java")
        add(ENABLE_NATIVE_ACCESS_ALL_UNNAMED)
        addAll(arguments)
    }

internal fun Path.toNioPath(): NioPath =
    NioPath.of(toString()).toAbsolutePath().normalize()

internal const val ENABLE_NATIVE_ACCESS_ALL_UNNAMED: String = "--enable-native-access=ALL-UNNAMED"
