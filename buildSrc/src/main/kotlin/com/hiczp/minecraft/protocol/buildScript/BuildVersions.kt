package com.hiczp.minecraft.protocol.buildScript

/**
 * The single source of truth for build-time platform versions.
 *
 * These versions are independent of the selected Minecraft release.
 */
object BuildVersions {
    const val JAVA_VERSION: Int = 25
    const val ANDROID_MIN_SDK: Int = 34
    const val ANDROID_COMPILE_SDK: Int = 36
}
