package io.github.rymos.turbotube.twitch

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

/** One adjustable row, shared by the settings page and the in-player settings. */
@Composable
internal fun Section(title: String) {
    Text(
        title.uppercase(),
        color = TwitchColors.Purple,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 22.dp, bottom = 4.dp),
    )
}

@Composable
internal fun SettingRow(
    label: String,
    value: String,
    enabled: Boolean,
    accent: Boolean = false,
    onFocusLost: () -> Unit = {},
    focusRequester: FocusRequester? = null,
    onChange: (Int) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (focused && !it.isFocused) onFocusLost()
                focused = it.isFocused
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) {
                    return@onKeyEvent false
                }
                when (event.key) {
                    Key.DirectionLeft -> {
                        onChange(-1)
                        true
                    }
                    Key.DirectionRight, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        onChange(1)
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .background(
                when {
                    focused -> TwitchColors.SurfaceHigh
                    else -> Color.Transparent
                },
                RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            label,
            color = when {
                focused -> Color.White
                accent -> Color(0xFFFFB4A9)
                else -> TwitchColors.Text
            },
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (value.isNotEmpty()) {
            Text(
                value,
                color = if (focused) TwitchColors.Purple else TwitchColors.TextMuted,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}
