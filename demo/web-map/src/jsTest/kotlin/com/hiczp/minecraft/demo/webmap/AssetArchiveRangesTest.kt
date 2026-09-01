package com.hiczp.minecraft.demo.webmap

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AssetArchiveRangesTest {
    @Test
    fun mergesOnlyTouchingOrOverlappingAssetSpans() {
        val mergedSpans = mergeAssetArchiveSpans(
            listOf(
                AssetArchiveSpan(300, 50),
                AssetArchiveSpan(100, 40),
                AssetArchiveSpan(140, 20),
                AssetArchiveSpan(330, 40),
                AssetArchiveSpan(500, 10),
            ),
        )

        assertEquals(
            listOf(
                AssetArchiveSpan(100, 60),
                AssetArchiveSpan(300, 70),
                AssetArchiveSpan(500, 10),
            ),
            mergedSpans,
        )
    }

    @Test
    fun splitsDisjointSpansIntoPagesAndDeduplicatesSharedPages() {
        val pageIndices = assetArchivePageIndices(
            assetArchiveSpans = listOf(
                AssetArchiveSpan(10, 20),
                AssetArchiveSpan(50, 10),
                AssetArchiveSpan(130, 130),
            ),
            pageSize = 64,
            archiveSize = 512,
        )

        assertEquals(listOf(0, 2, 3, 4), pageIndices)
    }

    @Test
    fun retriesPageLoadsWithoutAnAttemptLimit() = runTest {
        var attempts = 0
        val retryAttempts = mutableListOf<Int>()
        var waits = 0

        val value = retryAssetArchivePage(
            waitBeforeRetry = { waits++ },
            retrying = { attempt, _ -> retryAttempts += attempt },
            load = {
                attempts++
                if (attempts < 4) error("temporary failure")
                "loaded"
            },
        )

        assertEquals("loaded", value)
        assertEquals(4, attempts)
        assertEquals(listOf(1, 2, 3), retryAttempts)
        assertEquals(3, waits)
    }

    @Test
    fun pageRetryPreservesCancellation() = runTest {
        var waited = false

        assertFailsWith<CancellationException> {
            retryAssetArchivePage(
                waitBeforeRetry = { waited = true },
                retrying = { _, _ -> error("Cancellation must not be reported as a retry") },
                load = { throw CancellationException("cancelled") },
            )
        }
        assertEquals(false, waited)
    }
}
