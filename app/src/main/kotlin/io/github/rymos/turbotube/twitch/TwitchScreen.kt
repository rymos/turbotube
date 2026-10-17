package io.github.rymos.turbotube.twitch

import androidx.compose.animation.core.animateDpAsState
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import io.github.rymos.turbotube.core.twitch.TwitchCategory
import io.github.rymos.turbotube.core.twitch.TwitchChannel
import io.github.rymos.turbotube.ui.RemoteImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

private val RowCardWidth = 240.dp
private val PagePadding = 32.dp

/**
 * The Twitch side: a navigation rail on the left like Twitch's TV app, the pages next to it,
 * and the player on top when a stream runs. The pages stay underneath the player, so after the
 * stream the focus is back on the card it was started from.
 */
@Composable
fun TwitchScreen(controller: TwitchController) {
    LaunchedEffect(Unit) { controller.refresh() }

    val playing = controller.playing
    val browsing = controller.interactive && playing == null
    val railFocus = remember { FocusRequester() }

    // Back from a stream or out of the menu: to the card the stream came from, else to the rail
    LaunchedEffect(playing == null, controller.focusRequests) {
        if (playing == null) {
            val target = controller.returnFocus
            if (target == null || runCatching { target.requestFocus() }.isFailure) {
                runCatching { railFocus.requestFocus() }
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = TwitchColors.Purple, background = TwitchColors.Background)) {
        Box(Modifier.fillMaxSize().background(TwitchColors.Background)) {
            Row(Modifier.fillMaxSize()) {
                Rail(controller, browsing, railFocus)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (controller.page) {
                        TwitchPage.Home -> HomePage(controller, browsing)
                        TwitchPage.Following -> FollowingPage(controller, browsing)
                        TwitchPage.Browse -> {
                            val open = controller.category
                            if (open == null) BrowsePage(controller, browsing) else CategoryPage(controller, open, browsing)
                        }
                        TwitchPage.Search -> SearchPage(controller, browsing)
                        TwitchPage.Account -> AccountPage(controller, browsing)
                    }
                }
            }
            if (playing != null) TwitchPlayerScreen(controller, playing)
        }
    }
}

@Composable
private fun Rail(controller: TwitchController, enabled: Boolean, selectedFocus: FocusRequester) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) 220.dp else 72.dp, label = "rail")
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .background(TwitchColors.Surface)
            .onFocusChanged { expanded = it.hasFocus }
            .padding(vertical = 24.dp, horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.height(40.dp).padding(start = 10.dp), contentAlignment = Alignment.CenterStart) {
            Text(if (expanded) "Twitch" else "T", color = TwitchColors.Purple, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        TwitchPage.entries.forEach { page ->
            val selected = page == controller.page
            Surface(
                onClick = { controller.select(page) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .focusProperties { canFocus = enabled }
                    .then(if (selected) Modifier.focusRequester(selectedFocus) else Modifier),
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (selected) TwitchColors.SurfaceHigh else Color.Transparent,
                    contentColor = if (selected) TwitchColors.Text else TwitchColors.TextMuted,
                    focusedContainerColor = TwitchColors.Purple,
                    focusedContentColor = Color.White,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(page.icon(), contentDescription = page.label, modifier = Modifier.size(24.dp))
                    if (expanded) Text(page.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

private fun TwitchPage.icon(): ImageVector = when (this) {
    TwitchPage.Home -> Icons.Filled.Home
    TwitchPage.Following -> Icons.Filled.Favorite
    TwitchPage.Browse -> Icons.Filled.Menu
    TwitchPage.Search -> Icons.Filled.Search
    TwitchPage.Account -> Icons.Filled.Person
}

@Composable
private fun HomePage(controller: TwitchController, enabled: Boolean) {
    when (val load = controller.home) {
        Load.Loading -> Hint("Lädt …")
        is Load.Failed -> Hint(load.message)
        is Load.Done -> {
            val rows = load.value
            val play = { channel: TwitchChannel -> { from: FocusRequester -> controller.play(channel, from) } }
            LazyColumn(
                contentPadding = PaddingValues(vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                rows.popular.firstOrNull()?.let { hero ->
                    item(key = "hero") { Hero(hero, enabled, play(hero)) }
                }
                if (rows.following.isNotEmpty()) {
                    item(key = "following") { StreamRow("Live-Kanäle, denen du folgst", rows.following, enabled, play) }
                }
                if (rows.popular.size > 1) {
                    item(key = "popular") { StreamRow("Beliebte Live-Kanäle", rows.popular.drop(1), enabled, play) }
                }
                if (rows.categories.isNotEmpty()) {
                    item(key = "categories") {
                        Column {
                            RowTitle("Kategorien, die dir gefallen könnten")
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = PagePadding),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                items(rows.categories, key = { it.name }) {
                                    CategoryCard(it, enabled, { controller.open(it) }, width = 130.dp)
                                }
                            }
                        }
                    }
                }
                rows.byCategory.forEach { (category, streams) ->
                    item(key = "cat:${category.name}") { StreamRow("Live: ${category.displayName}", streams, enabled, play) }
                }
            }
        }
    }
}

/** The big first stream on the start page. */
@Composable
private fun Hero(channel: TwitchChannel, enabled: Boolean, onClick: (FocusRequester) -> Unit) {
    val requester = remember { FocusRequester() }
    Row(
        Modifier.padding(horizontal = PagePadding).height(220.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        FocusFrame(onClick = { onClick(requester) }, enabled = enabled, focusRequester = requester) {
            Box(Modifier.fillMaxHeight().aspectRatio(16f / 9f)) {
                RemoteImage(channel.previewUrl, Modifier.fillMaxSize())
                Badge("LIVE", TwitchColors.Live, Modifier.align(Alignment.TopStart).padding(10.dp))
            }
        }
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(channel.avatarUrl, 48.dp)
                Column {
                    Text(channel.displayName, color = TwitchColors.Purple, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(channel.game, color = TwitchColors.TextMuted, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(channel.title, color = TwitchColors.Text, fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Text(viewers(channel.viewers), color = TwitchColors.TextMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun StreamRow(
    title: String,
    streams: List<TwitchChannel>,
    enabled: Boolean,
    play: (TwitchChannel) -> (FocusRequester) -> Unit,
) {
    Column {
        RowTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = PagePadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(streams, key = { it.login }) { StreamCard(it, enabled, play(it), width = RowCardWidth) }
        }
    }
}

@Composable
private fun RowTitle(text: String) {
    Text(
        text,
        color = TwitchColors.Text,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = PagePadding, bottom = 12.dp),
    )
}

@Composable
private fun FollowingPage(controller: TwitchController, enabled: Boolean) {
    when (val load = controller.following) {
        Load.Loading -> Hint("Lädt …")
        is Load.Failed -> Hint(load.message)
        is Load.Done -> if (load.value.isEmpty()) {
            PageTitle("Folge ich")
            Hint(
                if (controller.account == null) {
                    "Du folgst noch niemandem. Verbinde unter „Konto“ dein Twitch-Konto, oder öffne einen Stream, drücke OK und wähle „Folgen“."
                } else {
                    "Dein Twitch-Konto folgt noch niemandem. Öffne einen Stream, drücke OK und wähle „Folgen“."
                },
            )
        } else {
            val live = load.value.filter { it.live }
            val offline = load.value.filter { !it.live }
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                contentPadding = PaddingValues(PagePadding),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { GridTitle("Folge ich") }
                if (live.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Live") }
                    items(live, key = { it.login }) { channel ->
                        StreamCard(channel, enabled, { controller.play(channel, it) })
                    }
                }
                if (offline.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Offline") }
                    items(offline, key = { "off:" + it.login }) { channel ->
                        OfflineCard(channel, enabled, { controller.play(channel, it) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowsePage(controller: TwitchController, enabled: Boolean) {
    when (val load = controller.categories) {
        Load.Loading -> Hint("Lädt …")
        is Load.Failed -> Hint(load.message)
        is Load.Done -> LazyVerticalGrid(
            columns = GridCells.Fixed(6),
            contentPadding = PaddingValues(PagePadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { GridTitle("Durchsuchen") }
            items(load.value, key = { it.name }) { CategoryCard(it, enabled, { controller.open(it) }) }
        }
    }
}

@Composable
private fun CategoryPage(controller: TwitchController, category: TwitchCategory, enabled: Boolean) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(PagePadding),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(category.boxArtUrl, Modifier.width(90.dp).aspectRatio(3f / 4f))
                Column {
                    Text(category.displayName, color = TwitchColors.Text, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(viewers(category.viewers), color = TwitchColors.TextMuted, fontSize = 14.sp)
                }
            }
        }
        when (val load = controller.categoryStreams) {
            Load.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { Hint("Lädt …") }
            is Load.Failed -> item(span = { GridItemSpan(maxLineSpan) }) { Hint(load.message) }
            is Load.Done -> items(load.value, key = { it.login }) { channel ->
                StreamCard(channel, enabled, { controller.play(channel, it) })
            }
        }
    }
}

@Composable
private fun SearchPage(controller: TwitchController, enabled: Boolean) {
    var focused by remember { mutableStateOf(false) }
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(PagePadding),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            BasicTextField(
                value = controller.query,
                onValueChange = { controller.query = it },
                singleLine = true,
                textStyle = TextStyle(color = TwitchColors.Text, fontSize = 18.sp),
                cursorBrush = SolidColor(TwitchColors.Text),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { controller.search() }),
                modifier = Modifier
                    .focusProperties { canFocus = enabled }
                    .fillMaxWidth()
                    .onFocusChanged { focused = it.isFocused }
                    .background(TwitchColors.SurfaceHigh, RoundedCornerShape(6.dp))
                    .border(2.dp, if (focused) TwitchColors.Purple else Color.Transparent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (controller.query.isEmpty()) {
                        Text("Suchen – OK drücken und Kanalnamen eingeben", color = TwitchColors.TextMuted, fontSize = 18.sp)
                    }
                    inner()
                },
            )
        }
        when (val load = controller.searchResults) {
            Load.Loading -> item(span = { GridItemSpan(maxLineSpan) }) { Hint("Sucht …") }
            is Load.Failed -> item(span = { GridItemSpan(maxLineSpan) }) { Hint(load.message) }
            is Load.Done -> if (load.value.isEmpty() && controller.query.isNotBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Hint("Keine Kanäle gefunden.") }
            } else {
                items(load.value, key = { it.login }) { channel ->
                    StreamCard(channel, enabled, { controller.play(channel, it) })
                }
            }
        }
    }
}

@Composable
private fun PageTitle(text: String) {
    Text(text, color = TwitchColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(PagePadding))
}

@Composable
private fun GridTitle(text: String) {
    Text(text, color = TwitchColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = TwitchColors.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        color = TwitchColors.TextMuted,
        fontSize = 16.sp,
        modifier = Modifier.padding(start = PagePadding, top = 72.dp, end = PagePadding),
    )
}

/** Connect a Twitch account: a code for twitch.tv/activate, or who is connected. */
@Composable
private fun AccountPage(controller: TwitchController, enabled: Boolean) {
    val buttonFocus = remember { FocusRequester() }
    Column(
        Modifier.fillMaxSize().padding(PagePadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Konto", color = TwitchColors.Text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        val account = controller.account
        when (val state = controller.signIn) {
            is SignIn.WaitingForCode -> Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                QrCode(state.login.verificationUri, Modifier.size(200.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("So verbindest du dein Twitch-Konto:", color = TwitchColors.Text, fontSize = 18.sp)
                    Text(
                        "1. Scanne den QR-Code mit dem Handy, oder öffne twitch.tv/activate.\n" +
                            "2. Melde dich dort bei Twitch an, falls nötig.\n" +
                            "3. Gib diesen Code ein und tippe auf „Aktivieren“:",
                        color = TwitchColors.TextMuted,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    )
                    Text(
                        state.login.userCode,
                        color = Color.White,
                        fontSize = 40.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .background(TwitchColors.SurfaceHigh, RoundedCornerShape(8.dp))
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                    Text("Der Fernseher wartet und meldet sich von selbst an. Zurück bricht ab.", color = TwitchColors.TextMuted, fontSize = 13.sp)
                }
            }
            else -> {
                if (account != null) {
                    Text("Verbunden als ${account.login}", color = TwitchColors.Text, fontSize = 18.sp)
                    Text(
                        "Unter „Folge ich“ und auf der Startseite stehen jetzt die Kanäle, denen du auf Twitch folgst. " +
                            "Streams laufen weiterhin ohne Anmeldung, das Konto liest nur deine Liste.",
                        color = TwitchColors.TextMuted,
                        fontSize = 15.sp,
                    )
                } else {
                    Text(
                        "Verbinde dein Twitch-Konto, damit die Kanäle, denen du folgst, hier auftauchen. " +
                            "Dein Passwort gibst du nur auf der Twitch-Seite ein, nicht am Fernseher.",
                        color = TwitchColors.TextMuted,
                        fontSize = 15.sp,
                    )
                }
                (state as? SignIn.Failed)?.let { Text(it.message, color = Color(0xFFFFB4A9), fontSize = 15.sp) }
                Button(
                    onClick = { if (account != null) controller.signOut() else controller.startSignIn() },
                    enabled = state !is SignIn.Starting,
                    modifier = Modifier.focusProperties { canFocus = enabled }.focusRequester(buttonFocus),
                    colors = ButtonDefaults.colors(
                        containerColor = if (account != null) TwitchColors.SurfaceHigh else TwitchColors.Purple,
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        when {
                            state is SignIn.Starting -> "Einen Moment …"
                            account != null -> "Abmelden"
                            else -> "Mit Twitch verbinden"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val image = remember(text) {
        val size = 400
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
        Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888).asImageBitmap()
    }
    Image(image, contentDescription = "QR-Code für twitch.tv/activate", modifier = modifier)
}
