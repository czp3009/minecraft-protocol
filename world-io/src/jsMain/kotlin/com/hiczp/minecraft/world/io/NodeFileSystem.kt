@file:JsModule("fs")
@file:JsNonModule

package com.hiczp.minecraft.world.io

internal external val constants: NodeFileSystemConstants

@Suppress("PropertyName")
internal external interface NodeFileSystemConstants {
    val O_CREAT: Number
    val O_WRONLY: Number
}

internal external fun openSync(path: String, flags: Number): Double

internal external fun fstatSync(
    fd: Number,
    options: NodeFileStatisticsOptions,
): NodeFileStatistics

internal external interface NodeFileStatisticsOptions {
    var bigint: Boolean
}

internal external interface NodeFileStatistics {
    val dev: Any
    val ino: Any
}

internal external fun writeSync(
    fd: Number,
    buffer: ByteArray,
    offset: Double,
    length: Double,
    position: Double,
): Double

internal external fun fsyncSync(fd: Number)

internal external fun closeSync(fd: Number)
