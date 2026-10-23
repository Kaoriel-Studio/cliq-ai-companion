package com.cliq.plugin.review.settings

enum class PreChangeContentMode(val displayName: String) {
    NEVER("Never"),
    REWRITES_ONLY("Only for heavily rewritten files"),
    ALWAYS("Always");

    companion object {
        fun parse(raw: String?): PreChangeContentMode =
            entries.firstOrNull { it.name == raw } ?: REWRITES_ONLY
    }
}
