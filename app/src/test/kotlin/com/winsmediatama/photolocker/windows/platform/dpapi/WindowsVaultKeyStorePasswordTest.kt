package com.winsmediatama.photolocker.windows.platform.dpapi

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WindowsVaultKeyStorePasswordTest {
    @Test
    fun passwordVaultCreateAndUnlockRoundTrip() {
        val dir = Files.createTempDirectory("plw-password-test")
        val file = dir.resolve("vault.key")
        try {
            val store = WindowsVaultKeyStore(file)
            val password = "StrongPass123!".toCharArray()
            val key = store.createVaultKey(password)
            assertTrue(store.isPasswordProtected())
            val unlocked = store.unlock("StrongPass123!".toCharArray())
            assertContentEquals(key, unlocked)
            key.fill(0)
            unlocked.fill(0)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun wrongPasswordFailsAuthentication() {
        val dir = Files.createTempDirectory("plw-password-wrong")
        val file = dir.resolve("vault.key")
        try {
            val store = WindowsVaultKeyStore(file)
            store.createVaultKey("CorrectPass123!".toCharArray()).fill(0)
            assertFailsWith<IllegalArgumentException> {
                store.unlock("WrongPass123!".toCharArray())
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun passwordMustBeAtLeastEightCharacters() {
        val dir = Files.createTempDirectory("plw-password-short")
        try {
            assertFailsWith<IllegalArgumentException> {
                WindowsVaultKeyStore(dir.resolve("vault.key")).createVaultKey("short".toCharArray())
            }
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
