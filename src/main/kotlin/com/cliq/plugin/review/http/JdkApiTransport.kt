package com.cliq.plugin.review.http

import com.intellij.util.net.JdkProxyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets

class JdkApiTransport(
    override val apiRoot: String,
    private val providerName: String,
    private val credentialHeaders: Map<String, String>,
    private val staticHeaders: Map<String, String>,
    private val defaultAccept: String,
    private val secret: String?,
    private val options: ConnectionOptions,
    maxConcurrentRequests: Int,
) : ApiTransport {

    private companion object {
        const val MAX_RETRIES = 2
        const val MAX_RETRY_DELAY_MS = 10_000L
        const val BASE_RETRY_DELAY_MS = 750L
        const val READ_BUFFER_SIZE = 16 * 1024
        const val USER_AGENT = "Cliq-AI-Companion"
        const val REDACTED = "[REDACTED]"
        val RETRYABLE_STATUSES = setOf(429, 502, 503, 504)
    }

    private val root = apiRoot.trimEnd('/') + "/"
    private val permits = Semaphore(maxConcurrentRequests)
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(options.timeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .applyProxyPolicy(options.ignoreProxy)
        .build()

    override suspend fun get(
        path: String,
        query: List<Pair<String, String>>,
        maxBytes: Int,
        window: BodyWindow,
        accept: String?,
    ): ApiResponse {
        val uri = URI.create(root + path + encodeQuery(query))
        val headers = credentialHeaders + staticHeaders + ("Accept" to (accept ?: defaultAccept))
        var attempt = 0
        while (true) {
            val response = try {
                permits.withPermit { execute(uri, headers, maxBytes, window) }
            } catch (e: IOException) {
                if (attempt >= MAX_RETRIES) throw ReviewNetworkException(providerName, path, e)
                null
            }
            if (response != null && (!isRetryable(response) || attempt >= MAX_RETRIES)) return response
            attempt++
            delay(retryDelayMillis(response, attempt))
        }
    }

    override suspend fun getExternal(url: String, maxBytes: Int, window: BodyWindow): ApiResponse {
        val uri = try {
            URI(url)
        } catch (e: URISyntaxException) {
            throw ReviewApiException("$providerName returned a malformed download link")
        }
        if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) {
            throw ReviewApiException("$providerName returned a download link that is not plain HTTPS")
        }
        return try {
            permits.withPermit { execute(uri, mapOf("Accept" to "*/*"), maxBytes, window) }
        } catch (e: IOException) {
            throw ReviewNetworkException(providerName, uri.host, e)
        }
    }

    override fun redact(text: String): String =
        if (secret.isNullOrEmpty()) text else text.replace(secret, REDACTED)

    override fun close() {
        client.shutdownNow()
    }

    private fun isRetryable(response: ApiResponse): Boolean =
        response.status in RETRYABLE_STATUSES || (response.status == 403 && response.header("Retry-After") != null)

    private suspend fun execute(uri: URI, headers: Map<String, String>, maxBytes: Int, window: BodyWindow): ApiResponse {
        val request = HttpRequest.newBuilder(uri)
            .timeout(options.timeout)
            .header("User-Agent", USER_AGENT)
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .GET()
            .build()

        val response = client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).await()
        val (body, truncated) = response.body().use { stream ->
            withTimeoutOrNull(options.timeout.toMillis()) {
                runInterruptible(Dispatchers.IO) {
                    when (window) {
                        BodyWindow.HEAD -> readHead(stream, maxBytes)
                        BodyWindow.TAIL -> readTail(stream, maxBytes)
                    }
                }
            } ?: throw HttpTimeoutException("Timed out reading the response body")
        }
        return ApiResponse(response.statusCode(), body, truncated, response.headers().map())
    }

    private fun readHead(stream: InputStream, maxBytes: Int): Pair<ByteArray, Boolean> {
        val out = ByteArrayOutputStream(minOf(maxBytes, READ_BUFFER_SIZE))
        val buffer = ByteArray(READ_BUFFER_SIZE)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) return out.toByteArray() to false
            val room = maxBytes - out.size()
            if (read > room) {
                out.write(buffer, 0, room.coerceAtLeast(0))
                return out.toByteArray() to true
            }
            out.write(buffer, 0, read)
        }
    }

    private fun readTail(stream: InputStream, maxBytes: Int): Pair<ByteArray, Boolean> {
        val ring = ByteArray(maxBytes)
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var position = 0
        var total = 0L
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            var offset = 0
            while (offset < read) {
                val count = minOf(read - offset, maxBytes - position)
                System.arraycopy(buffer, offset, ring, position, count)
                position = (position + count) % maxBytes
                offset += count
            }
            total += read
        }
        if (total <= maxBytes) return ring.copyOf(total.toInt()) to false
        return (ring.copyOfRange(position, maxBytes) + ring.copyOfRange(0, position)) to true
    }

    private fun retryDelayMillis(response: ApiResponse?, attempt: Int): Long {
        val retryAfterSeconds = response?.header("Retry-After")?.trim()?.toLongOrNull()
        val requested = retryAfterSeconds?.times(1000) ?: (BASE_RETRY_DELAY_MS shl (attempt - 1))
        return requested.coerceIn(0, MAX_RETRY_DELAY_MS)
    }

    private fun encodeQuery(query: List<Pair<String, String>>): String =
        if (query.isEmpty()) "" else query.joinToString("&", prefix = "?") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun HttpClient.Builder.applyProxyPolicy(ignoreProxy: Boolean): HttpClient.Builder {
        if (ignoreProxy) return proxy(HttpClient.Builder.NO_PROXY)
        val provider = JdkProxyProvider.getInstance()
        return proxy(provider.proxySelector).authenticator(provider.authenticator)
    }
}
