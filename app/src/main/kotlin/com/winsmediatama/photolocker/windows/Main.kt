package com.winsmediatama.photolocker.windows

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
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
    try { WindowsAppPaths.cleanupTemporaryFiles() } catch (_: Exception) { }
    Window(onCloseRequest = { exitApplication() }, title = "Photo-Locker Windows") {
        PhotoLockerDesktopApp()
    }
}

private data class WindowsThemeInfo(val dark: Boolean, val accent: Color)

private fun readWindowsThemeInfo(): WindowsThemeInfo {
    fun queryRegistry(key: String, value: String): String? = try {
        val process = ProcessBuilder("reg", "query", key, "/v", value)
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        output.lines().firstOrNull { it.contains(value, ignoreCase = true) }
            ?.substringAfterLast("REG_DWORD", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    val lightSetting = queryRegistry(
        "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        "AppsUseLightTheme"
    )
    val lightValue = lightSetting?.let { raw ->
        raw.removePrefix("0x").toLongOrNull(16)?.toInt()
    }
    val dark = lightValue?.let { it == 0 } ?: false

    val accentRaw = queryRegistry("HKCU\\Software\\Microsoft\\Windows\\DWM", "ColorizationColor")
    val accent = accentRaw?.removePrefix("0x")?.toLongOrNull(16)?.let { argb ->
        Color(
            red = ((argb shr 16) and 0xFF).toFloat() / 255f,
            green = ((argb shr 8) and 0xFF).toFloat() / 255f,
            blue = (argb and 0xFF).toFloat() / 255f,
            alpha = 1f
        )
    } ?: Color(0xFF0078D4)
    return WindowsThemeInfo(dark, accent)
}

private fun loadThemePreference(): String = try {
    java.util.prefs.Preferences.userNodeForPackage(PhotoLockerDesktopAppMarker::class.java).get("themeMode", "System")
} catch (_: Exception) { "System" }
private object PhotoLockerDesktopAppMarker
private fun saveThemePreference(value: String) {
    try { java.util.prefs.Preferences.userNodeForPackage(PhotoLockerDesktopAppMarker::class.java).put("themeMode", value) } catch (_: Exception) { }
}
@Composable
private fun WindowsAdaptiveTheme(themeMode: String, content: @Composable () -> Unit) {
    var info by remember { mutableStateOf(readWindowsThemeInfo()) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(5000); info = readWindowsThemeInfo() }
    }
    val dark = when(themeMode) { "Dark" -> true; "Light" -> false; else -> info.dark }
    val accent = info.accent
    val scheme = if (dark) darkColorScheme(primary=accent,onPrimary=Color.White,secondary=accent,onSecondary=Color.White,
        tertiary=accent,surface=Color(0xFF202020),background=Color(0xFF111111),surfaceVariant=Color(0xFF2B2B2B),
        onSurface=Color(0xFFE6E6E6),onSurfaceVariant=Color(0xFFC8C8C8),outline=Color(0xFF6D6D6D))
    else lightColorScheme(primary=accent,onPrimary=Color.White,secondary=accent,onSecondary=Color.White,tertiary=accent,
        surface=Color(0xFFFDFDFD),background=Color(0xFFF3F3F3),surfaceVariant=Color(0xFFE5E5E5),
        onSurface=Color(0xFF1A1A1A),onSurfaceVariant=Color(0xFF454545),outline=Color(0xFF777777))
    MaterialTheme(colorScheme=scheme,content=content)
}

@Composable
private fun PhotoLockerDesktopApp() {
    var selectedPage by remember { mutableStateOf("Home") }
    var themeMode by remember { mutableStateOf(loadThemePreference()) }
    var vaultSession by remember { mutableStateOf<UnlockedVaultSession?>(null) }
    val pages = listOf("Home", "Gallery", "Albums", "Backup", "Security", "Settings")
    var lastActivity by remember { mutableStateOf(System.currentTimeMillis()) }
    DisposableEffect(vaultSession) { onDispose { vaultSession?.lock() } }
    LaunchedEffect(vaultSession) {
        if (vaultSession?.isUnlocked == true) lastActivity = System.currentTimeMillis()
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
    WindowsAdaptiveTheme(themeMode) {
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
                "Settings" -> SettingsPage(themeMode, { themeMode = it; saveThemePreference(it) })
                else -> PlaceholderPage(selectedPage)
            }
        }
    }
    }
}

@Composable
private fun SettingsPage(themeMode: String, onThemeChange: (String) -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleLarge)
        Text("Choose how Photo-Locker Windows looks.")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                listOf("System", "Light", "Dark").forEach { mode ->
                    Row(Modifier.fillMaxWidth().clickable { onThemeChange(mode) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = themeMode == mode, onClick = { onThemeChange(mode) })
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(mode)
                            Text(when(mode) {
                                "System" -> "Follow Windows light/dark appearance and accent color."
                                "Light" -> "Always use a bright theme."
                                else -> "Always use a dark theme."
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        Text("Photo viewer", style = MaterialTheme.typography.titleLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Viewer controls", style = MaterialTheme.typography.titleMedium)
                Text("Photos open in a large image-viewer window with Fit, 100%, zoom controls, navigation, and image information.")
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
                            dialogTitle="Move photos and videos into encrypted vault"
                            fileFilter=FileNameExtensionFilter("Media files","jpg","jpeg","png","webp","gif","heic","heif","mp4","mov","mkv","avi")
                        }
                        if(chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION) {
                            val selected=chooser.selectedFiles
                            val confirm=javax.swing.JOptionPane.showConfirmDialog(null,
                                "Selected ${selected.size} file(s) will be encrypted into your vault. Originals will be deleted only after successful import. Continue?",
                                "Move media into vault",javax.swing.JOptionPane.YES_NO_OPTION,javax.swing.JOptionPane.WARNING_MESSAGE)
                            if(confirm==javax.swing.JOptionPane.YES_OPTION) {
                                val db=VaultDatabase(WindowsAppPaths.databaseFile(),getSession()); db.initialize()
                                val engine=MediaImportEngine(getSession(),db,WindowsAppPaths.encryptedMediaDirectory())
                                var success=0; var failed=0
                                selected.forEach { file -> try { engine.importFile(file.toPath()); success++ } catch (_:Exception) { failed++ } }
                                importedCount=db.countMedia()
                                "Move completed: $success file(s) moved; $failed failed. Failed files remain at source."
                            } else "Move cancelled. Originals were not changed."
                        } else "Move cancelled."
                    } catch(e:Exception) { "Move failed: ${e.message ?: e.javaClass.simpleName}" }
                }) { Text("Move Photos / Videos into Vault") }
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
private fun ImageViewerDialog(
    title: String,
    image: java.awt.image.BufferedImage,
    onClose: () -> Unit,
    fileName: String = title,
    fileSize: Long? = null,
    onPrevious: (() -> Unit)? = null,
    onNext: (() -> Unit)? = null
) {
    var zoom by remember(image) { mutableStateOf(1f) }
    Dialog(onDismissRequest = onClose) {
        Surface(color = Color(0xFF171717), shape = MaterialTheme.shapes.large) {
            Column(Modifier.width(1000.dp).height(700.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { zoom = 1f }) { Text("Fit") }
                    TextButton(onClick = { zoom = 1f }) { Text("100%") }
                    TextButton(onClick = { zoom = (zoom * 1.25f).coerceAtMost(4f) }) { Text("+") }
                    TextButton(onClick = { zoom = (zoom / 1.25f).coerceAtLeast(0.2f) }) { Text("−") }
                    if (onPrevious != null) TextButton(onClick = onPrevious) { Text("‹") }
                    if (onNext != null) TextButton(onClick = onNext) { Text("›") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = image.toComposeImageBitmap(),
                        contentDescription = title,
                        modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom),
                        contentScale = ContentScale.Fit
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fileName, color = Color.White, maxLines = 1)
                    Text("Zoom: ${(zoom * 100).toInt()}%", color = Color.LightGray)
                    Text(fileSize?.let { "${it} bytes" } ?: "Photo", color = Color.LightGray)
                }
            }
        }
    }
}

@Composable
private fun GalleryPage(session: UnlockedVaultSession?) {
    var records by remember { mutableStateOf(emptyList<com.winsmediatama.photolocker.windows.core.database.MediaRecord>()) }
    var albums by remember { mutableStateOf(emptyList<com.winsmediatama.photolocker.windows.core.database.AlbumRecord>()) }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var menuMediaId by remember { mutableStateOf<String?>(null) }
    var viewerImage by remember { mutableStateOf<java.awt.image.BufferedImage?>(null) }
    var viewerError by remember { mutableStateOf("") }
    var viewerTitle by remember { mutableStateOf("") }
    var thumbnailImages by remember { mutableStateOf<Map<String, java.awt.image.BufferedImage>>(emptyMap()) }
    var thumbnailMessage by remember { mutableStateOf("") }
    LaunchedEffect(session) { if (session?.isUnlocked != true) { viewerImage = null } }
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
                val engine = SecureThumbnailEngine(session, WindowsAppPaths.thumbnailDirectory(), WindowsAppPaths.temporaryDirectory())
                val loaded = mutableMapOf<String, java.awt.image.BufferedImage>()
                var failed = 0
                records.filter { it.mimeType.startsWith("image/") }.forEach { media ->
                    try {
                        val source = WindowsAppPaths.encryptedMediaDirectory().resolve(media.encryptedRelativePath.substringAfterLast('/'))
                        val encryptedThumb = WindowsAppPaths.thumbnailDirectory().resolve("${media.id}.plwthumb")
                        var preview: java.awt.image.BufferedImage? = null
                        if (java.nio.file.Files.exists(encryptedThumb)) {
                            try { preview = engine.loadEncryptedThumbnailPreview(encryptedThumb) }
                            catch (_: Exception) { java.nio.file.Files.deleteIfExists(encryptedThumb) }
                        }
                        if (preview == null) {
                            engine.createEncryptedThumbnail(source, media.id)
                            preview = engine.loadEncryptedThumbnailPreview(encryptedThumb)
                        }
                        loaded[media.id] = preview
                    } catch (_: Exception) { failed++ }
                }
                thumbnailMessage = if (failed > 0) "$" + "failed photo thumbnail(s) could not be generated." else ""
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
            LazyVerticalGrid(columns = GridCells.Adaptive(190.dp), modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(records) { item ->
                    Card(Modifier.fillMaxWidth().clickable {
                        if (item.mimeType.startsWith("image/")) {
                            viewerError = ""
                            viewerTitle = item.originalName
                            viewerImage = try {
                                val encrypted = WindowsAppPaths.encryptedMediaDirectory().resolve(item.encryptedRelativePath.substringAfterLast('/'))
                                val temp = SecureMediaViewerTemp.open(session, encrypted, WindowsAppPaths.temporaryDirectory())
                                try { ImageIO.read(temp.path.toFile()) ?: throw IllegalArgumentException("Unsupported image format.") }
                                finally { temp.close() }
                            } catch (ex: Exception) { viewerError = ex.message ?: "Unable to open photo."; null }
                        }
                    }) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            thumbnailImages[item.id]?.let { preview ->
                                Image(bitmap=preview.toComposeImageBitmap(), contentDescription=item.originalName, modifier=Modifier.fillMaxWidth().height(150.dp))
                            } ?: Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment=Alignment.Center) { Text("No preview") }
                            Text(item.originalName, style=MaterialTheme.typography.titleSmall, maxLines=2)
                            Text(item.mimeType, style=MaterialTheme.typography.bodySmall)
                            Text("Size: ${item.fileSize} bytes", style=MaterialTheme.typography.bodySmall)
                            TextButton(onClick={menuMediaId=item.id}, enabled=albums.isNotEmpty() || item.mimeType.startsWith("image/")) { Text("Options") }
                            DropdownMenu(expanded=menuMediaId==item.id,onDismissRequest={menuMediaId=null}) {
                                if (item.mimeType.startsWith("image/")) {
                                    DropdownMenuItem(text={Text("Export photo from vault…")},onClick={
                                        menuMediaId=null
                                        val chooser=JFileChooser().apply {
                                            dialogTitle="Choose destination folder for exported photo"
                                            fileSelectionMode=JFileChooser.DIRECTORIES_ONLY
                                            isAcceptAllFileFilterUsed=false
                                        }
                                        if (chooser.showOpenDialog(null)==JFileChooser.APPROVE_OPTION) {
                                            status=try {
                                                val destinationFolder=chooser.selectedFile.toPath()
                                                val encrypted=WindowsAppPaths.encryptedMediaDirectory().resolve(item.encryptedRelativePath.substringAfterLast('/'))
                                                val temp=SecureMediaViewerTemp.open(session!!,encrypted,WindowsAppPaths.temporaryDirectory())
                                                try {
                                                    var target=destinationFolder.resolve(item.originalName)
                                                    if (java.nio.file.Files.exists(target)) {
                                                        val base=item.originalName.substringBeforeLast('.',item.originalName)
                                                        val ext=item.originalName.substringAfterLast('.', "")
                                                        var index=1
                                                        do {
                                                            val candidateName=if(ext.isBlank()) "$base (export $index)" else "$base (export $index).$ext"
                                                            target=destinationFolder.resolve(candidateName)
                                                            index++
                                                        } while(java.nio.file.Files.exists(target))
                                                    }
                                                    java.nio.file.Files.copy(temp.path,target)
                                                    "Photo exported to: $target"
                                                } finally { temp.close() }
                                            } catch(ex:Exception) { "Photo export failed: ${ex.message ?: ex.javaClass.simpleName}" }
                                        }
                                    })
                                }
                                albums.forEach { album ->
                                    DropdownMenuItem(text={Text("Add to album: ${album.name}")},onClick={
                                        status=try {
                                            VaultDatabase(WindowsAppPaths.databaseFile(),session).addMediaToAlbum(item.id,album.id)
                                            "Added to album: ${album.name}"
                                        } catch(ex:Exception){ex.message ?: "Album assignment failed."}
                                        menuMediaId=null
                                    })
                                }
                            }
                        }
                    }
                }
            }
            if (albums.isEmpty()) Text("Create an album first in the Albums page to organize media.")
        }
    }
    if (viewerImage != null) {
        ImageViewerDialog(
            title = viewerTitle,
            image = viewerImage!!,
            onClose = { viewerImage = null },
            fileName = viewerTitle,
            fileSize = records.firstOrNull { it.originalName == viewerTitle }?.fileSize
        )
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
    var albumThumbnails by remember { mutableStateOf<Map<String, java.awt.image.BufferedImage>>(emptyMap()) }
    var albumViewerImage by remember { mutableStateOf<java.awt.image.BufferedImage?>(null) }
    var albumViewerTitle by remember { mutableStateOf("") }
    var albumViewerError by remember { mutableStateOf("") }
    LaunchedEffect(session, selectedAlbum, albumMedia) {
        albumThumbnails = emptyMap()
        if (session?.isUnlocked == true && selectedAlbum != null) {
            albumThumbnails = withContext(Dispatchers.IO) {
                val engine = SecureThumbnailEngine(session, WindowsAppPaths.thumbnailDirectory(), WindowsAppPaths.temporaryDirectory())
                val result = mutableMapOf<String, java.awt.image.BufferedImage>()
                albumMedia.filter { it.mimeType.startsWith("image/") }.forEach { media ->
                    try {
                        val encryptedSource = WindowsAppPaths.encryptedMediaDirectory().resolve(media.encryptedRelativePath.substringAfterLast('/'))
                        val thumbFile = WindowsAppPaths.thumbnailDirectory().resolve("${media.id}.plwthumb")
                        var preview: java.awt.image.BufferedImage? = null
                        if (java.nio.file.Files.exists(thumbFile)) {
                            try { preview = engine.loadEncryptedThumbnailPreview(thumbFile) }
                            catch (_: Exception) { java.nio.file.Files.deleteIfExists(thumbFile) }
                        }
                        if (preview == null) {
                            engine.createEncryptedThumbnail(encryptedSource, media.id)
                            preview = engine.loadEncryptedThumbnailPreview(thumbFile)
                        }
                        result[media.id] = preview
                    } catch (_: Exception) { }
                }
                result
            }
        }
    }
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
            LazyVerticalGrid(columns = GridCells.Adaptive(190.dp), modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(albumMedia) { media ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            albumThumbnails[media.id]?.let { preview ->
                                Image(bitmap=preview.toComposeImageBitmap(),contentDescription=media.originalName,modifier=Modifier.fillMaxWidth().height(150.dp).clickable {
                                    albumViewerError = ""
                                    albumViewerTitle = media.originalName
                                    albumViewerImage = try {
                                        val encrypted = WindowsAppPaths.encryptedMediaDirectory().resolve(media.encryptedRelativePath.substringAfterLast('/'))
                                        val temp = SecureMediaViewerTemp.open(session, encrypted, WindowsAppPaths.temporaryDirectory())
                                        try { ImageIO.read(temp.path.toFile()) ?: throw IllegalArgumentException("Unsupported image format.") }
                                        finally { temp.close() }
                                    } catch(ex:Exception){ albumViewerError=ex.message ?: "Unable to open photo."; null }
                                })
                            } ?: Box(Modifier.fillMaxWidth().height(150.dp),contentAlignment=Alignment.Center){Text("No preview")}
                            Text(media.originalName,style=MaterialTheme.typography.titleSmall,maxLines=2)
                            Text(media.mimeType,style=MaterialTheme.typography.bodySmall)
                            Text("Size: ${media.fileSize} bytes",style=MaterialTheme.typography.bodySmall)
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
    if (albumViewerImage != null) {
        ImageViewerDialog(title=albumViewerTitle,image=albumViewerImage!!,onClose={albumViewerImage=null},
            fileName=albumViewerTitle,fileSize=albumMedia.firstOrNull{it.originalName==albumViewerTitle}?.fileSize)
    }
    if (albumViewerError.isNotBlank()) AlertDialog(onDismissRequest={albumViewerError=""},title={Text("Photo viewer")},text={Text(albumViewerError)},confirmButton={TextButton(onClick={albumViewerError=""}){Text("OK")}})
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
    var backupPassword by remember { mutableStateOf("") }
    var backupConfirm by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Encrypted vault backup", style = MaterialTheme.typography.titleLarge)
        Text("Backup contains encrypted media, encrypted metadata fields, the vault password-key blob, and an integrity manifest. The entire backup archive is password-encrypted.")
        OutlinedTextField(value=backupPassword,onValueChange={backupPassword=it},label={Text("Backup password (8+ characters)")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),singleLine=true)
        OutlinedTextField(value=backupConfirm,onValueChange={backupConfirm=it},label={Text("Confirm backup password")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),singleLine=true)
        Button(enabled = session?.isUnlocked == true && backupPassword.length>=8 && backupPassword==backupConfirm, onClick = {
            val chooser = JFileChooser().apply { dialogTitle = "Save Photo-Locker backup"; selectedFile = java.io.File("Photo-Locker-Backup.plwbackup") }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                message = try {
                    val secret = backupPassword.toCharArray()
                    try { VaultBackupEngine.createBackup(WindowsAppPaths.vaultDirectory(), chooser.selectedFile.toPath(), secret) }
                    finally { secret.fill('\u0000') }
                    backupPassword=""; backupConfirm=""
                    "Backup created: encrypted archive and SHA-256 manifest verified for source files."
                } catch (e: Exception) { "Backup failed: ${e.message ?: e.javaClass.simpleName}" }
            }
        }) { Text("Create Backup") }
        OutlinedTextField(value=restorePassword,onValueChange={restorePassword=it},label={Text("Backup password for restore")},visualTransformation=androidx.compose.ui.text.input.PasswordVisualTransformation(),singleLine=true)
        OutlinedButton(enabled=restorePassword.length>=8,onClick = {
            val fileChooser = JFileChooser().apply { dialogTitle = "Select Photo-Locker backup"; fileFilter = javax.swing.filechooser.FileNameExtensionFilter("Photo-Locker Backup", "plwbackup") }
            if (fileChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                val folderChooser = JFileChooser().apply { dialogTitle = "Choose empty restore destination folder"; fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                if (folderChooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    val target = folderChooser.selectedFile.toPath()
                    message = try {
                        val secret = restorePassword.toCharArray()
                        try { VaultBackupEngine.restoreBackup(fileChooser.selectedFile.toPath(), target, secret) }
                        finally { secret.fill('\u0000') }
                        restorePassword=""
                        WindowsAppPaths.setVaultDirectory(target)
                        session?.lock()
                        setSession(null)
                        "Restore complete and integrity checks passed. Unlock the restored vault from Home."
                    } catch (e: Exception) { "Restore failed: ${e.message ?: e.javaClass.simpleName}" }
                }
            }
        }) { Text("Restore Backup") }
        Text("Backup encryption uses a separate password (not necessarily the vault password). Restore requires an empty destination folder. Store the backup on a separate drive or location.", style = MaterialTheme.typography.bodySmall)
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
        Text("Vault password wrapping uses PBKDF2-HMAC-SHA256 with random salt and AES-GCM. Media filenames, MIME types, relative paths, and album names are AES-GCM encrypted in SQLite; structural metadata such as UUIDs, sizes, timestamps, and relations remains visible. Encrypted thumbnails and temporary photo viewer cleanup are implemented best-effort. Portable backups are separately password-encrypted and include SHA-256 integrity checks. Full Windows runtime, temporary-file, installer/uninstaller, and recovery validation remain pending.")
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
