import SwiftUI
import UIKit
import RavenShared

struct RavenComposeView: UIViewControllerRepresentable {
    func makeCoordinator() -> RavenRuntime { RavenRuntime() }

    func makeUIViewController(context: Context) -> UIViewController {
        let controller = context.coordinator.host.viewController()
        context.coordinator.presenter = controller
        return controller
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    static func dismantleUIViewController(_ uiViewController: UIViewController, coordinator: RavenRuntime) {
        coordinator.shutdown()
    }
}

@main
struct RavenEmuApp: App {
    var body: some Scene {
        WindowGroup {
            RavenComposeView().ignoresSafeArea(edges: .bottom)
        }
    }
}
