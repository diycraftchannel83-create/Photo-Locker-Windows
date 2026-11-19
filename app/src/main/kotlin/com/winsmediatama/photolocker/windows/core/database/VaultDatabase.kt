package com.winsmediatama.photolocker.windows.core.database

import com.winsmediatama.photolocker.windows.core.session.UnlockedVaultSession
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
                        encrypted_relative_path TEXT NOT NULL UNIQUE
                    )
                """.trimIndent())
                st.execute("""
                    CREATE TABLE IF NOT EXISTS albums (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL UNIQUE,
                        created_at INTEGER NOT NULL
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
                ps.setString(2, originalName)
                ps.setString(3, mimeType)
                ps.setLong(4, fileSize)
                ps.setLong(5, importedAt)
                ps.setString(6, encryptedRelativePath)
                ps.executeUpdate()
            }
        }
    }

    fun listMedia(): List<MediaRecord> = guarded {
        connection().use { conn ->
            conn.prepareStatement("""
                SELECT id, original_name, mime_type, file_size, imported_at, encrypted_relative_path
                FROM media ORDER BY imported_at DESC
            """.trimIndent()).use { ps ->
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(MediaRecord(
                            id = rs.getString("id"),
                            originalName = rs.getString("original_name"),
                            mimeType = rs.getString("mime_type"),
                            fileSize = rs.getLong("file_size"),
                            importedAt = rs.getLong("imported_at"),
                            encryptedRelativePath = rs.getString("encrypted_relative_path")
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

data class MediaRecord(
    val id: String,
    val originalName: String,
    val mimeType: String,
    val fileSize: Long,
    val importedAt: Long,
    val encryptedRelativePath: String
)
