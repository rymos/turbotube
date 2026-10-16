package io.github.rymos.turbotube.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.NavigationDrawer
import androidx.tv.material3.NavigationDrawerItem
import androidx.tv.material3.Text
import io.github.rymos.turbotube.core.Item
import io.github.rymos.turbotube.core.Page
import io.github.rymos.turbotube.core.Playlist
import io.github.rymos.turbotube.core.SubscriptionFeed
import io.github.rymos.turbotube.core.YouTube
import kotlinx.coroutines.CancellationException

enum class Section(val label: String, val icon: ImageVector, val needsLogin: Boolean) {
    Home("Start", Icons.Filled.Home, true),
    Feed("Abos", Icons.Filled.Notifications, true),
    Search("Suche", Icons.Filled.Search, false),
    Subscriptions("Kanäle", Icons.Filled.Person, true),
    WatchLater("Später ansehen", Icons.Filled.DateRange, true),
    History("Verlauf", Icons.Filled.Refresh, true),
    Playlists("Playlists", Icons.Filled.List, true),
    Liked("Mag ich", Icons.Filled.ThumbUp, true),
    Explore("Entdecken", Icons.Filled.Star, false),
    Account("Konto", Icons.Filled.AccountCircle, false),
}

@Composable
fun HomeScreen() {
    val container = LocalContainer.current
    val loggedIn = container.loggedIn
    var section by rememberSaveable {
        mutableStateOf(if (loggedIn) Section.Home else Section.Explore)
    }
    // Look for a new version once per start; the menu then shows it next to "Konto"
    LaunchedEffect(Unit) { container.updater.check(quiet = true) }
    val updateWaiting = container.updater.lastFound != null

    NavigationDrawer(
        drawerContent = {
            Column(
                // Eleven entries don't fit on a 540dp TV screen, so the menu scrolls with the focus
                Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
            ) {
                Section.entries.filter { loggedIn || !it.needsLogin }.forEach { entry ->
                    NavigationDrawerItem(
                        selected = section == entry,
                        onClick = { section = entry },
                        leadingContent = { Icon(entry.icon, contentDescription = null) },
                    ) {
                        Text(if (entry == Section.Account && updateWaiting) "Konto · Update" else entry.label)
                    }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxSize().padding(start = 8.dp)) {
            when (section) {
                Section.Home -> TvListSection("home", "Start", "YouTube hat gerade keine Vorschläge.") { container.tv.home(it) }
                Section.Feed -> FeedSection()
                Section.Search -> SearchSection()
                Section.Subscriptions -> SubscriptionsSection()
                Section.WatchLater -> TvListSection("watchlater", "Später ansehen", "Die Liste ist leer.") { container.tv.watchLater(it) }
                Section.History -> TvListSection("history", "Verlauf", "Noch nichts angesehen.") { container.tv.history(it) }
                Section.Playlists -> PlaylistsSection()
                Section.Liked -> TvListSection("liked", "Videos, die du magst", "Noch keine Likes.") { container.tv.likedVideos(it) }
                Section.Explore -> ExploreSection()
                Section.Account -> AccountSection()
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(start = 32.dp, top = 24.dp),
    )
}

@Composable
private fun TvListSection(key: String, title: String, emptyText: String, load: suspend (Any?) -> Page<Item>) {
    val paged = rememberPaged(key) { next -> load(next) }
    Column {
        SectionTitle(title)
        ItemGrid(paged, emptyText = emptyText)
    }
}

@Composable
private fun FeedSection() {
    val container = LocalContainer.current
    val paged = rememberPaged("feed") { next ->
        if (next is RssFallback) {
            Page(emptyList())
        } else {
            val tvFeed = try {
                container.tv.subscriptionFeed(next).takeIf { next != null || it.items.isNotEmpty() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            // Fallback: the channels' public RSS feeds
            tvFeed ?: Page(SubscriptionFeed.load(container.subscriptions()), RssFallback)
        }
    }
    Column {
        SectionTitle("Neu von deinen Abos")
        ItemGrid(paged, emptyText = "Keine neuen Videos von deinen Abos.")
    }
}

/** Marks that the feed came from RSS, which has no further pages. */
private object RssFallback

@Composable
private fun SubscriptionsSection() {
    val container = LocalContainer.current
    val paged = rememberPaged("subscriptions") { next ->
        if (next != null) Page(emptyList()) else Page(container.subscriptions())
    }
    Column {
        SectionTitle("Abos")
        ItemGrid(paged, columns = 6, emptyText = "Du hast noch keine Kanäle abonniert.")
    }
}

@Composable
private fun PlaylistsSection() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    val paged = rememberPaged("playlists") { next -> container.tv.playlists(next) }
    Column {
        SectionTitle("Deine Playlists")
        ItemGrid(
            paged,
            emptyText = "Du hast noch keine Playlists.",
            // Own playlists can be private, so they are opened through the signed-in API
            onOpen = { item ->
                if (item is Playlist) {
                    navigator.push(Screen.Playlist(item.url, item.id, item.title, own = true))
                } else {
                    openItem(navigator, item)
                }
            },
        )
    }
}

@Composable
private fun ExploreSection() {
    var kiosk by rememberSaveable { mutableStateOf(YouTube.kiosks.first().first) }
    val paged = rememberPaged("kiosk:$kiosk") { next -> YouTube.kiosk(kiosk, next) }
    Column {
        SectionTitle("Entdecken")
        Row(
            Modifier.padding(start = 32.dp, top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            YouTube.kiosks.forEach { (id, label) ->
                FilterChip(selected = kiosk == id, onClick = { kiosk = id }) { Text(label) }
            }
        }
        ItemGrid(paged)
    }
}

@Composable
private fun AccountSection() {
    val container = LocalContainer.current
    val navigator = LocalNavigator.current
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Konto", style = MaterialTheme.typography.headlineMedium)
        AccountPanel()
        Spacer(Modifier.height(16.dp))
        QualityPanel()
        Spacer(Modifier.height(16.dp))
        UpdatePanel()
        Spacer(Modifier.height(16.dp))
        Text(
            "Turbotube zeigt keine Werbung und enthält keine Tracker. Was du in Turbotube schaust, " +
                "wird nicht an dein Konto gemeldet und landet nicht in deinem YouTube-Verlauf.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!container.loggedIn) {
            Button(onClick = { navigator.push(Screen.Login) }) { Text("Mit YouTube anmelden") }
        }
    }
}
