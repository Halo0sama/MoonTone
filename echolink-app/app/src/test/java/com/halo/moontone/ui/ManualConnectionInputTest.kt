package com.halo.moontone.ui

import org.junit.Assert.*
import org.junit.Test

class ManualConnectionInputTest {
    @Test fun emptyKeysKeepTheExistingZeroKeyBehavior() {
        assertArrayEquals(ByteArray(16), parseSessionKey(" ", "riKey"))
    }

    @Test fun validUpperAndLowerCaseHexPreservesAllSixteenBytes() {
        val expected = byteArrayOf(0, 17, 34, 51, 68, 85, 102, 119, -120, -103, -86, -69, -52, -35, -18, -1)
        assertArrayEquals(expected, parseSessionKey("00112233445566778899AAbbCCDDeeFF", "riKey"))
    }

    @Test fun malformedKeysFailBeforeAnyNetworkOrNativeConnection() {
        for (value in listOf("0", "0011", "z".repeat(32), "a".repeat(34))) {
            assertThrows(IllegalArgumentException::class.java) { parseSessionKey(value, "riKey") }
        }
    }
}
