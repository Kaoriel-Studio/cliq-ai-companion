package com.cliq.plugin.review.collect.render

class XmlBuilder(private val baseDepth: Int = 0) {

    private companion object {
        const val INDENT = "  "
        val NAME = Regex("^[A-Za-z_][A-Za-z0-9_.\\-]*$")
    }

    private val out = StringBuilder()
    private var depth = baseDepth

    fun declaration(): XmlBuilder = apply { out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n") }

    fun element(name: String, vararg attributes: Pair<String, Any?>, body: XmlBuilder.() -> Unit): XmlBuilder = apply {
        indent().append('<').append(checked(name)).appendAttributes(attributes).append(">\n")
        depth++
        body()
        depth--
        indent().append("</").append(name).append(">\n")
    }

    fun empty(name: String, vararg attributes: Pair<String, Any?>): XmlBuilder = apply {
        indent().append('<').append(checked(name)).appendAttributes(attributes).append("/>\n")
    }

    fun text(name: String, value: Any?, vararg attributes: Pair<String, Any?>): XmlBuilder = apply {
        indent().append('<').append(checked(name)).appendAttributes(attributes).append('>')
            .append(XmlText.text(value?.toString().orEmpty()))
            .append("</").append(name).append(">\n")
    }

    fun cdata(name: String, value: String, vararg attributes: Pair<String, Any?>): XmlBuilder = apply {
        indent().append('<').append(checked(name)).appendAttributes(attributes).append(">\n")
        out.append(XmlText.cdata(value)).append('\n')
        indent().append("</").append(name).append(">\n")
    }

    fun raw(fragment: String): XmlBuilder = apply { out.append(fragment) }

    fun build(): String = out.toString()

    private fun indent(): StringBuilder = out.append(INDENT.repeat(depth))

    private fun StringBuilder.appendAttributes(attributes: Array<out Pair<String, Any?>>): StringBuilder = apply {
        attributes.forEach { (key, value) ->
            if (value == null) return@forEach
            append(' ').append(checked(key)).append("=\"").append(XmlText.attribute(value.toString())).append('"')
        }
    }

    private fun checked(name: String): String {
        require(NAME.matches(name)) { "Invalid XML name: $name" }
        return name
    }
}
