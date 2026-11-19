package com.winsmediatama.photolocker.windows.core.database

import com.winsmediatama.photolocker.windows.core.session.UnlockedVaultSession
import com.winsmediatama.photolocker.windows.core.crypto.VaultMetadataCrypto
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * SQLite metadata store. Database metadata itself is currently NOT encrypted.
 * All public operations require an unlocked vault session.
 */
class VaultDatabase(
    private val databasePath: Path,
    private val session: UnlockedVaultSession
) {
    private fun <T> guarded(block: () -> T): T = session.withKey { block() }

    private fun connection(): Connection {
        Files.createDirectories(databasePath.parent)
        return DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath()}").also { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys=ON")
                st.execute("PRAGMA journal_mode=WAL")
                st.execute("PRAGMA busy_timeout=5000")
            }
        }
    }

    fun initialize() = guarded {
        connection().use { conn ->
            conn.createStatement().use { st ->
                st.execute("""
                    CREATE TABLE IF NOT EXISTS media (
                        id TEXT PRIMARY KEY,
                        original_name TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        file_size INTEGER NOT NULL,
                        imported_at INTEGER NOT NULL,
                        encrypted_relative_path TEXT NOT NULL UNIQUE,
                        metadata_encrypted INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                st.execute("""
                    CREATE TABLE IF NOT EXISTS albums (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL UNIQUE,
                        created_at INTEGER NOT NULL,
                        metadata_encrypted INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                st.execute("""
                    CREATE TABLE IF NOT EXISTS media_album (
                        media_id TEXT NOT NULL REFERENCES media(id) ON DELETE CASCADE,
                        album_id TEXT NOT NULL REFERENCES albums(id) ON DELETE CASCADE,
                        PRIMARY KEY(media_id, album_id)
                    )
                """.trimIndent())
                st.execute("CREATE INDEX IF NOT EXISTS idx_media_imported_at ON media(imported_at DESC)")
                ensureColumn(conn, "media", "metadata_encrypted", "INTEGER NOT NULL DEFAULT 0")
                ensureColumn(conn, "albums", "metadata_encrypted", "INTEGER NOT NULL DEFAULT 0")
                migratePlaintextMetadata(conn)

            }
        }
    }

    private fun ensureColumn(conn: Connection, table: String, column: String, definition: String) {
        val found = conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                var exists = false
                while (rs.next()) if (rs.getString("name") == column) exists = true
                exists
            }
        }
        if (!found) conn.createStatement().use { it.execute("ALTER TABLE $table ADD COLUMN $column $definition") }
    }

    private fun migratePlaintextMetadata(conn: Connection) {
        session.withKey { key ->
            conn.prepareStatement("SELECT id, original_name, mime_type, encrypted_relative_path FROM media WHERE metadata_encrypted=0").use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<Array<String>>()
                    while (rs.next()) rows.add(arrayOf(rs.getString("id"), rs.getString("original_name"), rs.getString("mime_type"), rs.getString("encrypted_relative_path")))
                    rows.forEach { row ->
                        conn.prepareStatement("UPDATE media SET original_name=?, mime_type=?, encrypted_relative_path=?, metadata_encrypted=1 WHERE id=?").use { up ->
                            up.setString(1, VaultMetadataCrypto.encrypt(row[1], key, "media.name"))
                            up.setString(2, VaultMetadataCrypto.encrypt(row[2], key, "media.mime"))
                            up.setString(3, VaultMetadataCrypto.encrypt(row[3], key, "media.path"))
                            up.setString(4, row[0]); up.executeUpdate()
                        }
                    }
                }
            }
            conn.prepareStatement("SELECT id, name FROM albums WHERE metadata_encrypted=0").use { ps ->
                ps.executeQuery().use { rs ->
                    val rows = mutableListOf<Pair<String,String>>()
                    while (rs.next()) rows.add(rs.getString("id") to rs.getString("name"))
                    rows.forEach { (id,name) ->
                        conn.prepareStatement("UPDATE albums SET name=?, metadata_encrypted=1 WHERE id=?").use { up ->
                            up.setString(1, VaultMetadataCrypto.encrypt(name, key, "album.name"))
                            up.setString(2, id); up.executeUpdate()
                        }
                    }
                }
            }
        }
    }

    fun insertMedia(
        id: String,
        originalName: String,
        mimeType: String,
        fileSize: Long,
        importedAt: Long,
        encryptedRelativePath: String
    ) = guarded {
        connection().use { conn ->
            conn.prepareStatement("""
                INSERT INTO media(id, original_name, mime_type, file_size, imported_at, encrypted_relative_path)
                VALUES(?,?,?,?,?,?)
            """.trimIndent()).use { ps ->
                ps.setString(1, id)
                session.withKey { key ->
                    ps.setString(2, VaultMetadataCrypto.encrypt(originalName, key, "media.name"))
                    ps.setString(3, VaultMetadataCrypto.encrypt(mimeType, key, "media.mime"))
                }
                ps.setLong(4, fileSize)
                ps.setLong(5, importedAt)
                session.withKey { key -> ps.setString(6, VaultMetadataCrypto.encrypt(encryptedRelativePath, key, "media.path")) }
                ps.setInt(7, 1)
                ps.executeUpdate()
            }
        }
    }

    fun listMedia(): List<MediaRecord> = guarded {
        connection().use { conn ->
            conn.prepareStatement("""
                SELECT id, original_name, mime_type, file_size, imported_at, encrypted_relative_path, metadata_encrypted
                FROM media ORDER BY imported_at DESC
            """.trimIndent()).use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(MediaRecord(
                            id = rs.getString("id"),
                            originalName = session.withKey { key -> VaultMetadataCrypto.decrypt(rs.getString("original_name"), key, "media.name") },
                            mimeType = session.withKey { key -> VaultMetadataCrypto.decrypt(rs.getString("mime_type"), key, "media.mime") },
                            fileSize = rs.getLong("file_size"),
                            importedAt = rs.getLong("imported_at"),
                            encryptedRelativePath = session.withKey { key -> VaultMetadataCrypto.decrypt(rs.getString("encrypted_relative_path"), key, "media.path") }
                        ))
                    }
                }
            }
        }
    }


    fun listAlbums(): List<AlbumRecord> = guarded {
        connection().use { conn ->
            conn.prepareStatement("SELECT id, name, created_at FROM albums ORDER BY created_at DESC").use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(AlbumRecord(rs.getString("id"), session.withKey { key -> VaultMetadataCrypto.decrypt(rs.getString("name"), key, "album.name") }, rs.getLong("created_at")))
                    }
                }
            }
        }
    }

    fun createAlbum(name: String): AlbumRecord = guarded {
        val cleanName = name.trim().take(128)
        require(cleanName.isNotBlank()) { "Album name cannot be empty." }
        val album = AlbumRecord(UUID.randomUUID().toString(), cleanName, System.currentTimeMillis())
        require(listAlbums().none { it.name.equals(cleanName, ignoreCase = true) }) { "An album with this name already exists." }
        connection().use { conn ->
            conn.prepareStatement("INSERT INTO albums(id, name, created_at, metadata_encrypted) VALUES(?,?,?,1)").use { ps ->
                ps.setString(1, album.id)
                session.withKey { key -> ps.setString(2, VaultMetadataCrypto.encrypt(album.name, key, "album.name")) }
                ps.setLong(3, album.createdAt)
                ps.executeUpdate()
            }
        }
        album
    }

    fun addMediaToAlbum(mediaId: String, albumId: String) = guarded {
        connection().use { conn ->
            conn.prepareStatement("INSERT OR IGNORE INTO media_album(media_id, album_id) VALUES(?,?)").use { ps ->
                ps.setString(1, mediaId)
                ps.setString(2, albumId)
                ps.executeUpdate()
            }
        }
    }

    fun renameAlbum(albumId: String, newName: String) = guarded {
        val clean = newName.trim().take(128)
        require(clean.isNotBlank()) { "Album name cannot be empty." }
        require(listAlbums().none { it.id != albumId && it.name.equals(clean, ignoreCase = true) }) { "An album with this name already exists." }
        connection().use { conn ->
            conn.prepareStatement("UPDATE albums SET name=? WHERE id=?").use { ps ->
                session.withKey { key -> ps.setString(1, VaultMetadataCrypto.encrypt(clean, key, "album.name")) }
                ps.setString(2, albumId)
                require(ps.executeUpdate() == 1) { "Album not found." }
            }
        }
    }

    fun removeMediaFromAlbum(mediaId: String, albumId: String) = guarded {
        connection().use { conn ->
            conn.prepareStatement("DELETE FROM media_album WHERE media_id=? AND album_id=?").use { ps ->
                ps.setString(1, mediaId)
                ps.setString(2, albumId)
                ps.executeUpdate()
            }
        }
    }

    fun deleteAlbum(albumId: String) = guarded {
        connection().use { conn ->
            conn.prepareStatement("DELETE FROM albums WHERE id=?").use { ps ->
                ps.setString(1, albumId)
                require(ps.executeUpdate() == 1) { "Album not found." }
            }
        }
    }

    fun listMediaForAlbum(albumId: String): List<MediaRecord> = guarded {
        connection().use { conn ->
            conn.prepareStatement("""
                SELECT m.id, m.original_name, m.mime_type, m.file_size, m.imported_at, m.encrypted_relative_path
                FROM media m
                INNER JOIN media_album ma ON ma.media_id = m.id
                WHERE ma.album_id = ?
                ORDER BY m.imported_at DESC
            """.trimIndent()).use { ps ->
                ps.setString(1, albumId)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(MediaRecord(
                            id = rs.getString("id"),
                            originalName = session.withKey { key -> VaultMetadataCrypto.decrypt(rs.getString("original_name"), key, "media.name") },
                            mimeType = session.withKey { key -> VaultMetadataCrypto.decrypt(rs.getString("mime_type"), key, "media.mime") },
                            fileSize = rs.getLong("file_size"),
                            importedAt = rs.getLong("imported_at"),
                            encryptedRelativePath = session.withKey { key -> VaultMetadataCrypto.decrypt(rs.getString("encrypted_relative_path"), key, "media.path") }
                        ))
                    }
                }
            }
        }
    }

    fun countMedia(): Int = guarded {
        connection().use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM media").use { rs ->
                    if (rs.next()) rs.getInt(1) else 0
                }
            }
        }
    }

    companion object {
        fun newMediaId(): String = UUID.randomUUID().toString()
    }
}

data class AlbumRecord(val id: String, val name: String, val createdAt: Long)

data class MediaRecord(
    val id: String,
    val originalName: String,
    val mimeType: String,
    val fileSize: Long,
    val importedAt: Long,
    val encryptedRelativePath: String
)
