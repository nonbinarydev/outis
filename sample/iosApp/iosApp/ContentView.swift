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
    var body: some View {
        ComposeView()
                // Let Compose own the insets (the shared UI applies statusBarsPadding itself). Without
                // this, SwiftUI insets the safe area AND Compose adds it again → a doubled top gap.
                .ignoresSafeArea()
    }
}



