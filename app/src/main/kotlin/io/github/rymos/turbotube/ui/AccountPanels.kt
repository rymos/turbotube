package io.github.rymos.turbotube.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.rymos.turbotube.core.Account
import kotlinx.coroutines.launch

@Composable
fun AccountPanel() {
    val container = LocalContainer.current
    val cache = LocalListCache.current
    val scope = rememberCoroutineScope()
    if (!container.loggedIn) {
        Text("Nicht angemeldet. Ohne Anmeldung gehen Suche, Kanäle und Entdecken.")
        return
    }
    var account by remember { mutableStateOf<Account?>(null) }
    LaunchedEffect(Unit) { account = runCatching { container.accountApi.account() }.getOrNull() }

    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = account?.avatarUrl,
            contentDescription = null,
            modifier = Modifier.size(64.dp).clip(CircleShape),
        )
        Column {
            Text("Angemeldet als", style = MaterialTheme.typography.bodyMedium)
            Text(account?.name ?: "…", style = MaterialTheme.typography.titleLarge)
        }
        Button(onClick = {
            scope.launch {
                container.auth.logout()
                container.onLoginChanged()
                cache.clear()
            }
        }) {
            Text("Abmelden")
        }
    }
}

@Composable
fun QualityPanel() {
    val container = LocalContainer.current
    var quality by remember { mutableIntStateOf(container.settings.maxQuality) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Höchste Videoqualität", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(0 to "Automatisch", 2160 to "4K", 1440 to "1440p", 1080 to "1080p", 720 to "720p")
                .forEach { (value, label) ->
                    FilterChip(
                        selected = quality == value,
                        onClick = {
                            quality = value
                            container.settings.maxQuality = value
                        },
                    ) { Text(label) }
                }
        }
    }
}
