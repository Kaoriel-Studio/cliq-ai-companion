package com.cliq.plugin.review.http

import java.util.concurrent.CopyOnWriteArrayList

class FakeApiTransport(
    override val apiRoot: String = "https://git.example.com/api/",
    private val token: String = "glpat-fake-token-0123456789",
) : ApiTransport {

    class Reply(val status: Int, val body: ByteArray, val headers: Map<String, String> = emptyMap())

    class Request(val path: String, val query: Map<String, String>, val accept: String?)

    private val handlers = CopyOnWriteArrayList<(Request) -> Reply?>()
    private val externalHandlers = CopyOnWriteArrayList<(String) -> Reply?>()
    val requests = CopyOnWriteArrayList<Request>()
    val externalRequests = CopyOnWriteArrayList<String>()
    var closed = false
        private set

    fun on(handler: (Request) -> Reply?) {
        handlers += handler
    }

    fun onExternal(handler: (String) -> Reply?) {
        externalHandlers += handler
    }

    fun json(path: String, body: String, headers: Map<String, String> = emptyMap()) =
        on { request -> if (request.path == path) Reply(200, body.toByteArray(), headers) else null }

    fun pagedJson(path: String, pages: List<String>, nextHeader: (Int, Int) -> Pair<String, String>?) = on { request ->
        if (request.path != path) return@on null
        val page = request.query["page"]?.toIntOrNull() ?: 1
        val header = nextHeader(page, pages.size)
        Reply(200, pages[page - 1].toByteArray(), listOfNotNull(header).toMap())
    }

    fun status(path: String, status: Int, body: String, headers: Map<String, String> = emptyMap()) =
        on { request -> if (request.path == path) Reply(status, body.toByteArray(), headers) else null }

    override suspend fun get(
        path: String,
        query: List<Pair<String, String>>,
        maxBytes: Int,
        window: BodyWindow,
        accept: String?,
    ): ApiResponse {
        val request = Request(path, query.toMap(), accept)
        requests += request
        val reply = handlers.firstNotNullOfOrNull { it(request) }
            ?: Reply(404, """{"message":"404 Not Found"}""".toByteArray())
        return respond(reply, maxBytes, window)
    }

    override suspend fun getExternal(url: String, maxBytes: Int, window: BodyWindow): ApiResponse {
        externalRequests += url
        val reply = externalHandlers.firstNotNullOfOrNull { it(url) } ?: Reply(404, ByteArray(0))
        return respond(reply, maxBytes, window)
    }

    override fun redact(text: String): String = text.replace(token, "[REDACTED]")

    override fun close() {
        closed = true
    }

    private fun respond(reply: Reply, maxBytes: Int, window: BodyWindow): ApiResponse {
        val truncated = reply.body.size > maxBytes
        val body = when {
            !truncated -> reply.body
            window == BodyWindow.TAIL -> reply.body.copyOfRange(reply.body.size - maxBytes, reply.body.size)
            else -> reply.body.copyOf(maxBytes)
        }
        return ApiResponse(reply.status, body, truncated, reply.headers.mapValues { listOf(it.value) })
    }

    companion object {
        fun gitLabPaging(page: Int, total: Int): Pair<String, String> =
            "X-Next-Page" to if (page < total) (page + 1).toString() else ""

        fun gitHubPaging(page: Int, total: Int): Pair<String, String>? =
            if (page < total) "Link" to "<https://api.github.com/x?page=${page + 1}>; rel=\"next\"" else null
    }
}
