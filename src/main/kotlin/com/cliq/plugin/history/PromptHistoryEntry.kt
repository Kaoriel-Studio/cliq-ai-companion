package com.cliq.plugin.history

data class PromptHistoryEntry(
    var text: String = "",
    var sentAt: Long = 0L,
)
