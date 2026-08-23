@file:JsModule("node-forge")
@file:JsNonModule

package com.hiczp.minecraft.protocol.auth

@JsName("options")
internal actual external val forgeOptions: ForgeOptions

@JsName("pki")
internal actual external val forgePki: ForgePki

@JsName("asn1")
internal actual external val forgeAsn1: ForgeAsn1

@JsName("util")
internal actual external val forgeUtil: ForgeUtil

@JsName("md")
internal actual external val forgeMd: ForgeMessageDigests
