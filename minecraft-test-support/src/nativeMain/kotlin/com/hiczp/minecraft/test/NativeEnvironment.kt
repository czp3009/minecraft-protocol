@file:OptIn(ExperimentalForeignApi::class)

package com.hiczp.minecraft.test

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

internal actual fun platformEnvironmentVariable(name: String): String? =
    getenv(name)?.toKString()
