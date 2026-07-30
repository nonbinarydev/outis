import UIKit
import SwiftUI
import OutisSample

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.mainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    // Driven by the shared UI's fullscreen toggle, via FullscreenBridge (see SampleWindow.ios.kt).
    @State private var statusBarHidden = false

    var body: some View {
        ComposeView()
                // Let Compose own the insets (the shared UI applies statusBarsPadding itself). Without
                // this, SwiftUI insets the safe area AND Compose adds it again → a doubled top gap.
                .ignoresSafeArea()
                // Hide the status bar in fullscreen (a scene-based SwiftUI app owns this, not Compose).
                .statusBarHidden(statusBarHidden)
                .onAppear {
                    FullscreenBridge.shared.onChange = {
                        statusBarHidden = FullscreenBridge.shared.isFullscreen
                    }
                }
    }
}



