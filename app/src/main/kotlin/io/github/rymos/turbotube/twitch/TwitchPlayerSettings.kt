package io.github.rymos.turbotube.twitch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/**
 * Settings on top of the running stream: every change is visible in the picture and the chat
 * right away, which is what makes overscan, picture format and chat size adjustable at all.
 */
@Composable
fun PlayerSettingsPanel(controller: TwitchController) {
    val settings = controller.settings
    val enabled = controller.interactive
    val firstRow = remember { FocusRequester() }

    LaunchedEffect(Unit) { runCatching { firstRow.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color(0x66000000))) {
        Column(
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(560.dp)
                .background(Color(0xF218181B))
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Text("Player-Einstellungen", color = TwitchColors.Text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "Alles wirkt sofort im Bild. Zurück schließt diese Leiste.",
                color = TwitchColors.TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "picture") { Section("Bild") }
                item(key = "quality") {
                    SettingRow(
                        label = "Qualität",
                        value = settings.quality.text,
                        enabled = enabled,
                        focusRequester = firstRow,
                        onChange = { settings.quality.next(it) },
                    )
                }
                item(key = "fit") {
                    SettingRow("Bildformat", settings.pictureFit.label, enabled) { settings.pictureFit.next(it) }
                }
                item(key = "overscan") {
                    SettingRow("Rand (Overscan)", settings.overscan.label, enabled) { settings.overscan.next(it) }
                }

                item(key = "chat") { Section("Chat") }
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
                    SettingRow("Hintergrund", settings.chatOpacity.label, enabled) { settings.chatOpacity.next(it) }
                }
                item(key = "chat-emotes") {
                    SettingRow(
                        label = "Emotes",
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
                        label = "Uhrzeit",
                        value = if (settings.chatTimestamps.value) "An" else "Aus",
                        enabled = enabled,
                        onChange = { settings.chatTimestamps.toggle() },
                    )
                }

                item(key = "bar") { Section("Leiste") }
                item(key = "infobar") {
                    SettingRow("Infoleiste aus nach", settings.infoBar.label, enabled) { settings.infoBar.next(it) }
                }
                item(key = "close") {
                    SettingRow(
                        label = "Fertig",
                        value = "",
                        enabled = enabled,
                        onChange = { controller.closePlayerSettings() },
                    )
                }
            }
        }
    }
}
