@file:OptIn(ExperimentalSerializationApi::class)

package com.hiczp.minecraft.account.auth

import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.properties.Properties
import kotlinx.serialization.properties.encodeToStringMap

internal val AccountAuthJson = Json {
    ignoreUnknownKeys = true
}

internal inline fun <reified T> formDataContent(request: T): FormDataContent =
    FormDataContent(
        Parameters.build {
            // kotlinx.serialization owns object-to-property encoding; Ktor owns form URL encoding.
            appendAll(Properties.encodeToStringMap(request))
        },
    )

internal suspend inline fun <reified SuccessBody, reified ErrorBody> HttpResponse.decodeAccountAuthResponse(
    createResponseException: (
        httpResponse: HttpResponse,
        responseBody: String,
        parsedErrorBody: ErrorBody,
    ) -> AccountAuthResponseException,
): SuccessBody {
    val responseBody = bodyAsText()
    if (status.isSuccess()) {
        return AccountAuthJson.decodeFromString<SuccessBody>(responseBody)
    }
    val parsedErrorBody = AccountAuthJson.decodeFromString<ErrorBody>(responseBody)
    throw createResponseException(this, responseBody, parsedErrorBody)
}
