package com.hiczp.minecraft.test

internal fun closeServiceConnection(
    primaryFailure: Throwable? = null,
    vararg closeActions: () -> Unit,
) {
    var failure = primaryFailure
    closeActions.forEach { close ->
        try {
            close()
        } catch (closeFailure: Throwable) {
            failure?.addSuppressed(closeFailure)
                ?: run { failure = closeFailure }
        }
    }
    if (primaryFailure == null) failure?.let { throw it }
}
