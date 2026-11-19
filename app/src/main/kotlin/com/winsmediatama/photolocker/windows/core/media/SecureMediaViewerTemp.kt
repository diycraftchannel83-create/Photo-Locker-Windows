package com.winsmediatama.photolocker.windows.core.media

import com.winsmediatama.photolocker.windows.core.crypto.VaultFileCrypto
import com.winsmediatama.photolocker.windows.core.session.UnlockedVaultSession
import java.nio.file.Files
import java.nio.file.Path

/**
 * Temporary decrypted media handle. Call close() when the viewer closes or the
 * vault locks. Cleanup is best-effort because JVM/OS storage semantics vary.
 */
class SecureMediaViewerTemp private constructor(private val tempFile: Path) : AutoCloseable {
    val path: Path get() = tempFile

    override fun close() {
        try {
            if (Files.exists(tempFile)) {
                Files.write(tempFile, ByteArray(0))
                Files.deleteIfExists(tempFile)
            }
        } catch (_: Exception) {
            try { Files.deleteIfExists(tempFile) } catch (_: Exception) { }
        }
    }

    companion object {
        fun open(session: UnlockedVaultSession, encryptedMedia: Path, tempRoot: Path = Path.of(System.getProperty("java.io.tmpdir"))): SecureMediaViewerTemp {
            Files.createDirectories(tempRoot)
            val directory = Files.createTempDirectory(tempRoot, "plw-view-")
            val plain = directory.resolve("view.tmp")
            try {
                session.withKey { key -> VaultFileCrypto.decryptFile(encryptedMedia, plain, key) }
                return SecureMediaViewerTemp(plain)
            } catch (e: Exception) {
                try { Files.deleteIfExists(plain) } catch (_: Exception) { }
                try { Files.deleteIfExists(directory) } catch (_: Exception) { }
                throw e
            }
        }
    }
}
