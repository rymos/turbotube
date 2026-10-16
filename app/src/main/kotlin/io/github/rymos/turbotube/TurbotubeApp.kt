package io.github.rymos.turbotube

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.rymos.turbotube.core.Channel
import io.github.rymos.turbotube.core.YouTube
import io.github.rymos.turbotube.core.account.GoogleDeviceAuth
import io.github.rymos.turbotube.core.account.TokenStore
import io.github.rymos.turbotube.core.account.YouTubeAccountApi
import io.github.rymos.turbotube.player.YouTubeMediaSources
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TurbotubeApp : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        YouTube.init(container.http)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .crossfade(true)
            .build()
}

/** Everything the screens share. Created once per app start. */
class AppContainer(context: Context) {
    val settings = Settings(context)

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val auth = GoogleDeviceAuth(
        client = http,
        clientId = BuildConfig.OAUTH_CLIENT_ID,
        clientSecret = BuildConfig.OAUTH_CLIENT_SECRET,
        store = settings,
    )

    val accountApi = YouTubeAccountApi(http, auth)

    /** Observable, so the menu updates right after logging in or out. */
    var loggedIn by mutableStateOf(auth.isLoggedIn)

    val mediaSources = YouTubeMediaSources(http)

    private val subscriptionsLock = Mutex()
    private var cachedSubscriptions: List<Channel>? = null

    /** The user's subscriptions, loaded once and kept until the login changes. */
    suspend fun subscriptions(): List<Channel> = subscriptionsLock.withLock {
        cachedSubscriptions ?: accountApi.subscriptions().also { cachedSubscriptions = it }
    }

    fun onLoginChanged() {
        cachedSubscriptions = null
        loggedIn = auth.isLoggedIn
    }
}

/** Small key-value settings in the app's private storage. */
class Settings(context: Context) : TokenStore {
    private val prefs = context.getSharedPreferences("turbotube", Context.MODE_PRIVATE)

    override var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        set(value) = prefs.edit().putString("refresh_token", value).apply()

    /** Highest video height to play; 0 means "as high as the TV supports". */
    var maxQuality: Int
        get() = prefs.getInt("max_quality", 0)
        set(value) = prefs.edit().putInt("max_quality", value).apply()
}
