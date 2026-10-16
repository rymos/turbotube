package io.github.rymos.turbotube.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import io.github.rymos.turbotube.core.Item
import io.github.rymos.turbotube.core.Page
import io.github.rymos.turbotube.player.PlayerScreen
import kotlinx.coroutines.CoroutineScope

sealed interface Screen {
    data object Home : Screen
    data object Login : Screen
    data class Channel(val url: String, val name: String) : Screen
    data class Playlist(val url: String, val id: String, val title: String, val own: Boolean) :
        Screen
    data class Player(val videoUrl: String) : Screen
}

class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()

    fun push(screen: Screen) {
        stack.add(screen)
    }

    fun pop() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator") }

/**
 * Lists stay loaded (with their scroll position) while the user is in the player, so coming
 * back does not reload anything.
 */
class ListCache(val scope: CoroutineScope) {
    private val lists = mutableMapOf<String, PagedItems>()

    fun get(key: String, load: suspend (Any?) -> Page<Item>): PagedItems =
        lists.getOrPut(key) { PagedItems(scope, load) }

    fun clear() {
        lists.clear()
    }
}

val LocalListCache = staticCompositionLocalOf<ListCache> { error("No ListCache") }

@Composable
fun rememberPaged(key: String, load: suspend (Any?) -> Page<Item>): PagedItems {
    val cache = LocalListCache.current
    return remember(key) { cache.get(key, load) }
}

@Composable
fun TurbotubeRoot(navigator: Navigator) {
    val scope = rememberCoroutineScope()
    val cache = remember { ListCache(scope) }
    CompositionLocalProvider(LocalNavigator provides navigator, LocalListCache provides cache) {
        BackHandler(enabled = navigator.stack.size > 1) { navigator.pop() }
        Box(Modifier.fillMaxSize().background(Background)) {
            when (val screen = navigator.current) {
                Screen.Home -> HomeScreen()
                Screen.Login -> LoginScreen(onDone = {
                    cache.clear()
                    navigator.pop()
                })
                is Screen.Channel -> ChannelScreen(screen)
                is Screen.Playlist -> PlaylistScreen(screen)
                is Screen.Player -> PlayerScreen(screen.videoUrl, onFinished = navigator::pop)
            }
        }
    }
}
