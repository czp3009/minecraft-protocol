package com.hiczp.minecraft.world.io

internal sealed interface CoordinatedRead<out T> {
    data class Complete<T>(
        val value: T,
    ) : CoordinatedRead<T>

    data object RequiresExclusive : CoordinatedRead<Nothing>
}
