# Streamplayer

Android-App, die Videos auf Webseiten findet und lädt. Im Kern ein Browser: Du öffnest
eine Seite, startest das Video, und die App zeigt unten „1 Video gefunden“. Ein Tipp
darauf, Qualität wählen, laden. Fertige Videos landen in der Mediathek unter
`Filme/Streamplayer`.

Vorbild ist die Funktionsweise von Video Downloader Pro (siehe Analyse-Artefakt
„Wie Video Downloader Pro Streams findet“). Der Code hier ist eigenständig geschrieben.
Die Oberfläche folgt dem Design-System **Ivory & Ink**.

## Was sie kann

- **Erkennen auf zwei Ebenen**
  - *Unter der Seite:* `shouldInterceptRequest` sieht jede Anfrage, auch aus iframes.
    Alles mit Video-Endung (`.mp4`, `.m3u8`, `.mpd`, …) wird gemeldet.
  - *In der Seite:* `assets/detect.js` läuft per `addDocumentStartJavaScript` in jedem Frame.
    Es hört auf `play`-Events (Capture-Phase), scannt das DOM nach `<video>`, `<audio>`, `<source>`
    und VideoObject-Schema und prüft den Content-Type von XHR- und fetch-Antworten.
    Zurück zur App geht es über einen `WebMessageListener`, nicht über `@JavascriptInterface`.
  - *Ohne Play:* Dasselbe Skript fragt 20 Sekunden lang jede Sekunde die Player der Seite ab,
    so wie das Original: JW Player (`getPlaylist()`, z. B. VOE) und Video.js (`currentSources()`).
    Dazu kommen Manifest-Adressen in Inline-Skripten und `data-`-Attributen. Viele Player kennen
    die Adresse lange bevor sie sie abrufen.
- **Prüfen, bevor etwas erscheint:** Ein-Byte-Range-Anfrage für Dateien. HLS-Master und
  DASH-Manifeste werden mit den Media3-Parsern gelesen und liefern Qualitäten, Dauer,
  geschätzte Größe sowie DRM- und Live-Erkennung. Segmente und Teil-Playlists werden
  herausgefiltert.
- **Laden**
  - Einzeldateien über OkHttp. Mit `Range` lassen sie sich nach einer Pause fortsetzen.
  - HLS und DASH über `HlsDownloader`/`DashDownloader` von Media3: vier Segmente parallel, in
    einen Cache. Danach baut **Transformer** daraus eine MP4. Es wird nur umverpackt
    (Transmux), nicht neu kodiert.
  - Foreground-Service mit Warteschlange, 1–3 gleichzeitig, auf Wunsch nur über WLAN.
- **Ablegen:** Videos in der Mediathek (`MediaStore`). Dafür braucht die App keine
  Speicher-Berechtigung.
- **Abspielen:** Fertige Dateien und eine Vorschau des gefundenen Streams laufen über
  ExoPlayer, mit denselben Kopfzeilen (Referer, Cookies, User-Agent) wie die Seite.

## Werbeblocker

uBlock Origin lässt sich in einer Android-WebView nicht installieren, denn WebViews laden keine
Browser-Erweiterungen. Deshalb gibt es einen eingebauten Blocker mit denselben Filterlisten,
die uBlock standardmäßig nutzt: **EasyList, EasyPrivacy, Peter Lowe** (rund 129.000 Regeln).

- **Netzwerk:** `shouldInterceptRequest` beantwortet Anfragen an Werbe- und Tracking-Hosts mit
  einem leeren 403. Umgesetzt werden `||host^` (auch `$third-party`), `||host/pfad*`,
  Ausnahmen (`@@`) und `$important`. Ausgelassen sind Regeln mit Ressourcentyp oder `$domain=`,
  weil die WebView den Typ einer Anfrage nicht zuverlässig verrät.
- **Popups und Umleitungen:** Neue Fenster öffnen nur nach einem Tipp auf einen echten Link,
  und zwar im selben Fenster. Popunder fallen weg. Hauptseiten-Umleitungen auf Werbe-Hosts ohne
  Fingertipp und Ziele aus `$popup`/`$document` werden gestoppt.
- **Ausblenden:** Die `##`-Regeln gehen als CSS in die Seite, über dieselbe Brücke wie die
  Video-Erkennung. Seitenspezifische Regeln gelten in jedem Frame, die allgemeinen nur im
  Hauptframe. Skript-Injektionen (`##+js`) und prozedurale Selektoren laufen in einer WebView
  nicht.
- **Nebeneffekt:** Blockierte Videowerbung taucht gar nicht erst als „gefundenes Video“ auf.
- Das Schild in der Adressleiste zählt die Blockaden der Seite. Ein Tipp darauf schaltet den
  Blocker für diese Seite aus oder wieder ein.
- Startbestand der Listen liegt in `assets/adblock` (aktualisieren mit `tools/update-filters.sh`).
  Die App holt sie danach alle 4 Tage selbst neu.

Lizenzen: EasyList und EasyPrivacy stehen unter GPLv3 / CC BY-SA 3.0. Die Liste von Peter Lowe
ist für die private Nutzung frei.

## Was sie bewusst nicht kann

- **DRM** (Widevine & Co.): wird erkannt und als „Geschützt“ markiert, nicht geladen.
- **Livestreams:** werden erkannt, nicht geladen.
- **Seitenspezifische Sonderfälle:** Es gibt keine handgeschriebenen Handler für einzelne
  Seiten. Players, die ihre Adresse nirgends preisgeben (reines MSE aus JS-Puffern ohne
  Manifest-Anfrage), bleiben unsichtbar.

## Aufbau

```
app/src/main/
├── assets/detect.js                 Erkennung in der Seite
└── java/de/marne/streamplayer/
    ├── App.kt                       app-weite Objekte, Coil-Loader
    ├── MainActivity.kt              Tabs, Snackbar, Vollbild-Video
    ├── browser/                     WebView, Adressleiste, Fund-Sheet
    ├── detect/                      UrlClassifier, Prober, DetectionHub
    ├── download/                    Service, Engines, Mediathek, Benachrichtigungen
    ├── downloads/                   Downloads-Screen
    ├── settings/                    Einstellungen
    ├── player/                      ExoPlayer-Activity
    └── ui/design/                   Ivory & Ink: Theme, Komponenten, Icons
```

`ui/design/Theme.kt`, `Components.kt` und die Schriften stammen aus dem Ivory-&-Ink-Paket
(GlowUp). `Extras.kt` ergänzt Bausteine, die das Design-System beschreibt, für die es aber
noch keinen Compose-Code gab: Segment-Umschalter, Hinweiskarte, Vorschaubild und Menü.
Die Icons sind Material Symbols Rounded in Stärke 300.

## Bauen

Voraussetzungen: JDK 17+, Android SDK mit Plattform 37.1, Gradle-Wrapper liegt bei.

```sh
./gradlew :app:assembleDebug        # APK unter app/build/outputs/apk/debug/
./gradlew :app:testDebugUnitTest    # Unit-Tests (UrlClassifier)
```

Der Debug-Build hat die ID `de.marne.streamplayer.debug` und läuft neben einem Release-Build.

**Signieren:** Das Release wird mit einem eigenen Schlüssel signiert. `keystore.properties`
(gitignored) zeigt auf `~/.android/streamplayer-release.jks`. Den Schlüssel sichern: Ohne ihn
lassen sich Updates nicht mehr über eine installierte Version spielen. Fehlt die Datei, nimmt
Gradle den Debug-Schlüssel.

## Testen ohne fremde Seiten

```sh
tools/testsite.sh                   # MP4 + HLS mit zwei Qualitäten auf Port 8765
adb shell am start -a android.intent.action.VIEW -d http://10.0.2.2:8765/ \
  -n de.marne.streamplayer.debug/de.marne.streamplayer.MainActivity
```

Hinweis zum Emulator auf diesem Rechner: `-gpu swiftshader_indirect` stürzt ab, sobald die
WebView rendert (SIGSEGV in qemu). Mit `-gpu host` läuft es.
