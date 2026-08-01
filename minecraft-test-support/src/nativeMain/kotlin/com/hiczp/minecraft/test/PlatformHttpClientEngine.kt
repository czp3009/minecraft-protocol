package com.hiczp.minecraft.test

import io.ktor.client.engine.*
import io.ktor.client.engine.curl.*

internal actual fun platformHttpClientEngine(): HttpClientEngineFactory<*> = Curl
