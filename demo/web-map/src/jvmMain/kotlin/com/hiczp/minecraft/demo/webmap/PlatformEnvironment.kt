package com.hiczp.minecraft.demo.webmap

actual fun platformEnvironmentVariable(name: String): String? = System.getenv(name)
