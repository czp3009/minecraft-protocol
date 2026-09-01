package com.hiczp.minecraft.demo.webmap

import io.ktor.client.*
import io.ktor.client.engine.curl.*

actual fun createOfficialAssetHttpClient(): HttpClient = HttpClient(Curl)
