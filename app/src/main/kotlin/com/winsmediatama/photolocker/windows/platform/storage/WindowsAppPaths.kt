package com.winsmediatama.photolocker.windows.platform.storage

import java.nio.file.Files
import java.nio.file.Path

/** Per-user config plus a vault folder that may be relocated to another drive. */
object WindowsAppPaths {
    private const val APP_DIRECTORY = "Photo-Locker-Windows"
    private const val VAULT_FOLDER = "vault"
    private const val LOCATION_CONFIG = "vault-location.txt"

    fun appDataRoot(): Path {
        val base = System.getenv("LOCALAPPDATA")
            ?: error("LOCALAPPDATA is unavailable; cannot safely determine user app-data location.")
        return Path.of(base, APP_DIRECTORY)
    }

    private fun defaultVaultDirectory(): Path = appDataRoot().resolve(VAULT_FOLDER)

    fun vaultDirectory(): Path {
        val config = appDataRoot().resolve(LOCATION_CONFIG)
        if (Files.exists(config)) {
            try {
                val configured = Files.readString(config).trim()
                if (configured.isNotBlank()) return Path.of(configured).toAbsolutePath().normalize()
            } catch (_: Exception) { /* fall back to the default location */ }
        }
        return defaultVaultDirectory()
    }

    /** Store a vault destination path. Caller should select an existing folder. */
    fun setVaultDirectory(directory: Path) {
        val normalized = directory.toAbsolutePath().normalize()
        Files.createDirectories(normalized)
        Files.createDirectories(appDataRoot())
        Files.writeString(appDataRoot().resolve(LOCATION_CONFIG), normalized.toString())
    }

    fun resetVaultDirectory() {
        Files.deleteIfExists(appDataRoot().resolve(LOCATION_CONFIG))
    }

    fun keyFile(): Path = vaultDirectory().resolve("vault.key.dpapi")
    fun encryptedMediaDirectory(): Path = vaultDirectory().resolve("media")
    fun thumbnailDirectory(): Path = vaultDirectory().resolve("thumbnails")
    fun databaseFile(): Path = vaultDirectory().resolve("vault.db")
    fun temporaryDirectory(): Path = vaultDirectory().resolve(".temporary")

    /** Remove stale plaintext work files left behind by an abnormal previous exit. */
    fun cleanupTemporaryFiles() {
        val temp = temporaryDirectory()
        if (Files.exists(temp)) Files.walk(temp).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    fun ensureVaultDirectories() {
        Files.createDirectories(encryptedMediaDirectory())
        Files.createDirectories(thumbnailDirectory())
    }
}
