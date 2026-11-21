import AVFoundation
import Shared
import SwiftUI

@main
struct StreamplayerApp: App {
    init() {
        // Als Wiedergabe-App anmelden: Voraussetzung für Bild im Bild und Ton im Hintergrund.
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playback, mode: .moviePlayback)
        try? session.setActive(true)
    }

    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}

/// Die ganze Oberfläche kommt aus Kotlin (Modul :ui, Compose Multiplatform) – dieselben Screens wie auf Android.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        PreviewAppKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
