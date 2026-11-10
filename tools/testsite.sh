#!/usr/bin/env bash
# Baut lokale Testseiten: MP4 + HLS (index.html), Erkennung ohne Play (jw.html, vjs.html,
# config.html) und Werbeblocker (ads.html)
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
fi

echo "Testseite in $DIR – im Emulator: http://10.0.2.2:$PORT/"
exec python3 -m http.server "$PORT" --bind 0.0.0.0 --directory "$DIR"
