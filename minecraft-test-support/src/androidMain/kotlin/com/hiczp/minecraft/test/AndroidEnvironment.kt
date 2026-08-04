package com.hiczp.minecraft.test

internal actual fun platformEnvironmentVariable(name: String): String? =
    System.getenv(name)
