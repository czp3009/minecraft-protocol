package com.hiczp.minecraft.buildlogic

/**
 * The single source of truth for build-time platform versions.
 *
 * [JAVA_VERSION] follows the Java major version required by the Minecraft release selected by
 * [MinecraftTarget.MINECRAFT_VERSION]; the Android versions are independent of that release.
 */
object BuildVersions {
    /** The Java major version required by [MinecraftTarget.MINECRAFT_VERSION]; update both constants together. */
    const val JAVA_VERSION: Int = 25
    const val ANDROID_MIN_SDK: Int = 34
    const val ANDROID_COMPILE_SDK: Int = 36
}
