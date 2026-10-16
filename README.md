# Turbotube

YouTube für den Fernseher, ohne Werbung und ohne Tracker. Gebaut für den **Google TV Streamer 4K**,
läuft aber auf jedem Android-TV- oder Google-TV-Gerät ab Android 8.

- Videos ohne Werbeunterbrechungen, bis 4K (AV1, VP9 oder H.264, je nachdem, was das Gerät in Hardware kann)
- Mit dem eigenen YouTube-Konto anmelden, ganz ohne Einrichtung: Startseite mit Empfehlungen, Abos,
  Kanäle, Später ansehen, Verlauf, eigene Playlists, „Mag ich“
- Eingebauter Update-Knopf (Konto → Nach Updates suchen), kein Browser nötig
- Suche mit Vorschlägen, Kanäle, öffentliche Playlists, Entdecken (Live, Musik, Gaming, Filme, Podcasts)
- Keine Analyse- oder Werbebibliotheken, keine Google-Play-Dienste

## So funktioniert es

| Teil | Woher die Daten kommen |
| --- | --- |
| Abspielen, Suche, Kanäle, Entdecken | [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), anonym, ohne Konto und ohne API-Schlüssel |
| Neue Videos deiner Abos | die öffentlichen RSS-Feeds der Kanäle, anonym |
| Alles mit Anmeldung (Start, Abos, Verlauf, Später ansehen, Playlists, „Mag ich“) | die interne Schnittstelle der YouTube-TV-App, mit deinem Konto |

Die Anmeldung funktioniert wie bei SmartTube: Der Fernseher zeigt einen Code, du gibst ihn auf dem
Handy unter **youtube.com/activate** ein. Turbotube nutzt dafür die Zugangsdaten der offiziellen
YouTube-TV-App, die es beim Anmelden von youtube.com/tv liest. Niemand muss eine eigene Google-Cloud-
Client-ID anlegen. Dein Passwort berührt die App nie, und was du schaust, wird nicht an dein Konto
gemeldet (also auch kein Eintrag im YouTube-Verlauf).

Das ist inoffiziell und verstößt streng genommen gegen YouTubes Nutzungsbedingungen. Wenn Google
etwas ändert, kann die Anmeldung zeitweise ausfallen; Suche und Abspielen gehen dann trotzdem.

Aufbau: `core/` ist reines Kotlin (YouTube-Zugriff, Anmeldung, Abo-Feed, Streamauswahl) und hat Tests,
`app/` ist die Android-TV-Oberfläche mit Jetpack Compose for TV und Media3/ExoPlayer.

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

**Updates** gehen danach direkt in der App: Konto → **Nach Updates suchen** → **Update installieren**.
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

- Links ins Menü: Start, Abos, Suche, Kanäle, Später ansehen, Verlauf, Playlists, Mag ich, Entdecken, Konto
- OK auf einem Video spielt es ab, **lange OK drücken** öffnet den Kanal
- Im Player: OK zeigt die Steuerung, Zurück beendet
- Konto → Höchste Videoqualität: Automatisch nimmt das Maximum, das der Fernseher kann

## Grenzen dieser ersten Version

- HDR-Varianten werden noch übersprungen; es läuft die SDR-Fassung in derselben Auflösung.
- Keine Untertitel, kein SponsorBlock, keine Kommentare (geplant).
- YouTube ändert seine Seiten öfter. Wenn Videos nicht mehr laden, hilft meist ein Update von
  NewPipeExtractor (`gradle/libs.versions.toml`).

## Lizenz

GPL-3.0-or-later, wie NewPipeExtractor, auf dem Turbotube aufbaut. Der Umgang mit YouTubes
Medienservern (`YouTubeStreamInterceptor`) folgt NewPipes `YoutubeHttpDataSource`.
Turbotube ist ein inoffizielles Projekt und gehört nicht zu Google oder YouTube.
