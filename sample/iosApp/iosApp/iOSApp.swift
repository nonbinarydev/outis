import SwiftUI
import FirebaseCore

@main
struct iOSApp: App {
    init() {
        // GoogleService-Info.plist is git-ignored; without it the app runs Firebase-free.
        if Bundle.main.path(forResource: "GoogleService-Info", ofType: "plist") != nil {
            FirebaseApp.configure()
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
