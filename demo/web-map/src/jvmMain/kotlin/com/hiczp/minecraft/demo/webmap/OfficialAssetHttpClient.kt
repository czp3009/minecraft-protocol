package com.hiczp.minecraft.demo.webmap

import io.ktor.client.*
import io.ktor.client.engine.cio.*

actual fun createOfficialAssetHttpClient(): HttpClient = HttpClient(CIO)
