# Streamplayer

Eine Mediathek für Videos aus dem Netz, gedacht wie Jellyfin oder Netflix, nur ohne Server.
Filme und Serien kommen per Link hinein. Abgespielt wird im eigenen Player, mit Fortschritt,
„Weiterschauen“ und „Nächste Folge“. Gespeichert wird immer die **Seite**, nie der Stream:
Stream-Adressen leben bei den meisten Hostern nur wenige Stunden. Aufgelöst wird erst beim
Abspielen, mit derselben Erkennung wie im eingebauten Browser.

Der Browser ist dafür nur noch ein Nebenraum: zum Finden, für Seiten, die mehr brauchen, und
als Werkzeug hinter den Kulissen. Die Oberfläche folgt dem Design-System **Ivory & Ink**.

## Zwei Apps, ein Kern

| Modul | APK | wofür |
| --- | --- | --- |
| `:app` | `de.marne.streamplayer` | Player: Mediathek, Abspielen, Downloads, Browser |
| `:creator` | `de.marne.streamplayer.creator` | Kataloge bauen: aus Link übernehmen, Serien-Muster, Staffeln, TMDB, Cover |
| `:core` | (Bibliothek) | Ivory & Ink, Katalogformat, Muster, TMDB-Client, Link-Analyse |

Filme und Serien kommen nur noch über **Katalog-Dateien** in den Player. Gebaut werden sie im
Creator und mit „An Streamplayer senden“ direkt übergeben. Alternativ öffnet bzw. teilt man eine
`.json` aus einem Dateimanager; solange die Bibliothek leer ist, gibt es dort auch „Katalog
importieren“. Die Symbole für Browser und Datei-Import oben rechts sind seit 1.4.1 weg. Ein erneuter Import aktualisiert Titel mit derselben Katalog-ID; Fortschritt,
Downloads und eigene Cover bleiben dabei erhalten. Kaputte Einträge werden übersprungen und
gemeldet, statt die ganze Datei abzulehnen.

### Katalogformat (`streamplayer-catalog`, Version 1)

```json
{
  "format": "streamplayer-catalog", "version": 1, "name": "Mein Katalog",
  "titles": [
    { "type": "series", "id": "beispiel-serie", "title": "Beispielserie", "year": 2020,
      "tmdb": { "id": 1396 },
      "template": "https://example.org/serie/beispiel/staffel-{s}/episode-{e}",
      "seasons": [
        { "number": 1, "episodes": 10 },
        { "number": 2, "episodes": 8, "start": 11 },
        { "number": 0, "episodes": 2, "title": "Specials", "template": "https://example.org/special-{e}" }
      ],
      "episodes": [ { "season": 1, "episode": 4, "url": "https://example.org/anders/folge-4" } ],
      "poster": "https://… oder data:image/jpeg;base64,…" },
    { "type": "movie", "id": "beispiel-film", "title": "Beispielfilm", "year": 2010,
      "tmdb": { "query": "Beispielfilm" }, "url": "https://example.org/film/beispiel" }
  ]
}
```

- **Platzhalter:** `{s}` Staffel, `{e}` Folge, `{a}` absolute Folge über alle Staffeln (für
  Anime-Seiten). Mit Nullen aufgefüllt: `{ss}`, `{ee}`, `{eee}`, `{aa}`, `{aaa}`.
- **Staffeln:** eigene Folgenzahl, Startnummer (`start`, wenn eine Seite weiterzählt), eigenes
  Muster und Titel. Die Kurzform `"seasons": [12, 8, 10]` geht auch.
- **`episodes`:** Ausnahmen für einzelne Folgen (eigene Adresse oder eigener Titel).
- **`tmdb`:** feste `id` oder `query`. Fehlt beides, sucht der Player bei „automatisch erkennen“
  nach dem Titel (plus Jahr).
- **Bilder:** als Adresse oder eingebettet (`data:`). Der Creator bettet Bilder aus der Galerie
  verkleinert ein, damit der Katalog jede Weitergabe übersteht.

### TMDB

Ein kostenloser Schlüssel von themoviedb.org kommt in die Einstellungen beider Apps. Akzeptiert
werden der API-Schlüssel (v3) und das Lese-Token (v4, `eyJ…`). Der Schlüssel bleibt auf dem
Gerät und landet nie im Katalog. Im Player ergänzt `Enricher` danach Poster, Hintergrund,
Beschreibung, Jahr und Laufzeit. Fehlen einer Serie die Staffeln, liefert TMDB sie samt
Folgenzahl, und je Folge kommen Name, Standbild und Inhalt dazu. Katalog-Angaben gewinnen immer,
TMDB füllt nur Lücken. Die Bildreihenfolge ist: eigenes Cover › Katalog › TMDB › Seitenbild.

### Creator

Eine Liste von Serien und Filmen, dazu „Aus Link übernehmen“ (siehe unten) und ein Editor, der
jede Änderung sofort speichert. Er bietet:
- Name und Jahr
- TMDB-Suche mit Postern und „Staffeln übernehmen“
- „Link einer Folge“ → Muster ableiten
- Staffeln mit Plus/Minus, Schnell-Eingabe „12, 8, 10“ sowie Nummer, Startfolge, eigenem
  Muster und Titel je Staffel
- Vorschau aller Adressen: tippen öffnet sie im Browser, einzelne Folgen lassen sich ändern
- Cover und Hintergrund aus der Galerie oder als Adresse

### Aus Link übernehmen (Creator 1.1.0)

Link zu einer Serie, Staffel, Folge oder einem Film einfügen, oder ihn aus dem Browser an den
Creator **teilen**. Der Creator liest die Seite und zeigt einen fertigen Entwurf mit Name, Jahr,
Bild, Staffeln, Muster und erster bzw. letzter Adresse. „In den Katalog“ übernimmt ihn mit einem
Tipp. Es gibt **keine Regeln für einzelne Seiten**, nur allgemeine Verfahren
(`core/…/importer/`):

1. **Laden:** schlicht über OkHttp (Kennung der WebView, Cookies nur für diese Analyse). Ist die
   Seite gesperrt (401/403/429/503), eine Vorschaltseite, hat sie kaum Verweise oder kein
   erkennbares Muster, kommt eine verdeckte WebView dazu (`BrowserFetcher`). Sie wartet, bis sich
   die Zahl der Verweise nicht mehr ändert. Will die Seite eine Bestätigung (Captcha), wird die
   WebView sichtbar, bis man „Weiter“ tippt.
2. **Was die Seite über sich sagt** (`Info`): schema.org als JSON-LD (`TVSeries`, `TVEpisode` →
   `partOfSeries`, `Movie`), dann `og:`-Angaben, `<h1>` und `<title>`. Titel werden aufgeräumt:
   Seitenname, „Staffel 2 Folge 5“, „online anschauen“ und dergleichen fallen weg.
3. **Struktur aus Verweisen** (`Structure`): Jede Adresse wird in Text und Zahlen zerlegt.
   Gleiche Form ergibt eine Familie, und die Zahlen, die sich unterscheiden, sind die Variablen.
   Wörter davor sagen, was sie bedeuten (`staffel`, `season`, `s` → Staffel; `episode`, `folge`,
   `e`, `x` → Folge; `film`, `ova`, `special` → Specials; `page`, `jahr`, `id` → nichts davon).
   Gezählt werden nur Verweise derselben Serie: Ein Pfadsegment muss nach dem Entfernen von
   „staffel-2“, „folge-5“ und Endungen genauso heißen wie die Serie in der Startadresse. So
   fallen Seitenleisten mit anderen Serien heraus, auch „foo-2“ neben „foo“. Ergebnis:
   Muster mit `{s}`/`{e}` (mit Nullen `{ss}`/`{ee}`/`{eee}`), Folgen je Staffel, `start` für
   weiterzählende Seiten, Specials als Staffel 0 mit eigenem Muster, Lücken als Hinweis.
4. **Nachladen, höchstens 40 Abrufe, 0,4 s Abstand:** War die Startseite eine Folge, kommt die
   Übersicht der Serie dazu (aus dem Muster abgeleitet). Dann je Staffel, deren Folgen noch keine
   geladene Seite lückenlos zeigt, eine Seite: die Staffelseite, sonst die erste bekannte Folge.
   Ganze Seiten werden nie abgegrast.
5. **TMDB** (mit Schlüssel): Ein eindeutiger Treffer (gleicher Name oder Originalname, Jahr ±1)
   wird verknüpft. Poster, Hintergrund und Beschreibung kommen dann von TMDB und stehen direkt im
   Katalog, also auch ohne TMDB-Schlüssel im Player. Ohne Treffer kommen sie von der Seite. Sonst stehen
   bis zu vier Kandidaten zur Wahl. Die Staffeln der Seite werden gegen TMDB (oder gegen
   `containsSeason` der Seite) geprüft. Zählt die Seite durch und passt die Summe genau, werden
   die TMDB-Staffeln mit `{a}` übernommen. Abweichungen erscheinen als Hinweis, geraten wird
   nicht.

**Viele Links auf einmal (Creator 1.2.0):** Stehen im Feld mehrere Links, wird jeder ein eigener
Titel. Dasselbe gilt für geteilten Text mit mehreren Links und für Dateien („Aus Datei“ oder aus
einem Dateimanager geteilt). Erlaubt ist Text mit beliebig verteilten Links, ein JSON-Array oder
JSON mit eigenen Namen:

```json
{ "links": ["https://…/serie/a", { "url": "https://…/serie/b", "title": "Eigener Name" }] }
```

Doppelte fallen weg, höchstens 200 Links pro Durchgang. Gelesen wird nacheinander, mit Pause
zwischen den Links. Fertige Einträge sind gewählt, ein Tipp wählt ab. „N Titel übernehmen“ nimmt
alle gewählten in den Katalog, schon vorhandene werden aktualisiert. Ist eine Datei ein Katalog
im Streamplayer-Format, wird sie wie mit „Datei öffnen“ übernommen. Wo TMDB nicht eindeutig
war, steht „TMDB unklar“: im Editor nachwählen, oder der Player sucht später selbst.

**Ganze Übersicht (Creator 1.3.0):** Oben auf „Ganze Übersicht“ umschalten und die Adresse einer
Seite eingeben, die viele Titel auflistet (Katalog, Genre, Neuheiten). Der Creator findet die
Titel darauf, liest die Folgeseiten der Übersicht mit und dann jeden Titel wie einen einzelnen
Link, also mit Staffeln, Folgen, Muster und TMDB. Auch hier gibt es keine Regeln für einzelne
Seiten (`Overview`):

1. **Titel finden:** schema.org `ItemList` in JSON-LD, sonst Familien von Verweisen, die sich in
   genau einem Pfadsegment oder Query-Wert unterscheiden (`/serie/foo`, `/serie/bar` …).
   Familien mit Navigationswörtern (`genre`, `page`, `login`, `impressum` …), A–Z-Leisten und
   Folgen-Adressen (`staffel-2`, `episode-5`) fallen weg. Kacheln mit Bild zählen mehr.
   Stehen Serien und Filme in eigenen Familien, kommen beide mit.
2. **Folgeseiten:** `rel="next"`, sonst die Adresse mit Seitenzahl + 1 (`?page=2`,
   `/seite/2/`), sonst ein Verweis „Weiter“. Höchstens 20 Seiten und 200 Titel.
3. **Grenzen** (`Throttle`), einstellbar im Eingabeschirm: Pause zwischen zwei Abrufen
   (0,5–5 s, Standard 1 s) und Abrufe pro Durchgang (100/300/1000, Standard 300). Sie gelten
   für alles zusammen: Übersicht, Titel, Staffeln, Browser. Die robots.txt der Seite wird
   beachtet (`Disallow`/`Allow`, `Crawl-delay` hebt die Pause an). Antwortet die Seite mit 429,
   verdoppelt sich die Pause, und nach 20 s kommt ein zweiter Versuch. Kommt wieder 429, hält
   der Durchgang an.
4. **Durchgänge:** Ein Titel bekommt höchstens so viele Abrufe, wie noch übrig sind. Reicht das
   nicht für alle Staffeln, bleibt er wartend, statt unvollständig in der Liste zu landen. An der
   Grenze hält der Creator an, „Weiterlesen“ startet den nächsten Durchgang. Wartende Titel lassen
   sich vorher abwählen und kosten dann nichts. Übernommen wird wie beim Massenimport.

Grenzen der Übersicht: Listen, die erst beim Scrollen nachladen, zeigt die WebView nur so weit,
wie sie anfangs da sind. A–Z-Unterseiten werden nicht verfolgt.

Liefert ein Link denselben Titel noch einmal (gleiches Muster bzw. gleiche Filmseite), heißt der
Knopf „Im Katalog aktualisieren“. Das übernimmt Staffeln und neue Folgen. Eigene Namen, Bilder,
Staffeltitel und Ausnahmen bleiben.

Grenzen: Inhalte hinter Logins, endlos nachladende Listen, Folgen nur mit IDs statt Nummern
(dann bleibt es beim Hinweis „Kein Folgen-Muster“) und Listen, die erst nach einem Klick
erscheinen.

## Mediathek

- **Startseite:** Oben die eine dunkle Hero-Karte (Ivory & Ink erlaubt eine pro Screen), mit
  „Weiterschauen“ bzw. „Nächste Folge“, sonst dem neuesten Titel. Darunter Regale:
  Weiterschauen, Serien, Filme, Offline.
- **Detailseite:** Bild, Titel, Beschreibung, „Abspielen“ bzw. „Fortsetzen · noch 12 Min.“.
  Bei Serien gibt es Staffel-Pills und eine Folgenliste mit Fortschritt; gesehene Folgen haben
  ein gefülltes Häkchen. Folgen lassen sich einzeln laden oder als (un)gesehen markieren.
- **Film hinzufügen (bis 1.0, heute per Katalog):** ein Link. Titel, Bild und Beschreibung holt `MetaFetcher` per einfachem
  Abruf aus og:-Angaben. Was fehlt, ergänzt das erste Ansehen.
- **Serie hinzufügen (bis 1.0, heute im Creator):** Link zu einer Folge. `Pattern.derive` macht daraus eine Vorlage
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

## Selbst-Update

Der Player hält sich selbst aktuell, wie HashRush. Beim Start fragt er den Cloudflare-Worker
`streamplayer-update` nach `version.json`. Gibt es dort eine höhere `versionCode`, lädt er die
APK still im Hintergrund, prüft die SHA-256-Summe und bietet sie dann an: „Installieren“ öffnet
Androids Installationsdialog. Beim ersten Mal fragt Android, ob Streamplayer Apps installieren
darf. Ganz ohne Tippen geht es nicht, still installieren darf nur ein App-Store. Von Hand:
Einstellungen → Über → „Nach Updates suchen“.

Veröffentlichen: `versionCode` und `versionName` in `app/build.gradle.kts` erhöhen, committen, dann

```
tools/publish.sh
```

Das Skript baut das Release aus einem sauberen Abzug von `HEAD` (halbfertige Änderungen im
Arbeitsordner kommen nicht mit) und teilt die APK in 20-MB-Stücke (Grenze des Workers:
25 MB pro Datei). Danach schreibt es `version.json`, lädt alles mit `wrangler deploy` hoch und
legt die APK nach `dist/`. Eine Nummer, die nicht höher ist als die veröffentlichte, lehnt es ab.
Es muss von diesem Rechner laufen, weil Android ein Update nur mit demselben Release-Schlüssel annimmt.

- Debug-Builds (`….debug`) fragen nie; sie sind anders signiert.
- Testen ohne Worker: `./gradlew :app:assembleRelease -Pupdate.url=http://10.0.2.2:8766` und
  `version.json` samt Stücken lokal ausliefern.
- Der Creator aktualisiert sich noch nicht selbst.

## Konto, Profile, Abgleich (Firebase)

Profile wie bei Netflix: eine gemeinsame Bibliothek, jedes Profil mit eigenem Fortschritt.
Profile gibt es auch ohne Konto. Mit Konto (Google oder E-Mail) werden Bibliothek, Profile,
Fortschritt und eigene Cover zwischen Geräten abgeglichen.

Bei mehreren Profilen fragt die App beim Start „Wer schaut?“ – abschaltbar unter Einstellungen →
Profile, dann öffnet das zuletzt gewählte. Als Start zählt ein kalter Start oder die Rückkehr nach
30 Minuten im Hintergrund (`ProfileStore.RESTART_AFTER`).

- **Das Gerät bleibt die Quelle.** Die App liest immer aus ihren lokalen Dateien; Firestore ist
  nur der Weg zwischen Geräten. Offline geht alles, Änderungen werden nachgeliefert.
- **Kosten:** gelesen wird nur, was seit dem letzten Abgleich neu ist (`updatedAt > since`), und
  nur im Vordergrund. Geschrieben wird nur Geändertes (Hash-Vergleich), Fortschritt erst nach
  20 s Ruhe. Bilder sind eigene Dokumente und werden nur geholt, wenn sie fehlen.
- **Nie im Konto:** der TMDB-Schlüssel, Downloads (Videodateien), welches Profil gerade schaut.

```
users/{uid}/titles/{titleId}                         { json, updatedAt, deleted }
users/{uid}/profiles/{profileId}                     { json, updatedAt, deleted }
users/{uid}/profiles/{profileId}/progress/{titleId}  { json, updatedAt, deleted }
users/{uid}/covers/{name}                            { data (Bytes, < 1 MB), updatedAt }
```

**Einrichtung:** `app/google-services.json` aus der Firebase-Konsole (gitignored; ohne die
Datei baut die App, das Konto zeigt „nicht eingerichtet“). In der Konsole: beide Android-Apps
(`de.marne.streamplayer`, `de.marne.streamplayer.debug`) mit SHA-1 der Signierschlüssel,
Authentication mit E-Mail und Google, Firestore-Datenbank.

**Regeln:** `firebase/firestore.rules` – jedes Konto nur seine eigenen Daten, alles andere zu.
Nach jeder Änderung die Tests laufen lassen (Firestore-Emulator, braucht Java):

```sh
cd firebase && npm install
npm test                       # Regeltests gegen den Emulator
npx firebase login             # einmalig
npm run deploy:rules           # Regeln ins Projekt streamplayer-eba4c
# oder mit Dienstkonto statt Login (Schlüssel bleibt in ~/Downloads, nie ins Repo):
GOOGLE_APPLICATION_CREDENTIALS=~/Downloads/<…adminsdk…>.json npm run deploy:rules:sa
```

**Testen ohne echtes Projekt:** `npm run emulators` startet Auth und Firestore lokal;
`./gradlew :app:assembleDebug -Pfirebase.emulator=10.0.2.2` baut einen Debug-Player, der im
Android-Emulator dagegen läuft (Demo-Projekt, keine echten Daten).

**Nie ins Repo oder in die App:** Dienstkonto-Schlüssel (`*-firebase-adminsdk-*.json`). Die App
braucht keinen; wer ihn hat, umgeht alle Regeln.

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
    ├── account/                     Konto (Firebase), Profile, Abgleich
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
./gradlew :app:assembleDebug        # Player-APK unter app/build/outputs/apk/debug/
./gradlew :creator:assembleDebug    # Creator-APK unter creator/build/outputs/apk/debug/
./gradlew testDebugUnitTest         # alle Unit-Tests (Kern, Player)
```

Der Debug-Build hat die ID `de.marne.streamplayer.debug` und läuft neben einem Release-Build.

**Signieren:** Das Release wird mit einem eigenen Schlüssel signiert. `keystore.properties`
(gitignored) zeigt auf `~/.android/streamplayer-release.jks`. Den Schlüssel sichern: Ohne ihn
lassen sich Updates nicht mehr über eine installierte Version spielen. Fehlt die Datei, nimmt
Gradle den Debug-Schlüssel.

## Testen ohne fremde Seiten

```sh
tools/testsite.sh                   # MP4 + HLS mit zwei Qualitäten auf Port 8765, dazu katalog/ für den Import
adb shell am start -a android.intent.action.VIEW -d http://10.0.2.2:8765/ \
  -n de.marne.streamplayer.debug/de.marne.streamplayer.MainActivity
```

Import im Creator: `katalog/testserie/` (Übersicht mit JSON-LD, Staffel-Reitern und Specials,
auch von einer Folge aus), `katalog/jsliste/` (Liste erst per JavaScript), `katalog/pruefung/`
(Vorschaltseite mit „Ich bin ein Mensch“), `katalog/testfilm.html`, `katalog/links.json`
(Linkliste für den Massenimport) und `katalog/uebersicht/` für „Ganze Übersicht“ (zwei Seiten,
Navigation in derselben Familie wie die Titel, ein Titel per `/robots.txt` gesperrt). Teilen per adb:

```sh
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "http://10.0.2.2:8765/katalog/testserie/staffel-2/episode-3.html" \
  -n de.marne.streamplayer.creator.debug/de.marne.streamplayer.creator.MainActivity
```

Hinweis zum Emulator auf diesem Rechner: `-gpu swiftshader_indirect` stürzt ab, sobald die
WebView rendert (SIGSEGV in qemu). Mit `-gpu host` läuft es.
