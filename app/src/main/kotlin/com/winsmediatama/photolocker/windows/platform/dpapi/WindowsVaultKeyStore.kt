package com.winsmediatama.photolocker.windows.platform.dpapi

import com.winsmediatama.photolocker.windows.core.crypto.VaultFileCrypto
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password-wrapped vault key storage with legacy DPAPI migration support. */
class WindowsVaultKeyStore(private val keyBlobPath: Path) {
    companion object {
        private val MAGIC = "PLWPASS01".toByteArray(Charsets.US_ASCII)
        private const val SALT_SIZE = 16
        private const val NONCE_SIZE = 12
        private const val ITERATIONS = 310_000
        private const val TAG_BITS = 128
        private const val MIN_PASSWORD_CHARS = 8
        private val random = SecureRandom()
    }

    fun createVaultKey(password: CharArray): ByteArray {
        check(!Files.exists(keyBlobPath)) { "Vault key already exists; use unlock instead." }
        validatePassword(password)
        val key = VaultFileCrypto.generateVaultKey()
        try { writePasswordBlob(key, password); return key }
        catch (e: Exception) { key.fill(0); throw e }
    }

    /** Unlock password-based vault. Legacy DPAPI blobs require migration first. */
    fun unlock(password: CharArray): ByteArray {
        check(Files.exists(keyBlobPath)) { "Vault key file is missing. Create a new vault first." }
        val blob = Files.readAllBytes(keyBlobPath)
        require(isPasswordBlob(blob)) { "This vault uses legacy Windows DPAPI. Use 'Migrate Existing Vault' first." }
        return unwrapPasswordBlob(blob, password)
    }

    /** Converts an existing DPAPI-only vault to password-wrapped key storage without changing media. */
    fun migrateLegacyToPassword(password: CharArray): ByteArray {
        check(Files.exists(keyBlobPath)) { "Vault key file is missing." }
        validatePassword(password)
        val oldBlob = Files.readAllBytes(keyBlobPath)
        require(!isPasswordBlob(oldBlob)) { "Vault is already password-protected. Use Unlock." }
        val key = WindowsDpapiKeyProtector.unprotect(oldBlob)
        require(key.size == 32) { "Legacy protected key has an invalid size." }
        try { writePasswordBlob(key, password); return key }
        catch (e: Exception) { key.fill(0); throw e }
    }

    fun isPasswordProtected(): Boolean = Files.exists(keyBlobPath) && isPasswordBlob(Files.readAllBytes(keyBlobPath))
    fun exists(): Boolean = Files.exists(keyBlobPath)

    private fun writePasswordBlob(key: ByteArray, password: CharArray) {
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val derived = derive(password, salt)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(MAGIC)
            val ciphertext = cipher.doFinal(key)
            val blob = MAGIC + intBytes(ITERATIONS) + salt + nonce + ciphertext
            writeAtomically(blob)
        } finally { derived.fill(0) }
    }

    private fun unwrapPasswordBlob(blob: ByteArray, password: CharArray): ByteArray {
        validatePassword(password)
        val minLength = MAGIC.size + 4 + SALT_SIZE + NONCE_SIZE + 16
        require(blob.size >= minLength) { "Password vault file is malformed." }
        var offset = MAGIC.size
        val iterations = readInt(blob, offset); offset += 4
        require(iterations in 100_000..2_000_000) { "Invalid password KDF settings." }
        val salt = blob.copyOfRange(offset, offset + SALT_SIZE); offset += SALT_SIZE
        val nonce = blob.copyOfRange(offset, offset + NONCE_SIZE); offset += NONCE_SIZE
        val derived = derive(password, salt, iterations)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(MAGIC)
            val key = cipher.doFinal(blob.copyOfRange(offset, blob.size))
            require(key.size == 32) { "Vault key has an invalid size." }
            return key
        } catch (e: Exception) {
            throw IllegalArgumentException("Incorrect password or vault file damaged.", e)
        } finally { derived.fill(0) }
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun validatePassword(password: CharArray) {
        require(password.size >= MIN_PASSWORD_CHARS) { "Password must be at least 8 characters." }
    }
    private fun isPasswordBlob(blob: ByteArray) = blob.size >= MAGIC.size && blob.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
    private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun readInt(b: ByteArray, o: Int) = ((b[o].toInt() and 255) shl 24) or ((b[o+1].toInt() and 255) shl 16) or ((b[o+2].toInt() and 255) shl 8) or (b[o+3].toInt() and 255)

    private fun writeAtomically(bytes: ByteArray) {
        keyBlobPath.parent?.let(Files::createDirectories)
        val temp = keyBlobPath.resolveSibling(keyBlobPath.fileName.toString() + ".tmp")
        try {
            Files.write(temp, bytes)
            try { Files.move(temp, keyBlobPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp, keyBlobPath, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }
}
