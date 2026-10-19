package io.github.rymos.turbotube.twitch

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import io.github.rymos.turbotube.core.twitch.DeviceLogin
import io.github.rymos.turbotube.core.twitch.TwitchAccount
import io.github.rymos.turbotube.core.twitch.TwitchApi
import io.github.rymos.turbotube.core.twitch.TwitchAuth
import io.github.rymos.turbotube.core.twitch.TwitchException
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
    Account("Konto"),
    Settings("Einstellungen"),
}

/** How the stream fills the screen. Overscan insets are a separate setting. */
enum class PictureFit(override val label: String) : Labelled {
    Fit("Einpassen"),
    Stretch("Gestreckt"),
    Zoom("Zoom"),
}

/** Where the sign-in stands. */
sealed interface SignIn {
    data object Idle : SignIn
    data object Starting : SignIn
    data class WaitingForCode(val login: DeviceLogin) : SignIn
    data class Failed(val message: String) : SignIn
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
    private val auth: TwitchAuth,
    val http: OkHttpClient,
    private val scope: CoroutineScope,
    context: Context,
) {
    private val prefs = context.getSharedPreferences("twitch", Context.MODE_PRIVATE)

    /** All Twitch adjustments: chat layout, picture, quality, page look. */
    val settings = TwitchSettings(prefs)

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

    /** Kept as reading accessors so the player keeps its short names. */
    val chatVisible: Boolean get() = settings.chatVisible.value

    val pictureFit: PictureFit get() = settings.pictureFit.value

    /** The connected Twitch account; its followed channels show up next to the ones saved here. */
    var account by mutableStateOf<TwitchAccount?>(
        prefs.getString(KEY_TOKEN, null)?.let { TwitchAccount(it, prefs.getString(KEY_LOGIN, null).orEmpty(), "") },
    )
        private set
    var signIn by mutableStateOf<SignIn>(SignIn.Idle)
        private set

    /** Logins the account follows on Twitch, for the Follow button. */
    var accountFollows by mutableStateOf<Set<String>>(emptySet())
        private set

    private var signInJob: Job? = null

    /** Highest picture height, or null for automatic. */
    val maxHeight: Int? get() = settings.quality.value.takeIf { it > 0 }

    /** False while Turbotube's menu lies on top, so the remote can't wander behind it. */
    var interactive by mutableStateOf(true)

    /** Counts up when the focus has to come back, after the menu closed. */
    var focusRequests by mutableIntStateOf(0)
        private set

    /** The card a stream was started from; the focus returns there when the stream ends. */
    var returnFocus: FocusRequester? = null
        private set

    private var job: Job? = null

    /** Where the settings page came from, so Back returns there. */
    private var lastPage = TwitchPage.Home

    fun restoreFocus() {
        focusRequests++
    }

    fun select(newPage: TwitchPage) {
        if (newPage == page && category == null) return
        if (page != newPage) lastPage = page
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
                    following = load { allFollowed() }
                }
                TwitchPage.Account -> Unit
                TwitchPage.Settings -> Unit
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

    /** Shows a code to enter on twitch.tv/activate and waits for it. */
    fun startSignIn() {
        signInJob?.cancel()
        signInJob = scope.launch {
            signIn = SignIn.Starting
            try {
                val code = auth.start()
                signIn = SignIn.WaitingForCode(code)
                val signedIn = auth.await(code)
                if (signedIn == null) {
                    signIn = SignIn.Failed("Der Code ist abgelaufen. Bitte nochmal versuchen.")
                } else {
                    account = signedIn
                    prefs.edit().putString(KEY_TOKEN, signedIn.token).putString(KEY_LOGIN, signedIn.login).apply()
                    signIn = SignIn.Idle
                    home = Load.Loading
                    following = Load.Loading
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                signIn = SignIn.Failed(e.message ?: "Anmeldung fehlgeschlagen.")
            }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        signIn = SignIn.Idle
    }

    fun signOut() {
        account = null
        accountFollows = emptySet()
        prefs.edit().remove(KEY_TOKEN).remove(KEY_LOGIN).apply()
        home = Load.Loading
        following = Load.Loading
    }

    fun nextPictureFit() = settings.pictureFit.next(1)

    fun isFollowed(login: String) = favorites.contains(login) || login in accountFollows

    fun toggleChat() = settings.chatVisible.toggle()

    /** Auto → 1080p → 720p → 480p → 360p → Auto. */
    fun nextQuality() = settings.quality.next(1)

    /** Short press of Back. False when there is nothing left to go back to. */
    fun back(): Boolean = when {
        playing != null && playerPanelOpen -> {
            playerPanelOpen = false
            true
        }
        page == TwitchPage.Account && signIn is SignIn.WaitingForCode && playing == null -> {
            cancelSignIn()
            true
        }
        playing != null -> {
            playing = null
            // Following may have changed while watching
            if (page == TwitchPage.Following || page == TwitchPage.Home) refresh()
            true
        }
        page == TwitchPage.Settings -> {
            val back = if (lastPage == TwitchPage.Settings) TwitchPage.Home else lastPage
            select(back)
            restoreFocus()
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
        val followed = async { runCatching { allFollowed().filter { it.live } }.getOrDefault(emptyList()) }
        val popular = async { api.topStreams() }
        val tops = async { runCatching { api.topCategories() }.getOrDefault(emptyList()) }
        val byCategory = tops.await().take(CATEGORY_ROWS).map { category ->
            async { category to runCatching { api.categoryStreams(category.name) }.getOrDefault(emptyList()) }
        }.awaitAll().filter { it.second.isNotEmpty() }
        HomeRows(followed.await(), popular.await(), tops.await(), byCategory)
    }

    /**
     * The channels saved here plus the ones the Twitch account follows. If Twitch refuses the
     * account (signed out elsewhere), the saved ones still show and the error says why.
     */
    private suspend fun allFollowed(): List<TwitchChannel> = coroutineScope {
        val local = async { api.channels(favorites.logins) }
        val token = account?.token
        val remote = if (token == null) {
            emptyList()
        } else {
            try {
                api.followedChannels(token).also { list -> accountFollows = list.map { it.login }.toSet() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Twitch no longer accepts the token: sign out, so the Account page offers a new code
                val check = runCatching { auth.validate(token) }
                if (check.isSuccess && check.getOrNull() == null) signOut()
                throw TwitchException("Gefolgte Kanäle aus dem Twitch-Konto lassen sich nicht laden: ${e.message}")
            }
        }
        // Live first, then in the order Twitch or this TV has them
        (local.await() + remote).distinctBy { it.login }.sortedByDescending { it.live }
    }

    private suspend fun <T> load(block: suspend () -> T): Load<T> = try {
        Load.Done(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Load.Failed(e.message ?: "Fehler beim Laden.")
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_LOGIN = "login"
        const val CATEGORY_ROWS = 4
    }
}
