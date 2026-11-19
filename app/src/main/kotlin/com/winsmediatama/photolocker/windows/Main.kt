package com.winsmediatama.photolocker.windows

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
    var vaultSession by remember { mutableStateOf<UnlockedVaultSession?>(null) }
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
                "Home" -> HomePage(vaultSession, { vaultSession = it })
                "Gallery" -> GalleryPage(vaultSession)
                "Albums" -> AlbumsPage(vaultSession)
                "Security" -> SecurityPage()
                else -> PlaceholderPage(selectedPage)
            }
        }
    }
}

@Composable
private fun HomePage(session: UnlockedVaultSession?, setSession: (UnlockedVaultSession?) -> Unit) {
    val keyFile = WindowsAppPaths.keyFile()
    var vaultStatus by remember { mutableStateOf(if (java.nio.file.Files.exists(keyFile)) "Vault status: Locked (vault exists)" else "Vault status: Not initialized") }
    var message by remember { mutableStateOf("") }
    var operation by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var importedCount by remember { mutableStateOf(0) }
    val store = remember { WindowsVaultKeyStore(keyFile) }
    fun getSession(): UnlockedVaultSession {
        check(System.getProperty("os.name").lowercase().contains("windows")) { "Vault operations require Windows DPAPI." }
        return session ?: UnlockedVaultSession(store).also { setSession(it) }
    }
    val vaultExists = store.exists()
    val passwordProtected = vaultExists && store.isPasswordProtected()
    if (operation != null) {
        AlertDialog(
            onDismissRequest = { operation = null; password = ""; confirmPassword = "" },
            title = { Text(when(operation) { "create" -> "Create Vault Password"; "unlock" -> "Unlock Vault"; else -> "Protect Existing Vault" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(when(operation) {
                        "create" -> "Set a password of at least 8 characters. Keep it safe: forgetting it may permanently prevent access to your encrypted media."
                        "unlock" -> "Enter your vault password."
                        else -> "This vault was created using Windows DPAPI. Set a password to migrate it. Existing encrypted files will not be changed."
                    })
                    OutlinedTextField(value=password,onValueChange={password=it},label={Text("Password")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),singleLine=true)
                    if(operation!="unlock") OutlinedTextField(value=confirmPassword,onValueChange={confirmPassword=it},label={Text("Confirm password")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),singleLine=true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    message = try {
                        val p = password.toCharArray()
                        val cp = confirmPassword.toCharArray()
                        try {
                            if(operation!="unlock") require(password == confirmPassword) { "Passwords do not match." }
                            val active = getSession()
                            when(operation) {
                                "create" -> { WindowsAppPaths.ensureVaultDirectories(); active.initialize(p) }
                                "unlock" -> active.unlock(p)
                                else -> active.migrateLegacyVault(p)
                            }
                            VaultDatabase(WindowsAppPaths.databaseFile(), active).initialize()
                            importedCount = VaultDatabase(WindowsAppPaths.databaseFile(), active).countMedia()
                            vaultStatus = "Vault status: Unlocked"
                            if(operation=="create") "Vault created and password protection enabled."
                            else if(operation=="unlock") "Vault unlocked."
                            else "Existing vault migrated to password protection. Please remember your password."
                        } finally { p.fill('\u0000'); cp.fill('\u0000') }
                    } catch(e:Exception) { "Vault operation failed: ${e.message ?: e.javaClass.simpleName}" }
                    password=""; confirmPassword=""; operation=null
                }, enabled=password.length>=8 && (operation=="unlock" || confirmPassword.length>=8) ) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick={operation=null;password="";confirmPassword=""}) { Text("Cancel") } }
        )
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your private media vault", style = MaterialTheme.typography.titleLarge)
            Text("Local-first photo and video protection. Media uses AES-256-GCM. Vault password is protected with PBKDF2-HMAC-SHA256 and AES-GCM.")
            Text(vaultStatus, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled=!vaultExists && session?.isUnlocked!=true, onClick={operation="create"}) { Text("Create Vault") }
                OutlinedButton(enabled=vaultExists && session?.isUnlocked!=true && passwordProtected, onClick={operation="unlock"}) { Text("Unlock") }
                OutlinedButton(enabled=vaultExists && session?.isUnlocked!=true && !passwordProtected, onClick={operation="migrate"}) { Text("Migrate Existing Vault") }
                OutlinedButton(enabled=session?.isUnlocked==true, onClick={
                    session?.lock()
                    vaultStatus="Vault status: Locked"
                    message="Vault locked. In-memory key cleared (best effort)."
                }) { Text("Lock") }
            }
            if(vaultExists && !passwordProtected && session?.isUnlocked!=true) Text("Legacy vault detected: unlock by migrating it to a new password. This keeps existing encrypted media intact.",color=MaterialTheme.colorScheme.tertiary)
            if(session?.isUnlocked==true) {
                OutlinedButton(onClick={
                    message=try {
                        val chooser=JFileChooser().apply {
                            isMultiSelectionEnabled=true
                            dialogTitle="Import photos and videos into encrypted vault"
                            fileFilter=FileNameExtensionFilter("Media files","jpg","jpeg","png","webp","gif","heic","heif","mp4","mov","mkv","avi")
                        }
                        if(chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION) {
                            val db=VaultDatabase(WindowsAppPaths.databaseFile(),getSession()); db.initialize()
                            val engine=MediaImportEngine(getSession(),db,WindowsAppPaths.encryptedMediaDirectory())
                            val results=chooser.selectedFiles.map{engine.importFile(it.toPath())}
                            importedCount=db.countMedia()
                            "Encrypted import completed: ${results.size} file(s)."
                        } else "Import cancelled."
                    } catch(e:Exception) { "Import failed: ${e.message ?: e.javaClass.simpleName}" }
                }) { Text("Import Photos / Videos") }
                Text("Encrypted media count: $importedCount")
                Text("Metadata warning: filenames and media metadata currently reside in unencrypted SQLite.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.error)
            }
            if(message.isNotBlank()) Text(message,color=MaterialTheme.colorScheme.primary)
        }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        FeatureCard("Encrypted Vault","AES-256-GCM encryption")
        FeatureCard("Password Protection","PBKDF2 + AES-GCM")
        FeatureCard("Portable Backup","Android .plb compatibility planned")
    }
}

@Composable
private fun GalleryPage(session: UnlockedVaultSession?) {
    var records by remember { mutableStateOf(emptyList<com.winsmediatama.photolocker.windows.core.database.MediaRecord>()) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(session) {
        records = emptyList()
        error = ""
        if (session?.isUnlocked == true) {
            try {
                records = VaultDatabase(WindowsAppPaths.databaseFile(), session).listMedia()
            } catch (e: Exception) {
                error = e.message ?: "Could not load gallery."
            }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Encrypted media library", style = MaterialTheme.typography.titleLarge)
        if (session?.isUnlocked != true) {
            Text("Unlock your vault from Home to view imported media metadata.")
        } else if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
        } else if (records.isEmpty()) {
            Text("No media imported yet. Use Home → Import Photos / Videos.")
        } else {
            Text("${records.size} item(s)")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(records) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(item.originalName, style = MaterialTheme.typography.titleMedium)
                            Text(item.mimeType)
                            Text("Size: ${item.fileSize} bytes")
                            Text("Imported: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.importedAt))}")
                            Text("ID: ${item.id}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumsPage(session: UnlockedVaultSession?) {
    var albums by remember { mutableStateOf(emptyList<com.winsmediatama.photolocker.windows.core.database.AlbumRecord>()) }
    var albumName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    fun reload() {
        if (session?.isUnlocked == true) {
            albums = try { VaultDatabase(WindowsAppPaths.databaseFile(), session).listAlbums() }
            catch (e: Exception) { message = e.message ?: "Could not load albums."; emptyList() }
        } else albums = emptyList()
    }
    LaunchedEffect(session) { reload() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Albums", style = MaterialTheme.typography.titleLarge)
        if (session?.isUnlocked != true) {
            Text("Unlock your vault from Home to manage albums.")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = albumName, onValueChange = { albumName = it }, label = { Text("Album name") }, modifier = Modifier.weight(1f))
                Button(onClick = {
                    message = try {
                        VaultDatabase(WindowsAppPaths.databaseFile(), session).createAlbum(albumName)
                        albumName = ""
                        reload()
                        "Album created."
                    } catch (e: Exception) { e.message ?: "Could not create album." }
                }, enabled = albumName.trim().isNotEmpty()) { Text("Create") }
            }
            if (message.isNotBlank()) Text(message)
            if (albums.isEmpty()) Text("No albums created yet.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(albums) { album ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(album.name, style = MaterialTheme.typography.titleMedium)
                            Text("Created: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(album.createdAt))}")
                        }
                    }
                }
            }
        }
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
        Text("Password protection now uses PBKDF2-HMAC-SHA256 with a random salt and AES-GCM wrapping for the vault key. Legacy DPAPI vaults can be migrated without re-encrypting media. SQLite metadata is still unencrypted, and secure thumbnail/cache lifecycle is not implemented.")
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
