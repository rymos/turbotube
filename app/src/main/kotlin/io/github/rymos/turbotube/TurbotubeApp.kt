package io.github.rymos.turbotube

import android.app.Application
import android.content.Context
import io.github.rymos.turbotube.core.twitch.TwitchApi
import io.github.rymos.turbotube.core.twitch.TwitchAuth
import io.github.rymos.turbotube.twitch.Favorites
import io.github.rymos.turbotube.ui.RemoteImages
import io.github.rymos.turbotube.update.Updater
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TurbotubeApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** What the activity and the install receiver share. Created once per app start. */
class AppContainer(context: Context) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val updater = Updater(context.applicationContext, http)

    val twitchApi = TwitchApi(http)

    val twitchAuth = TwitchAuth(http)

    val favorites = Favorites(context.applicationContext)

    init {
        RemoteImages.http = http
    }
}
