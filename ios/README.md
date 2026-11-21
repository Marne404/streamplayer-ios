# iPad-App (Compose Multiplatform)

Die Oberfläche kommt aus dem Modul `:ui` (dieselben Screens wie die Android-App). Diese Hülle
wird ohne Mac auf GitHub Actions gebaut: Workflow `.github/workflows/ios.yml`, läuft nur im
öffentlichen Build-Repo `Marne404/streamplayer-ios` (Remote `ios`), nicht im privaten Repo.

- Ergebnis: unsignierte `.ipa` als Release `ipad-<Lauf>`, SideStore-Quelle
  https://marne404.github.io/streamplayer-ios/source.json – SideStore signiert beim Installieren.
- Stand Etappe 1: Vorschau mit erfundenen Beispieltiteln (`ui/src/iosMain/…/ios/DemoData.kt`).
- Schnell prüfen ohne Mac: `./gradlew :ui:compileKotlinIosArm64` (kompiliert, linkt nicht).
