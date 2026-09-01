package com.hiczp.minecraft.demo.webmap

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AsyncResourceCacheTest {
    @Test
    fun cancellingAWaitingViewportDoesNotCancelTheSharedResourceLoad() = runTest {
        var startCount = 0
        val value = CompletableDeferred<String>()
        val asyncResourceCache = AsyncResourceCache<String, String>(this) {
            startCount++
            CachedResource.Available(value.await())
        }

        asyncResourceCache.prefetch("stone")
        val viewportWaiter = async { asyncResourceCache.get("stone") }
        runCurrent()
        assertEquals(1, startCount)

        viewportWaiter.cancel()
        value.complete("texture")
        runCurrent()

        asyncResourceCache.prefetch("stone")
        assertEquals(CachedResource.Available("texture"), asyncResourceCache.get("stone"))
        assertEquals(1, startCount)
    }
}
