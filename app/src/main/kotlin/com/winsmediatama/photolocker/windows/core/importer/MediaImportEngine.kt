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
 * Moves a source file into the encrypted vault: encrypts first, verifies the
 * encrypted output, commits metadata, and only then deletes the original source.
 * If encryption or metadata insertion fails, the original source is preserved.
 * Metadata database remains plaintext and is explicitly documented.
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

        // Phase 1: encrypt and verify output. Keep source untouched if this fails.
        try {
            session.withKey { key ->
                VaultFileCrypto.encryptFile(source, destination, key)
            }
            require(Files.isRegularFile(destination) && Files.size(destination) > 0L) {
                "Encrypted vault file verification failed; source was kept."
            }
        } catch (e: Exception) {
            Files.deleteIfExists(destination)
            throw e
        }

        // Phase 2: commit metadata. On DB failure, remove encrypted output but keep source.
        try {
            database.insertMedia(id, originalName, mime, size, now, relative)
        } catch (e: Exception) {
            Files.deleteIfExists(destination)
            throw e
        }

        // Phase 3: remove original only after encryption and DB metadata succeed.
        // If deletion fails, the vault copy remains valid and source is left in place.
        try {
            Files.delete(source)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Imported successfully, but could not delete original source file: $originalName", e
            )
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
