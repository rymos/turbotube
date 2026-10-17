package io.github.rymos.turbotube

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.rymos.turbotube.twitch.TwitchController
import io.github.rymos.turbotube.twitch.TwitchScreen
import io.github.rymos.turbotube.ui.TurbotubeMenu
import io.github.rymos.turbotube.update.UpdateState
import io.github.rymos.turbotube.web.PlaybackLimits
import io.github.rymos.turbotube.web.TizenTubeScript
import io.github.rymos.turbotube.web.TrackerBlocker
import kotlinx.coroutines.launch

/**
 * YouTube's own TV interface (youtube.com/tv) with an ad-removing user script on top, and a Twitch
 * side with its own interface and player. Holding Back opens Turbotube's own menu (updates,
 * reload, switching between YouTube and Twitch).
 */
class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var menuOpen by mutableStateOf(false)
    private var backLongPressed = false
    private var scriptForFallback: String? = null
    private var adBlockLoaded by mutableStateOf(false)
    private var twitchMode by mutableStateOf(false)
    private lateinit var twitch: TwitchController
    private var scriptReady = false
    private var youTubeStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TurbotubeApp).container
        webView = createWebView()
        twitch = TwitchController(
            container.twitchApi, container.favorites, container.twitchAuth, container.http, lifecycleScope, this,
        )
        // Opens where it was left, except for a YouTube link sent to the TV
        twitchMode = intent?.data == null && prefs().getBoolean(KEY_TWITCH, false)
        if (twitchMode) {
            webView.isFocusable = false
            webView.visibility = View.INVISIBLE
            webView.onPause()
        }

        setContent {
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
                if (twitchMode) TwitchScreen(twitch)
                if (menuOpen) {
                    TurbotubeMenu(
                        updater = container.updater,
                        twitchMode = twitchMode,
                        adBlockLoaded = adBlockLoaded,
                        onReload = {
                            closeMenu()
                            if (twitchMode) twitch.refresh() else webView.reload()
                        },
                        onSwitch = { switchMode(!twitchMode) },
                        onExit = { finish() },
                        onClose = { closeMenu() },
                    )
                }
            }
        }

        lifecycleScope.launch {
            val script = TizenTubeScript.load(this@MainActivity, container.http)
            if (script == null) {
                Toast.makeText(
                    this@MainActivity,
                    "Der Werbeblocker konnte nicht geladen werden, Werbung wird diesmal nicht entfernt.",
                    Toast.LENGTH_LONG,
                ).show()
            }
            inject(script)
            scriptReady = true
            if (!twitchMode) {
                startYouTube(savedInstanceState)
                webView.requestFocus()
            }

            // Look for a new Turbotube version once per start and offer it right away
            container.updater.check(quiet = true)
            if (container.updater.state is UpdateState.Available) openMenu()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = WebView(this).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(android.graphics.Color.BLACK)
        isFocusable = true
        isFocusableInTouchMode = true
        // youtube.com/tv is laid out for a 1920 pixel wide screen. Android TV reports a much
        // smaller one (960 dp on a 1080p screen), which made everything twice as large and cut
        // off rows. Scale the page so it sees exactly 1920 CSS pixels across.
        setInitialScale((resources.displayMetrics.widthPixels * 100f / PAGE_WIDTH).toInt())
        settings.apply {
            useWideViewPort = false
            loadWithOverviewMode = false
            textZoom = 100
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = TV_USER_AGENT
        }
        val view = this
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (TrackerBlocker.isBlocked(request.url)) TrackerBlocker.emptyResponse() else null

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                // Links in descriptions and comments can lead anywhere. They must not turn a
                // browser without an address bar into a page that looks like YouTube.
                if (isYouTubePage(request.url)) return false
                openOutside(request.url)
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                // Older WebViews can't run scripts at document start; inject as early as possible
                scriptForFallback?.let { view.evaluateJavascript(it, null) }
            }
        }
        webChromeClient = object : WebChromeClient() {
            // "Exit YouTube?" in the TV interface closes the window
            override fun onCloseWindow(window: WebView) = finish()
        }
    }

    /** Only YouTube itself stays in the WebView; login and video hosts included. */
    private fun isYouTubePage(uri: Uri): Boolean {
        if (uri.scheme != "https" && uri.scheme != "http") return false
        val host = uri.host?.lowercase() ?: return false
        // Google redirects the login to country domains (google.de and friends), so a fixed list
        // of hosts is not enough — anything google.<tld> counts as ours.
        return ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") } || GOOGLE_DOMAINS.matches(host)
    }

    private fun openOutside(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Diesen Link kann Turbotube nicht öffnen.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun inject(script: String?) {
        // Guarded, so a page that gets the script twice doesn't patch itself twice
        // The bridge and the playback limits have to be in place before YouTube's own scripts
        // run. The ad blocker itself must wait until YouTube's app object (_yttv) exists: it
        // hooks the JSON parsers kept in there, and finds none when it runs too early, which let
        // every ad through.
        val adBlock = script?.let { "$JSON_BRIDGE\nwindow.__ttRun=function(){\n$it\n};\n$WAIT_AND_RUN" }.orEmpty()
        val guarded = "if(!window.__turbotube){window.__turbotube=true;\n" +
            "${PlaybackLimits.script(this)}\n$adBlock\n}"
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, guarded, setOf("https://www.youtube.com"))
        } else {
            scriptForFallback = guarded
        }
    }

    /** YouTube only loads once it is shown, so a start on the Twitch side doesn't load it. */
    private fun startYouTube(savedInstanceState: Bundle?) {
        if (youTubeStarted) return
        youTubeStarted = true
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(startUrl(intent))
        }
    }

    private fun switchMode(toTwitch: Boolean) {
        menuOpen = false
        twitchMode = toTwitch
        prefs().edit().putBoolean(KEY_TWITCH, toTwitch).apply()
        if (toTwitch) {
            // YouTube keeps playing in the background otherwise
            webView.evaluateJavascript(PAUSE_VIDEOS, null)
            webView.onPause()
            webView.isFocusable = false
            // YouTube's video sits on its own layer above everything else; hidden, it goes away
            webView.visibility = View.INVISIBLE
            twitch.interactive = true
        } else {
            webView.visibility = View.VISIBLE
            webView.onResume()
            webView.isFocusable = true
            if (scriptReady) startYouTube(null)
            webView.requestFocus()
        }
    }

    private fun prefs() = getSharedPreferences("turbotube", MODE_PRIVATE)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data == null) return
        setIntent(intent)
        if (twitchMode) switchMode(false)
        // Not started yet: the start picks the link up from the intent
        if (youTubeStarted) webView.loadUrl(startUrl(intent))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }

    // Back: short press goes to YouTube (as Escape, which the TV interface treats as back),
    // holding it opens Turbotube's menu.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.repeatCount == 0) backLongPressed = false
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            backLongPressed = true
            if (menuOpen) closeMenu() else openMenu()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (!backLongPressed && !event.isCanceled) {
                when {
                    menuOpen -> closeMenu()
                    twitchMode -> if (!twitch.back()) {
                        Toast.makeText(this, "Zurück gedrückt halten öffnet das Turbotube-Menü.", Toast.LENGTH_SHORT).show()
                    }
                    else -> sendEscape()
                }
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Asks the page whether the ad blocker really runs, for the status line in the menu. */
    private fun checkAdBlock() {
        webView.evaluateJavascript("!!window.__ttOk") { adBlockLoaded = it == "true" }
    }

    // While the menu is open the WebView must not take focus, or the remote steers YouTube
    // behind the menu as soon as the focused menu button changes.
    // On the Twitch side its lists are taken out of the focus order the same way.
    private fun openMenu() {
        webView.isFocusable = false
        twitch.interactive = false
        menuOpen = true
        checkAdBlock()
    }

    private fun closeMenu() {
        menuOpen = false
        twitch.interactive = true
        if (twitchMode) {
            twitch.restoreFocus()
        } else {
            webView.isFocusable = true
            webView.requestFocus()
        }
    }

    private fun sendEscape() {
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE))
    }

    companion object {
        /**
         * A Samsung TV. With it youtube.com/tv serves the TV interface instead of the desktop
         * site, and the user script finds the environment it was written for.
         */
        const val TV_USER_AGENT =
            "Mozilla/5.0 (SMART-TV; LINUX; Tizen 6.5) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "85.0.4183.93/6.5 TV Safari/537.36"

        private const val HOME = "https://www.youtube.com/tv"

        private const val KEY_TWITCH = "twitch"

        private const val PAUSE_VIDEOS = "document.querySelectorAll('video').forEach(function (v) { v.pause(); });"

        /** Everything else is handed to the system browser. */
        private val ALLOWED_HOSTS = listOf(
            "youtube.com",
            "youtu.be",
            "youtube-nocookie.com",
            "googlevideo.com",
            "googleapis.com",
            "googleusercontent.com",
            "gstatic.com",
            "ytimg.com",
            "ggpht.com",
        )

        /** google.com and its country domains (google.de, google.co.uk) for the login. */
        private val GOOGLE_DOMAINS = Regex("""(^|\.)google\.[a-z]{2,3}(\.[a-z]{2})?$""")

        private const val PAGE_WIDTH = 1920

        /**
         * The ad blocker edits YouTube's answers inside JSON.parse. In Android's WebView YouTube
         * may also read answers with fetch's Response.json() or XHR responseType "json", which
         * skip JSON.parse; route both through it so ads are removed there too.
         */
        private val JSON_BRIDGE = """
            (function () {
              Response.prototype.json = function () {
                return this.text().then(function (t) { return JSON.parse(t); });
              };
              var x = XMLHttpRequest.prototype;
              var type = Object.getOwnPropertyDescriptor(x, 'responseType');
              var resp = Object.getOwnPropertyDescriptor(x, 'response');
              Object.defineProperty(x, 'responseType', {
                configurable: true,
                get: function () { return this.__ttJson ? 'json' : type.get.call(this); },
                set: function (v) {
                  this.__ttJson = v === 'json';
                  type.set.call(this, this.__ttJson ? 'text' : v);
                },
              });
              Object.defineProperty(x, 'response', {
                configurable: true,
                get: function () {
                  if (!this.__ttJson) return resp.get.call(this);
                  if (this.readyState !== 4 || !this.responseText) return null;
                  if (this.__ttParsed === undefined) {
                    try { this.__ttParsed = JSON.parse(this.responseText); } catch (e) { this.__ttParsed = null; }
                  }
                  return this.__ttParsed;
                },
              });
            })();
        """.trimIndent()

        /**
         * Starts the ad blocker once the page has loaded and YouTube's app object is filled, then
         * keeps pointing YouTube's own JSON parsers at the patched one for a while, because some
         * of its modules only load later.
         */
        private val WAIT_AND_RUN = """
            (function () {
              function ready() {
                return document.readyState !== 'loading' &&
                  window._yttv && Object.keys(window._yttv).length > 0;
              }
              function repatch() {
                var y = window._yttv;
                if (!y) return;
                for (var k in y) {
                  try {
                    var j = y[k] && y[k].JSON;
                    if (j && j.parse && j.parse !== JSON.parse) j.parse = JSON.parse;
                  } catch (e) {}
                }
              }
              var wait = setInterval(function () {
                if (!ready()) return;
                clearInterval(wait);
                try { window.__ttRun(); window.__ttOk = true; } catch (e) { console.error('Turbotube: ad blocker failed', e); }
                var n = 0;
                var keep = setInterval(function () { repatch(); if (++n >= 60) clearInterval(keep); }, 1000);
              }, 50);
            })();
        """.trimIndent()

        /** A youtube.com or youtu.be link sent to the TV opens the video in the TV interface. */
        fun startUrl(intent: Intent?): String {
            val uri = intent?.data ?: return HOME
            val id = videoId(uri) ?: return HOME
            return "$HOME#/watch?v=$id"
        }

        private fun videoId(uri: Uri): String? = when {
            uri.host == "youtu.be" -> uri.lastPathSegment
            uri.path?.startsWith("/shorts/") == true || uri.path?.startsWith("/live/") == true ->
                uri.lastPathSegment
            else -> uri.getQueryParameter("v")
        }?.takeIf { Regex("[\\w-]{11}").matches(it) }
    }
}
