#!/usr/bin/env bash
# Baut eine lokale Testseite mit einer MP4 und einem HLS-Stream (zwei Qualitäten)
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
fi

echo "Testseite in $DIR – im Emulator: http://10.0.2.2:$PORT/"
exec python3 -m http.server "$PORT" --bind 0.0.0.0 --directory "$DIR"
