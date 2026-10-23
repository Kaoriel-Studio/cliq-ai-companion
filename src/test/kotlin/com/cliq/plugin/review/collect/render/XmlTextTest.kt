package com.cliq.plugin.review.collect.render

import org.junit.Test
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class XmlTextTest {

    companion object {
        fun parse(xml: String): Document {
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                isExpandEntityReferences = false
            }
            return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun escapesAttributeInjectionAttempts() {
        val hostile = "x\" injected=\"1\"/><evil a='b'>\n\t&amp;"
        val xml = XmlBuilder().declaration().empty("file", "path" to hostile).build()
        val element = parse(xml).documentElement
        assertEquals("file", element.tagName)
        assertEquals(hostile, element.getAttribute("path"))
        assertEquals(1, element.attributes.length)
    }

    @Test
    fun keepsCdataPayloadIntactEvenWithTerminator() {
        val payload = "if (a[b[0]]> c) { }\n]]><injected/><![CDATA[\n]]]]>"
        val xml = XmlBuilder().declaration().cdata("diff", payload).build()
        val element = parse(xml).documentElement
        assertEquals(0, element.getElementsByTagName("injected").length)
        assertEquals("\n$payload\n", element.textContent)
    }

    @Test
    fun replacesCharactersForbiddenInXml() {
        val raw = "ok\u0000\u0001\u001Bend\uD800lone😀emoji"
        val sanitized = XmlText.sanitize(raw)
        assertEquals("ok���end�lone😀emoji", sanitized)
        parse(XmlBuilder().declaration().text("value", raw).build())
    }

    @Test
    fun rejectsInvalidElementNames() {
        assertFailsWith<IllegalArgumentException> { XmlBuilder().empty("bad name") }
        assertFailsWith<IllegalArgumentException> { XmlBuilder().empty("ok", "bad\"attr" to "x") }
    }
}
