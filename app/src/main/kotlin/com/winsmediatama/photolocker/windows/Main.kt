package com.winsmediatama.photolocker.windows

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.winsmediatama.photolocker.windows.core.session.UnlockedVaultSession
import com.winsmediatama.photolocker.windows.core.database.VaultDatabase
import com.winsmediatama.photolocker.windows.core.importer.MediaImportEngine
import com.winsmediatama.photolocker.windows.platform.dpapi.WindowsVaultKeyStore
import com.winsmediatama.photolocker.windows.platform.storage.WindowsAppPaths
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Photo-Locker Windows") {
        MaterialTheme {
            PhotoLockerDesktopApp()
        }
    }
}

@Composable
private fun PhotoLockerDesktopApp() {
    var selectedPage by remember { mutableStateOf("Home") }
    val pages = listOf("Home", "Gallery", "Albums", "Backup", "Security", "Settings")
    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(220.dp).fillMaxHeight().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("PHOTO-LOCKER", style = MaterialTheme.typography.titleLarge)
            Text("Windows Edition", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(16.dp))
            pages.forEach { page ->
                NavigationDrawerItem(label = { Text(page) }, selected = selectedPage == page,
                    onClick = { selectedPage = page }, modifier = Modifier.fillMaxWidth())
            }
        }
        VerticalDivider()
        Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(selectedPage, style = MaterialTheme.typography.headlineMedium)
            when (selectedPage) {
                "Home" -> HomePage()
                "Security" -> SecurityPage()
                else -> PlaceholderPage(selectedPage)
            }
        }
    }
}

@Composable
private fun HomePage() {
    var vaultStatus by remember { mutableStateOf("Vault status: Locked / Not initialized") }
    var message by remember { mutableStateOf("") }
    var session by remember { mutableStateOf<UnlockedVaultSession?>(null) }
    var importedCount by remember { mutableStateOf(0) }
    fun getSession(): UnlockedVaultSession {
        check(System.getProperty("os.name").lowercase().contains("windows")) {
            "Vault initialization and unlock require Windows DPAPI."
        }
        val store = WindowsVaultKeyStore(WindowsAppPaths.keyFile())
        return session ?: UnlockedVaultSession(store).also { session = it }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your private media vault", style = MaterialTheme.typography.titleLarge)
            Text("Local-first photo and video protection. Encryption core uses AES-256-GCM and Windows user-bound DPAPI key protection.")
            Text(vaultStatus, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    message = try {
                        WindowsAppPaths.ensureVaultDirectories()
                        getSession().initialize()
                        vaultStatus = "Vault status: Unlocked (new vault)"
                        "Vault initialized. Key created and protected by Windows DPAPI."
                    } catch (e: Exception) {
                        "Initialization failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                }) { Text("Create Vault") }
                OutlinedButton(onClick = {
                    message = try {
                        getSession().unlock()
                        vaultStatus = "Vault status: Unlocked"
                        "Vault unlocked successfully."
                    } catch (e: Exception) {
                        "Unlock failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                }) { Text("Unlock") }
                OutlinedButton(onClick = {
                    session?.lock()
                    vaultStatus = "Vault status: Locked"
                    message = "Vault locked. In-memory key was cleared (best effort)."
                }) { Text("Lock") }
            }
            if (vaultStatus.contains("Unlocked")) {
                OutlinedButton(onClick = {
                    message = try {
                        val chooser = JFileChooser().apply {
                            isMultiSelectionEnabled = true
                            dialogTitle = "Import photos and videos into encrypted vault"
                            fileFilter = FileNameExtensionFilter("Media files", "jpg", "jpeg", "png", "webp", "gif", "heic", "heif", "mp4", "mov", "mkv", "avi")
                        }
                        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                            val db = VaultDatabase(WindowsAppPaths.databaseFile(), getSession())
                            db.initialize()
                            val engine = MediaImportEngine(getSession(), db, WindowsAppPaths.encryptedMediaDirectory())
                            val results = chooser.selectedFiles.map { engine.importFile(it.toPath()) }
                            importedCount = db.countMedia()
                            "Encrypted import completed: ${results.size} file(s)."
                        } else "Import cancelled."
                    } catch (e: Exception) {
                        "Import failed: ${e.message ?: e.javaClass.simpleName}"
                    }
                }) { Text("Import Photos / Videos") }
                Text("Encrypted media count: $importedCount")
                Text("Metadata warning: filenames and media metadata currently reside in an unencrypted SQLite database.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FeatureCard("Encrypted Vault", "AES-256-GCM encryption")
        FeatureCard("Windows Key Protection", "DPAPI current-user protection")
        FeatureCard("Portable Backup", "Android .plb compatibility planned")
    }
}

@Composable
private fun SecurityPage() {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Security architecture", style = MaterialTheme.typography.titleLarge)
        Text("• AES-256-GCM authenticated file encryption")
        Text("• 256-bit vault key")
        Text("• Windows DPAPI protected key file")
        Text("• Key held in memory only while unlocked (best-effort JVM zeroization)")
        Text("• UI vault initialization, unlock, and lock controls integrated")
        Text("PIN/password authentication and secure thumbnail/cache lifecycle are not yet implemented. SQLite metadata is currently stored unencrypted; encrypted media import is implemented.")
    }
}

@Composable
private fun RowScope.FeatureCard(title: String, subtitle: String) {
    Card(Modifier.weight(1f)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PlaceholderPage(page: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$page module is planned for a future milestone.", style = MaterialTheme.typography.bodyLarge)
    }
}
