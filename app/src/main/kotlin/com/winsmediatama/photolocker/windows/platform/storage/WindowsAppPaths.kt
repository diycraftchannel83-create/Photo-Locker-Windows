package com.winsmediatama.photolocker.windows.platform.storage

import java.nio.file.Files
import java.nio.file.Path

/** Standard Windows per-user application storage paths. */
object WindowsAppPaths {
    private const val APP_DIRECTORY = "Photo-Locker-Windows"

    fun appDataRoot(): Path {
        val base = System.getenv("LOCALAPPDATA")
            ?: error("LOCALAPPDATA is unavailable; cannot safely determine user app-data location.")
        return Path.of(base, APP_DIRECTORY)
    }

    fun vaultDirectory(): Path = appDataRoot().resolve("vault")
    fun keyFile(): Path = vaultDirectory().resolve("vault.key.dpapi")
    fun encryptedMediaDirectory(): Path = vaultDirectory().resolve("media")
    fun thumbnailDirectory(): Path = vaultDirectory().resolve("thumbnails")
    fun databaseFile(): Path = vaultDirectory().resolve("vault.db")

    fun ensureVaultDirectories() {
        Files.createDirectories(encryptedMediaDirectory())
        Files.createDirectories(thumbnailDirectory())
    }
}
