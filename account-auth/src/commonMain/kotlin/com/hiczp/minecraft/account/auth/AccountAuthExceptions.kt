package com.hiczp.minecraft.account.auth

import io.ktor.client.plugins.*
import io.ktor.client.statement.*

open class AccountAuthResponseException(
    response: HttpResponse,
    val responseBody: String,
) : ResponseException(response, responseBody)

class MicrosoftOAuthResponseException(
    response: HttpResponse,
    responseBody: String,
    val parsedErrorBody: MicrosoftOAuthErrorResponse,
) : AccountAuthResponseException(response, responseBody)

class XboxAuthenticationResponseException(
    response: HttpResponse,
    responseBody: String,
    val parsedErrorBody: XboxAuthenticationErrorResponse,
) : AccountAuthResponseException(response, responseBody)

class MinecraftServicesResponseException(
    response: HttpResponse,
    responseBody: String,
    val parsedErrorBody: MinecraftServicesErrorResponse,
) : AccountAuthResponseException(response, responseBody)
