package io.github.rymos.turbotube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import io.github.rymos.turbotube.core.update.AppUpdate
import io.github.rymos.turbotube.update.UpdateState

/**
 * A strip across the top of the screen that offers a new build right away — at every start and
 * whenever YouTube and Twitch are switched, so the update is never buried in the menu. It comes
 * up already focused, so one press of OK installs it; Back or "Später" keeps the current build.
 */
@Composable
fun UpdateBanner(
    state: UpdateState,
    currentBuild: Int,
    onInstall: (AppUpdate) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val firstButton = remember { FocusRequester() }
    val update = (state as? UpdateState.Available)?.update
    val downloading = state as? UpdateState.Downloading
    val installing = state is UpdateState.Installing
    val needsPermission = state is UpdateState.NeedsPermission
    val failed = state as? UpdateState.Failed

    // The whole point of the strip: the remote lands on "install" without any searching
    LaunchedEffect(update?.buildNumber, needsPermission, failed?.message) {
        if (update != null || needsPermission || failed != null) {
            runCatching { firstButton.requestFocus() }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier
                    .padding(top = 20.dp, start = 32.dp, end = 32.dp)
                    .widthIn(max = 960.dp)
                    .background(Color(0xF21B1B1B), RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0xFF3C3C3C), RoundedCornerShape(14.dp))
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            update != null -> "Update verfügbar: Build ${update.buildNumber}"
                            downloading != null ->
                                "Update wird geladen … ${(downloading.progress * 100).toInt()} %"
                            installing -> "Installation wird gestartet …"
                            needsPermission -> "Turbotube darf noch keine Apps installieren"
                            failed != null -> "Update fehlgeschlagen"
                            else -> "Update"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Text(
                        when {
                            update != null ->
                                "Installiert ist Build $currentBuild. Hier direkt aktualisieren, ohne das Menü."
                            downloading != null -> "Bitte warten, gleich fragt Android nach der Bestätigung."
                            installing -> "Android zeigt gleich den Installationsdialog."
                            needsPermission ->
                                "Einstellungen → Apps → Sicherheit und Einschränkungen → " +
                                    "Unbekannte Quellen → Turbotube einschalten, dann hier nochmal."
                            failed != null -> failed.message
                            else -> ""
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFBBBBBB),
                    )
                }
                when {
                    update != null -> {
                        Button(
                            onClick = { onInstall(update) },
                            modifier = Modifier.focusRequester(firstButton),
                        ) { Text("Jetzt installieren") }
                        Button(onClick = onClose) { Text("Später") }
                    }
                    needsPermission || failed != null -> {
                        Button(
                            onClick = onRetry,
                            modifier = Modifier.focusRequester(firstButton),
                        ) { Text("Nochmal") }
                        Button(onClick = onClose) { Text("Später") }
                    }
                    else -> Unit
                }
            }
        }
    }
}
