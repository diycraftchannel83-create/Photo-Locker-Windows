package com.winsmediatama.photolocker.windows.platform.dpapi

import com.winsmediatama.photolocker.windows.core.crypto.VaultFileCrypto
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Persists only a DPAPI-wrapped vault key. The unwrapped key is returned to the caller
 * and should be kept in memory only while the vault is unlocked.
 */
class WindowsVaultKeyStore(private val keyBlobPath: Path) {
    fun createVaultKey(): ByteArray {
        check(!Files.exists(keyBlobPath)) { "Vault key already exists; use unlock instead." }
        val key = VaultFileCrypto.generateVaultKey()
        try {
            val wrapped = WindowsDpapiKeyProtector.protect(key)
            writeAtomically(wrapped)
            return key
        } catch (e: Exception) {
            key.fill(0)
            throw e
        }
    }

    fun unlock(): ByteArray {
        check(Files.exists(keyBlobPath)) { "Vault key file is missing. Create a new vault first." }
        val wrapped = Files.readAllBytes(keyBlobPath)
        val key = WindowsDpapiKeyProtector.unprotect(wrapped)
        require(key.size == 32) { "Protected vault key has an invalid size." }
        return key
    }

    private fun writeAtomically(bytes: ByteArray) {
        keyBlobPath.parent?.let(Files::createDirectories)
        val temp = keyBlobPath.resolveSibling(keyBlobPath.fileName.toString() + ".tmp")
        try {
            Files.write(temp, bytes)
            try {
                Files.move(temp, keyBlobPath, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, keyBlobPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
