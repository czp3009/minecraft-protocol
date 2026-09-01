package com.hiczp.minecraft.demo.webmap

import kotlinx.coroutines.*

sealed interface CachedResource<out V> {
    data class Available<V>(val value: V) : CachedResource<V>

    data class Unavailable(val message: String) : CachedResource<Nothing>
}

class AsyncResourceCache<K, V>(
    private val coroutineScope: CoroutineScope,
    private val loader: suspend (K) -> CachedResource<V>,
) {
    private val resources = mutableMapOf<K, Deferred<CachedResource<V>>>()
    private val completedResources = mutableMapOf<K, CachedResource<V>>()

    fun prefetch(key: K): Boolean {
        if (key in resources) return false
        resources[key] = load(key)
        return true
    }

    suspend fun get(key: K): CachedResource<V> = deferred(key).await()

    private fun deferred(key: K): Deferred<CachedResource<V>> = resources.getOrPut(key) { load(key) }

    private fun load(key: K): Deferred<CachedResource<V>> = coroutineScope.async {
        val cachedResource = loader(key)
        currentCoroutineContext().ensureActive()
        completedResources[key] = cachedResource
        cachedResource
    }

    fun completed(key: K): CachedResource<V>? = completedResources[key]

    val size: Int
        get() = resources.size
}
