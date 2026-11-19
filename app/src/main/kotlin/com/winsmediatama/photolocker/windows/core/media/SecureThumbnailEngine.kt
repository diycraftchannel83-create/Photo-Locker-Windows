package com.winsmediatama.photolocker.windows.core.media

import com.winsmediatama.photolocker.windows.core.crypto.VaultFileCrypto
import com.winsmediatama.photolocker.windows.core.session.UnlockedVaultSession
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Creates thumbnails without keeping plaintext thumbnails in persistent storage.
 * Temporary plaintext files are created only in a private temp directory and
 * deleted best-effort on every exit path.
 */
class SecureThumbnailEngine(
    private val session: UnlockedVaultSession,
    private val thumbnailDirectory: Path,
    private val tempRoot: Path = Path.of(System.getProperty("java.io.tmpdir"))
) {
    fun createEncryptedThumbnail(encryptedMedia: Path, thumbnailId: String, maxEdge: Int = 360): Path {
        require(maxEdge in 64..2048) { "Thumbnail size is outside the allowed range." }
        Files.createDirectories(thumbnailDirectory)
        Files.createDirectories(tempRoot)
        val privateTemp = Files.createTempDirectory(tempRoot, "plw-thumb-")
        val plainMedia = privateTemp.resolve("media.tmp")
        val plainThumbnail = privateTemp.resolve("thumbnail.png")
        val encryptedThumbnail = thumbnailDirectory.resolve("$thumbnailId.plwthumb")
        try {
            session.withKey { key ->
                VaultFileCrypto.decryptFile(encryptedMedia, plainMedia, key)
                val image = ImageIO.read(plainMedia.toFile())
                    ?: throw IllegalArgumentException("Unsupported or invalid image file.")
                val scaled = scale(image, maxEdge)
                check(ImageIO.write(scaled, "png", plainThumbnail.toFile())) { "PNG encoder unavailable." }
                VaultFileCrypto.encryptFile(plainThumbnail, encryptedThumbnail, key)
            }
            return encryptedThumbnail
        } catch (e: Exception) {
            Files.deleteIfExists(encryptedThumbnail)
            throw e
        } finally {
            deleteTreeBestEffort(privateTemp)
        }
    }

    fun loadEncryptedThumbnailPreview(encryptedThumbnail: Path): BufferedImage {
        val privateTemp = Files.createTempDirectory(tempRoot, "plw-thumb-view-")
        val plain = privateTemp.resolve("preview.png")
        try {
            session.withKey { key -> VaultFileCrypto.decryptFile(encryptedThumbnail, plain, key) }
            return ImageIO.read(plain.toFile()) ?: throw IllegalArgumentException("Thumbnail image is invalid.")
        } finally {
            deleteTreeBestEffort(privateTemp)
        }
    }

    private fun scale(source: BufferedImage, maxEdge: Int): BufferedImage {
        val largest = maxOf(source.width, source.height)
        val ratio = if (largest <= maxEdge) 1.0 else maxEdge.toDouble() / largest
        val width = maxOf(1, (source.width * ratio).toInt())
        val height = maxOf(1, (source.height * ratio).toInt())
        val target = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = target.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.drawImage(source, 0, 0, width, height, null)
        } finally {
            graphics.dispose()
        }
        return target
    }

    private fun deleteTreeBestEffort(path: Path) {
        try {
            Files.walk(path).use { walk ->
                walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        } catch (_: Exception) { /* best-effort cleanup; temp files are OS-managed */ }
    }

    private inline fun java.awt.Graphics2D.useGraphics(block: java.awt.Graphics2D.() -> Unit) {
        try { block() } finally { dispose() }
    }
}
