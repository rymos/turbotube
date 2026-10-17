package io.github.rymos.turbotube.preview

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import io.github.rymos.turbotube.MainActivity
import io.github.rymos.turbotube.R
import org.json.JSONArray

/**
 * Writes the tiles of YouTube's start page into a channel of Turbotube's own, so the TV's home
 * screen has something to show while Turbotube is highlighted in the app row — the same way Nuvio
 * puts its posters there.
 *
 * Everything is provider work: call it off the main thread.
 */
object PreviewShelf {
    /** A tile of the start page, as the page handed it over. */
    data class Item(val videoId: String, val title: String)

    /** A channel shows a handful of cards; more than that only fills the provider. */
    private const val MAX_ITEMS = 8

    private const val PREFS = "turbotube"
    private const val KEY_CHANNEL = "preview_channel"
    private const val KEY_SIGNATURE = "preview_signature"
    private const val KEY_PROGRAMS = "preview_programs"

    private const val WATCH_SCHEME = "turbotube"
    private const val THUMBNAIL = "https://i.ytimg.com/vi/%s/%s.jpg"
    private val ID = Regex("[\\w-]{11}")

    /** Reads what [PreviewExtract] sent. Anything malformed is dropped instead of trusted. */
    fun parse(json: String): List<Item> = runCatching {
        val tiles = JSONArray(json)
        (0 until tiles.length()).mapNotNull { index ->
            val tile = tiles.optJSONObject(index) ?: return@mapNotNull null
            val id = tile.optString("id").trim()
            if (!ID.matches(id)) return@mapNotNull null
            Item(id, tile.optString("title").trim().ifEmpty { "YouTube" })
        }
    }.getOrDefault(emptyList()).distinctBy { it.videoId }

    fun publish(context: Context, items: List<Item>) {
        val shelf = items.take(MAX_ITEMS)
        if (shelf.isEmpty()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val signature = shelf.joinToString("|") { it.videoId }
        // The same tiles twice would only stack up programs the launcher never shows.
        if (signature == prefs.getString(KEY_SIGNATURE, null)) return
        val helper = PreviewChannelHelper(context)
        try {
            val channelId = channel(context, helper, prefs)
            if (channelId <= 0) return
            // Replace the last round instead of piling onto it
            prefs.getStringSet(KEY_PROGRAMS, emptySet()).orEmpty().forEach { old ->
                runCatching { helper.deletePreviewProgram(old.toLong()) }
            }
            val written = shelf.mapIndexedNotNull { index, item ->
                helper.publishPreviewProgram(program(channelId, item, shelf.size - index))
                    .takeIf { it > 0 }?.toString()
            }
            prefs.edit()
                .putString(KEY_SIGNATURE, signature)
                .putStringSet(KEY_PROGRAMS, written.toSet())
                .apply()
        } catch (e: Exception) {
            // A revoked provider permission must never take the app down
            Log.w(TAG, "Die Vorschau-Zeile ließ sich nicht schreiben", e)
        }
    }

    /** Our channel, created as the default one on first use so the launcher shows it right away. */
    private fun channel(
        context: Context,
        helper: PreviewChannelHelper,
        prefs: SharedPreferences,
    ): Long {
        val known = prefs.getLong(KEY_CHANNEL, 0)
        if (known > 0 && helper.getPreviewChannel(known) != null) return known
        val logo = Uri.parse("android.resource://" + context.packageName + "/" + R.drawable.icon)
        val fresh = PreviewChannel.Builder()
            .setDisplayName("YouTube")
            .setDescription("Turbotube – die Startseite als Reihe")
            .setLogo(logo)
            .setAppLinkIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
            .setInternalProviderId("turbotube.youtube")
            .build()
        val id = helper.publishDefaultChannel(fresh)
        if (id > 0) prefs.edit().putLong(KEY_CHANNEL, id).apply()
        return id
    }

    private fun program(channelId: Long, item: Item, weight: Int): PreviewProgram =
        PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(TvContractCompat.PreviewPrograms.TYPE_CLIP)
            .setTitle(item.title)
            .setDescription(item.title)
            .setContentId(item.videoId)
            .setPosterArtAspectRatio(TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9)
            .setPosterArtUri(Uri.parse(thumbnail(item.videoId)))
            // Tapping a card opens the video in the TV interface (MainActivity reads this scheme)
            .setIntentUri(Uri.parse("$WATCH_SCHEME://watch/" + item.videoId))
            .setWeight(weight)
            .build()

    /** 1280x720 when YouTube has it, otherwise the small 16:9 one that always exists. */
    private fun thumbnail(videoId: String): String {
        val large = THUMBNAIL.format(videoId, "hq720")
        return if (reachable(large)) large else THUMBNAIL.format(videoId, "mqdefault")
    }

    private fun reachable(url: String): Boolean = runCatching {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 3000
        connection.readTimeout = 3000
        connection.requestMethod = "HEAD"
        val code = connection.responseCode
        connection.disconnect()
        code == 200
    }.getOrDefault(false)

    private const val TAG = "PreviewShelf"
}
