import AppKit
import SwiftUI

@MainActor
final class ReflectionAppDelegate: NSObject, NSApplicationDelegate {
    weak var model: ReflectionAppModel?

    func applicationWillTerminate(_ notification: Notification) {
        model?.terminateOwnedHost()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        true
    }
}

@main
struct ReflectionApp: App {
    @NSApplicationDelegateAdaptor(ReflectionAppDelegate.self) private var delegate
    @StateObject private var model = ReflectionAppModel()

    var body: some Scene {
        WindowGroup("Reflection") {
            ReflectionWindow(model: model)
                .frame(minWidth: 920, minHeight: 660)
                .onAppear { delegate.model = model }
        }
        .windowToolbarStyle(.unified)
        .commands {
            CommandGroup(replacing: .newItem) {}
            CommandGroup(after: .saveItem) {
                Button("Save Configuration") { model.saveConfiguration() }
                    .keyboardShortcut("s")
                Button("Reload Configuration") { model.reloadConfiguration() }
                    .keyboardShortcut("r", modifiers: [.command, .shift])
            }
        }
    }
}
