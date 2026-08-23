package com.hiczp.minecraft.protocol.auth

import io.ktor.client.plugins.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import kotlinx.serialization.json.Json

/** A successful conditional response with the exact headers needed for caller-owned cache and timing policy. */
data class MinecraftConditionalResponse<T>(
    val status: HttpStatusCode,
    val body: T?,
    val etag: String?,
    val retryAfter: String?,
) {
    val isNotModified: Boolean
        get() = status == HttpStatusCode.NotModified
}

internal val MinecraftServiceJson = Json {
    ignoreUnknownKeys = true
}

internal inline fun <reified T> minecraftServiceJsonContent(value: T): TextContent = TextContent(
    text = MinecraftServiceJson.encodeToString(value),
    contentType = ContentType.Application.Json,
)

internal suspend inline fun <reified SuccessBody, reified ErrorBody> HttpResponse.decodeOptionalServiceResponse(
    createFailure: (
        response: HttpResponse,
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
        response: HttpResponse,
        responseBody: String,
        parsedErrorBody: ErrorBody,
    ) -> ResponseException,
): MinecraftConditionalResponse<SuccessBody> {
    val responseBody = bodyAsText()
    if (status != HttpStatusCode.NotModified && !status.isSuccess()) {
        throw createFailure(this, responseBody, MinecraftServiceJson.decodeFromString<ErrorBody>(responseBody))
    }
    val body: SuccessBody? = if (status == HttpStatusCode.NotModified || responseBody.isEmpty()) {
        null
    } else {
        MinecraftServiceJson.decodeFromString<SuccessBody>(responseBody)
    }
    return MinecraftConditionalResponse(
        status = status,
        body = body,
        etag = headers[HttpHeaders.ETag] ?: requestEtag.takeIf { status == HttpStatusCode.NotModified },
        retryAfter = headers[HttpHeaders.RetryAfter],
    )
}
