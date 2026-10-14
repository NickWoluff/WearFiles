package com.nickwoluff.wearfiles

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun comparesNumericSegmentsInsteadOfTextOrDecimal() {
        assertTrue(isNewerVersion("v0.10.0", "0.9.9"))
        assertTrue(isNewerVersion("V1.0", "0.3.0"))
        assertFalse(isNewerVersion("v0.3", "0.3.0"))
        assertFalse(isNewerVersion("0.2.99", "0.3.0"))
        assertTrue(isNewerVersion("999999999999999999999.0", "0.3.0"))
    }

    @Test fun rejectsMalformedAndPrereleaseVersions() {
        listOf("", "latest", "v1.0-beta", "1..2", " 1.0", "1.0+build").forEach {
            assertThrows(IllegalArgumentException::class.java) { isNewerVersion(it, "0.3.0") }
        }
        assertThrows(IllegalArgumentException::class.java) { isNewerVersion("1.0", "invalid") }
    }

    @Test fun differentiatesMissingReleasesRateLimitAndNetworkFailure() {
        assertEquals("暂无正式发布版本", updateErrorMessage(404))
        assertEquals("检查过于频繁，请稍后重试", updateErrorMessage(403))
        assertEquals(updateErrorMessage(403), updateErrorMessage(429))
        assertEquals("检查失败，请检查网络后重试", updateErrorMessage(-1))
        assertEquals(updateErrorMessage(-1), updateErrorMessage(500))
    }

    @Test fun copyrightExpandsOnlyAfterStartingYear() {
        assertEquals("© 2026 Nick Woluff. All Rights Reserved.", copyrightText(2026))
        assertEquals("© 2026-2027 Nick Woluff. All Rights Reserved.", copyrightText(2027))
    }
}
