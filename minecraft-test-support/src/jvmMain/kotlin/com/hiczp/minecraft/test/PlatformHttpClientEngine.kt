package com.hiczp.minecraft.test

import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*

internal actual fun platformHttpClientEngine(): HttpClientEngineFactory<*> = CIO
