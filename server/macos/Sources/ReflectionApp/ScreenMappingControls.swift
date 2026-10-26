import AppKit
import CoreGraphics
import ReflectionCore
import SwiftUI

struct ScreenLayoutView: View {
    let status: MappingStatus
    let select: (String) -> Void

    var body: some View {
        GeometryReader { geometry in
            let left = status.displays.map(\.x).min() ?? 0
            let top = status.displays.map(\.y).min() ?? 0
            let width = max(1, (status.displays.map { $0.x + $0.width }.max() ?? 1) - left)
            let height = max(1, (status.displays.map { $0.y + $0.height }.max() ?? 1) - top)
            let scale = min((geometry.size.width - 20) / width, (geometry.size.height - 20) / height)
            let originX = (geometry.size.width - width * scale) / 2
            let originY = (geometry.size.height - height * scale) / 2
            ZStack(alignment: .topLeading) {
                Color.clear
                ForEach(Array(status.displays.enumerated()), id: \.element.id) { index, display in
                    let selected = status.mode == "all" || status.selected == display.id
                    Button { select(display.id) } label: {
                        VStack(spacing: 4) {
                            Text("\(index + 1)").font(.title2.weight(.semibold))
                            Text(display.name + (display.primary ? " · Main" : ""))
                                .font(.caption).lineLimit(1)
                        }
                        .frame(width: max(1, display.width * scale - 4), height: max(1, display.height * scale - 4))
                        .background(selected ? Color.accentColor.opacity(0.18) : Color.secondary.opacity(0.08))
                        .overlay(RoundedRectangle(cornerRadius: 6)
                            .stroke(selected ? Color.accentColor : Color.secondary.opacity(0.5), lineWidth: selected ? 2 : 1))
                        .cornerRadius(6)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Map tablet to \(display.name)")
                    .offset(x: originX + (display.x - left) * scale + 2,
                            y: originY + (display.y - top) * scale + 2)
                }
            }
        }.frame(height: 185)
    }
}

/// Local and global monitors cover disjoint event streams. Global key monitoring
/// requires this app's Accessibility permission; no event tap or key logging is used.
@MainActor
final class MappingShortcuts {
    private var global: Any?
    private var local: Any?

    init(action: @escaping (String) -> Void) {
        let command: (NSEvent) -> String? = { event in
            let modifiers = event.modifierFlags.intersection([.control, .option, .command, .shift])
            guard !event.isARepeat, modifiers == [.control, .option] else { return nil }
            switch event.keyCode {
            case 121: return "next"       // Page Down
            case 116: return "previous"   // Page Up
            case 29: return "all"         // 0
            default: return nil
            }
        }
        global = NSEvent.addGlobalMonitorForEvents(matching: .keyDown) { event in
            if let value = command(event) { action(value) }
        }
        local = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            if let value = command(event) { action(value); return nil }
            return event
        }
    }

    func stop() {
        if let global { NSEvent.removeMonitor(global); self.global = nil }
        if let local { NSEvent.removeMonitor(local); self.local = nil }
    }
}

@MainActor
final class MappingFeedback {
    private var panels: [NSPanel] = []
    private var dismiss: DispatchWorkItem?

    func show(_ status: MappingStatus) {
        hide()
        let selected = status.displays.filter { status.mode == "all" || $0.id == status.selected }
        let screens = NSScreen.screens.filter { screen in
            guard let id = (screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)?.uint32Value,
                  let description = try? availableDisplays().first(where: { $0.id == id }) else { return false }
            return selected.contains(where: { $0.id == description.stableID })
        }
        for screen in screens {
            let panel = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 300, height: 80),
                                styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
            panel.level = .floating
            panel.isReleasedWhenClosed = false
            panel.isOpaque = false
            panel.backgroundColor = .clear
            panel.hasShadow = true
            panel.ignoresMouseEvents = true
            panel.hidesOnDeactivate = false
            panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
            let text = status.mode == "all" ? "Reflection · All displays" : "Reflection · \(screen.localizedName)"
            panel.contentView = NSHostingView(rootView:
                VStack(spacing: 5) {
                    Text(text).font(.headline)
                    Text(status.mode == "all" ? "Tablet spans the desktop layout" : "Tablet mapped to this display")
                        .font(.caption).foregroundColor(.secondary)
                }.frame(width: 300, height: 80).background(.regularMaterial)
                    .clipShape(RoundedRectangle(cornerRadius: 15)))
            panel.setFrameOrigin(NSPoint(x: screen.visibleFrame.midX - 150, y: screen.visibleFrame.maxY - 108))
            panel.orderFrontRegardless()
            panels.append(panel)
        }
        let work = DispatchWorkItem { [weak self] in self?.hide() }
        dismiss = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8, execute: work)
    }

    func hide() {
        dismiss?.cancel(); dismiss = nil
        panels.forEach { $0.close() }
        panels.removeAll()
    }
}
