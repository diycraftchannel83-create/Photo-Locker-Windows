package com.winsmediatama.photolocker.windows.core.crypto
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Streaming AES-256-GCM file encryption; authenticated header: magic, version, nonce. */
object VaultFileCrypto {
    private const val MAGIC = "PLWENC01"
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BYTES = 32
    private val random = SecureRandom()
    fun generateVaultKey(): ByteArray = ByteArray(KEY_BYTES).also(random::nextBytes)

    fun encryptFile(source: Path, destination: Path, key: ByteArray) {
        require(key.size == KEY_BYTES) { "Vault key must be 256 bits." }
        require(source != destination) { "Source and destination must differ." }
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = MAGIC.toByteArray(Charsets.US_ASCII) + byteArrayOf(VERSION) + nonce
        val cipher = cipher(Cipher.ENCRYPT_MODE, key, nonce, header)
        try {
            Files.newOutputStream(destination).use { raw ->
                raw.write(header)
                CipherOutputStream(raw, cipher).use { encrypted ->
                    BufferedInputStream(Files.newInputStream(source)).use { it.copyTo(encrypted) }
                }
            }
        } catch (e: Exception) {
            Files.deleteIfExists(destination)
            throw e
        }
    }

    /** GCM authentication is checked when the decrypted stream reaches EOF. */
    fun decryptFile(source: Path, destination: Path, key: ByteArray) {
        require(key.size == KEY_BYTES) { "Vault key must be 256 bits." }
        val temp = destination.resolveSibling(destination.fileName.toString() + ".decrypting")
        try {
            BufferedInputStream(Files.newInputStream(source)).use { input ->
                val header = ByteArray(MAGIC.length + 1 + NONCE_BYTES)
                readFully(input, header)
                require(header.copyOfRange(0, MAGIC.length).toString(Charsets.US_ASCII) == MAGIC) { "Invalid encrypted file." }
                require(header[MAGIC.length] == VERSION) { "Unsupported vault file version." }
                val nonce = header.copyOfRange(MAGIC.length + 1, header.size)
                CipherInputStream(input, cipher(Cipher.DECRYPT_MODE, key, nonce, header)).use { decrypted ->
                    Files.newOutputStream(temp).use { decrypted.copyTo(it) }
                }
            }
            Files.move(temp, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            Files.deleteIfExists(temp)
            throw e
        }
    }
    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray) =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
    private fun readFully(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = input.read(bytes, offset, bytes.size - offset)
            if (n < 0) throw java.io.EOFException("Truncated vault file header.")
            offset += n
        }
    }
}