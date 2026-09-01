package com.hiczp.minecraft.demo.webmap

object BrowserConsole {
    fun debug(message: String) {
        browserConsole.debug("$PREFIX $message")
    }

    fun info(message: String) {
        browserConsole.info("$PREFIX $message")
    }

    fun warn(message: String) {
        browserConsole.warn("$PREFIX $message")
    }

    fun error(message: String, failure: Throwable? = null) {
        if (failure == null) {
            browserConsole.error("$PREFIX $message")
        } else {
            browserConsole.error("$PREFIX $message", failure)
        }
    }
}

private val browserConsole: dynamic = js("console")
private const val PREFIX: String = "[minecraft-web-map]"
