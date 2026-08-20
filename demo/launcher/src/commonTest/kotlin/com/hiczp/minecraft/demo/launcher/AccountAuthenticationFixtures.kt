package com.hiczp.minecraft.demo.launcher

import com.hiczp.minecraft.account.auth.*

internal fun authenticationResponses(): List<String> {
    val xboxClaims = XboxTokenResponse.DisplayClaims(
        listOf(XboxTokenResponse.DisplayClaims.UserClaim("user-hash")),
    )
    return listOf(
        launcherJson.encodeToString(
            MicrosoftTokenResponse(
                tokenType = "Bearer",
                expiresIn = 3_600,
                accessToken = "microsoft-access",
                refreshToken = "refresh-token",
            ),
        ),
        launcherJson.encodeToString(
            XboxTokenResponse("now", "later", "user-token", xboxClaims),
        ),
        launcherJson.encodeToString(
            XboxTokenResponse("now", "later", "xsts-token", xboxClaims),
        ),
        launcherJson.encodeToString(
            MinecraftLoginResponse("user", emptyList(), "minecraft-access", "Bearer", 3_600),
        ),
        launcherJson.encodeToString(
            MinecraftEntitlementsResponse(listOf(MinecraftEntitlementsResponse.Item("game_minecraft"))),
        ),
        launcherJson.encodeToString(
            MinecraftProfileResponse(TEST_PROFILE_ID, "OnlinePlayer", emptyList(), emptyList()),
        ),
    )
}

internal const val TEST_PROFILE_ID = "0123456789abcdef0123456789abcdef"
