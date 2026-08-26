package com.hiczp.minecraft.protocol.auth

import io.ktor.client.plugins.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

/** A successful conditional response with the exact headers needed for caller-owned cache and timing policy. */
data class MinecraftConditionalResponse<T>(
    val httpStatusCode: HttpStatusCode,
    val body: T?,
    val etag: String?,
    val retryAfter: String?,
) {
    val isNotModified: Boolean
        get() = httpStatusCode == HttpStatusCode.NotModified
}

internal val MinecraftServiceJson = Json {
    ignoreUnknownKeys = true
}

internal suspend inline fun <reified SuccessBody, reified ErrorBody> HttpResponse.decodeOptionalServiceResponse(
    createFailure: (
        httpResponse: HttpResponse,
        responseBody: String,
        parsedErrorBody: ErrorBody,
    ) -> ResponseException,
): SuccessBody? {
    val responseBody = bodyAsText()
    if (!status.isSuccess()) {
        throw createFailure(this, responseBody, MinecraftServiceJson.decodeFromString<ErrorBody>(responseBody))
    }
    return if (responseBody.isEmpty()) null else MinecraftServiceJson.decodeFromString<SuccessBody>(responseBody)
}

internal suspend inline fun <reified SuccessBody, reified ErrorBody> HttpResponse.decodeConditionalServiceResponse(
    requestEtag: String?,
    createFailure: (
        httpResponse: HttpResponse,
        responseBody: String,
        parsedErrorBody: ErrorBody,
    ) -> ResponseException,
): MinecraftConditionalResponse<SuccessBody> {
    val responseBody = bodyAsText()
    if (status != HttpStatusCode.NotModified && !status.isSuccess()) {
        throw createFailure(this, responseBody, MinecraftServiceJson.decodeFromString<ErrorBody>(responseBody))
    }
    val successBody: SuccessBody? = if (status == HttpStatusCode.NotModified || responseBody.isEmpty()) {
        null
    } else {
        MinecraftServiceJson.decodeFromString<SuccessBody>(responseBody)
    }
    return MinecraftConditionalResponse(
        httpStatusCode = status,
        body = successBody,
        etag = headers[HttpHeaders.ETag] ?: requestEtag.takeIf { status == HttpStatusCode.NotModified },
        retryAfter = headers[HttpHeaders.RetryAfter],
    )
}
