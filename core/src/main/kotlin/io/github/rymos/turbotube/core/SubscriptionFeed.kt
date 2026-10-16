package io.github.rymos.turbotube.core

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Builds the "new from your subscriptions" feed. Each channel's public RSS feed is read
 * anonymously, so building the feed costs no API quota and tells Google nothing about it.
 */
object SubscriptionFeed {
    suspend fun load(
        channels: List<Channel>,
        maxAgeDays: Long = 30,
        parallelism: Int = 8,
    ): List<Video> = coroutineScope {
        val gate = Semaphore(parallelism)
        val oldest = System.currentTimeMillis() - maxAgeDays * 24 * 60 * 60 * 1000
        channels.map { channel ->
            async {
                gate.withPermit {
                    runCatching { YouTube.channelFeed(channel.id) }.getOrDefault(emptyList())
                }
            }
        }.awaitAll()
            .flatten()
            .filter { (it.uploadedAtMillis ?: 0) >= oldest }
            .distinctBy { it.id }
            .sortedByDescending { it.uploadedAtMillis ?: 0 }
    }
}
