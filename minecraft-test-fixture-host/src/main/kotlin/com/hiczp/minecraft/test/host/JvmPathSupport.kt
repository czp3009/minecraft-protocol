package com.hiczp.minecraft.test.host

import kotlinx.io.files.Path
import java.nio.file.Path as NioPath

internal fun Path.toNioPath(): NioPath =
    NioPath.of(toString()).toAbsolutePath().normalize()
