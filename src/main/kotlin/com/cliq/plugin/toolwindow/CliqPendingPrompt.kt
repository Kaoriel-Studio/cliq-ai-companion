package com.cliq.plugin.toolwindow

import com.intellij.openapi.components.Service
import java.util.concurrent.atomic.AtomicReference

@Service(Service.Level.PROJECT)
class CliqPendingPrompt {

    private val pending = AtomicReference<String?>(null)

    fun put(text: String) {
        pending.set(text)
    }

    fun consume(): String? = pending.getAndSet(null)

    fun peek(): String? = pending.get()
}
