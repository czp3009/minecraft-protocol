package com.hiczp.minecraft.test.host

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

internal fun log4jNullConfigurationXml(): String {
    val encoded = XML.v1 {
        setIndent(4)
        xmlDeclMode = XmlDeclMode.Charset
        xmlVersion = XmlVersion.XML10
    }.encodeToString(
        Log4jConfigurationXml.serializer(),
        Log4jConfigurationXml(
            status = "OFF",
            appenders = Log4jAppendersXml(
                nullAppender = Log4jNullAppenderXml(name = "Null"),
            ),
            loggers = Log4jLoggersXml(
                root = Log4jRootXml(
                    level = "off",
                    appenderRef = Log4jAppenderRefXml(ref = "Null"),
                ),
            ),
        ),
    )
    return "$encoded\n"
}

@Serializable
@XmlSerialName("Configuration")
private data class Log4jConfigurationXml(
    @XmlElement(false)
    val status: String,
    @XmlElement(true)
    val appenders: Log4jAppendersXml,
    @XmlElement(true)
    val loggers: Log4jLoggersXml,
)

@Serializable
@XmlSerialName("Appenders")
private data class Log4jAppendersXml(
    @XmlElement(true)
    val nullAppender: Log4jNullAppenderXml,
)

@Serializable
@XmlSerialName("Null")
private data class Log4jNullAppenderXml(
    @XmlElement(false)
    val name: String,
)

@Serializable
@XmlSerialName("Loggers")
private data class Log4jLoggersXml(
    @XmlElement(true)
    val root: Log4jRootXml,
)

@Serializable
@XmlSerialName("Root")
private data class Log4jRootXml(
    @XmlElement(false)
    val level: String,
    @XmlElement(true)
    val appenderRef: Log4jAppenderRefXml,
)

@Serializable
@XmlSerialName("AppenderRef")
private data class Log4jAppenderRefXml(
    @XmlElement(false)
    val ref: String,
)
