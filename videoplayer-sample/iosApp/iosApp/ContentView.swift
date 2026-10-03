import Shared
import SwiftUI
import UIKit

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Self.Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Self.Context) {}
}

/// Hides the status bar and home indicator while a player is fullscreen.
final class FullscreenModel: ObservableObject {
    @Published var isFullscreen = false

    init() {
        MainViewControllerKt.observeFullscreenStatusBar { [weak self] hidden in
            self?.isFullscreen = hidden.boolValue
        }
    }
}

struct ContentView: View {
    @StateObject private var fullscreen = FullscreenModel()

    var body: some View {
        ComposeView()
            .ignoresSafeArea()
            .statusBarHidden(fullscreen.isFullscreen)
            .persistentSystemOverlays(fullscreen.isFullscreen ? .hidden : .automatic)
    }
}
