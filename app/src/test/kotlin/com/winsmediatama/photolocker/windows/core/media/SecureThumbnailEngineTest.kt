package com.winsmediatama.photolocker.windows.core.media

import com.winsmediatama.photolocker.windows.core.crypto.VaultFileCrypto
import com.winsmediatama.photolocker.windows.core.session.UnlockedVaultSession
import com.winsmediatama.photolocker.windows.platform.dpapi.WindowsVaultKeyStore
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureThumbnailEngineTest {
    @Test fun thumbnailIsEncryptedAndTemporaryPreviewIsCleaned() {
        val root = Files.createTempDirectory("plw-thumb-test")
        val store = WindowsVaultKeyStore(root.resolve("keyblob"))
        val session = UnlockedVaultSession(store)
        session.initialize("test-password-123".toCharArray())
        val source = root.resolve("photo.png")
        val image = BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB)
        ImageIO.write(image, "png", source.toFile())
        val encryptedMedia = root.resolve("media.plwenc")
        session.withKey { key -> VaultFileCrypto.encryptFile(source, encryptedMedia, key) }
        val tempRoot = root.resolve("tmp")
        val engine = SecureThumbnailEngine(session, root.resolve("thumbs"), tempRoot)
        val encryptedThumb = engine.createEncryptedThumbnail(encryptedMedia, "item-1", 240)
        assertTrue(Files.readAllBytes(encryptedThumb).take(8).toByteArray().toString(Charsets.US_ASCII).startsWith("PLWENC"))
        val preview = engine.loadEncryptedThumbnailPreview(encryptedThumb)
        assertEquals(240, maxOf(preview.width, preview.height))
        assertFalse(Files.exists(tempRoot) && Files.list(tempRoot).use { it.findAny().isPresent })
        session.lock()
    }

    @Test fun viewerTempIsRemovedWhenClosed() {
        val root = Files.createTempDirectory("plw-view-test")
        val store = WindowsVaultKeyStore(root.resolve("keyblob"))
        val session = UnlockedVaultSession(store)
        session.initialize("test-password-123".toCharArray())
        val source = root.resolve("file")
        Files.write(source, "sample".toByteArray())
        val encrypted = root.resolve("encrypted")
        session.withKey { key -> VaultFileCrypto.encryptFile(source, encrypted, key) }
        val temp = SecureMediaViewerTemp.open(session, encrypted, root.resolve("temp"))
        assertTrue(Files.exists(temp.path))
        temp.close()
        assertFalse(Files.exists(temp.path))
        session.lock()
    }
}
