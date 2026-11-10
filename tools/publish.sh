#!/bin/bash
# Stellt den Player als Update bereit: Release bauen, in Stücke teilen, version.json schreiben,
# auf den Worker „streamplayer-update“ hochladen, APK nach dist/.
#
# Vorher versionCode in app/build.gradle.kts erhöhen und committen – nur eine höhere Nummer gilt
# als Update. Gebaut wird aus einem sauberen Abzug von HEAD, nicht aus dem Arbeitsordner: was
# ausgeliefert wird, soll genau ein Commit sein, nicht halbfertige Änderungen daneben.
#
# Muss von diesem Rechner laufen: Android nimmt ein Update nur an, wenn es mit demselben
# Schlüssel signiert ist (~/.android/streamplayer-release.jks über keystore.properties).
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"
BASE=https://streamplayer-update.marne-gamingx.workers.dev

CODE=$(git show HEAD:app/build.gradle.kts | grep -oP 'versionCode = \K[0-9]+')
NAME=$(git show HEAD:app/build.gradle.kts | grep -oP 'versionName = "\K[^"]+')
LIVE=$(curl -fsS "$BASE/version.json" 2>/dev/null | grep -oP '"versionCode":\K[0-9]+' || echo 0)
if [ "$CODE" -le "$LIVE" ]; then
  echo "versionCode $CODE ist nicht höher als die veröffentlichte $LIVE – erst erhöhen und committen." >&2
  exit 1
fi
if ! git diff --quiet HEAD -- app core; then
  echo "Hinweis: Ungespeicherte Änderungen in app/ oder core/ kommen NICHT mit – gebaut wird HEAD ($(git rev-parse --short HEAD))."
fi

WORK=$(mktemp -d)
trap 'git -C "$ROOT" worktree remove --force "$WORK/src" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT
git worktree add --detach -q "$WORK/src" HEAD
# Was nicht im Repo liegt, aber zum Bauen gehört.
cp local.properties keystore.properties "$WORK/src/"
cp app/google-services.json "$WORK/src/app/"
(cd "$WORK/src" && ./gradlew -q :app:testDebugUnitTest :app:assembleRelease -Pupdate.url="$BASE")
APK="$WORK/src/app/build/outputs/apk/release/app-release.apk"

rm -rf update/public && mkdir -p update/public
split -b 20M -d "$APK" "update/public/streamplayer-$CODE.part"
PARTS=$(cd update/public && ls streamplayer-$CODE.part* | sed 's/.*/"&"/' | paste -sd,)
printf '{"versionCode":%s,"versionName":"%s","size":%s,"sha256":"%s","parts":[%s]}\n' \
  "$CODE" "$NAME" "$(stat -c %s "$APK")" "$(sha256sum "$APK" | cut -d' ' -f1)" "$PARTS" > update/public/version.json
(cd update && npx --yes wrangler deploy)
mkdir -p dist && cp "$APK" "dist/streamplayer-$NAME.apk"
echo "Veröffentlicht: $NAME ($CODE) → $BASE/version.json, APK in dist/streamplayer-$NAME.apk"
