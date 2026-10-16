# Turbotube

YouTube für den Fernseher, ohne Werbung, mit SponsorBlock. Gebaut für den **Google TV Streamer 4K**,
läuft aber auf jedem Android-TV- oder Google-TV-Gerät ab Android 8.

Die App zeigt YouTubes eigene TV-Oberfläche (youtube.com/tv) und lädt darüber ein Skript, das die
Werbung entfernt. Deshalb sieht alles exakt aus wie die YouTube-App, mit Anmeldung, Abos, Verlauf und
allen Einstellungen.

- Werbung entfernt, SponsorBlock, DeArrow, Wiedergabegeschwindigkeit (einstellbar in YouTube unter
  Einstellungen)
- Werbe- und Statistik-Adressen werden in der App gesperrt (siehe `TrackerBlocker`). Wiedergabezeit
  und Verlauf gehen weiter an YouTube, sonst gäbe es keinen Verlauf und kein „Weiterschauen“.
- Eingebaute Updates: **Zurück-Taste gedrückt halten** öffnet das Turbotube-Menü mit
  „Nach Updates suchen“. Gibt es eine neue Version, erscheint das Menü beim Start von selbst.

## So funktioniert es

- `MainActivity` zeigt youtube.com/tv in einem WebView mit dem Browser-Kennzeichen eines Samsung-TVs
  und gibt die Fernbedienung weiter (Zurück wird als Escape an YouTube geschickt).
- `TizenTubeScript` lädt das Skript (siehe Lizenz) in einer festen Version von npm (jsDelivr, ersatzweise unpkg),
  prüft die SHA-256-Prüfsumme und speichert es auf dem Gerät. Es läuft, bevor YouTubes eigene
  Skripte starten.
- `core/` enthält die Update-Prüfung gegen die GitHub-Releases, mit Tests.

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

### Signaturschlüssel

Ohne eigenen Schlüssel wird die Release-APK mit dem Debug-Schlüssel des jeweiligen Rechners signiert.
Damit Updates sich über die installierte Version legen lassen, einmal einen Schlüssel erzeugen

```sh
keytool -genkeypair -v -keystore turbotube.jks -alias turbotube -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 turbotube.jks
```

und als Secrets hinterlegen: `TURBOTUBE_KEYSTORE_BASE64` (die base64-Ausgabe),
`TURBOTUBE_KEYSTORE_PASSWORD`, `TURBOTUBE_KEY_ALIAS` (`turbotube`), `TURBOTUBE_KEY_PASSWORD`.

## Bedienung

- Alles wie in der YouTube-App. Anmelden in YouTube unter „Anmelden“, dann den angezeigten Code auf dem Handy eingeben.
- **Zurück gedrückt halten:** Turbotube-Menü (Updates, YouTube neu laden, Beenden)

## Grenzen

- Ob YouTube im WebView 4K anbietet, hängt vom Gerät ab; im schlimmsten Fall ist bei 1080p Schluss.
- Casting vom Handy auf den Fernseher geht nicht.
- Googles eigene Oberfläche lädt weiterhin Googles Skripte; gesperrt werden nur die bekannten
  Werbe- und Statistik-Adressen.

## Lizenz

GPL-3.0-or-later. Das eingebundene Skript ist [TizenTube](https://github.com/reisxd/TizenTube)
von Reis Can, lizenziert unter GPL-3.0; die App lädt es unverändert von npm (`@foxreis/tizentube`).
Turbotube ist ein inoffizielles Projekt und gehört nicht zu Google oder YouTube.
