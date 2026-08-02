package com.hiczp.minecraft.test

import kotlinx.io.files.Path

internal fun Path.toNioPath(): java.nio.file.Path =
    java.nio.file.Path.of(toString()).toAbsolutePath().normalize()
