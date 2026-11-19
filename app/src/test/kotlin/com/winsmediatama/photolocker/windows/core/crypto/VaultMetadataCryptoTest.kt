package com.winsmediatama.photolocker.windows.core.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFails

class VaultMetadataCryptoTest {
    @Test fun encryptDecryptRoundTrip() {
        val key = VaultFileCrypto.generateVaultKey()
        val original = "private-photo-name.jpg"
        val encrypted = VaultMetadataCrypto.encrypt(original, key, "media.name")
        assertNotEquals(original, encrypted)
        assertEquals(original, VaultMetadataCrypto.decrypt(encrypted, key, "media.name"))
    }

    @Test fun fieldAssociatedDataPreventsCrossFieldUse() {
        val key = VaultFileCrypto.generateVaultKey()
        val encrypted = VaultMetadataCrypto.encrypt("secret", key, "media.name")
        assertFails { VaultMetadataCrypto.decrypt(encrypted, key, "media.mime") }
    }

    @Test fun legacyPlaintextIsReadableForMigration() {
        val key = VaultFileCrypto.generateVaultKey()
        assertEquals("legacy value", VaultMetadataCrypto.decrypt("legacy value", key, "field"))
    }
}
