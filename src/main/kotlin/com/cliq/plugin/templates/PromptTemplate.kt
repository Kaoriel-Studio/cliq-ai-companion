package com.cliq.plugin.templates

import java.util.UUID

data class PromptTemplate(
    var id: String = UUID.randomUUID().toString(),
    var title: String = "",
    var content: String = "",
    var description: String = "",
) {
    fun isValid(): Boolean = title.isNotBlank() && content.isNotBlank()
}
