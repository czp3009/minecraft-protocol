package com.hiczp.minecraft.account.auth

import io.ktor.client.plugins.*
import io.ktor.client.statement.*

open class AccountAuthResponseException(
    httpResponse: HttpResponse,
    val responseBody: String,
) : ResponseException(httpResponse, responseBody)

class MicrosoftOAuthResponseException(
    httpResponse: HttpResponse,
    responseBody: String,
    val parsedErrorBody: MicrosoftOAuthErrorResponse,
) : AccountAuthResponseException(httpResponse, responseBody)

class XboxAuthenticationResponseException(
    httpResponse: HttpResponse,
    responseBody: String,
    val parsedErrorBody: XboxAuthenticationErrorResponse,
) : AccountAuthResponseException(httpResponse, responseBody)

class MinecraftServicesResponseException(
    httpResponse: HttpResponse,
    responseBody: String,
    val parsedErrorBody: MinecraftServicesErrorResponse,
) : AccountAuthResponseException(httpResponse, responseBody)
