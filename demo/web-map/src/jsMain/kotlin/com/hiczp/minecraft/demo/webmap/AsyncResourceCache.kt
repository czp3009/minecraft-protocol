package com.hiczp.minecraft.demo.webmap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

internal sealed interface CachedResource<out V> {
    data class Available<V>(val value: V) : CachedResource<V>

    data class Unavailable(val message: String) : CachedResource<Nothing>
}

internal class AsyncResourceCache<K, V>(
    private val coroutineScope: CoroutineScope,
    private val loader: suspend (K) -> CachedResource<V>,
) {
    private val resources = mutableMapOf<K, Deferred<CachedResource<V>>>()
    private val completedResources = mutableMapOf<K, CachedResource<V>>()

    fun start(key: K): Deferred<CachedResource<V>> = resources.getOrPut(key) {
        coroutineScope.async {
            loader(key).also { cachedResource -> completedResources[key] = cachedResource }
        }
    }

    suspend fun get(key: K): CachedResource<V> = start(key).await()

    fun completed(key: K): CachedResource<V>? = completedResources[key]

    val size: Int
        get() = resources.size
}
