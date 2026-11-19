package com.winsmediatama.photolocker.windows

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.awt.SwingPanel
import javax.swing.JLabel
import javax.swing.ImageIcon
import javax.imageio.ImageIO
import com.winsmediatama.photolocker.windows.core.media.SecureMediaViewerTemp
import com.winsmediatama.photolocker.windows.core.media.SecureThumbnailEngine
import com.winsmediatama.photolocker.windows.core.backup.VaultBackupEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.key.onKeyEvent
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
    Window(onCloseRequest = { exitApplication() }, title = "Photo-Locker Windows") {
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
    var lastActivity by remember { mutableStateOf(System.currentTimeMillis()) }
    DisposableEffect(vaultSession) { onDispose { vaultSession?.lock() } }
    LaunchedEffect(vaultSession) {
        while (vaultSession?.isUnlocked == true) {
            kotlinx.coroutines.delay(1000)
            if (System.currentTimeMillis() - lastActivity >= 5 * 60 * 1000L) {
                vaultSession?.lock()
                vaultSession = null
                selectedPage = "Home"
                break
            }
        }
    }
    Row(Modifier.fillMaxSize().onKeyEvent { lastActivity = System.currentTimeMillis(); false }.pointerInput(Unit) {
        detectTapGestures(onPress = { lastActivity = System.currentTimeMillis() })
    }) {
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
                "Backup" -> BackupPage(vaultSession, { vaultSession = it })
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
    var selectedVaultFolder by remember { mutableStateOf<java.nio.file.Path?>(null) }
    val store = remember(keyFile) { WindowsVaultKeyStore(keyFile) }
    fun getSession(): UnlockedVaultSession {
        check(System.getProperty("os.name").lowercase().contains("windows")) { "Vault operations require Windows DPAPI." }
        return session ?: UnlockedVaultSession(store).also { setSession(it) }
    }
    LaunchedEffect(session) { if (session == null && store.exists()) vaultStatus = "Vault status: Locked (idle timeout or manual lock)" }
    val vaultExists = store.exists()
    val passwordProtected = vaultExists && store.isPasswordProtected()
    if (operation != null) {
        AlertDialog(
            onDismissRequest = { operation = null; password = ""; confirmPassword = "" },
            title = { Text(when(operation) { "create" -> "Create Vault Password"; "unlock" -> "Unlock Vault"; else -> "Protect Existing Vault" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(when(operation) {
                        "create" -> "Set a password of at least 8 characters. Vault location: ${selectedVaultFolder?.toString() ?: WindowsAppPaths.vaultDirectory().toString()}. Keep the password safe; forgetting it may permanently prevent access to encrypted media."
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
            Text("Important: uninstalling the application should not delete your vault. Vault data is stored separately in your Windows user AppData folder. Back up your vault before reinstalling Windows or deleting user data.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.tertiary)
            Text(vaultStatus, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(enabled=!vaultExists && session?.isUnlocked!=true, onClick={
                    val chooser = JFileChooser().apply { dialogTitle = "Choose vault storage folder (another drive is supported)"; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY; isAcceptAllFileFilterUsed = false }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        selectedVaultFolder = chooser.selectedFile.toPath()
                        try { WindowsAppPaths.setVaultDirectory(selectedVaultFolder!!); operation = "create" }
                        catch (e: Exception) { message = "Could not set vault folder: ${e.message ?: e.javaClass.simpleName}" }
                    }
                }) { Text("Create Vault") }
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
    var albums by remember { mutableStateOf(emptyList<com.winsmediatama.photolocker.windows.core.database.AlbumRecord>()) }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var menuMediaId by remember { mutableStateOf<String?>(null) }
    var viewerTemp by remember { mutableStateOf<SecureMediaViewerTemp?>(null) }
    var viewerError by remember { mutableStateOf("") }
    var viewerTitle by remember { mutableStateOf("") }
    var thumbnailImages by remember { mutableStateOf<Map<String, java.awt.image.BufferedImage>>(emptyMap()) }
    var thumbnailMessage by remember { mutableStateOf("") }
    DisposableEffect(viewerTemp) { onDispose { viewerTemp?.close() } }
    LaunchedEffect(session) { if (session?.isUnlocked != true) { viewerTemp?.close(); viewerTemp = null } }
    LaunchedEffect(session) {
        records = emptyList(); albums = emptyList(); error = ""; status = ""
        if (session?.isUnlocked == true) {
            try {
                val db = VaultDatabase(WindowsAppPaths.databaseFile(), session)
                records = db.listMedia()
                albums = db.listAlbums()
            } catch (e: Exception) { error = e.message ?: "Could not load gallery." }
        }
    }
    LaunchedEffect(session, records) {
        thumbnailImages = emptyMap()
        thumbnailMessage = ""
        if (session?.isUnlocked == true) {
            thumbnailImages = withContext(Dispatchers.IO) {
                val engine = SecureThumbnailEngine(session, WindowsAppPaths.thumbnailDirectory())
                val loaded = mutableMapOf<String, java.awt.image.BufferedImage>()
                records.filter { it.mimeType.startsWith("image/") }.forEach { media ->
                    try {
                        val source = WindowsAppPaths.encryptedMediaDirectory().resolve(media.encryptedRelativePath.substringAfterLast('/'))
                        val encryptedThumb = WindowsAppPaths.thumbnailDirectory().resolve("${media.id}.plwthumb")
                        if (!java.nio.file.Files.exists(encryptedThumb)) engine.createEncryptedThumbnail(source, media.id)
                        loaded[media.id] = engine.loadEncryptedThumbnailPreview(encryptedThumb)
                    } catch (_: Exception) { /* unsupported image format or preview failure; viewer remains available */ }
                }
                loaded
            }
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Encrypted media library", style = MaterialTheme.typography.titleLarge)
        Text("Thumbnails are stored encrypted; preview images are loaded into memory after temporary-file cleanup.", style = MaterialTheme.typography.bodySmall)
        if (session?.isUnlocked != true) {
            Text("Unlock your vault from Home to view imported media.")
        } else if (error.isNotBlank()) {
            Text(error, color = MaterialTheme.colorScheme.error)
        } else if (records.isEmpty()) {
            Text("No media imported yet. Use Home → Import Photos / Videos.")
        } else {
            Text("${records.size} item(s) • Album assignment available")
            if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.primary)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(records) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            thumbnailImages[item.id]?.let { preview ->
                                SwingPanel(modifier = Modifier.size(120.dp), factory = { JLabel(ImageIcon(preview)) })
                            }
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.originalName, style = MaterialTheme.typography.titleMedium)
                                Text(item.mimeType)
                                Text("Size: ${item.fileSize} bytes")
                                Text("Imported: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(item.importedAt))}")
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                if (item.mimeType.startsWith("image/")) {
                                    TextButton(onClick = {
                                        viewerError = ""
                                        viewerTitle = item.originalName
                                        viewerTemp = try {
                                            val encrypted = WindowsAppPaths.encryptedMediaDirectory().resolve(item.encryptedRelativePath.substringAfterLast('/'))
                                            SecureMediaViewerTemp.open(session, encrypted)
                                        } catch (ex: Exception) {
                                            viewerError = ex.message ?: "Unable to open photo."
                                            null
                                        }
                                    }) { Text("View photo") }
                                }
                                Box {
                                    OutlinedButton(onClick = { menuMediaId = item.id }, enabled = albums.isNotEmpty()) {
                                        Text("Add to album")
                                    }
                                    DropdownMenu(expanded = menuMediaId == item.id, onDismissRequest = { menuMediaId = null }) {
                                        albums.forEach { album ->
                                            DropdownMenuItem(
                                                text = { Text(album.name) },
                                                onClick = {
                                                    status = try {
                                                        VaultDatabase(WindowsAppPaths.databaseFile(), session).addMediaToAlbum(item.id, album.id)
                                                        "Added to album: ${album.name}"
                                                    } catch (ex: Exception) { ex.message ?: "Album assignment failed." }
                                                    menuMediaId = null
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (albums.isEmpty()) Text("Create an album first in the Albums page to organize media.")
        }
    }
    if (viewerTemp != null) {
        Dialog(onDismissRequest = { viewerTemp?.close(); viewerTemp = null }) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(viewerTitle, style = MaterialTheme.typography.titleMedium)
                    SwingPanel(
                        modifier = Modifier.fillMaxWidth().height(520.dp),
                        factory = { JLabel(ImageIcon(ImageIO.read(viewerTemp!!.path.toFile()))).apply { horizontalAlignment = JLabel.CENTER } }
                    )
                    Button(onClick = { viewerTemp?.close(); viewerTemp = null }, modifier = Modifier.align(Alignment.End)) { Text("Close photo") }
                }
            }
        }
    }
    if (viewerError.isNotBlank()) AlertDialog(onDismissRequest={viewerError=""}, title={Text("Photo viewer")}, text={Text(viewerError)}, confirmButton={TextButton(onClick={viewerError=""}){Text("OK")}})
}

@Composable
private fun AlbumsPage(session: UnlockedVaultSession?) {
    var albums by remember { mutableStateOf(emptyList<com.winsmediatama.photolocker.windows.core.database.AlbumRecord>()) }
    var albumName by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var selectedAlbum by remember { mutableStateOf<com.winsmediatama.photolocker.windows.core.database.AlbumRecord?>(null) }
    var renameAlbumId by remember { mutableStateOf<String?>(null) }
    var renameValue by remember { mutableStateOf("") }
    var albumMedia by remember { mutableStateOf(emptyList<com.winsmediatama.photolocker.windows.core.database.MediaRecord>()) }
    fun reload() {
        if (session?.isUnlocked == true) {
            albums = try { VaultDatabase(WindowsAppPaths.databaseFile(), session).listAlbums() }
            catch (e: Exception) { message = e.message ?: "Could not load albums."; emptyList() }
        } else albums = emptyList()
    }
    LaunchedEffect(session) { reload(); selectedAlbum = null; albumMedia = emptyList() }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Albums", style = MaterialTheme.typography.titleLarge)
        if (session?.isUnlocked != true) {
            Text("Unlock your vault from Home to manage albums.")
        } else if (selectedAlbum != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { selectedAlbum = null; albumMedia = emptyList() }) { Text("Back to Albums") }
                Text(selectedAlbum!!.name, style = MaterialTheme.typography.titleMedium)
            }
            Text("${albumMedia.size} media item(s) in this album")
            if (albumMedia.isEmpty()) Text("This album is empty. Add media from Gallery using Add to album.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(albumMedia) { media ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(media.originalName, style = MaterialTheme.typography.titleMedium)
                            Text(media.mimeType)
                            Text("Size: ${media.fileSize} bytes")
                            TextButton(onClick = {
                                try {
                                    VaultDatabase(WindowsAppPaths.databaseFile(), session).removeMediaFromAlbum(media.id, selectedAlbum!!.id)
                                    albumMedia = VaultDatabase(WindowsAppPaths.databaseFile(), session).listMediaForAlbum(selectedAlbum!!.id)
                                    message = "Removed from album. The encrypted media file was not deleted."
                                } catch (e: Exception) { message = e.message ?: "Could not remove media from album." }
                            }) { Text("Remove from album") }
                        }
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = albumName, onValueChange = { albumName = it }, label = { Text("Album name") }, modifier = Modifier.weight(1f))
                Button(onClick = {
                    message = try {
                        VaultDatabase(WindowsAppPaths.databaseFile(), session).createAlbum(albumName)
                        albumName = ""; reload(); "Album created."
                    } catch (e: Exception) { e.message ?: "Could not create album." }
                }, enabled = albumName.trim().isNotEmpty()) { Text("Create") }
            }
            if (message.isNotBlank()) Text(message)
            if (albums.isEmpty()) Text("No albums created yet.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(albums) { album ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(album.name, style = MaterialTheme.typography.titleMedium)
                                Text("Created: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(album.createdAt))}")
                            }
                            OutlinedButton(onClick = {
                                selectedAlbum = album
                                albumMedia = try { VaultDatabase(WindowsAppPaths.databaseFile(), session).listMediaForAlbum(album.id) }
                                catch (e: Exception) { message = e.message ?: "Could not open album."; emptyList() }
                            }) { Text("Open") }
                            OutlinedButton(onClick = { renameAlbumId = album.id; renameValue = album.name }) { Text("Rename") }
                            OutlinedButton(onClick = {
                                try {
                                    VaultDatabase(WindowsAppPaths.databaseFile(), session).deleteAlbum(album.id)
                                    reload()
                                    message = "Album deleted. Media files remain in the vault."
                                } catch (e: Exception) { message = e.message ?: "Could not delete album." }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    if (renameAlbumId != null) {
        AlertDialog(
            onDismissRequest = { renameAlbumId = null },
            title = { Text("Rename album") },
            text = { OutlinedTextField(value = renameValue, onValueChange = { renameValue = it }, label = { Text("New album name") }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    try {
                        VaultDatabase(WindowsAppPaths.databaseFile(), session!!).renameAlbum(renameAlbumId!!, renameValue)
                        renameAlbumId = null
                        reload()
                        message = "Album renamed."
                    } catch (e: Exception) { message = e.message ?: "Could not rename album." }
                }, enabled = renameValue.trim().isNotEmpty()) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameAlbumId = null }) { Text("Cancel") } }
        )
    }

}

@Composable
private fun BackupPage(session: UnlockedVaultSession?, setSession: (UnlockedVaultSession?) -> Unit) {
    var message by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Encrypted vault backup", style = MaterialTheme.typography.titleLarge)
        Text("Backup contains encrypted media, encrypted metadata fields, the password-protected vault-key blob, and an integrity manifest. It is not a plaintext export.")
        Button(enabled = session?.isUnlocked == true, onClick = {
            val chooser = JFileChooser().apply { dialogTitle = "Save Photo-Locker backup"; selectedFile = java.io.File("Photo-Locker-Backup.plwbackup") }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                message = try {
                    VaultBackupEngine.createBackup(WindowsAppPaths.vaultDirectory(), chooser.selectedFile.toPath())
                    "Backup created with SHA-256 manifest."
                } catch (e: Exception) { "Backup failed: ${e.message ?: e.javaClass.simpleName}" }
            }
        }) { Text("Create Backup") }
        OutlinedButton(onClick = {
            val fileChooser = JFileChooser().apply { dialogTitle = "Select Photo-Locker backup"; fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Photo-Locker Backup", "plwbackup") }
            if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val folderChooser = JFileChooser().apply { dialogTitle = "Choose empty restore destination folder"; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                if (folderChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    val target = folderChooser.selectedFile.toPath()
                    message = try {
                        VaultBackupEngine.restoreBackup(fileChooser.selectedFile.toPath(), target)
                        WindowsAppPaths.setVaultDirectory(target)
                        session?.lock()
                        setSession(null)
                        "Restore complete and integrity checks passed. Unlock the restored vault from Home."
                    } catch (e: Exception) { "Restore failed: ${e.message ?: e.javaClass.simpleName}" }
                }
            }
        }) { Text("Restore Backup") }
        Text("Restore requires an empty destination folder. Keep the backup file in a separate drive or location.", style = MaterialTheme.typography.bodySmall)
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
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
