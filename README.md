# Turbotube

YouTube und Twitch für den Fernseher, ohne Werbung, mit SponsorBlock. Gebaut für den **Google TV Streamer 4K**,
läuft aber auf jedem Android-TV- oder Google-TV-Gerät ab Android 8.

Die App zeigt YouTubes eigene TV-Oberfläche (youtube.com/tv) und lädt darüber ein Skript, das die
Werbung entfernt. Deshalb sieht alles exakt aus wie die YouTube-App, mit Anmeldung, Abos, Verlauf und
allen Einstellungen.

- Werbung entfernt, SponsorBlock, DeArrow, Wiedergabegeschwindigkeit (einstellbar in YouTube unter
  Einstellungen)
- Werbe- und Statistik-Adressen werden in der App gesperrt (siehe `TrackerBlocker`). Wiedergabezeit
  und Verlauf gehen weiter an YouTube, sonst gäbe es keinen Verlauf und kein „Weiterschauen“.
- **Twitch** mit eigener TV-Oberfläche (Favoriten, Beliebt, Kategorien, Suche) und Werbefilter, ohne
  Twitch-Konto. Umschalten über das Turbotube-Menü: Zurück-Taste gedrückt halten →
  „Zu Twitch wechseln“. Die App startet dort, wo du zuletzt warst.
- Eingebaute Updates: **Zurück-Taste gedrückt halten** öffnet das Turbotube-Menü mit
  „Nach Updates suchen“. Gibt es eine neue Version, erscheint das Menü beim Start von selbst.

## So funktioniert es

- `MainActivity` zeigt youtube.com/tv in einem WebView mit dem Browser-Kennzeichen eines Samsung-TVs
  und gibt die Fernbedienung weiter (Zurück wird als Escape an YouTube geschickt).
- `TizenTubeScript` liefert das Skript in einer festen Version mit der APK aus (der Workflow lädt es
  von npm, prüft die SHA-256-Prüfsumme und legt es unter `app/src/main/assets` ab). Die App prüft die
  Kopie erneut und legt sie auf dem Gerät ab; ohne mitgeliefertes Skript lädt sie es aus dem eigenen
  Release, erst danach von npm. Es läuft, bevor YouTubes eigene Skripte starten.
- `core/` enthält die Update-Prüfung gegen die GitHub-Releases, mit Tests. Sie liest die letzten
  Releases und nimmt den höchsten Build — „das neueste Release“ wäre nicht dasselbe.
- `twitch/` ist die Twitch-Seite: eine eigene Oberfläche in Compose und ein Media3-Player.
  `core/twitch/TwitchApi` fragt Twitchs Web-Schnittstelle anonym ab und sucht für jeden Stream einen
  Zugang, der gerade keine Werbung trägt. Was trotzdem an Werbung im Stream steckt, schneidet
  `PlaylistCleaner` aus der Wiedergabeliste, bevor der Player sie sieht (mit Tests).
- Links, die nicht zu YouTube gehören, öffnet die App im System-Browser statt im WebView.

## Auf den Google TV Streamer bringen

1. **Entwickleroptionen einschalten:** Einstellungen → System → Info → sieben Mal auf
   „Android TV OS-Build“ drücken.
2. **App „Downloader“** (von AFTVnews) aus dem Play Store installieren.
3. **Unbekannte Apps erlauben:** Einstellungen → Apps → Sicherheit und Einschränkungen →
   Unbekannte Quellen → „Downloader“ einschalten.
4. In Downloader diesen Link eintippen und „Installieren“ wählen:
   `https://github.com/rymos/turbotube/releases/latest/download/turbotube.apk`
5. Turbotube erscheint danach in der App-Leiste unter „Apps“.

Alternative für Leute mit Computer: In den Entwickleroptionen „Debugging über WLAN“ einschalten, dann
`adb connect <IP-des-Streamers>` und `adb install turbotube.apk`.

**Updates** gehen danach direkt in der App: Zurück-Taste gedrückt halten → **Nach Updates suchen** →
**Update installieren**.
Beim ersten Mal fragt Android, ob Turbotube Apps installieren darf. Das klappt nur, wenn alte und neue
APK mit demselben Schlüssel signiert sind (siehe „Signaturschlüssel“ unten).

## Bauen

Jeder Push auf `main` baut über GitHub Actions eine APK und veröffentlicht sie als Release `build-<n>`.
`n` ist die Laufnummer von GitHub Actions und zugleich der `versionCode` der APK; danach richtet sich
der Update-Knopf in der App.

Lokal: JDK 21 und das Android SDK installieren, dann

```sh
./gradlew :core:test :app:assembleRelease
```

Wer das Werbe-Skript lokal mitbauen will, legt es vorher dorthin (der Workflow macht genau das):

```sh
mkdir -p app/src/main/assets
curl -fsSL -o app/src/main/assets/tizentube-1.15.1.js \
  https://cdn.jsdelivr.net/npm/@foxreis/tizentube@1.15.1/dist/userScript.js
```

Ohne die Datei baut die App trotzdem; sie lädt das Skript dann beim ersten Start aus dem Netz.

### Signaturschlüssel

Ohne eigenen Schlüssel wird die Release-APK mit dem Debug-Schlüssel des jeweiligen Rechners signiert.
Damit Updates sich über die installierte Version legen lassen, einmal einen Schlüssel erzeugen

```sh
keytool -genkeypair -v -keystore turbotube.jks -alias turbotube -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 turbotube.jks
```

und als Secrets hinterlegen: `TURBOTUBE_KEYSTORE_BASE64` (die base64-Ausgabe),
`TURBOTUBE_KEYSTORE_PASSWORD`, `TURBOTUBE_KEY_ALIAS` (`turbotube`), `TURBOTUBE_KEY_PASSWORD`.

Ein Build für `main` **bricht ab**, wenn kein Schlüssel hinterlegt ist, statt eine debug-signierte
APK zu veröffentlichen — die ließe sich nie wieder updaten. Ein lokaler Build ohne Schlüssel geht
weiterhin und ist dann debug-signiert (nur zum Ausprobieren).

Releases tragen immer den Tag `build-<n>`. Ein Release mit einem anderen Tag (`v1.0` o. ä.) wird vom
Update-Knopf ignoriert, weil ihm die Build-Nummer fehlt.

## Bedienung

- Alles wie in der YouTube-App. Anmelden in YouTube unter „Anmelden“, dann den angezeigten Code auf dem Handy eingeben.
- **Zurück gedrückt halten:** Turbotube-Menü (Updates, neu laden, YouTube/Twitch wechseln, Beenden)
- **Twitch:** Mit den Pfeiltasten durch die Streams, OK startet einen Stream. Im Stream öffnet OK die
  Leiste mit „Als Favorit speichern“, Zurück beendet den Stream. Favoriten liegen nur auf dem Gerät.

## Grenzen

- Ob YouTube im WebView 4K anbietet, hängt vom Gerät ab; im schlimmsten Fall ist bei 1080p Schluss.
- Casting vom Handy auf den Fernseher geht nicht.
- Twitch fügt Werbung direkt in den Videostrom ein. Turbotube nimmt einen Zugang ohne Werbung, wenn es
  einen gibt, und schneidet sonst die Werbeschnipsel heraus; dann steht das Bild kurz mit dem Hinweis
  „Werbepause“, bis der Stream weitergeht. Weil Twitch das immer wieder ändert, kann der Filter
  zeitweise schlechter greifen, bis er angepasst ist. Ist ein werbefreier Zugang nur in niedriger
  Qualität verfügbar, wechselt der Player später von selbst zurück auf volle Qualität.
- Twitch ohne Anmeldung: kein Chat, keine gefolgten Kanäle aus dem Twitch-Konto (dafür Favoriten in
  der App), keine Videos auf Abruf.
- Googles eigene Oberfläche lädt weiterhin Googles Skripte; gesperrt werden nur die bekannten
  Werbe- und Statistik-Adressen.

## Lizenz

GPL-3.0-or-later. Das eingebundene Skript ist [TizenTube](https://github.com/reisxd/TizenTube)
von Reis Can, lizenziert unter GPL-3.0; die App liefert es unverändert mit (Version und Prüfsumme
stehen in `TizenTubeScript.kt`). Turbotube ist ein inoffizielles Projekt und gehört nicht zu Google,
YouTube oder Twitch.
