package com.hiczp.minecraft.protocol.auth

import com.hiczp.minecraft.protocol.model.type.GameProfile
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.Uuid

/** Caller-driven access to Mojang's unauthenticated Java profile lookup endpoints. */
class MinecraftProfileLookupApi(
    private val httpClient: HttpClient,
) {
    suspend fun findProfileByName(name: String): MinecraftProfileLookupResponse? {
        val endpoint = URLBuilder(MINECRAFT_PROFILE_LOOKUP_BY_NAME_ENDPOINT).apply {
            appendPathSegments(name.lowercase())
        }.build()
        val response = httpClient.get(endpoint) {
            expectSuccess = false
            accept(ContentType.Application.Json)
        }
        return response.decodeOptionalServiceResponse<
                MinecraftProfileLookupResponse,
                MinecraftProfileLookupErrorResponse
                >(::MinecraftProfileLookupResponseException)
    }

    /** Sends one bulk request. Official authlib batches two names; the caller owns batching, retry, and timing. */
    suspend fun findProfilesByNames(
        request: MinecraftProfileLookupRequest,
    ): List<MinecraftProfileLookupResponse> {
        val response = httpClient.post(MINECRAFT_PROFILE_LOOKUP_BY_NAME_BULK_ENDPOINT) {
            expectSuccess = false
            accept(ContentType.Application.Json)
            setBody(minecraftServiceJsonContent(request))
        }
        return response.decodeOptionalServiceResponse<
                List<MinecraftProfileLookupResponse>,
                MinecraftProfileLookupErrorResponse
                >(::MinecraftProfileLookupResponseException).orEmpty()
    }
}

/** The bulk endpoint represents this request as a top-level JSON array. */
@Serializable(with = MinecraftProfileLookupRequestSerializer::class)
data class MinecraftProfileLookupRequest(
    val names: List<String>,
)

object MinecraftProfileLookupRequestSerializer : KSerializer<MinecraftProfileLookupRequest> {
    private val delegate = ListSerializer(String.serializer())

    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun serialize(encoder: Encoder, value: MinecraftProfileLookupRequest) {
        encoder.encodeSerializableValue(delegate, value.names)
    }

    override fun deserialize(decoder: Decoder): MinecraftProfileLookupRequest = MinecraftProfileLookupRequest(
        names = decoder.decodeSerializableValue(delegate),
    )
}

@Serializable
data class MinecraftProfileLookupResponse(
    val id: String,
    val name: String,
)

@Serializable
data class MinecraftProfileLookupErrorResponse(
    val path: String? = null,
    val error: String? = null,
    val errorMessage: String? = null,
    val details: JsonObject? = null,
)

open class MinecraftProfileLookupResponseException(
    response: HttpResponse,
    val responseBody: String,
    val parsedErrorBody: MinecraftProfileLookupErrorResponse,
) : ResponseException(response, responseBody)

fun MinecraftProfileLookupResponse.toGameProfile(): GameProfile = GameProfile(
    id = Uuid.parse(id),
    name = name,
    properties = emptyList(),
)

private val MINECRAFT_PROFILE_LOOKUP_BY_NAME_ENDPOINT = Url("https://api.mojang.com/minecraft/profile/lookup/name/")
private val MINECRAFT_PROFILE_LOOKUP_BY_NAME_BULK_ENDPOINT =
    Url("https://api.mojang.com/minecraft/profile/lookup/bulk/byname")
