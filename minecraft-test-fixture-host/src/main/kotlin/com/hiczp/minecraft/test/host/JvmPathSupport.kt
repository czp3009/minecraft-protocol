package com.hiczp.minecraft.test.host

internal fun fixtureJavaCommand(vararg arguments: String): List<String> =
    buildList {
        add("java")
        add(ENABLE_NATIVE_ACCESS_ALL_UNNAMED)
        addAll(arguments)
    }

internal const val ENABLE_NATIVE_ACCESS_ALL_UNNAMED: String = "--enable-native-access=ALL-UNNAMED"
