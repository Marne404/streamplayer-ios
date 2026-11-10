#!/usr/bin/env bash
# Baut lokale Testseiten: MP4 + HLS (index.html), Erkennung ohne Play (jw.html, vjs.html,
# config.html), Werbeblocker (ads.html) und Serienseiten für den Creator-Import (katalog/)
# und stellt sie auf Port 8765 bereit. Im Emulator: http://10.0.2.2:8765/
set -euo pipefail
DIR="${1:-${TMPDIR:-/tmp}/streamplayer-testsite}"
PORT="${PORT:-8765}"
mkdir -p "$DIR/hls"
cd "$DIR"

if [ ! -f sample.mp4 ]; then
  ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=640x360:rate=25 -f lavfi -i sine=frequency=440:sample_rate=44100 \
    -t 12 -c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart sample.mp4
  for v in "360:640x360:800k" "240:426x240:400k"; do
    IFS=: read -r h size br <<< "$v"
    ffmpeg -loglevel error -y -f lavfi -i "testsrc=size=$size:rate=25" -f lavfi -i sine=frequency=660:sample_rate=44100 -t 16 \
      -c:v libx264 -b:v "$br" -pix_fmt yuv420p -g 50 -c:a aac -b:a 96k \
      -f hls -hls_time 2 -hls_playlist_type vod -hls_segment_filename "hls/${h}p_seg%03d.ts" "hls/${h}p.m3u8"
  done
  cat > hls/master.m3u8 <<'M3U'
#EXTM3U
#EXT-X-VERSION:3
#EXT-X-STREAM-INF:BANDWIDTH=950000,RESOLUTION=640x360,CODECS="avc1.64001e,mp4a.40.2"
360p.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=520000,RESOLUTION=426x240,CODECS="avc1.640015,mp4a.40.2"
240p.m3u8
M3U
  cat > index.html <<'HTML'
<!doctype html>
<html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Testseite Streamplayer</title>
<meta property="og:title" content="Testvideo: Farbbalken">
<style>body{font-family:sans-serif;margin:16px;background:#f4f1ea;color:#1b1a17}video{width:100%;background:#000;margin:8px 0 20px}</style>
</head><body>
<h1>Testseite</h1>
<p>Progressiv (MP4):</p>
<video controls src="sample.mp4"></video>
<p>HLS (zwei Qualitäten), geladen per hls.js:</p>
<video id="hls" controls muted></video>
<script src="https://cdn.jsdelivr.net/npm/hls.js@1.5.20/dist/hls.min.js"></script>
<script>
  var v = document.getElementById('hls');
  if (window.Hls && Hls.isSupported()) { var h = new Hls(); h.loadSource('hls/master.m3u8'); h.attachMedia(v); }
  else { v.src = 'hls/master.m3u8'; }
</script>
</body></html>
HTML
  cat > jw.html <<'HTML'
<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>JW-Test</title><style>body{font-family:sans-serif;margin:16px;background:#f4f1ea}#player{height:200px;background:#222;color:#aaa;display:grid;place-items:center}</style></head>
<body><h1>JW Player (wie VOE)</h1><div id="player">Player – nicht gestartet</div>
<script>
// Nachbau der öffentlichen JW-Player-API. Wie bei VOE: Aufbau verzögert, kein Netzwerkzugriff vor Play.
setTimeout(function () {
  var items = [{ title: 'Episode 1 (JW)', image: 'poster.jpg',
    sources: [{ file: 'hls/master.m3u8', type: 'hls', label: 'Auto' }] }];
  var api = { getPlaylist: function () { return items; }, getPlaylistItem: function () { return items[0]; }, on: function () { return api; } };
  window.jwplayer = function (i) { return (i === undefined || i === 0 || i === 'player') ? api : {}; };
  document.getElementById('player').textContent = 'JW Player bereit – noch nicht gestartet';
}, 2500);
</script></body></html>
HTML
  cat > config.html <<'HTML'
<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Konfig-Test</title><style>body{font-family:sans-serif;margin:16px;background:#f4f1ea}</style></head>
<body><h1>Adresse nur im Code</h1><p>Kein Player, kein video-Tag – nur eine Konfiguration.</p>
<script>var playerConfig = {"title":"Konfig-Video","hls":"http:\/\/10.0.2.2:8765\/hls\/master.m3u8","autoplay":false};</script>
</body></html>
HTML
  cat > vjs.html <<'HTML'
<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Video.js-Test</title>
<link href="https://cdnjs.cloudflare.com/ajax/libs/video.js/8.10.0/video-js.min.css" rel="stylesheet">
<style>body{font-family:sans-serif;margin:16px;background:#f4f1ea}</style></head>
<body><h1>Video.js, preload=none</h1>
<video id="v" class="video-js" controls preload="none" width="340" height="200"></video>
<script src="https://cdnjs.cloudflare.com/ajax/libs/video.js/8.10.0/video.min.js"></script>
<script>videojs('v').src({ src: 'sample.mp4', type: 'video/mp4' });</script>
</body></html>
HTML
  cat > ads.html <<'HTML'
<!doctype html>
<html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Werbetest</title>
<style>body{font-family:sans-serif;margin:16px;background:#f4f1ea}.box{padding:16px;margin:10px 0;border:2px solid #333}</style>
<script async src="https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"></script>
<script async src="https://securepubads.g.doubleclick.net/tag/js/gpt.js"></script>
</head><body>
<h1>Werbetest</h1>
<div class="box">Normaler Inhalt bleibt sichtbar.</div>
<div class="box ad-slot" style="background:#e33;color:#fff">WERBEBANNER (sollte verschwinden)</div>
<ins class="adsbygoogle box" style="display:block;background:#e33;color:#fff">ADSENSE-FLÄCHE (sollte verschwinden)</ins>
<p id="status">Tipp irgendwo hin: ein Popunder versucht ein Fenster zu öffnen.</p>
<p><a href="https://example.com/" target="_blank">Echter Link mit target=_blank</a></p>
<script>
  document.addEventListener('click', function (e) {
    if (e.target.tagName === 'A') return;
    var w = window.open('https://example.org/popunder');
    document.getElementById('status').textContent = w ? 'Popup ging auf' : 'Popup blockiert';
  }, true);
</script>
</body></html>
HTML
  cat > playonly.html <<'HTML'
<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Erst nach Play</title><style>body{font-family:sans-serif;margin:16px;background:#f4f1ea}video{width:100%;background:#000}</style></head>
<body><h1>Video erst nach Play</h1><video id="v" controls></video>
<script src="https://cdn.jsdelivr.net/npm/hls.js@1.5.20/dist/hls.min.js"></script>
<script>
  // Die Quelle wird erst beim Start zusammengesetzt – vorher steht sie nirgends auf der Seite.
  var v = document.getElementById('v'), started = false;
  v.addEventListener('play', function () {
    if (started) return; started = true;
    var src = ['hls', 'master'].join('/') + '.m3u8';
    var h = new Hls(); h.loadSource(src); h.attachMedia(v);
  });
</script></body></html>
HTML
  cat > novideo.html <<'HTML'
<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Ohne Video</title></head><body><h1>Hier gibt es kein Video.</h1></body></html>
HTML
  # Mediathek: Film (3 Minuten, für Fortsetzen) und Serie (2 Staffeln à 3 Folgen)
  mkdir -p hls-long img
  ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=480x270:rate=24" -f lavfi -i "sine=frequency=330:sample_rate=44100" -t 180 \
    -c:v libx264 -preset veryfast -b:v 300k -g 48 -pix_fmt yuv420p -c:a aac -b:a 64k \
    -f hls -hls_time 4 -hls_playlist_type vod -hls_segment_filename "hls-long/seg%03d.ts" hls-long/index.m3u8
  ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=1280x720" -frames:v 1 img/film.jpg
  ffmpeg -loglevel error -y -f lavfi -i "testsrc=size=1280x720" -frames:v 1 img/serie.jpg
  cat > film.html <<'HTML'
<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Der Testfilm</title><meta property="og:title" content="Der Testfilm"><meta property="og:image" content="/img/film.jpg"><meta property="og:description" content="Drei Minuten Farbbalken in ruhigem Tempo. Ein Film über Geduld.">
<style>body{font-family:sans-serif;margin:16px;background:#f4f1ea}#p{height:200px;background:#222;color:#aaa;display:grid;place-items:center}</style></head>
<body><h1>Der Testfilm</h1><div id="p">Player</div>
<script>setTimeout(function(){var items=[{title:'Der Testfilm',sources:[{file:'/hls-long/index.m3u8',type:'hls'}]}];var api={getPlaylist:function(){return items},getPlaylistItem:function(){return items[0]},on:function(){return api}};window.jwplayer=function(){return api};},1500);</script>
</body></html>
HTML
  for s in 1 2; do for e in 1 2 3; do
    mkdir -p serie/staffel-$s
    cat > serie/staffel-$s/episode-$e.html <<HTML
<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Testserie – Staffel $s, Folge $e</title><meta property="og:title" content="Testserie – Staffel $s, Folge $e"><meta property="og:image" content="/img/serie.jpg"><meta property="og:description" content="Eine Serie aus Testbildern.">
<style>body{font-family:sans-serif;margin:16px;background:#f4f1ea}#p{height:200px;background:#222;color:#aaa;display:grid;place-items:center}</style></head>
<body><h1>Testserie – Staffel $s, Folge $e</h1><div id="p">Player</div>
<script>setTimeout(function(){var items=[{title:'Testserie – Staffel $s, Folge $e',sources:[{file:'/hls/master.m3u8',type:'hls'}]}];var api={getPlaylist:function(){return items},getPlaylistItem:function(){return items[0]},on:function(){return api}};window.jwplayer=function(){return api};},1500);</script>
</body></html>
HTML
  done; done
fi

# „Aus Link übernehmen“ (Creator): nachgebaute Serienseiten. Keine echte Seite, nur übliche Formen.
#  katalog/testserie/   Übersicht mit JSON-LD, Staffel-Reitern, Staffel 1 sichtbar, Film als Special
#  katalog/jsliste/     Folgenliste erst per JavaScript (braucht die WebView)
#  katalog/testfilm.html Film mit JSON-LD
if [ ! -d katalog ]; then
  mkdir -p katalog/testserie/filme katalog/jsliste
  player() { # $1 = Titel, $2 = Stream
    printf '%s' "<div id=\"p\">Player</div><script>setTimeout(function(){var items=[{title:'$1',sources:[{file:'$2',type:'hls'}]}];var api={getPlaylist:function(){return items},getPlaylistItem:function(){return items[0]},on:function(){return api}};window.jwplayer=function(){return api};},1500);</script>"
  }
  head() { printf '%s' "<!doctype html><html lang=\"de\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>$1</title><style>body{font-family:sans-serif;margin:16px;background:#f4f1ea}a{display:inline-block;margin:4px 8px 4px 0}#p{height:180px;background:#222;color:#aaa;display:grid;place-items:center}</style>$2</head><body>"; }
  NAV='<nav><a href="/katalog/">Start</a> <a href="/katalog/genre/action/">Action</a> <a href="/katalog/andere/staffel-1/episode-1.html">Andere Serie</a> <a href="/katalog/testserie-2/staffel-1/episode-1.html">Testserie 2</a> <a href="/katalog/testserie-2/staffel-1/episode-2.html">Testserie 2, Folge 2</a></nav>'
  COUNTS=(0 4 3 5)
  # Schleifenvariablen lokal: sonst überschreiben tabs/list das s und e der äußeren Schleifen.
  tabs() { local t='<p>' s; for s in 1 2 3; do t+="<a href=\"/katalog/testserie/staffel-$s/\">Staffel $s</a>"; done; t+='<a href="/katalog/testserie/filme/film-1.html">Film</a> <a href="/katalog/testserie/">Zur Serie</a></p>'; printf '%s' "$t"; }
  list() { local t='<ul>' e; for ((e = 1; e <= COUNTS[$1]; e++)); do t+="<li><a href=\"/katalog/testserie/staffel-$1/episode-$e.html\">Folge $e</a></li>"; done; printf '%s' "$t</ul>"; }
  LD='<meta property="og:site_name" content="Testkatalog"><meta property="og:image" content="/img/serie.jpg"><script type="application/ld+json">{"@context":"https://schema.org","@type":"TVSeries","name":"Importserie","startDate":"2021-03-01","description":"Eine Serie aus Testbildern, nur zum Ausprobieren des Imports."}</script>'
  { head "Importserie – Stream | Testkatalog" "$LD"; printf '%s' "$NAV<h1>Importserie</h1>"; tabs; list 1; echo '</body></html>'; } > katalog/testserie/index.html
  for s in 1 2 3; do
    mkdir -p katalog/testserie/staffel-$s
    { head "Importserie Staffel $s | Testkatalog" ""; printf '%s' "$NAV<h1>Importserie – Staffel $s</h1>"; tabs; list $s; echo '</body></html>'; } > katalog/testserie/staffel-$s/index.html
    for ((e = 1; e <= COUNTS[s]; e++)); do
      { head "Importserie Staffel $s Folge $e | Testkatalog" ""; printf '%s' "$NAV<h1>Importserie – Staffel $s, Folge $e</h1>"; player "Importserie $s/$e" /hls/master.m3u8; tabs; list $s; echo '</body></html>'; } \
        > katalog/testserie/staffel-$s/episode-$e.html
    done
  done
  { head "Importserie – Der Film | Testkatalog" ""; printf '%s' "$NAV<h1>Importserie – Der Film</h1>"; player "Importserie Film" /hls-long/index.m3u8; tabs; echo '</body></html>'; } > katalog/testserie/filme/film-1.html

  { head "JS-Serie | Testkatalog" ""; cat <<'JS'
<h1>JS-Serie</h1><ul id="l"><li>Lade Folgen …</li></ul>
<script>setTimeout(function () {
  var h = ''; for (var i = 1; i <= 6; i++) h += '<li><a href="/katalog/jsliste/folge-' + i + '.html">Folge ' + i + '</a></li>';
  document.getElementById('l').innerHTML = h;
}, 800);</script></body></html>
JS
  } > katalog/jsliste/index.html
  for e in 1 2 3 4 5 6; do
    { head "JS-Serie Folge $e" ""; player "JS-Serie $e" /hls/master.m3u8; echo '<a href="/katalog/jsliste/">Alle Folgen</a></body></html>'; } > katalog/jsliste/folge-$e.html
  done

  { head "Der Importfilm online anschauen | Testkatalog" '<script type="application/ld+json">{"@context":"https://schema.org","@type":"Movie","name":"Der Importfilm","datePublished":"2020-05-01","image":"/img/film.jpg","description":"Ein Film zum Testen des Imports."}</script>'
    printf '%s' "$NAV<h1>Der Importfilm</h1>"; player "Der Importfilm" /hls-long/index.m3u8; echo '</body></html>'; } > katalog/testfilm.html
fi

# Vorschaltseite wie bei Captcha-Diensten: erst nach „Ich bin ein Mensch“ (Cookie) erscheint die
# Serie, und zwar unter derselben Adresse. Prüft die sichtbare WebView im Creator.
if [ ! -d katalog/pruefung ]; then
  mkdir -p katalog/pruefung
  cat > katalog/pruefung/index.html <<'HTML'
<!doctype html><html lang="de"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Einen Moment bitte …</title><style>body{font-family:sans-serif;margin:24px;background:#f4f1ea}button{font-size:20px;padding:16px 24px}</style></head>
<body><div id="c"><h1>Einen Moment bitte …</h1><p>Wir prüfen, ob du ein Mensch bist.</p><button id="b">Ich bin ein Mensch</button></div>
<script>
if (document.cookie.indexOf('mensch=1') >= 0) {
  document.title = 'Prüfserie';
  var h = '<h1>Prüfserie</h1><ul>';
  for (var i = 1; i <= 8; i++) h += '<li><a href="/katalog/pruefung/folge-' + i + '.html">Folge ' + i + '</a></li>';
  document.getElementById('c').innerHTML = h + '</ul>';
} else {
  document.getElementById('b').onclick = function () { document.cookie = 'mensch=1; path=/'; location.reload(); };
}
</script></body></html>
HTML
fi

# Linkliste für den Massenimport im Creator (Aus Datei oder geteilt).
if [ ! -f katalog/links.json ]; then
  cat > katalog/links.json <<'JSON'
{
  "links": [
    "http://10.0.2.2:8765/katalog/testserie/",
    { "url": "http://10.0.2.2:8765/katalog/jsliste/", "title": "Die JS-Serie" },
    "http://10.0.2.2:8765/katalog/testfilm.html",
    "http://10.0.2.2:8765/katalog/gibtsnicht/"
  ]
}
JSON
fi

echo "Testseite in $DIR – im Emulator: http://10.0.2.2:$PORT/"
exec python3 -m http.server "$PORT" --bind 0.0.0.0 --directory "$DIR"
