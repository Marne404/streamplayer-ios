#!/bin/bash
# Veröffentlicht Player, Creator und Admin als Selbst-Update auf dem Worker „streamplayer-update“:
#   /version.json            + streamplayer-<code>.partNN   (Player)
#   /creator/version.json    + creator-<code>.partNN        (Creator)
#   /admin/version.json      + admin-<code>.partNN          (Admin, ohne Schlüssel)
#
# Veröffentlicht wird jede App, deren versionCode in HEAD höher ist als die live – die andere wird
# vom Worker zurückgeladen und unverändert mit hochgeladen (ein Deploy ersetzt immer alle Dateien).
# Ist nichts neuer, endet das Skript sofort. Darum kann es nach jedem Commit laufen
# (tools/git-hooks/post-commit, eingeschaltet mit `git config core.hooksPath tools/git-hooks`).
#
# Gebaut wird aus einem sauberen Abzug von HEAD, nicht aus dem Arbeitsordner: was ausgeliefert
# wird, soll genau ein Commit sein, nicht halbfertige Änderungen daneben.
# Muss von diesem Rechner laufen: Android nimmt ein Update nur an, wenn es mit demselben
# Schlüssel signiert ist (~/.android/streamplayer-release.jks über keystore.properties).
set -euo pipefail
# Aus einem Hook gestartet, setzt Git GIT_DIR, GIT_INDEX_FILE & Co. – die gälten sonst auch im
# Hilfs-Abzug weiter unten, und `git worktree add` griffe auf den falschen Index.
unset $(git rev-parse --local-env-vars)
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
BASE=https://streamplayer-update.marne-gamingx.workers.dev
STAGE="$ROOT/update/public"

# Nie zwei Läufe gleichzeitig (zwei Commits kurz hintereinander).
mkdir -p update
exec 9>"$ROOT/update/.lock"
flock 9

# Modul : Pfad beim Worker : Name der Stücke : Name in dist/
# Admin: öffentlich ohne eingebauten Schlüssel (-Pstats.noToken); die installierte App hat ihn gemerkt.
APPS=("app::streamplayer:streamplayer" "creator:creator/:creator:streamplayer-creator" "admin:admin/:admin:streamplayer-admin")

code_of() { git show "HEAD:$1/build.gradle.kts" | grep -oP 'versionCode = \K[0-9]+'; }
name_of() { git show "HEAD:$1/build.gradle.kts" | grep -oP 'versionName = "\K[^"]+'; }

rm -rf "$STAGE" && mkdir -p "$STAGE"
TODO=()
for spec in "${APPS[@]}"; do
  IFS=: read -r module prefix stem dist <<<"$spec"
  live=$(curl -fsS "$BASE/${prefix}version.json" 2>/dev/null || true)
  livecode=$(grep -oP '"versionCode":\K[0-9]+' <<<"$live" || echo 0)
  if [ "$(code_of "$module")" -gt "$livecode" ]; then
    TODO+=("$spec")
  elif [ -n "$live" ]; then
    # Unverändert: die veröffentlichte Fassung wieder mitnehmen.
    mkdir -p "$STAGE/$prefix"
    printf '%s\n' "$live" > "$STAGE/${prefix}version.json"
    for part in $(grep -oP '"parts":\[\K[^]]*' <<<"$live" | tr -d '"' | tr , ' '); do
      curl -fsS "$BASE/$prefix$part" -o "$STAGE/$prefix$part"
    done
  fi
done

if [ ${#TODO[@]} -eq 0 ]; then
  echo "$(date '+%F %T') Nichts Neues – Player $(code_of app), Creator $(code_of creator), Admin $(code_of admin) sind schon veröffentlicht."
  exit 0
fi

echo "$(date '+%F %T') Veröffentliche aus $(git rev-parse --short HEAD): ${TODO[*]}"
WORK=$(mktemp -d)
trap 'git -C "$ROOT" worktree remove --force "$WORK/src" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT
git worktree add --detach -q "$WORK/src" HEAD
# Was nicht im Repo liegt, aber zum Bauen gehört.
cp local.properties keystore.properties "$WORK/src/"
cp app/google-services.json "$WORK/src/app/"

TASKS=(:core:testDebugUnitTest)
for spec in "${TODO[@]}"; do
  IFS=: read -r module _ <<<"$spec"
  TASKS+=(":$module:testDebugUnitTest" ":$module:assembleRelease")
done
(cd "$WORK/src" && ./gradlew -q "${TASKS[@]}" -Pupdate.url="$BASE" -Pstats.noToken=1)

mkdir -p dist
for spec in "${TODO[@]}"; do
  IFS=: read -r module prefix stem dist <<<"$spec"
  code=$(code_of "$module"); name=$(name_of "$module")
  apk="$WORK/src/$module/build/outputs/apk/release/$module-release.apk"
  mkdir -p "$STAGE/$prefix"
  # In Stücken: eine Datei des Workers darf höchstens 25 MB groß sein.
  split -b 20M -d "$apk" "$STAGE/$prefix$stem-$code.part"
  parts=$(cd "$STAGE/$prefix" && ls "$stem-$code".part* | sed 's/.*/"&"/' | paste -sd,)
  printf '{"versionCode":%s,"versionName":"%s","size":%s,"sha256":"%s","parts":[%s]}\n' \
    "$code" "$name" "$(stat -c %s "$apk")" "$(sha256sum "$apk" | cut -d' ' -f1)" "$parts" > "$STAGE/${prefix}version.json"
  cp "$apk" "dist/$dist-$name.apk"
done

(cd update && npx --yes wrangler deploy)
for spec in "${TODO[@]}"; do
  IFS=: read -r module prefix stem dist <<<"$spec"
  echo "$(date '+%F %T') Veröffentlicht: $module $(name_of "$module") ($(code_of "$module")) → $BASE/${prefix}version.json, dist/$dist-$(name_of "$module").apk"
done
