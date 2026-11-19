package com.winsmediatama.photolocker.windows.core.backup

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable backup of already-encrypted vault files with SHA-256 integrity manifest. */
object VaultBackupEngine {
    private const val MANIFEST = "manifest.sha256"

    fun createBackup(vaultDirectory: Path, outputFile: Path, password: CharArray) {
        require(Files.isDirectory(vaultDirectory)) { "Vault folder does not exist." }
        val files = Files.walk(vaultDirectory).use { walk ->
            walk.filter { Files.isRegularFile(it) && !it.startsWith(vaultDirectory.resolve(".temporary")) && !it.fileName.toString().endsWith(".plwbackup", ignoreCase = true) }.toList()
        }.sortedBy { vaultDirectory.relativize(it).toString() }
        require(files.isNotEmpty()) { "Vault contains no files to back up." }
        val manifestLines = files.map { file ->
            val relative = vaultDirectory.relativize(file).toString().replace('\\', '/')
            "${sha256(file)}  ${Base64.getEncoder().encodeToString(relative.toByteArray(Charsets.UTF_8))}"
        }
        val tempRoot = vaultDirectory.resolve(".temporary")
        Files.createDirectories(tempRoot)
        val zipTemp = Files.createTempFile(tempRoot, "backup-", ".zip")
        try {
        ZipOutputStream(Files.newOutputStream(zipTemp)).use { zip ->
            files.forEach { file ->
                val relative = vaultDirectory.relativize(file).toString().replace('\\', '/')
                zip.putNextEntry(ZipEntry(relative))
                Files.copy(file, zip)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(manifestLines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        PasswordBackupCrypto.encrypt(zipTemp, outputFile, password, tempRoot)
        } finally { Files.deleteIfExists(zipTemp); try { Files.deleteIfExists(tempRoot) } catch (_: Exception) { } }
    }

    /** Restore only into an empty destination. Decrypts outer backup and verifies manifest before publishing files. */
    fun restoreBackup(backupFile: Path, destination: Path, password: CharArray) {
        require(Files.isRegularFile(backupFile)) { "Backup file not found." }
        require(!Files.exists(destination) || Files.list(destination).use { it.findAny().isEmpty }) {
            "Restore destination must be absent or an empty directory."
        }
        val tempRoot = destination.resolve(".temporary")
        Files.createDirectories(tempRoot)
        val zipTemp = Files.createTempFile(tempRoot, "restore-", ".zip")
        val staging = destination.resolveSibling(destination.fileName.toString() + ".restore-staging")
        try {
            PasswordBackupCrypto.decrypt(backupFile, zipTemp, password, tempRoot)
            if (Files.exists(staging)) deleteTree(staging)
            Files.createDirectories(staging)
            ZipInputStream(Files.newInputStream(zipTemp)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name.replace('\\', '/')
                    require(name.isNotBlank() && !name.startsWith("/") && !name.contains("../") && name != "..") { "Unsafe backup path." }
                    val target = staging.resolve(name).normalize()
                    require(target.startsWith(staging.normalize())) { "Backup path escapes destination." }
                    if (!entry.isDirectory) {
                        Files.createDirectories(target.parent)
                        Files.copy(zip, target)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            val manifest = staging.resolve(MANIFEST)
            require(Files.isRegularFile(manifest)) { "Backup manifest is missing." }
            val expected = Files.readAllLines(manifest).filter { it.isNotBlank() }.associate { line ->
                val parts = line.split("  ", limit = 2)
                require(parts.size == 2) { "Invalid backup manifest entry." }
                val path = String(Base64.getDecoder().decode(parts[1]), Charsets.UTF_8).replace('\\', '/')
                require(path.isNotBlank() && !path.startsWith("/") && !path.contains("../")) { "Unsafe manifest path." }
                path to parts[0]
            }
            expected.forEach { (path, hash) ->
                val file = staging.resolve(path).normalize()
                require(file.startsWith(staging.normalize()) && Files.isRegularFile(file)) { "Backup file is missing: $path" }
                require(sha256(file).equals(hash, ignoreCase = true)) { "Backup integrity check failed: $path" }
            }
            val actual = Files.walk(staging).use { walk ->
                walk.filter { Files.isRegularFile(it) && it != manifest }.map { staging.relativize(it).toString().replace('\\', '/') }.toList().toSet()
            }
            require(actual == expected.keys) { "Backup contains unlisted files or manifest entries." }
            Files.deleteIfExists(manifest)
            Files.createDirectories(destination)
            Files.walk(staging).use { walk ->
                walk.filter { it != staging }.sorted().forEach { source ->
                    val target = destination.resolve(staging.relativize(source))
                    if (Files.isDirectory(source)) Files.createDirectories(target)
                    else {
                        Files.createDirectories(target.parent)
                        Files.move(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            deleteTree(staging)
        } catch (e: Exception) {
            deleteTree(staging)
            throw e
        } finally { Files.deleteIfExists(zipTemp) }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
    }
}
