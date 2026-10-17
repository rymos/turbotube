package io.github.rymos.turbotube.twitch

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Favourite channels, kept on the TV only. No Twitch account needed. */
class Favorites(context: Context) {
    private val prefs = context.getSharedPreferences("twitch", Context.MODE_PRIVATE)

    /** Newest first. */
    var logins: List<String> by mutableStateOf(load())
        private set

    fun contains(login: String) = login in logins

    fun toggle(login: String) {
        logins = if (contains(login)) logins - login else listOf(login) + logins
        prefs.edit().putString(KEY, logins.joinToString(",")).apply()
    }

    private fun load(): List<String> =
        prefs.getString(KEY, null)?.split(',')?.filter { it.isNotBlank() }.orEmpty()

    private companion object {
        const val KEY = "favorites"
    }
}
