package com.winsmediatama.photolocker.windows.core.backup

import com.winsmediatama.photolocker.windows.core.crypto.VaultFileCrypto
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** Password-based outer encryption for portable backup packages. */
object PasswordBackupCrypto {
    private val magic = "PLWBACK01".toByteArray(Charsets.US_ASCII)
    private const val ITERATIONS = 310_000
    private const val SALT_BYTES = 16
    private val random = SecureRandom()

    fun encrypt(zipFile: Path, outputFile: Path, password: CharArray, tempRoot: Path) {
        require(password.size >= 8) { "Backup password must be at least 8 characters." }
        Files.createDirectories(tempRoot)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val derived = derive(password, salt)
        val encryptedTemp = Files.createTempFile(tempRoot, "backup-cipher-", ".tmp")
        try {
            VaultFileCrypto.encryptFile(zipFile, encryptedTemp, derived)
            outputFile.parent?.let(Files::createDirectories)
            Files.newOutputStream(outputFile).use { out ->
                out.write(magic)
                out.write(byteArrayOf((ITERATIONS ushr 24).toByte(), (ITERATIONS ushr 16).toByte(), (ITERATIONS ushr 8).toByte(), ITERATIONS.toByte()))
                out.write(salt)
                Files.copy(encryptedTemp, out)
            }
        } catch (e: Exception) {
            Files.deleteIfExists(outputFile)
            throw e
        } finally {
            derived.fill(0)
            Files.deleteIfExists(encryptedTemp)
        }
    }

    fun decrypt(backupFile: Path, zipFile: Path, password: CharArray, tempRoot: Path) {
        require(password.size >= 8) { "Backup password must be at least 8 characters." }
        Files.createDirectories(tempRoot)
        val encryptedTemp = Files.createTempFile(tempRoot, "backup-payload-", ".tmp")
        var derived: ByteArray? = null
        try {
            Files.newInputStream(backupFile).use { input ->
                val header = ByteArray(magic.size + 4 + SALT_BYTES)
                var offset = 0
                while (offset < header.size) {
                    val n = input.read(header, offset, header.size - offset)
                    if (n < 0) throw java.io.EOFException("Backup header is truncated.")
                    offset += n
                }
                require(header.copyOfRange(0, magic.size).contentEquals(magic)) { "Not a Photo-Locker password backup." }
                val iterations = ((header[magic.size].toInt() and 255) shl 24) or
                    ((header[magic.size + 1].toInt() and 255) shl 16) or
                    ((header[magic.size + 2].toInt() and 255) shl 8) or
                    (header[magic.size + 3].toInt() and 255)
                require(iterations in 100_000..2_000_000) { "Invalid backup KDF settings." }
                val salt = header.copyOfRange(magic.size + 4, header.size)
                derived = derive(password, salt, iterations)
                Files.newOutputStream(encryptedTemp).use { input.copyTo(it) }
            }
            VaultFileCrypto.decryptFile(encryptedTemp, zipFile, derived!!)
        } catch (e: Exception) {
            throw IllegalArgumentException("Backup password incorrect or backup damaged.", e)
        } finally {
            derived?.fill(0)
            Files.deleteIfExists(encryptedTemp)
        }
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
}
