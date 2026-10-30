# Turbotube

[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/rymos/turbotube?label=release)](https://github.com/rymos/turbotube/releases/latest)
[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)](#build)

YouTube and Twitch on your TV, ad-free, with SponsorBlock. Built for the **Google TV Streamer 4K**
and runs on any Android TV or Google TV device from Android 8.0 up.

Turbotube renders YouTube's own TV interface (`youtube.com/tv`) in a WebView and injects a script
that removes the ads. The result looks exactly like the official YouTube app — sign-in,
subscriptions, watch history and all settings included.

> Turbotube is an unofficial project. It is not affiliated with, endorsed by, or supported by
> Google, YouTube, Twitch or Amazon.

## Features

- **Ad-free YouTube** through the bundled [TizenTube](https://github.com/reisxd/TizenTube) user
  script, plus SponsorBlock, DeArrow and playback speed (configurable inside YouTube under
  Settings).
- **Ad and telemetry hosts are blocked** in the app (see `TrackerBlocker`). Playback time and watch
  history still reach YouTube — that is what makes the "Continue watching" row work.
- **Twitch** with a native TV-style UI (home rows, Following, Browse, Search), chat next to the
  stream and an ad filter. No Twitch account is required; you can connect one under "Account" with
  a code from `twitch.tv/activate` to get your followed channels. Switch with the Turbotube menu:
  hold Back → "Switch to Twitch". The app reopens on the last used side.
- **Built-in updates.** When a new build is out, a strip with "Install now" appears on start, on
  every switch between YouTube and Twitch, and whenever the app returns to the foreground. Press OK
  to install, "Later" or Back to dismiss. While the app stays open it re-checks every 30 minutes.
  The strip also lists what the new build brings — the workflow writes the commit subjects into the
  release body. "Check for updates" in the Turbotube menu still works as a manual trigger.

## How it works

- `MainActivity` shows `youtube.com/tv` in a WebView with a Samsung TV user agent and forwards
  remote control events (Back is sent to YouTube as Escape).
- `TizenTubeScript` ships the script in a pinned version inside the APK. The workflow downloads it
  from npm, verifies its SHA-256 and places it in `app/src/main/assets`; the app verifies the copy
  again, stores it on the device, and falls back to the app's own release and then npm if no
  bundled copy exists. It runs before YouTube's own scripts.
- `core/` holds the update checker that reads the GitHub releases, with tests. It evaluates the
  recent releases and picks the highest build — "the newest release" is not the same thing.
- `twitch/` is the Twitch side: a custom Compose UI plus a Media3 player. `core/twitch/TwitchApi`
  queries Twitch's internal API anonymously and looks for a playback access point that currently
  carries no ads. Ads that still slip into the stream are cut out of the playlist by
  `PlaylistCleaner` before the player sees it (covered by tests).
- Links outside YouTube open in the system browser instead of the WebView.
- If Android's WebView renderer dies (low memory, or a page takes it down), the app builds a fresh
  WebView in the background and reloads the same page instead of crashing.

## Requirements

- Android TV or Google TV, Android 8.0 (API 26) or newer.
- To build: JDK 21 and the Android SDK (`compileSdk` 37, `targetSdk` 35).

## Install

1. **Enable developer options:** Settings → System → About → press "Android TV OS build"
   seven times.
2. **Install "Downloader"** (by AFTVnews) from the Play Store.
3. **Allow unknown apps:** Settings → Apps → Security & restrictions → Unknown sources →
   enable "Downloader".
4. Type this URL into Downloader and choose "Install":
   `https://github.com/rymos/turbotube/releases/latest/download/turbotube.apk`
5. Turbotube then shows up in the app row under "Apps".

With a computer at hand: enable "Wireless debugging" in developer options, then
`adb connect <device-ip>` and `adb install turbotube.apk`.

### Updating

Updates install from inside the app: hold Back → **Check for updates** → **Install update**. On the
first run Android asks whether Turbotube may install apps. This only works if the old and the new
APK are signed with the same key (see [Signing key](#signing-key)).

## Build

Every push to `main` builds an APK through GitHub Actions and publishes it as release `build-<n>`.
`n` is the GitHub Actions run number and also the APK's `versionCode`; the in-app update button
relies on it.

Locally: install JDK 21 and the Android SDK, then

```sh
./gradlew :core:test :app:assembleRelease
```

To build the ad-removal script locally, fetch it into place first (this is what the workflow does):

```sh
mkdir -p app/src/main/assets
curl -fsSL -o app/src/main/assets/tizentube-1.15.2.js \
  https://cdn.jsdelivr.net/npm/@foxreis/tizentube@1.15.2/dist/userScript.js
```

The app still builds without that file; it then downloads the script from the network on first
start.

### Signing key

Without a custom key the release APK is signed with the debug key of the machine that built it.
To make updates install over the existing app, create a key once:

```sh
keytool -genkeypair -v -keystore turbotube.jks -alias turbotube -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 turbotube.jks
```

and store it as secrets: `TURBOTUBE_KEYSTORE_BASE64` (the base64 output),
`TURBOTUBE_KEYSTORE_PASSWORD`, `TURBOTUBE_KEY_ALIAS` (`turbotube`), `TURBOTUBE_KEY_PASSWORD`.

A build for `main` **fails** when no key is configured instead of publishing a debug-signed APK —
that one could never be updated again. A local build without a key still works and is
debug-signed (for trying things out only).

Releases always carry the tag `build-<n>`. A release with any other tag (`v1.0` and the like) is
ignored by the update button, because it has no build number.

## Usage

- Everything works like the YouTube app. Sign in under "Sign in", then enter the shown code on your
  phone.
- **Hold Back:** Turbotube menu (updates, reload, switch YouTube/Twitch, quit).
- **Twitch:** the left rail has Home, Following, Browse, Search, Account and Settings; OK starts a
  stream. Inside a stream OK opens the overlay with follow, chat on/off, quality and picture.
  Back stops the stream. "Follow" stores the channel on the device only, not in the Twitch account.
- **Twitch settings:** chat side (left/right), width, font size, line spacing, background opacity,
  name colours, on/off for emotes with their own size, timestamps and auto-scroll; picture format,
  quality, overscan border and how long the info bar stays. Also the size of the home cards, the
  big hero banner and the viewer counts. Left/Right changes a value, OK moves it forward, and the
  "reset" row restores all defaults. Everything is stored on the device and applies immediately.
  During a stream the same panel opens with "Settings" in the info bar, so picture format,
  overscan and chat size can be judged on the live picture.

## Limitations

- Whether YouTube offers 4K inside a WebView depends on the device; worst case it stops at 1080p.
- Casting from a phone to the TV does not work.
- Twitch inserts ads into the video stream itself. Turbotube picks an access point without ads when
  one exists and otherwise cuts the ad segments out; the picture then briefly shows "Ad break"
  until the stream continues. Twitch changes this regularly, so the filter can get worse for a
  while until it is adjusted. If an ad-free access point is only available in low quality, the
  player switches back to full quality later on its own.
- Twitch: chat is read-only and shows Twitch's own emotes inline (third-party emotes from BTTV,
  FFZ and 7TV are not loaded), no video on demand. With a connected account
  the app only reads the list of followed channels; streams keep playing without signing in.
  Following and unfollowing inside the Twitch account only works on Twitch itself.
- Google's own interface still loads Google's scripts; only the known ad and telemetry hosts are
  blocked.

## License

GPL-3.0-or-later. The bundled script is [TizenTube](https://github.com/reisxd/TizenTube) by
Reis Can, licensed under GPL-3.0; the app ships it unmodified (version and checksum live in
`TizenTubeScript.kt`).
