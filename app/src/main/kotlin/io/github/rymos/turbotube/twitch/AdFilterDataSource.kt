@file:androidx.annotation.OptIn(UnstableApi::class)

package io.github.rymos.turbotube.twitch

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceUtil
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.github.rymos.turbotube.core.twitch.PlaylistCleaner

/**
 * Hands the player Twitch's stream playlists with the ads cut out (see [PlaylistCleaner]).
 * Video segments and the master playlist pass through untouched.
 */
class AdFilterDataSource(
    private val upstream: DataSource,
    private val cleaner: PlaylistCleaner,
    private val onAdBreak: (Boolean) -> Unit,
) : DataSource {
    private var cleaned: ByteArrayDataSource? = null

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        if (!isStreamPlaylist(dataSpec.uri)) return upstream.open(dataSpec)
        val original = try {
            upstream.open(dataSpec)
            String(DataSourceUtil.readToEnd(upstream), Charsets.UTF_8)
        } finally {
            upstream.close()
        }
        val key = dataSpec.uri.buildUpon().clearQuery().build().toString()
        val result = cleaner.clean(key, original)
        onAdBreak(result.inAdBreak)
        val source = ByteArrayDataSource(result.text.toByteArray(Charsets.UTF_8))
        cleaned = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        cleaned?.read(buffer, offset, length) ?: upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = cleaned?.uri ?: upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        if (cleaned != null) emptyMap() else upstream.responseHeaders

    override fun close() {
        val source = cleaned
        if (source != null) {
            cleaned = null
            source.close()
        } else {
            upstream.close()
        }
    }

    /** The playlists of one quality; the master playlist comes from usher.ttvnw.net. */
    private fun isStreamPlaylist(uri: Uri): Boolean {
        val host = uri.host ?: return false
        return host.endsWith(".ttvnw.net") && host != "usher.ttvnw.net" &&
            uri.path?.endsWith(".m3u8") == true
    }

    class Factory(
        private val upstream: DataSource.Factory,
        private val cleaner: PlaylistCleaner,
        private val onAdBreak: (Boolean) -> Unit,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            AdFilterDataSource(upstream.createDataSource(), cleaner, onAdBreak)
    }
}
