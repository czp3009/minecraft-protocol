package com.hiczp.minecraft.protocol.auth

internal actual val forgeOptions: ForgeOptions
    get() = nodeForgeDefault.options

internal actual val forgePki: ForgePki
    get() = nodeForgeDefault.pki

internal actual val forgeAsn1: ForgeAsn1
    get() = nodeForgeDefault.asn1

internal actual val forgeUtil: ForgeUtil
    get() = nodeForgeDefault.util

internal actual val forgeMd: ForgeMessageDigests
    get() = nodeForgeDefault.md
