package com.winsmediatama.photolocker.windows.core.crypto
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails
import kotlin.test.assertNotEquals
class VaultFileCryptoTest {
    @Test fun roundTripStreaming() {
        val d=Files.createTempDirectory("plw-crypto"); val src=d.resolve("src"); val enc=d.resolve("enc"); val dec=d.resolve("dec")
        val bytes=ByteArray(256*1024) { (it%239).toByte() }; Files.write(src,bytes)
        val key=VaultFileCrypto.generateVaultKey(); VaultFileCrypto.encryptFile(src,enc,key); VaultFileCrypto.decryptFile(enc,dec,key)
        assertContentEquals(bytes,Files.readAllBytes(dec))
    }
    @Test fun nonceRandomized() {
        val d=Files.createTempDirectory("plw-nonce"); val src=d.resolve("src"); Files.write(src,byteArrayOf(9))
        val key=VaultFileCrypto.generateVaultKey(); val a=d.resolve("a"); val b=d.resolve("b")
        VaultFileCrypto.encryptFile(src,a,key); VaultFileCrypto.encryptFile(src,b,key)
        assertNotEquals(Files.readAllBytes(a).toList(),Files.readAllBytes(b).toList())
    }
    @Test fun tamperingFailsAuthentication() {
        val d=Files.createTempDirectory("plw-tamper"); val src=d.resolve("src"); val enc=d.resolve("enc"); val dec=d.resolve("dec")
        Files.write(src,ByteArray(80){1}); val key=VaultFileCrypto.generateVaultKey(); VaultFileCrypto.encryptFile(src,enc,key)
        val data=Files.readAllBytes(enc); data[data.lastIndex-1]=(data[data.lastIndex-1].toInt() xor 0x08).toByte(); Files.write(enc,data)
        assertFails { VaultFileCrypto.decryptFile(enc,dec,key) }; assert(!Files.exists(dec))
    }
}