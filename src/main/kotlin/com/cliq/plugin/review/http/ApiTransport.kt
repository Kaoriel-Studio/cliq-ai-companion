package com.cliq.plugin.review.http

import java.time.Duration

enum class BodyWindow { HEAD, TAIL }

data class ConnectionOptions(
    val timeout: Duration,
    val ignoreProxy: Boolean,
)

class ApiResponse(
    val status: Int,
    val body: ByteArray,
    val truncated: Boolean,
    private val headers: Map<String, List<String>>,
) {
    val isSuccess: Boolean get() = status in 200..299

    val isRedirect: Boolean get() = status in 300..399

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

sealed interface RawFetch {
    class Found(val bytes: ByteArray) : RawFetch
    data object NotFound : RawFetch
    data object TooLarge : RawFetch
}

interface ApiTransport : AutoCloseable {

    val apiRoot: String

    suspend fun get(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        maxBytes: Int,
        window: BodyWindow = BodyWindow.HEAD,
        accept: String? = null,
    ): ApiResponse

    suspend fun getExternal(url: String, maxBytes: Int, window: BodyWindow = BodyWindow.HEAD): ApiResponse

    fun redact(text: String): String
}

open class ReviewApiException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ReviewHttpException(
    val provider: String,
    val status: Int,
    val endpoint: String,
    detail: String?,
) : ReviewApiException(describe(provider, status, endpoint, detail)) {

    private companion object {
        fun describe(provider: String, status: Int, endpoint: String, detail: String?): String {
            val reason = when (status) {
                401 -> "the token is invalid or expired"
                403 -> "access denied or rate limited; check the token scopes"
                404 -> "not found or not visible with this token"
                in 300..399 -> "the server redirected the request; check the host URL"
                429 -> "rate limited"
                else -> null
            }
            return buildString {
                append(provider).append(" request to ").append(endpoint).append(" failed with HTTP ").append(status)
                reason?.let { append(": ").append(it) }
                detail?.takeIf { it.isNotBlank() }?.let { append(" (").append(it).append(')') }
            }
        }
    }
}

class ReviewNetworkException(provider: String, endpoint: String, cause: Throwable) :
    ReviewApiException("Could not reach $provider for $endpoint: ${cause.javaClass.simpleName}", cause)
