package com.hiczp.minecraft.protocol.buildScript

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class NullabilityAuditSupportTest {
    @Test
    fun globalInventorySignatureIsOrderIndependentButContentSensitive() {
        val rows = listOf(
            "source/B.kt#Owner.second|Owner|second|String|non-null|known",
            "source/a.kt#Owner.first|Owner|first|Int?|nullable|known",
            "source/A.kt#Owner.third|Owner|third|Long|non-null|known",
        )

        val expected = stableNullabilityInventorySignature(rows)

        assertEquals(
            expected,
            stableNullabilityInventorySignature(rows.reversed()),
        )
        assertEquals(
            expected,
            stableNullabilityInventorySignature(
                listOf(rows[1], rows[2], rows[0]),
            ),
        )
        assertNotEquals(
            expected,
            stableNullabilityInventorySignature(
                rows.dropLast(1) + rows.last().replace("Long", "Long?"),
            ),
        )
    }
}
