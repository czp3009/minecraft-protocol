package com.hiczp.minecraft.demo.launcher

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*

internal fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureLauncherHttpClient() {
    expectSuccess = true
    install(ContentNegotiation) {
        json(launcherJson)
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 60_000
        requestTimeoutMillis = 300_000
    }
    install(UserAgent) {
        agent = "$LAUNCHER_NAME/$LAUNCHER_VERSION"
    }
}
