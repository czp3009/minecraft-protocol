package com.hiczp.minecraft.demo.webmap

internal actual fun platformEnvironmentVariable(name: String): String? = System.getenv(name)
