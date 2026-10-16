# Turbotube

YouTube für den Fernseher, ohne Werbung und ohne Tracker. Gebaut für den **Google TV Streamer 4K**,
läuft aber auf jedem Android-TV- oder Google-TV-Gerät ab Android 8.

- Videos ohne Werbeunterbrechungen, bis 4K (AV1, VP9 oder H.264, je nachdem, was das Gerät in Hardware kann)
- Mit dem eigenen Google-Konto anmelden: Abos, neue Videos der Abos, eigene Playlists, „Mag ich“
- Suche mit Vorschlägen, Kanäle, öffentliche Playlists, Entdecken (Live, Musik, Gaming, Filme, Podcasts)
- Keine Analyse- oder Werbebibliotheken, keine Google-Play-Dienste

## So funktioniert es

| Teil | Woher die Daten kommen |
| --- | --- |
| Abspielen, Suche, Kanäle, Entdecken | [NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor), anonym, ohne Konto und ohne API-Schlüssel |
| Neue Videos deiner Abos | die öffentlichen RSS-Feeds der Kanäle, anonym |
| Liste deiner Abos, Playlists, „Mag ich“ | offizielle YouTube Data API v3, nur lesend (`youtube.readonly`) |

Die Anmeldung läuft über den Google-Gerätecode-Ablauf für Fernseher: Der Fernseher zeigt einen Code,
du gibst ihn auf dem Handy unter google.com/device ein. Dein Passwort berührt die App nie, und was du
schaust, wird nicht an dein Konto gemeldet (also auch kein Verlauf bei Google).

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

Updates installiert man genauso über die alte Version drüber. Das klappt nur, wenn beide APKs mit
demselben Schlüssel signiert sind (siehe „Signaturschlüssel“ unten).

## Google-Login einrichten

Google verlangt für jede App, die auf ein Konto zugreift, eine eigene OAuth-Client-ID. Die legt man
einmal kostenlos an:

1. Auf [console.cloud.google.com](https://console.cloud.google.com) ein neues Projekt „Turbotube“ anlegen.
2. *APIs und Dienste → Bibliothek* → **YouTube Data API v3** suchen und aktivieren.
3. *Google Auth Platform* (früher „OAuth-Zustimmungsbildschirm“) einrichten: App-Name „Turbotube“,
   deine E-Mail als Support- und Kontaktadresse, Zielgruppe **Extern**.
4. Unter *Zielgruppe* auf **App veröffentlichen** klicken. Im Testmodus würde die Anmeldung nach
   7 Tagen ablaufen. Beim ersten Login zeigt Google dann „Diese App wurde nicht überprüft“; dort auf
   *Erweitert → Weiter zu Turbotube* gehen. Das ist bei privat genutzten Apps normal.
5. *Clients → Client erstellen* → Anwendungstyp **Fernseher und Geräte mit begrenzter Eingabe** →
   Client-ID und Clientschlüssel kopieren.
6. Im GitHub-Repository unter *Settings → Secrets and variables → Actions* zwei Secrets anlegen:
   `TURBOTUBE_OAUTH_CLIENT_ID` und `TURBOTUBE_OAUTH_CLIENT_SECRET`.

Wer lokal baut, trägt die beiden Werte stattdessen in `local.properties` ein:

```properties
TURBOTUBE_OAUTH_CLIENT_ID=1234-abc.apps.googleusercontent.com
TURBOTUBE_OAUTH_CLIENT_SECRET=GOCSPX-...
```

Ohne diese Werte läuft die App trotzdem, nur ohne Anmeldung.

## Bauen

Jeder Push auf `main` baut über GitHub Actions eine APK (unter *Actions → letzter Lauf → Artifacts*).
Ein Tag wie `v0.1.0` erzeugt zusätzlich ein Release mit der APK zum Herunterladen.

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

- Links ins Menü: Neu von Abos, Suche, Abos, Playlists, Mag ich, Entdecken, Konto
- OK auf einem Video spielt es ab, **lange OK drücken** öffnet den Kanal
- Im Player: OK zeigt die Steuerung, Zurück beendet
- Konto → Höchste Videoqualität: Automatisch nimmt das Maximum, das der Fernseher kann

## Grenzen dieser ersten Version

- „Später ansehen“ und der Verlauf sind über die offizielle API nicht abrufbar.
- HDR-Varianten werden noch übersprungen; es läuft die SDR-Fassung in derselben Auflösung.
- Keine Untertitel, kein SponsorBlock, keine Kommentare (geplant).
- YouTube ändert seine Seiten öfter. Wenn Videos nicht mehr laden, hilft meist ein Update von
  NewPipeExtractor (`gradle/libs.versions.toml`).

## Lizenz

GPL-3.0-or-later, wie NewPipeExtractor, auf dem Turbotube aufbaut. Der Umgang mit YouTubes
Medienservern (`YouTubeStreamInterceptor`) folgt NewPipes `YoutubeHttpDataSource`.
Turbotube ist ein inoffizielles Projekt und gehört nicht zu Google oder YouTube.
