package io.github.rymos.turbotube.twitch

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * The settings page: one row per adjustment. Left/Right changes a value, OK moves it forward,
 * so the whole page works with a plain remote.
 */
@Composable
fun TwitchSettingsPage(controller: TwitchController, enabled: Boolean) {
    val settings = controller.settings
    var confirmReset by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 32.dp, end = 64.dp, top = 32.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(key = "title") {
            Column(Modifier.padding(bottom = 12.dp)) {
                Text("Einstellungen", color = TwitchColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Links/Rechts ändert einen Wert, OK schaltet weiter. Alles wird sofort gespeichert.",
                    color = TwitchColors.TextMuted,
                    fontSize = 13.sp,
                )
            }
        }

        item(key = "chat-header") { Section("Chat") }
        item(key = "chat-visible") {
            SettingRow(
                label = "Chat anzeigen",
                value = if (settings.chatVisible.value) "An" else "Aus",
                enabled = enabled,
                onChange = { settings.chatVisible.toggle() },
            )
        }
        item(key = "chat-side") {
            SettingRow("Chat-Seite", settings.chatSide.label, enabled) { settings.chatSide.next(it) }
        }
        item(key = "chat-width") {
            SettingRow("Chat-Breite", settings.chatWidth.label, enabled) { settings.chatWidth.next(it) }
        }
        item(key = "chat-size") {
            SettingRow("Chat-Schrift", settings.chatTextSize.label, enabled) { settings.chatTextSize.next(it) }
        }
        item(key = "chat-density") {
            SettingRow("Zeilenabstand", settings.chatDensity.label, enabled) { settings.chatDensity.next(it) }
        }
        item(key = "chat-opacity") {
            SettingRow("Chat-Hintergrund", settings.chatOpacity.label, enabled) { settings.chatOpacity.next(it) }
        }
        item(key = "chat-colors") {
            SettingRow("Namensfarben", settings.chatPalette.label, enabled) { settings.chatPalette.next(it) }
        }
        item(key = "chat-emotes") {
            SettingRow(
                label = "Emotes anzeigen",
                value = if (settings.chatEmotes.value) "An" else "Aus",
                enabled = enabled,
                onChange = { settings.chatEmotes.toggle() },
            )
        }
        item(key = "emote-size") {
            SettingRow("Emote-Größe", settings.emoteSize.label, enabled) { settings.emoteSize.next(it) }
        }
        item(key = "chat-time") {
            SettingRow(
                label = "Uhrzeit vor der Nachricht",
                value = if (settings.chatTimestamps.value) "An" else "Aus",
                enabled = enabled,
                onChange = { settings.chatTimestamps.toggle() },
            )
        }
        item(key = "chat-scroll") {
            SettingRow(
                label = "Automatisch mitscrollen",
                value = if (settings.chatAutoScroll.value) "An" else "Aus",
                enabled = enabled,
                onChange = { settings.chatAutoScroll.toggle() },
            )
        }

        item(key = "player-header") { Section("Player") }
        item(key = "quality") {
            SettingRow("Qualität", settings.quality.text, enabled) { settings.quality.next(it) }
        }
        item(key = "fit") {
            SettingRow("Bildformat", settings.pictureFit.label, enabled) { settings.pictureFit.next(it) }
        }
        item(key = "overscan") {
            SettingRow("Rand (Overscan)", settings.overscan.label, enabled) { settings.overscan.next(it) }
        }
        item(key = "infobar") {
            SettingRow("Infoleiste ausblenden nach", settings.infoBar.label, enabled) { settings.infoBar.next(it) }
        }

        item(key = "home-header") { Section("Startseite") }
        item(key = "cards") {
            SettingRow("Kartengröße", settings.cardSize.label, enabled) { settings.cardSize.next(it) }
        }
        item(key = "hero") {
            SettingRow(
                label = "Großes Titelbild oben",
                value = if (settings.showHero.value) "An" else "Aus",
                enabled = enabled,
                onChange = { settings.showHero.toggle() },
            )
        }
        item(key = "viewers") {
            SettingRow(
                label = "Zuschauerzahlen",
                value = if (settings.showViewers.value) "An" else "Aus",
                enabled = enabled,
                onChange = { settings.showViewers.toggle() },
            )
        }

        item(key = "reset-header") { Section("Zurücksetzen") }
        item(key = "reset") {
            SettingRow(
                label = if (confirmReset) "Wirklich zurücksetzen?" else "Alle Einstellungen zurücksetzen",
                value = if (confirmReset) "OK = ja" else "",
                enabled = enabled,
                accent = confirmReset,
                onChange = {
                    if (confirmReset) {
                        settings.resetAll()
                        confirmReset = false
                    } else {
                        confirmReset = true
                    }
                },
                onFocusLost = { confirmReset = false },
            )
        }
    }
}
