package com.hiczp.minecraft.demo.webmap

import kotlinx.coroutines.CancellationException

internal data class AssetArchiveSpan(
    val offset: Int,
    val length: Int,
) {
    init {
        require(offset >= 0) { "Asset archive span offset must not be negative" }
        require(length > 0) { "Asset archive span length must be positive" }
        require(offset.toLong() + length <= Int.MAX_VALUE) { "Asset archive span exceeds the supported offset range" }
    }

    val endOffset: Int
        get() = offset + length
}

internal fun mergeAssetArchiveSpans(assetArchiveSpans: List<AssetArchiveSpan>): List<AssetArchiveSpan> {
    val sortedSpans = assetArchiveSpans.sortedBy(AssetArchiveSpan::offset)
    if (sortedSpans.isEmpty()) return emptyList()
    val mergedSpans = mutableListOf<AssetArchiveSpan>()
    var current = sortedSpans.first()
    sortedSpans.drop(1).forEach { assetArchiveSpan ->
        if (assetArchiveSpan.offset <= current.endOffset) {
            val endOffset = maxOf(current.endOffset, assetArchiveSpan.endOffset)
            current = AssetArchiveSpan(current.offset, endOffset - current.offset)
        } else {
            mergedSpans += current
            current = assetArchiveSpan
        }
    }
    mergedSpans += current
    return mergedSpans
}

internal fun assetArchivePageIndices(
    assetArchiveSpans: List<AssetArchiveSpan>,
    pageSize: Int,
    archiveSize: Int,
): List<Int> {
    require(pageSize > 0) { "Asset archive page size must be positive" }
    require(archiveSize > 0) { "Asset archive size must be positive" }
    val pageIndices = mutableSetOf<Int>()
    assetArchiveSpans.forEach { assetArchiveSpan ->
        require(assetArchiveSpan.endOffset <= archiveSize) { "Asset archive span is outside the archive" }
        val firstPage = assetArchiveSpan.offset / pageSize
        val lastPage = (assetArchiveSpan.endOffset - 1) / pageSize
        for (pageIndex in firstPage..lastPage) pageIndices += pageIndex
    }
    return pageIndices.sorted()
}

internal suspend fun <V> retryAssetArchivePage(
    waitBeforeRetry: suspend () -> Unit,
    retrying: (Int, Throwable) -> Unit,
    load: suspend () -> V,
): V {
    var failedAttempts = 0
    while (true) {
        try {
            return load()
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (failure: Throwable) {
            failedAttempts++
            retrying(failedAttempts, failure)
            waitBeforeRetry()
        }
    }
}
