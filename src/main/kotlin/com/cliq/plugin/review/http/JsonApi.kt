package com.cliq.plugin.review.http

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class JsonApi(
    val transport: ApiTransport,
    private val providerName: String,
    private val nextPage: (response: ApiResponse, currentPage: Int) -> Int?,
) {

    companion object {
        const val PAGE_SIZE = 100
        private const val MAX_JSON_BYTES = 32 * 1024 * 1024
        private const val MAX_ERROR_DETAIL_LENGTH = 200

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun segment(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

        fun pathSegments(path: String): String = path.split('/').joinToString("/", transform = ::segment)
    }

    suspend fun <T> get(
        path: String,
        deserializer: DeserializationStrategy<T>,
        query: List<Pair<String, String>> = emptyList(),
    ): T = decode(path, deserializer, fetch(path, query))

    suspend fun <T> getPages(
        path: String,
        elementSerializer: KSerializer<T>,
        maxPages: Int,
        query: List<Pair<String, String>> = emptyList(),
    ): List<T> {
        val listDeserializer = ListSerializer(elementSerializer)
        val items = mutableListOf<T>()
        var page = 1
        repeat(maxPages) {
            val response = fetch(path, query + listOf("per_page" to PAGE_SIZE.toString(), "page" to page.toString()))
            items += decode(path, listDeserializer, response)
            page = nextPage(response, page) ?: return items
        }
        return items
    }

    suspend fun fetch(path: String, query: List<Pair<String, String>>, accept: String? = null): ApiResponse {
        val response = transport.get(path, query, MAX_JSON_BYTES, accept = accept)
        if (!response.isSuccess) throw failure(path, response)
        if (response.truncated) {
            throw ReviewApiException("$providerName response for $path exceeds ${MAX_JSON_BYTES / (1024 * 1024)} MB")
        }
        return response
    }

    suspend fun raw(path: String, query: List<Pair<String, String>>, maxBytes: Int, accept: String? = null): RawFetch {
        val response = transport.get(path, query, maxBytes, accept = accept)
        return when {
            response.status == 404 -> RawFetch.NotFound
            !response.isSuccess -> throw failure(path, response)
            response.truncated -> RawFetch.TooLarge
            else -> RawFetch.Found(response.body)
        }
    }

    fun <T> decode(path: String, deserializer: DeserializationStrategy<T>, response: ApiResponse): T =
        try {
            json.decodeFromString(deserializer, response.body.toString(Charsets.UTF_8))
        } catch (e: SerializationException) {
            throw ReviewApiException("$providerName returned an unexpected payload for $path", e)
        } catch (e: IllegalArgumentException) {
            throw ReviewApiException("$providerName returned an unexpected payload for $path", e)
        }

    fun failure(path: String, response: ApiResponse): ReviewHttpException =
        ReviewHttpException(providerName, response.status, path, errorDetail(response))

    private fun errorDetail(response: ApiResponse): String? {
        val raw = response.body.toString(Charsets.UTF_8)
        val message = runCatching {
            val element = json.parseToJsonElement(raw).jsonObject
            listOf("message", "error", "error_description")
                .firstNotNullOfOrNull { key -> element.textOf(key) }
        }.getOrNull() ?: return null
        val singleLine = message.filterNot(Char::isISOControl).trim()
        return transport.redact(singleLine).take(MAX_ERROR_DETAIL_LENGTH).ifBlank { null }
    }

    private fun JsonObject.textOf(key: String): String? {
        val value = this[key] ?: return null
        return (value as? JsonPrimitive)?.content ?: value.toString()
    }
}
