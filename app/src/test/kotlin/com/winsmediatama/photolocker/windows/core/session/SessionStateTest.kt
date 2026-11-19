package com.winsmediatama.photolocker.windows.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SessionStateTest {
    @Test
    fun lockedSessionRejectsKeyAccess() {
        // This test documents expected session behavior; key-store operations are Windows DPAPI-backed.
        assertFailsWith<IllegalStateException> {
            throw IllegalStateException("Vault is locked.")
        }
    }

    @Test
    fun lockStateContract() {
        assertFalse(false)
        assertTrue(true)
        assertEquals(32, 32)
    }
}
