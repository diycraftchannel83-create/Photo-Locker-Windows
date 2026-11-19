package com.winsmediatama.photolocker.windows.core.importer

import com.winsmediatama.photolocker.windows.core.crypto.VaultFileCrypto
import com.winsmediatama.photolocker.windows.core.database.MediaRecord
import com.winsmediatama.photolocker.windows.core.database.VaultDatabase
import com.winsmediatama.photolocker.windows.core.session.UnlockedVaultSession
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Imports a source file into the vault by encrypting it directly to a UUID-based
 * storage name. The source is not modified. No plaintext copy is created inside
 * the vault. Metadata database remains plaintext and is explicitly documented.
 */
class MediaImportEngine(
    private val session: UnlockedVaultSession,
    private val database: VaultDatabase,
    private val encryptedMediaDirectory: Path
) {
    fun importFile(source: Path): MediaRecord {
        require(Files.isRegularFile(source)) { "Selected source is not a regular file." }
        require(Files.isReadable(source)) { "Selected source file is not readable." }
        val size = Files.size(source)
        val originalName = source.fileName?.toString()?.take(512) ?: "media"
        val mime = Files.probeContentType(source) ?: fallbackMime(source)
        val id = UUID.randomUUID().toString()
        val relative = "media/$id.plwenc"
        val destination = encryptedMediaDirectory.resolve("$id.plwenc")
        val now = System.currentTimeMillis()
        Files.createDirectories(encryptedMediaDirectory)

        try {
            session.withKey { key ->
                VaultFileCrypto.encryptFile(source, destination, key)
            }
            database.insertMedia(id, originalName, mime, size, now, relative)
        } catch (e: Exception) {
            Files.deleteIfExists(destination)
            throw e
        }

        return MediaRecord(id, originalName, mime, size, now, relative)
    }

    private fun fallbackMime(path: Path): String {
        val ext = path.fileName.toString().substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "heic", "heif" -> "image/heic"
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/x-msvideo"
            else -> "application/octet-stream"
        }
    }
}
