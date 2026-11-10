#!/usr/bin/env bash
# Holt die Filterlisten, die als Startbestand ins APK kommen. Die App aktualisiert
# sie danach selbst (alle 4 Tage). Gleiche Listen, die uBlock Origin standardmäßig nutzt.
set -euo pipefail
cd "$(dirname "$0")/../app/src/main/assets/adblock"
fetch() { curl -sfL -m 120 -o "$1.tmp" "$2" && [ "$(wc -l < "$1.tmp")" -gt 1000 ] && mv "$1.tmp" "$1"; }
fetch easylist.txt    https://easylist.to/easylist/easylist.txt
fetch easyprivacy.txt https://easylist.to/easylist/easyprivacy.txt
fetch peterlowe.txt   "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=1&mimetype=plaintext"
date -u +%Y-%m-%dT%H:%M:%SZ > stand.txt
wc -l ./*.txt
