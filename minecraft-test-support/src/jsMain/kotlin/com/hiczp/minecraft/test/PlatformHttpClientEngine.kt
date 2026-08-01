package com.hiczp.minecraft.test

import io.ktor.client.engine.*
import io.ktor.client.engine.js.*

internal actual fun platformHttpClientEngine(): HttpClientEngineFactory<*> = Js
