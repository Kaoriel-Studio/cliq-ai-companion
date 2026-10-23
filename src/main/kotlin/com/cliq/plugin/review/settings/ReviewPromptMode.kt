package com.cliq.plugin.review.settings

enum class ReviewPromptMode(val displayName: String) {
    BUILT_IN("Insert the review prompt below"),
    TEMPLATE("Insert one of my prompt templates"),
    NONE("Don't insert anything");

    companion object {
        fun parse(raw: String?): ReviewPromptMode = entries.firstOrNull { it.name == raw } ?: BUILT_IN
    }
}
