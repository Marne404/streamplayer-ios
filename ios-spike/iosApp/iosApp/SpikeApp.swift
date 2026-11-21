import AVFoundation
import Shared
import SwiftUI

@main
struct SpikeApp: App {
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

/// Die ganze Oberfläche kommt aus Kotlin (Compose Multiplatform).
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        AppKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
