package com.winsmediatama.photolocker.windows.core.database

import com.winsmediatama.photolocker.windows.core.session.UnlockedVaultSession
import com.winsmediatama.photolocker.windows.platform.dpapi.WindowsVaultKeyStore
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class VaultDatabaseMetadataTest {
    @Test fun newMediaAndAlbumMetadataAreEncryptedInSqlite() {
        val root = Files.createTempDirectory("plw-db-test")
        val store = WindowsVaultKeyStore(root.resolve("keyblob"))
        val session = UnlockedVaultSession(store)
        session.initialize("test-password-123".toCharArray())
        val dbPath = root.resolve("vault.db")
        val db = VaultDatabase(dbPath, session)
        db.initialize()
        db.insertMedia("media-id", "private.jpg", "image/jpeg", 123L, 456L, "media/private.plwenc")
        db.createAlbum("Secret album")
        assertEquals("private.jpg", db.listMedia().single().originalName)
        assertEquals("Secret album", db.listAlbums().single().name)
        DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT original_name, mime_type, encrypted_relative_path FROM media").use { rs ->
                    assertEquals(true, rs.next())
                    assertNotEquals("private.jpg", rs.getString(1))
                    assertNotEquals("image/jpeg", rs.getString(2))
                    assertNotEquals("media/private.plwenc", rs.getString(3))
                }
                st.executeQuery("SELECT name FROM albums").use { rs ->
                    assertEquals(true, rs.next())
                    assertNotEquals("Secret album", rs.getString(1))
                }
            }
        }
        session.lock()
    }
}
