package com.winsmediatama.photolocker.windows.core.backup

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

class VaultBackupEngineTest {
    @Test fun backupRestoreRoundTrip() {
        val root = Files.createTempDirectory("plw-backup-test")
        val vault = root.resolve("vault")
        Files.createDirectories(vault.resolve("media"))
        Files.write(vault.resolve("vault.key.dpapi"), byteArrayOf(1, 2, 3))
        Files.write(vault.resolve("vault.db"), byteArrayOf(4, 5, 6))
        Files.write(vault.resolve("media/file.plwenc"), ByteArray(1024) { (it % 255).toByte() })
        Files.createDirectories(vault.resolve(".temporary"))
        Files.write(vault.resolve(".temporary/plain.tmp"), byteArrayOf(99))
        val backup = root.resolve("backup.plwbackup")
        VaultBackupEngine.createBackup(vault, backup, "backup-password-123".toCharArray())
        val restored = root.resolve("restored")
        VaultBackupEngine.restoreBackup(backup, restored, "backup-password-123".toCharArray())
        assertContentEquals(Files.readAllBytes(vault.resolve("vault.key.dpapi")), Files.readAllBytes(restored.resolve("vault.key.dpapi")))
        assertContentEquals(Files.readAllBytes(vault.resolve("media/file.plwenc")), Files.readAllBytes(restored.resolve("media/file.plwenc")))
        assertFails { Files.readAllBytes(restored.resolve(".temporary/plain.tmp")) }
    }

    @Test fun restoreRejectsMissingManifest() {
        val root = Files.createTempDirectory("plw-backup-invalid")
        val invalid = root.resolve("invalid.zip")
        ZipOutputStream(Files.newOutputStream(invalid)).use { zip ->
            zip.putNextEntry(ZipEntry("vault.db"))
            zip.write(byteArrayOf(1, 2))
            zip.closeEntry()
        }
        assertFails { VaultBackupEngine.restoreBackup(invalid, root.resolve("restore"), "backup-password-123".toCharArray()) }
    }
}
