package io.github.rymos.turbotube.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import io.github.rymos.turbotube.update.UpdateState
import io.github.rymos.turbotube.update.Updater
import kotlinx.coroutines.launch

/** Turbotube's own menu: updates, reload, YouTube or Twitch, exit. Opened by holding Back. */
@Composable
fun TurbotubeMenu(
    updater: Updater,
    twitchMode: Boolean,
    adBlockLoaded: Boolean,
    onReload: () -> Unit,
    onSwitch: () -> Unit,
    onExit: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val firstButton = remember { FocusRequester() }
    val state = updater.state
    val pending = (state as? UpdateState.Available)?.update ?: updater.lastFound
    val busy = state is UpdateState.Checking || state is UpdateState.Downloading ||
        state is UpdateState.Installing

    LaunchedEffect(Unit) { runCatching { firstButton.requestFocus() } }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            Modifier.fillMaxSize().background(Color(0xCC000000)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                Modifier
                    .widthIn(max = 760.dp)
                    .background(Color(0xFF1F1F1F), RoundedCornerShape(16.dp))
                    .padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Turbotube", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text(
                    statusText(state, updater.currentBuild),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFDDDDDD),
                )
                // The Twitch filter is part of the app itself, so it is always there
                val filterOk = twitchMode || adBlockLoaded
                Text(
                    when {
                        twitchMode -> "Twitch-Werbefilter: aktiv"
                        adBlockLoaded -> "Werbeblocker: aktiv"
                        else -> "Werbeblocker: nicht geladen, bitte Turbotube neu starten"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (filterOk) Color(0xFF8BD18B) else Color(0xFFFFB4A9),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // One button that changes its label, so the focus stays on it when the
                    // search finds an update. It is never disabled for the same reason.
                    Button(
                        onClick = {
                            if (!busy) {
                                scope.launch {
                                    if (pending != null) updater.install(pending) else updater.check()
                                }
                            }
                        },
                        modifier = Modifier.focusRequester(firstButton),
                    ) { Text(if (pending != null) "Update installieren" else "Nach Updates suchen") }
                    Button(onClick = onReload) { Text(if (twitchMode) "Twitch neu laden" else "YouTube neu laden") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onSwitch) { Text(if (twitchMode) "Zu YouTube wechseln" else "Zu Twitch wechseln") }
                    Button(onClick = onClose) { Text(if (twitchMode) "Zurück zu Twitch" else "Zurück zu YouTube") }
                    Button(onClick = onExit) { Text("Beenden") }
                }
                Text(
                    "Dieses Menü öffnest du jederzeit, indem du die Zurück-Taste gedrückt hältst. " +
                        if (twitchMode) {
                            "Favoriten speicherst du im Stream mit OK."
                        } else {
                            "Werbeblocker und SponsorBlock stellst du in YouTube unter Einstellungen ein."
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFAAAAAA),
                )
            }
        }
    }
}

private fun statusText(state: UpdateState, currentBuild: Int): String = when (state) {
    UpdateState.Idle -> "Installiert: Build $currentBuild"
    UpdateState.Checking -> "Suche nach Updates …"
    UpdateState.UpToDate -> "Du hast die neueste Version (Build $currentBuild)."
    is UpdateState.Available ->
        "Neue Version verfügbar: Build ${state.update.buildNumber} (installiert: $currentBuild)"
    is UpdateState.Downloading ->
        "Lädt Build ${state.update.buildNumber} … ${(state.progress * 100).toInt()} %"
    UpdateState.Installing -> "Installation wird gestartet …"
    UpdateState.NeedsPermission ->
        "Turbotube braucht einmalig die Erlaubnis, Apps zu installieren: Einstellungen → Apps → " +
            "Sicherheit und Einschränkungen → Unbekannte Quellen → Turbotube einschalten. " +
            "Danach hier nochmal auf „Update installieren“ drücken."
    is UpdateState.Failed -> state.message
}
