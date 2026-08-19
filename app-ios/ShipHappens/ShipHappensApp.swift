import BackgroundTasks
import SwiftUI
import SharedUI

private let dailyRefreshTaskId = "com.dgmltn.shiphappens.dailyrefresh"

class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Registration must happen before launch completes, which is why this lives in Swift
        // rather than in the shared Kotlin bootstrap.
        BGTaskScheduler.shared.register(forTaskWithIdentifier: dailyRefreshTaskId, using: nil) { task in
            IosDailyRefresh.shared.run { success in
                task.setTaskCompleted(success: success.boolValue)
            }
        }
        return true
    }
}

@main
struct ShipHappensApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    init() {
        AppModulesKt.doInitKoin()
    }
    var body: some Scene {
        WindowGroup {
            ComposeView().ignoresSafeArea()
        }
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }
    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
