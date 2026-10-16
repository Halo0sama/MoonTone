package com.halo.moontone.update

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {
    @Test fun comparesVersionsWithoutCountingPrereleaseDigitsAsPatchNumbers() {
        assertFalse(UpdateChecker.isNewer("v0.2.0-beta1", "0.2.0"))
        assertTrue(UpdateChecker.isNewer("v0.2.0", "0.2.0-beta1"))
        assertTrue(UpdateChecker.isNewer("v0.2.10", "0.2.9"))
        assertFalse(UpdateChecker.isNewer("v0.2.0+build999", "0.2.0"))
        assertTrue(UpdateChecker.isNewer("v0.2.0-beta.10", "0.2.0-beta.9"))
        assertTrue(UpdateChecker.isNewer("v0.2.0-beta", "0.2.0-alpha"))
        assertFalse(UpdateChecker.isNewer("v0.2.0-beta.1", "0.2.0-beta.1"))
    }

    @Test fun malformedAndLargeVersionsCannotCrashStartup() {
        assertFalse(UpdateChecker.isNewer("release-999", "0.2.0"))
        assertFalse(UpdateChecker.isNewer("", "0.2.0"))
        assertTrue(UpdateChecker.isNewer("v999999999999999999999.0.0", "0.2.0"))
    }
}
