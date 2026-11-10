# Streamplayer – Hinweise für Claude-Sitzungen

Zwei Android-Apps in einem Repo: `app/` (Player „Streamplayer“), `creator/` (Kataloge bauen),
gemeinsamer Code in `core/`. Gesprochen und kommentiert wird Deutsch. Mehr in README.md.

## Neue Versionen kommen per Selbst-Update aufs Handy

- Wer Player oder Creator für den Nutzer fertig macht, erhöht `versionCode` (+1) und
  `versionName` in `app/build.gradle.kts` bzw. `creator/build.gradle.kts` und committet.
- Der Commit veröffentlicht von selbst: Der Hook `tools/git-hooks/post-commit` startet
  `tools/publish.sh` im Hintergrund. Danach `update/publish.log` prüfen, ob „Veröffentlicht:“
  dasteht oder ein Fehler. Notfalls `tools/publish.sh` von Hand ausführen.
- Ohne höhere `versionCode` kommt nichts beim Nutzer an. Ändert ein Commit eine App, gehört die
  Nummer dazu, auch wenn nur `core/` geändert ist (dann beide Apps, sofern beide es benutzen).
- Die fertigen APKs liegen danach in `dist/` (gitignored).

## Sonst

- Geheimnisse nie ins Repo: `keystore.properties`, `*.jks`, `google-services.json`,
  Firebase-Dienstkonto-Schlüssel (liegen in ~/Downloads – nicht verschieben, nicht ausgeben).
- Es können mehrere Sitzungen gleichzeitig in diesem Ordner und auf demselben Emulator
  arbeiten: nur eigene Dateien stagen, vor dem Tippen im Emulator prüfen, welche App vorne liegt.
