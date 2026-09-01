package com.hiczp.minecraft.demo.webmap

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual fun platformEnvironmentVariable(name: String): String? = getenv(name)?.toKString()
