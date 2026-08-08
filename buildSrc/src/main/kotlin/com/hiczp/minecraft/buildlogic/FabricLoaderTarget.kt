package com.hiczp.minecraft.buildlogic

/** The independently selected stable Fabric Loader release. */
object FabricLoaderTarget {
    const val FABRIC_LOADER_VERSION = "0.19.3"

    fun profileId(minecraftVersion: String): String =
        "fabric-loader-$FABRIC_LOADER_VERSION-$minecraftVersion"

    fun profileUrl(minecraftVersion: String): String =
        "https://meta.fabricmc.net/v2/versions/loader/$minecraftVersion/$FABRIC_LOADER_VERSION/profile/json"
}
