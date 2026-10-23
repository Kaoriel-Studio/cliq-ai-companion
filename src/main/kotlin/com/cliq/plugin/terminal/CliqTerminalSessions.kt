package com.cliq.plugin.terminal

import com.intellij.openapi.components.Service
import java.util.Collections
import java.util.WeakHashMap

@Service(Service.Level.PROJECT)
class CliqTerminalSessions {

    data class PendingLaunch(
        val agentName: String,
        val environment: Map<String, String>,
        val requestedAt: Long,
    )

    companion object {
        const val PENDING_TTL_MS = 60_000L
    }

    private val lock = Any()
    private val pending = ArrayDeque<PendingLaunch>()
    private val sessions: MutableSet<Any> = Collections.newSetFromMap(WeakHashMap())

    fun requestSession(agentName: String, environment: Map<String, String>) {
        synchronized(lock) {
            purgeExpired()
            pending.addLast(PendingLaunch(agentName, environment.toMap(), System.currentTimeMillis()))
        }
    }

    fun consumePendingLaunch(): PendingLaunch? = synchronized(lock) {
        purgeExpired()
        pending.removeFirstOrNull()
    }

    fun registerSession(widget: Any) {
        synchronized(lock) { sessions.add(widget) }
    }

    fun isCliqSession(widget: Any): Boolean = synchronized(lock) {
        sessions.any { it === widget }
    }

    fun hasSessions(): Boolean = synchronized(lock) { sessions.isNotEmpty() }

    private fun purgeExpired() {
        val now = System.currentTimeMillis()
        while (pending.isNotEmpty() && now - pending.first().requestedAt > PENDING_TTL_MS) {
            pending.removeFirst()
        }
    }
}
