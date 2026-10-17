package io.github.rymos.turbotube.twitch

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import io.github.rymos.turbotube.core.twitch.TwitchApi
import io.github.rymos.turbotube.core.twitch.TwitchCategory
import io.github.rymos.turbotube.core.twitch.TwitchChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** What a list shows: still loading, the items, or why there are none. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Done<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

enum class TwitchPage(val label: String) {
    Home("Startseite"),
    Following("Folge ich"),
    Browse("Durchsuchen"),
    Search("Suche"),
}

/** The start page: rows like on Twitch, each filled from one list. */
data class HomeRows(
    val following: List<TwitchChannel>,
    val popular: List<TwitchChannel>,
    val categories: List<TwitchCategory>,
    val byCategory: List<Pair<TwitchCategory, List<TwitchChannel>>>,
)

/** Everything the Twitch side shows, kept across recompositions and menu visits. */
class TwitchController(
    val api: TwitchApi,
    val favorites: Favorites,
    val http: OkHttpClient,
    private val scope: CoroutineScope,
    context: Context,
) {
    private val prefs = context.getSharedPreferences("twitch", Context.MODE_PRIVATE)

    var page by mutableStateOf(TwitchPage.Home)
        private set
    var home by mutableStateOf<Load<HomeRows>>(Load.Loading)
        private set
    var following by mutableStateOf<Load<List<TwitchChannel>>>(Load.Loading)
        private set
    var categories by mutableStateOf<Load<List<TwitchCategory>>>(Load.Loading)
        private set
    var category by mutableStateOf<TwitchCategory?>(null)
        private set
    var categoryStreams by mutableStateOf<Load<List<TwitchChannel>>>(Load.Loading)
        private set
    var query by mutableStateOf("")
    var searchResults by mutableStateOf<Load<List<TwitchChannel>>>(Load.Done(emptyList()))
        private set

    var playing by mutableStateOf<TwitchChannel?>(null)
        private set
    var playerPanelOpen by mutableStateOf(false)

    var chatVisible by mutableStateOf(prefs.getBoolean(KEY_CHAT, true))
        private set

    /** Highest picture height, or null for automatic. */
    var maxHeight by mutableStateOf(prefs.getInt(KEY_QUALITY, 0).takeIf { it > 0 })
        private set

    /** False while Turbotube's menu lies on top, so the remote can't wander behind it. */
    var interactive by mutableStateOf(true)

    /** Counts up when the focus has to come back, after the menu closed. */
    var focusRequests by mutableIntStateOf(0)
        private set

    /** The card a stream was started from; the focus returns there when the stream ends. */
    var returnFocus: FocusRequester? = null
        private set

    private var job: Job? = null

    fun restoreFocus() {
        focusRequests++
    }

    fun select(newPage: TwitchPage) {
        if (newPage == page && category == null) return
        page = newPage
        category = null
        refresh()
    }

    fun refresh() {
        job?.cancel()
        job = scope.launch {
            when (page) {
                TwitchPage.Home -> {
                    if (home !is Load.Done) home = Load.Loading
                    home = load { loadHome() }
                }
                TwitchPage.Following -> {
                    if (following !is Load.Done) following = Load.Loading
                    following = load { api.channels(favorites.logins) }
                }
                TwitchPage.Browse -> {
                    val open = category
                    if (open != null) {
                        categoryStreams = Load.Loading
                        categoryStreams = load { api.categoryStreams(open.name) }
                    } else {
                        if (categories !is Load.Done) categories = Load.Loading
                        categories = load { api.topCategories() }
                    }
                }
                TwitchPage.Search -> search()
            }
        }
    }

    fun search() {
        if (query.isBlank()) {
            searchResults = Load.Done(emptyList())
            return
        }
        job?.cancel()
        job = scope.launch {
            searchResults = Load.Loading
            searchResults = load { api.search(query) }
        }
    }

    fun open(newCategory: TwitchCategory) {
        page = TwitchPage.Browse
        category = newCategory
        refresh()
    }

    fun play(channel: TwitchChannel, from: FocusRequester?) {
        returnFocus = from
        playerPanelOpen = false
        playing = channel
    }

    fun toggleChat() {
        chatVisible = !chatVisible
        prefs.edit().putBoolean(KEY_CHAT, chatVisible).apply()
    }

    /** Auto → 1080p → 720p → 480p → 360p → Auto. */
    fun nextQuality() {
        val steps = listOf(null, 1080, 720, 480, 360)
        maxHeight = steps[(steps.indexOf(maxHeight) + 1) % steps.size]
        prefs.edit().putInt(KEY_QUALITY, maxHeight ?: 0).apply()
    }

    /** Short press of Back. False when there is nothing left to go back to. */
    fun back(): Boolean = when {
        playing != null && playerPanelOpen -> {
            playerPanelOpen = false
            true
        }
        playing != null -> {
            playing = null
            // Following may have changed while watching
            if (page == TwitchPage.Following || page == TwitchPage.Home) refresh()
            true
        }
        category != null -> {
            category = null
            refresh()
            true
        }
        page != TwitchPage.Home -> {
            select(TwitchPage.Home)
            true
        }
        else -> false
    }

    private suspend fun loadHome(): HomeRows = coroutineScope {
        val followed = async { runCatching { api.channels(favorites.logins).filter { it.live } }.getOrDefault(emptyList()) }
        val popular = async { api.topStreams() }
        val tops = async { runCatching { api.topCategories() }.getOrDefault(emptyList()) }
        val byCategory = tops.await().take(CATEGORY_ROWS).map { category ->
            async { category to runCatching { api.categoryStreams(category.name) }.getOrDefault(emptyList()) }
        }.awaitAll().filter { it.second.isNotEmpty() }
        HomeRows(followed.await(), popular.await(), tops.await(), byCategory)
    }

    private suspend fun <T> load(block: suspend () -> T): Load<T> = try {
        Load.Done(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Load.Failed(e.message ?: "Fehler beim Laden.")
    }

    private companion object {
        const val KEY_CHAT = "chat"
        const val KEY_QUALITY = "quality"
        const val CATEGORY_ROWS = 4
    }
}
