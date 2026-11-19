package com.winsmediatama.photolocker.windows.core.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-GCM field encryption for private vault metadata. */
object VaultMetadataCrypto {
    private const val PREFIX = "ENC1:"
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun encrypt(value: String, key: ByteArray, field: String): String {
        if (value.startsWith(PREFIX)) return value
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(field.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(nonce + ciphertext)
    }

    fun decrypt(value: String, key: ByteArray, field: String): String {
        if (!value.startsWith(PREFIX)) return value // legacy plaintext row
        val bytes = Base64.getDecoder().decode(value.removePrefix(PREFIX))
        require(bytes.size > NONCE_SIZE) { "Encrypted metadata is truncated." }
        val nonce = bytes.copyOfRange(0, NONCE_SIZE)
        val ciphertext = bytes.copyOfRange(NONCE_SIZE, bytes.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(field.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
    }
}
