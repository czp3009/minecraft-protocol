package com.hiczp.minecraft.test

import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class Log4jConfigurationXmlTest {
    @Test
    fun generatedConfigurationHasTheExpectedXmlStructure() {
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(log4jNullConfigurationXml().byteInputStream())

        assertEquals("Configuration", document.documentElement.tagName)
        assertEquals("OFF", document.documentElement.getAttribute("status"))
        assertEquals(
            "Null",
            document.getElementsByTagName("Null")
                .item(0)
                .attributes
                .getNamedItem("name")
                .nodeValue,
        )
        assertEquals(
            "off",
            document.getElementsByTagName("Root")
                .item(0)
                .attributes
                .getNamedItem("level")
                .nodeValue,
        )
        assertEquals(
            "Null",
            document.getElementsByTagName("AppenderRef")
                .item(0)
                .attributes
                .getNamedItem("ref")
                .nodeValue,
        )
    }
}
