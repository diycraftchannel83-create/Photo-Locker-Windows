package com.winsmediatama.photolocker.windows

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

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
                else -> PlaceholderPage(selectedPage)
            }
        }
    }
}

@Composable
private fun HomePage() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Your private media vault", style = MaterialTheme.typography.titleLarge)
            Text("Keep photos and videos protected locally on this PC.")
            Text("Security foundation is under development. This initial desktop shell does not yet store or encrypt media.", color = MaterialTheme.colorScheme.error)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FeatureCard("Encrypted Vault", "AES-256-GCM planned")
        FeatureCard("Windows Key Protection", "DPAPI integration planned")
        FeatureCard("Portable Backup", "Android .plb compatibility planned")
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
