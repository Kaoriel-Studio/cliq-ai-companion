package com.cliq.plugin.mcp

import com.cliq.plugin.context.OpenFilesTracker
import com.cliq.plugin.context.WorkspaceContext
import com.cliq.plugin.diff.CliqDiffManager
import com.cliq.plugin.diff.CliqDiffOutcome
import com.cliq.plugin.mcp.transport.HttpExchangeAdapter
import com.cliq.plugin.mcp.transport.StreamableHttpServerTransport
import com.cliq.plugin.mcp.transport.reject
import com.cliq.plugin.settings.CliqSettings
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ProjectRootManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.McpJson as SdkMcpJson
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.Executors

@Service(Service.Level.PROJECT)
class CliqIdeServer(private val project: Project) : Disposable {

    private companion object {
        const val EDT_BRIDGE_TIMEOUT_MS = 10_000L
        const val OWNER_ONLY_DIRECTORY = "rwx------"
        const val OWNER_ONLY_FILE = "rw-------"
    }

    private val log = logger<CliqIdeServer>()
    private val authToken: String = generateAuthToken()
    private val transports = java.util.concurrent.ConcurrentHashMap<String, StreamableHttpServerTransport>()
    private val sessionsWithInitialNotification = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val mcpServer = createMcpServer()
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var server: HttpServer? = null
    @Volatile private var boundPort: Int? = null
    @Volatile private var workspacePath: String = ""
    private val discoveryFiles = java.util.concurrent.CopyOnWriteArrayList<File>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                httpServer.createContext("/mcp", McpHandler())
                httpServer.executor = Executors.newCachedThreadPool { r ->
                    Thread(r, "Cliq-IdeServer").apply { isDaemon = true }
                }
                httpServer.start()
                server = httpServer
                boundPort = httpServer.address.port
                log.info("Cliq IDE server listening on 127.0.0.1:$boundPort")

                workspacePath = resolveWorkspacePath()
                writeDiscoveryFiles()
                wireListeners()
            } catch (t: Throwable) {
                started.set(false)
                log.warn("Failed to start Cliq IDE server", t)
            }
        }
    }

    fun port(): Int? = boundPort
    fun authToken(): String = authToken
    fun workspacePath(): String = workspacePath
    fun hasActiveSessions(): Boolean = transports.isNotEmpty()

    private suspend fun <T> onEdtAsync(timeoutMillis: Long = EDT_BRIDGE_TIMEOUT_MS, block: () -> T): T? =
        withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { continuation ->
                ApplicationManager.getApplication().invokeLater(
                    { continuation.resumeWith(runCatching(block)) },
                    ModalityState.any(),
                    project.disposed,
                )
            }
        }

    private inner class McpHandler : HttpHandler {
        override fun handle(exchange: HttpExchange) {
            coroutineScope.launch {
                try {
                    if (!exchange.requestURI.path.startsWith("/mcp")) {
                        sendResponse(exchange, 404, "Not Found")
                        return@launch
                    }

                    val authHeader = exchange.requestHeaders.getFirst("Authorization")
                    if (authHeader != "Bearer $authToken") {
                        sendResponse(exchange, 401, "Unauthorized")
                        return@launch
                    }

                    val adapter = HttpExchangeAdapter(exchange)
                    when (exchange.requestMethod.uppercase()) {
                        "POST" -> handleMcpPostRequest(adapter)
                        "GET" -> handleMcpGetRequest(adapter)
                        "DELETE" -> handleMcpDeleteRequest(adapter)
                        else -> {
                            exchange.responseHeaders.add("Allow", "GET, POST, DELETE")
                            sendResponse(exchange, 405, "Method Not Allowed")
                        }
                    }
                } catch (e: Exception) {
                    log.error("Error handling MCP request", e)
                    try {
                        sendResponse(exchange, 500, "Internal Server Error")
                    } catch (ioe: IOException) {
                        log.error("Failed to send error response", ioe)
                    }
                }
            }
        }

        private fun sendResponse(exchange: HttpExchange, code: Int, body: String) {
            try {
                exchange.sendResponseHeaders(code, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            } finally {
                exchange.close()
            }
        }
    }

    private fun createMcpServer(): Server {
        val server = Server(
            Implementation(name = "cliq-ide-server", version = "0.1.0"),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )

        server.addTool(
            name = "openDiff",
            description = "(IDE Tool) Open a diff view to create or modify a file. Returns immediately. The user's accept/reject decision is sent later as a separate ide/diffAccepted or ide/diffClosed notification.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("filePath", buildJsonObject { put("type", "string") })
                    put("newContent", buildJsonObject { put("type", "string") })
                },
                required = listOf("filePath", "newContent")
            )
        ) { request ->
            val filePath = (request.arguments["filePath"] as? JsonPrimitive)?.content
                ?: throw IllegalArgumentException("filePath is required and must be a string")
            val newContent = (request.arguments["newContent"] as? JsonPrimitive)?.content
                ?: throw IllegalArgumentException("newContent is required and must be a string")

            log.info("Tool openDiff called for $filePath")

            val diffManager = project.service<CliqDiffManager>()
            val rejection = diffManager.validateTarget(filePath)
            if (rejection != null) {
                log.warn("Tool openDiff rejected for $filePath: $rejection")
                CallToolResult(content = listOf(TextContent(rejection)), isError = true)
            } else {
                if (CliqSettings.getInstance().autoApplyChanges) {
                    diffManager.applyDirectly(filePath, newContent)
                } else {
                    diffManager.showDiff(filePath, newContent, CliqDiffManager.Origin.AGENT)
                }
                CallToolResult(content = emptyList())
            }
        }

        server.addTool(
            name = "closeDiff",
            description = "(IDE Tool) Close an open diff view for a specific file.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("filePath", buildJsonObject { put("type", "string") })
                    put("suppressNotification", buildJsonObject { put("type", "boolean") })
                },
                required = listOf("filePath")
            )
        ) { request ->
            val filePath = (request.arguments["filePath"] as? JsonPrimitive)?.content
                ?: throw IllegalArgumentException("filePath is required and must be a string")
            val suppressNotification =
                (request.arguments["suppressNotification"] as? JsonPrimitive)?.booleanOrNull ?: false
            val finalContent = onEdtAsync {
                project.service<CliqDiffManager>().closeDiff(filePath, suppressNotification)
            }

            @kotlinx.serialization.Serializable
            data class CloseDiffResponse(val content: String?)
            val response = io.modelcontextprotocol.kotlin.sdk.shared.McpJson.encodeToString(
                CloseDiffResponse.serializer(),
                CloseDiffResponse(finalContent)
            )

            CallToolResult(content = listOf(TextContent(response)))
        }

        return server
    }

    private suspend fun handleMcpPostRequest(adapter: HttpExchangeAdapter) {
        val sessionId = adapter.getRequestHeader("mcp-session-id")
        val existing = sessionId?.let { transports[it] }
        val transport = existing ?: run {
            val newTransport = StreamableHttpServerTransport(
                enableJsonResponse = false,
                allowedHosts = listOf("localhost", "127.0.0.1")
            )
            newTransport.setOnSessionInitialized { newSessionId ->
                log.info("New MCP session initialized: $newSessionId")
                transports.putIfAbsent(newSessionId, newTransport)
            }
            newTransport.setOnSessionClosed { closedSessionId ->
                log.info("MCP session closed: $closedSessionId")
                cleanupSession(closedSessionId)
            }
            mcpServer.connect(newTransport)
            newTransport
        }
        transport.handlePostRequest(adapter)
    }

    private suspend fun handleMcpGetRequest(adapter: HttpExchangeAdapter) {
        val sessionId = adapter.getRequestHeader("mcp-session-id")
        val transport = if (sessionId != null) transports[sessionId] else null
        if (transport == null) {
            reject(adapter, 400, ErrorCode.Unknown(-32001), "Invalid or missing session ID")
            return
        }
        transport.handleGetRequest(adapter)

        if (!sessionsWithInitialNotification.contains(sessionId)) {
            sendInitialIdeContextToSession(sessionId!!, transport)
            sessionsWithInitialNotification.add(sessionId)
        }
    }

    private suspend fun handleMcpDeleteRequest(adapter: HttpExchangeAdapter) {
        val sessionId = adapter.getRequestHeader("mcp-session-id")
        val transport = if (sessionId != null) transports[sessionId] else null
        if (transport == null) {
            reject(adapter, 400, ErrorCode.Unknown(-32001), "Invalid or missing session ID")
            return
        }
        transport.handleDeleteRequest(adapter)
    }

    private fun sendInitialIdeContextToSession(sessionId: String, transport: StreamableHttpServerTransport) {
        if (sessionsWithInitialNotification.contains(sessionId)) return
        val notification = buildContextNotification(project.service<OpenFilesTracker>().snapshot())
        coroutineScope.launch {
            try {
                transport.send(notification)
                sessionsWithInitialNotification.add(sessionId)
                log.debug("Initial IDE context sent to session: $sessionId")
            } catch (e: Exception) {
                log.warn("Failed to send initial IDE context to session $sessionId: ${e.message}")
            }
        }
    }

    private fun cleanupSession(sessionId: String) {
        log.info("Cleaning up session: $sessionId")
        sessionsWithInitialNotification.remove(sessionId)
        val t = transports.remove(sessionId) ?: return
        coroutineScope.launch { runCatching { t.close() } }
    }

    private fun wireListeners() {
        val connection = project.messageBus.connect(this)
        
        connection.subscribe(OpenFilesTracker.TOPIC, object : OpenFilesTracker.WorkspaceContextListener {
            override fun onContextChanged(context: WorkspaceContext) {
                broadcastIdeContextUpdate()
            }
        })

        connection.subscribe(CliqDiffManager.TOPIC, object : CliqDiffManager.DiffListener {
            override fun onDiffOutcome(outcome: CliqDiffOutcome) {
                if (outcome is CliqDiffOutcome.Rejected && outcome.suppressed) return
                val notification = when (outcome) {
                    is CliqDiffOutcome.Accepted -> JSONRPCNotification(
                        method = "ide/diffAccepted",
                        params = buildJsonObject {
                            put("filePath", outcome.filePath)
                            put("content", outcome.finalContent)
                        }
                    )
                    is CliqDiffOutcome.Rejected -> JSONRPCNotification(
                        method = "ide/diffClosed",
                        params = buildJsonObject { put("filePath", outcome.filePath) }
                    )
                }
                broadcastNotification(notification)
            }
        })

        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                val newPath = resolveWorkspacePath()
                if (newPath != workspacePath) {
                    workspacePath = newPath
                    writeDiscoveryFiles()
                }
            }
        })
    }

    private fun broadcastIdeContextUpdate() {
        val context = project.service<OpenFilesTracker>().snapshot()
        broadcastNotification(buildContextNotification(context))
    }

    private fun buildContextNotification(context: WorkspaceContext): JSONRPCNotification {
        val payload = WorkspaceSnapshot(
            workspaceState = WorkspaceStatePayload(
                openFiles = context.openFiles.map { file ->
                    OpenFilePayload(
                        path = file.path,
                        timestamp = file.openedAt,
                        isActive = file.isActive,
                        cursor = file.cursor?.let { CursorPayload(it.line, it.column) },
                        selectedText = file.selectedText,
                    )
                },
                isTrusted = context.isTrusted,
            ),
        )
        return JSONRPCNotification(method = "ide/contextUpdate", params = SdkMcpJson.encodeToJsonElement(WorkspaceSnapshot.serializer(), payload))
    }

    private fun broadcastNotification(notification: JSONRPCNotification) {
        log.debug("Broadcasting notification '${notification.method}' to ${transports.size} sessions")
        transports.forEach { (sessionId, transport) ->
            coroutineScope.launch {
                try {
                    transport.send(notification)
                } catch (e: Exception) {
                    log.warn("Failed to send notification to session $sessionId: ${e.message}")
                    cleanupSession(sessionId)
                }
            }
        }
    }

    private fun resolveWorkspacePath(): String {
        val roots = ProjectRootManager.getInstance(project).contentRoots
        return if (roots.isNotEmpty()) roots.joinToString(File.pathSeparator) { it.path }
        else project.basePath ?: ""
    }

    private fun writeDiscoveryFiles() {
        val port = boundPort ?: return
        discoveryFiles.forEach { runCatching { it.delete() } }
        discoveryFiles.clear()
        val info = ApplicationInfo.getInstance()
        val ideInfo = IdeInfo(
            info.versionName.lowercase(Locale.getDefault()).replace(" ", ""),
            info.fullApplicationName
        )
        val ppid = ProcessHandle.current().pid()
        val record = DiscoveryRecord(port, authToken, ppid, ideInfo, workspacePath)
        val json = McpJson.codec.encodeToString(DiscoveryRecord.serializer(), record)
        val dir = prepareDiscoveryDirectory() ?: return
        val pids = mutableSetOf(ppid).apply {
            ProcessHandle.of(ppid).ifPresent { h -> h.parent().ifPresent { add(it.pid()) } }
        }
        for (pid in pids) {
            val file = File(dir.toFile(), "gemini-ide-server-$pid-$port.json")
            val written = runCatching { writeSecretFile(file.toPath(), json) }
            if (written.isFailure) {
                log.warn("Failed to write discovery file ${file.absolutePath}", written.exceptionOrNull())
                continue
            }
            file.deleteOnExit()
            discoveryFiles.add(file)
        }
        log.info("Wrote ${discoveryFiles.size} discovery file(s) to ${dir.toAbsolutePath()}")
    }

    private fun prepareDiscoveryDirectory(): java.nio.file.Path? {
        val root = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"))
        val geminiDir = root.resolve("gemini")
        val ideDir = geminiDir.resolve("ide")

        for (candidate in listOf(geminiDir, ideDir)) {
            if (Files.isSymbolicLink(candidate)) {
                log.warn("Refusing to use discovery directory: $candidate is a symbolic link")
                return null
            }
            if (Files.exists(candidate) && !isOwnedByCurrentUser(candidate)) {
                log.warn("Refusing to use discovery directory: $candidate is owned by another user")
                return null
            }
        }

        return runCatching {
            if (supportsPosixPermissions(root)) {
                val attribute = PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString(OWNER_ONLY_DIRECTORY)
                )
                if (!Files.exists(geminiDir)) Files.createDirectory(geminiDir, attribute)
                if (!Files.exists(ideDir)) Files.createDirectory(ideDir, attribute)
                Files.setPosixFilePermissions(ideDir, PosixFilePermissions.fromString(OWNER_ONLY_DIRECTORY))
            } else {
                Files.createDirectories(ideDir)
            }
            ideDir
        }.getOrElse {
            log.warn("Failed to prepare discovery directory $ideDir", it)
            null
        }
    }

    private fun isOwnedByCurrentUser(path: java.nio.file.Path): Boolean {
        val currentUser = System.getProperty("user.name") ?: return true
        val owner = runCatching { Files.getOwner(path)?.name }.getOrNull() ?: return true
        return owner == currentUser || owner.substringAfterLast('\\') == currentUser
    }

    private fun writeSecretFile(path: java.nio.file.Path, content: String) {
        Files.deleteIfExists(path)
        if (supportsPosixPermissions(path.parent)) {
            Files.createFile(
                path,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(OWNER_ONLY_FILE)),
            )
        } else {
            Files.createFile(path)
            path.toFile().apply {
                setReadable(false, false)
                setWritable(false, false)
                setReadable(true, true)
                setWritable(true, true)
            }
        }
        Files.newBufferedWriter(path, StandardCharsets.UTF_8).use { it.write(content) }
    }

    private fun supportsPosixPermissions(path: java.nio.file.Path?): Boolean =
        path != null && runCatching {
            path.fileSystem.supportedFileAttributeViews().contains("posix")
        }.getOrDefault(false)

    override fun dispose() {
        transports.keys.toList().forEach { cleanupSession(it) }
        coroutineScope.cancel()
        server?.stop(0)
        server = null
        discoveryFiles.forEach { runCatching { it.delete() } }
        discoveryFiles.clear()
    }

    private fun generateAuthToken(): String =
        Base64.getEncoder().withoutPadding()
            .encodeToString(ByteArray(32).apply { SecureRandom().nextBytes(this) })
}
