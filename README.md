# Streamplayer

Eine Mediathek für Videos aus dem Netz, gedacht wie Jellyfin oder Netflix, nur ohne Server.
Filme und Serien kommen per Link hinein. Abgespielt wird im eigenen Player, mit Fortschritt,
„Weiterschauen“ und „Nächste Folge“. Gespeichert wird immer die **Seite**, nie der Stream:
Stream-Adressen leben bei den meisten Hostern nur wenige Stunden. Aufgelöst wird erst beim
Abspielen, mit derselben Erkennung wie im eingebauten Browser.

Der Browser ist dafür nur noch ein Nebenraum: zum Finden, für Seiten, die mehr brauchen, und
als Werkzeug hinter den Kulissen. Die Oberfläche folgt dem Design-System **Ivory & Ink**.

## Mediathek

- **Startseite:** Oben die eine dunkle Hero-Karte (Ivory & Ink erlaubt eine pro Screen), mit
  „Weiterschauen“ bzw. „Nächste Folge“, sonst dem neuesten Titel. Darunter Regale:
  Weiterschauen, Serien, Filme, Offline.
- **Detailseite:** Bild, Titel, Beschreibung, „Abspielen“ bzw. „Fortsetzen · noch 12 Min.“.
  Bei Serien gibt es Staffel-Pills und eine Folgenliste mit Fortschritt; gesehene Folgen haben
  ein gefülltes Häkchen. Folgen lassen sich einzeln laden oder als (un)gesehen markieren.
- **Film hinzufügen:** ein Link. Titel, Bild und Beschreibung holt `MetaFetcher` per einfachem
  Abruf aus og:-Angaben. Was fehlt, ergänzt das erste Ansehen.
- **Serie hinzufügen:** Link zu einer Folge. `Pattern.derive` macht daraus eine Vorlage
  (`staffel-1/episode-1` → `staffel-{s}/episode-{e}`, `s01e05` → `s{ss}e{ee}`, sonst die
  letzten zwei Zahlen im Pfad). Dazu kommen die Folgen je Staffel („12, 8, 10“). Die Vorschau
  zeigt erste und letzte Adresse, damit ein falsches Muster sofort auffällt. Neue Staffel:
  „Folgen bearbeiten“, Zahl anhängen.
- **Fortschritt:** `PlayerActivity` meldet die Position alle 10 Sekunden und beim Verlassen.
  Weniger als 2 Minuten oder 5 % Rest zählen als gesehen (Abspann). Fortgesetzt wird ab 30
  Sekunden.
- **Offline:** Geladene Filme und Folgen hängen am Titel (`offline`), und Abspielen nimmt dann
  die Datei.
- **Abspielen von Links:** Der Browser lädt die Seite verdeckt („Nur ansehen“). Ist der Stream
  gefunden, öffnet sich der Player, und danach geht es zurück in die Bibliothek.
- **Geteilte Links** öffnen das Hinzufügen-Sheet, mit „Nur ansehen“ und „Im Browser öffnen“.

Gespeichert wird in filesDir: `titles.json` (Mediathek) und `progress.json` (Fortschritt). Die
Bibliothek bis 0.5 (`library.json`) wird beim ersten Start in Filme übernommen.

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

## Modus „Nur ansehen“

Schalter auf der Startseite: **Browsen** oder **Nur ansehen**. Im Modus „Nur ansehen“ zeigt die
App einen eingefügten Link nicht an, sondern spielt das Video direkt im eigenen Player ab. Das gilt
auch für Links, die per „Teilen“ oder als Link aus anderen Apps kommen.

1. Die Seite lädt in der normalen WebView, verdeckt von einer Warte-Ansicht. Sie bleibt dabei
   auf dem Bildschirm, weil viele Player sich erst aufbauen, wenn sie sichtbar sind.
2. Die Erkennung läuft wie immer: Netzwerk, Player fragen, Seitencode.
3. Ist nach 4 Sekunden (spätestens 10) nichts da, startet die App das Video stumm. Dafür schickt
   sie über die Brücke `nudge` an jeden Frame (JW Player, Video.js, `<video>`). Sie klickt nirgends
   hin, damit nichts Fremdes aufgeht.
4. Beim ersten Fund sammelt sie noch 1,5 Sekunden weiter und nimmt dann das Hauptvideo: Streams
   vor Dateien, sonst das längste. So rutschen kurze Werbeclips nicht durch. Die Seite wird
   angehalten, der Player öffnet sich.
5. Zurück aus dem Player gibt es „Nochmal ansehen“, „Laden“ und „Seite anzeigen“. Nach 30 Sekunden
   ohne Fund erscheint ein Hinweis mit „Seite anzeigen“. Livestreams lassen sich ansehen, DRM nicht.

## Bibliothek (bis 0.5)

Abgelöst durch die Mediathek oben; alte Einträge werden übernommen. Damals: ein Tab mit Kacheln in zwei Arten:

- **Links:** Ein Tipp spielt über „Nur ansehen“ direkt im Player ab. Gespeichert wird die Seite,
  nicht die Stream-Adresse, denn die verfällt meist nach Stunden. Fehlen Titel oder Bild, trägt
  die App sie beim ersten Ansehen nach.
- **Geladen:** fertige Downloads, offline abspielbar. Wird der Download gelöscht, verschwindet
  auch die Kachel.

Hinein kommt etwas über das Lesezeichen in der Adressleiste, „Merken“ im Fund-Sheet und nach dem
Ansehen, „In Bibliothek“ bei fertigen Downloads und „+“ in der Bibliothek (Link einfügen).
Lange drücken öffnet Ansehen, Seite öffnen, Umbenennen und Entfernen. Gespeichert wird als
`library.json` in filesDir.

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
