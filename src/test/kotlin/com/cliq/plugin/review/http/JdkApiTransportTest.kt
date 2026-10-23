package com.cliq.plugin.review.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JdkApiTransportTest {

    private val token = "glpat-transport-test-token"
    private val receivedTokens = CopyOnWriteArrayList<String?>()
    private val receivedVersions = CopyOnWriteArrayList<String?>()
    private val flakyCalls = AtomicInteger()
    private val foreignHits = AtomicInteger()
    private lateinit var server: HttpServer
    private lateinit var foreign: HttpServer

    @Before
    fun startServers() {
        foreign = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                foreignHits.incrementAndGet()
                respond(exchange, 200, "stolen")
            }
            start()
        }
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v4/user") { exchange ->
                receivedTokens += exchange.requestHeaders.getFirst("PRIVATE-TOKEN")
                receivedVersions += exchange.requestHeaders.getFirst("X-Api-Version")
                respond(exchange, 200, """{"username":"alice"}""")
            }
            createContext("/api/v4/redirect") { exchange ->
                exchange.responseHeaders.add("Location", "http://127.0.0.1:${foreign.address.port}/steal")
                respond(exchange, 302, "")
            }
            createContext("/api/v4/flaky") { exchange ->
                if (flakyCalls.incrementAndGet() == 1) {
                    exchange.responseHeaders.add("Retry-After", "0")
                    respond(exchange, 503, "busy")
                } else {
                    respond(exchange, 200, "[]")
                }
            }
            createContext("/api/v4/limited") { exchange ->
                if (flakyCalls.incrementAndGet() == 1) {
                    exchange.responseHeaders.add("Retry-After", "0")
                    respond(exchange, 403, """{"message":"secondary rate limit"}""")
                } else {
                    respond(exchange, 200, "[]")
                }
            }
            createContext("/api/v4/big") { exchange -> respond(exchange, 200, (0 until 1_000).joinToString("") { (it % 10).toString() }) }
            start()
        }
    }

    @After
    fun stopServers() {
        server.stop(0)
        foreign.stop(0)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private fun transport() = JdkApiTransport(
        apiRoot = "http://127.0.0.1:${server.address.port}/api/v4/",
        providerName = "Test",
        credentialHeaders = mapOf("PRIVATE-TOKEN" to token),
        staticHeaders = mapOf("X-Api-Version" to "7"),
        defaultAccept = "application/json",
        secret = token,
        options = ConnectionOptions(timeout = Duration.ofSeconds(5), ignoreProxy = true),
        maxConcurrentRequests = 4,
    )

    @Test
    fun sendsCredentialAndStaticHeaders() = runBlocking {
        transport().use { transport ->
            assertEquals(200, transport.get("user", maxBytes = 1_000).status)
        }
        assertEquals(listOf<String?>(token), receivedTokens.toList())
        assertEquals(listOf<String?>("7"), receivedVersions.toList())
    }

    @Test
    fun neverFollowsRedirects() = runBlocking {
        transport().use { transport ->
            val response = transport.get("redirect", maxBytes = 1_000)
            assertTrue(response.isRedirect)
        }
        assertEquals(0, foreignHits.get())
    }

    @Test
    fun retriesTransientFailures() = runBlocking {
        transport().use { transport ->
            assertEquals(200, transport.get("flaky", maxBytes = 1_000).status)
        }
        assertEquals(2, flakyCalls.get())
    }

    @Test
    fun retriesSecondaryRateLimitsThatAskForIt() = runBlocking {
        transport().use { transport ->
            assertEquals(200, transport.get("limited", maxBytes = 1_000).status)
        }
        assertEquals(2, flakyCalls.get())
    }

    @Test
    fun capsResponseBodies() = runBlocking {
        transport().use { transport ->
            val head = transport.get("big", maxBytes = 100)
            assertTrue(head.truncated)
            assertEquals(100, head.body.size)

            val tail = transport.get("big", maxBytes = 10, window = BodyWindow.TAIL)
            assertTrue(tail.truncated)
            assertContentEquals("0123456789".toByteArray(), tail.body)

            val whole = transport.get("big", maxBytes = 5_000)
            assertFalse(whole.truncated)
            assertEquals(1_000, whole.body.size)
        }
    }

    @Test
    fun refusesExternalLinksThatAreNotHttps() = runBlocking {
        transport().use { transport ->
            assertFailsWith<ReviewApiException> {
                transport.getExternal("http://127.0.0.1:${foreign.address.port}/logs", 1_000)
            }
            assertFailsWith<ReviewApiException> { transport.getExternal("https://user:pw@example.com/logs", 1_000) }
            assertFailsWith<ReviewApiException> { transport.getExternal("not a url", 1_000) }
        }
        assertEquals(0, foreignHits.get())
    }

    @Test
    fun redactsTokenFromText() {
        transport().use { transport ->
            assertEquals("x [REDACTED] y", transport.redact("x $token y"))
        }
    }
}
