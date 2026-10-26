import AppKit
import ReflectionCore
import SwiftUI

// Keep the property-wrapper state API available to Command Line Tools builds.
private typealias ViewState<Value> = SwiftUI.State<Value>

private enum SettingsPage: String, CaseIterable, Identifiable {
    case overview = "Overview", pen = "Pen response", mapping = "Mapping"
    case profiles = "Profiles", connection = "Connection", diagnostics = "Diagnostics"
    var id: String { rawValue }
    var icon: String {
        switch self {
        case .overview: return "rectangle.and.pencil.and.ellipsis"
        case .pen: return "pencil.tip.crop.circle"
        case .mapping: return "display"
        case .profiles: return "square.stack"
        case .connection: return "cable.connector"
        case .diagnostics: return "waveform.path.ecg"
        }
    }
    var caption: String {
        switch self {
        case .overview: return "A direct connection between your pen and your desktop."
        case .pen: return "Shape the response to match the way you draw."
        case .mapping: return "Choose where each movement lands."
        case .profiles: return "Keep a setup for every kind of work."
        case .connection: return "Connect a tablet over USB, Wi-Fi or Bluetooth."
        case .diagnostics: return "Inspect the host, permissions and incoming pen data."
        }
    }
}

struct ReflectionWindow: View {
    @ObservedObject var model: ReflectionAppModel
    @ViewState private var selected: SettingsPage? = .overview
    @ViewState private var revealToken = false
    @ViewState private var confirmDelete = false

    var body: some View {
        navigation
            .tint(Color(red: 0.02, green: 0.48, blue: 0.46))
            .alert("Reflection", isPresented: Binding(
                get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
                    Button("OK", role: .cancel) { model.error = nil }
                } message: { Text(model.error ?? "") }
            .confirmationDialog("Delete the selected profile?", isPresented: $confirmDelete) {
                Button("Delete profile", role: .destructive) { model.deleteSelectedProfile() }
            } message: {
                Text("This removes the saved profile. The settings in the editor are kept.")
            }
    }

    @ViewBuilder
    private var navigation: some View {
        if #available(macOS 13, *) {
            NavigationSplitView {
                sidebar.navigationSplitViewColumnWidth(min: 185, ideal: 210, max: 250)
            } detail: { detail }
        } else {
            NavigationView {
                sidebar.frame(minWidth: 185, idealWidth: 210, maxWidth: 250)
                detail
            }
        }
    }

    private var sidebar: some View {
        List(selection: $selected) {
            Section {
                ForEach(SettingsPage.allCases) { page in
                    Label(page.rawValue, systemImage: page.icon).tag(page)
                        .padding(.vertical, 5)
                }
            } header: {
                Text("TABLET CONTROL").font(.system(size: 10, weight: .semibold, design: .monospaced))
            }
        }
        .listStyle(.sidebar)
        .safeAreaInset(edge: .bottom) {
            VStack(alignment: .leading, spacing: 5) {
                Text("Reflection").font(.system(.title3, design: .rounded).weight(.semibold))
                Text("Pen to desktop").font(.caption).foregroundColor(.secondary)
            }.frame(maxWidth: .infinity, alignment: .leading).padding(20)
        }
    }

    private var detail: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text((selected ?? .overview).rawValue)
                            .font(.system(size: 30, weight: .semibold, design: .rounded))
                        Text((selected ?? .overview).caption).foregroundColor(.secondary)
                    }.padding(.bottom, 2)
                    pageContent
                    if !model.notice.isEmpty {
                        Text(model.notice).font(.caption).foregroundColor(.secondary)
                            .textSelection(.enabled)
                    }
                }
                .frame(maxWidth: 780, alignment: .leading)
                .padding(28)
                .frame(maxWidth: .infinity)
            }
            Divider()
            controlBar.padding(.horizontal, 22).padding(.vertical, 14)
        }
        .background {
            ZStack {
                Color(nsColor: .windowBackgroundColor)
                LinearGradient(colors: [Color.accentColor.opacity(0.045), .clear],
                               startPoint: .topTrailing, endPoint: .center)
            }
        }
        .navigationTitle("Reflection")
        .toolbar {
            ToolbarItem {
                Button { model.reloadConfiguration() } label: {
                    Image(systemName: "arrow.clockwise")
                }.help("Reload saved configuration")
            }
        }
    }

    @ViewBuilder
    private var pageContent: some View {
        switch selected ?? .overview {
        case .overview: overview
        case .pen: pen
        case .mapping: mapping
        case .profiles: profiles
        case .connection: connection
        case .diagnostics: diagnostics
        }
    }

    private var controlBar: some View {
        VStack(alignment: .leading, spacing: 9) {
            if model.externalConfigurationChange {
                Text("Configuration changed outside this window. Reload before saving.")
                    .font(.caption).foregroundColor(.orange)
            }
            HStack(spacing: 12) {
                HStack(spacing: 8) {
                    Circle().fill(model.running ? Color.accentColor : Color.secondary.opacity(0.5))
                        .frame(width: 7, height: 7)
                    Text(model.connectionLabel).font(.callout)
                }
                .padding(.horizontal, 12).padding(.vertical, 8)
                .modifier(ControlMaterial())
                Spacer()
                if model.hasChanges {
                    Text("Unsaved changes").font(.caption).foregroundColor(.secondary)
                }
                Button("Save") { model.saveConfiguration() }.disabled(model.busy || !model.hasChanges)
                Button(model.running ? "Apply & restart" : "Apply & start") { model.applyAndRestart() }
                    .disabled(model.busy)
                Button(model.running ? "Stop host" : "Start host") {
                    if model.running { model.stop() } else { model.start() }
                }
                .buttonStyle(.borderedProminent)
                .disabled(model.busy)
            }
        }
    }

    private var overview: some View {
        VStack(alignment: .leading, spacing: 18) {
            SettingsCard {
                HStack(alignment: .top, spacing: 22) {
                    Image(systemName: model.connected && model.running ? "pencil.tip.crop.circle.badge.checkmark" : "pencil.tip.crop.circle")
                        .font(.system(size: 44, weight: .light)).foregroundColor(.accentColor)
                        .frame(width: 56)
                    VStack(alignment: .leading, spacing: 9) {
                        Text(model.connectionLabel).font(.title2.weight(.medium))
                        Text(model.running
                            ? "The host is listening for a paired tablet. Pen data appears here once it arrives."
                            : "Start the host, then connect from the Reflection tablet app.")
                            .foregroundColor(.secondary)
                    }
                    Spacer(minLength: 0)
                }
            }
            HStack(spacing: 14) {
                metric("Samples received", value: "\(model.samples)", symbol: "waveform.path")
                metric("Protocol errors", value: "\(model.protocolErrors)", symbol: "exclamationmark.shield")
                metric("Mapping mode", value: model.configuration.mapping.mode.capitalized, symbol: "display")
            }
            SettingsCard {
                VStack(alignment: .leading, spacing: 15) {
                    Text("Latest pen sample").font(.headline)
                    sampleRows
                    Text("The status view refreshes once per second. It is a diagnostic snapshot, not a drawing preview.")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            SettingsCard {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Your next setup").font(.headline)
                    Text("Tune pressure response, choose a display or active area, then save the result as a profile.")
                        .foregroundColor(.secondary)
                    HStack {
                        Button("Tune pen") { selected = .pen }
                        Button("Choose mapping") { selected = .mapping }
                        Button("Manage profiles") { selected = .profiles }
                    }
                }
            }
        }
    }

    private var pen: some View {
        VStack(spacing: 18) {
            SettingsCard {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Host stabilization").font(.headline)
                    Toggle("Smooth pen movement on this computer", isOn: $model.configuration.stabilization.enabled)
                    HStack {
                        Text("Strength")
                        Slider(value: $model.configuration.stabilization.strength, in: 0...1)
                        Text("\(Int(model.configuration.stabilization.strength * 100))%")
                            .monospacedDigit().frame(width: 42)
                    }.disabled(!model.configuration.stabilization.enabled)
                    Toggle("Adapt to changing pen noise", isOn: $model.configuration.stabilization.adaptiveNoise)
                        .disabled(!model.configuration.stabilization.enabled)
                    Text("Adjusts smoothing limits as pen noise changes. Your saved noise calibration stays unchanged.")
                        .font(.caption).foregroundColor(.secondary)
                    Toggle("Steady horizontal and vertical strokes", isOn: $model.configuration.stabilization.independentAxes)
                        .disabled(!model.configuration.stabilization.enabled)
                    Text("Experimental. Reduces sideways wobble; may alter fine curves and diagonal strokes.")
                        .font(.caption).foregroundColor(.secondary)
                    Text("Save and apply to change smoothing. Jump protection remains active when smoothing is off. Pressure and pen transitions are not delayed.")
                        .font(.caption).foregroundColor(.secondary)
                    if model.stabilizationStatus["legacy_client"] as? Bool == true {
                        Text("This tablet uses an older client. Update it to send raw samples before enabling host smoothing or calibration.")
                            .font(.caption).foregroundColor(.orange)
                    }
                    Text("Saved noise estimate: \(model.configuration.stabilization.noise, specifier: "%.6f") of the tablet surface")
                        .font(.caption).foregroundColor(.secondary)
                    if let x = model.stabilizationStatus["estimated_noise_x"] as? Double,
                       let y = model.stabilizationStatus["estimated_noise_y"] as? Double {
                        Text("Current noise estimate: X \(x, specifier: "%.6f") · Y \(y, specifier: "%.6f")")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    Divider()
                    Text("Calibrate noise").font(.subheadline.weight(.semibold))
                    Text("Start, then touch down once and hold lightly for at least 1.2 seconds. Small hand drift is OK. Input pauses during calibration; lift the pen before finishing.")
                        .font(.caption).foregroundColor(.secondary)
                    if let calibration = model.calibration {
                        ProgressView(value: calibration.progress)
                        Text("\(calibration.samples) samples · \(calibration.durationMS / 1000, specifier: "%.1f") s · \(calibration.phase)")
                            .font(.caption).monospacedDigit()
                        Text(calibration.reason).font(.caption).foregroundColor(.secondary)
                    }
                    HStack {
                        Button("Start calibration") { model.calibrate("start") }.disabled(model.calibrationActive)
                        Button("Finish calibration") { model.calibrate("finish") }.disabled(!model.calibrationActive)
                        Button("Cancel") { model.calibrate("cancel") }.disabled(!model.calibrationActive)
                    }.disabled(!model.running || model.busy)
                    DisclosureGroup("Advanced stabilization") {
                        VStack(spacing: 10) {
                            HStack { Text("Minimum cutoff (Hz)"); DecimalField("Minimum cutoff", value: $model.configuration.stabilization.minCutoffHz) }
                            HStack { Text("Speed response"); DecimalField("Speed response", value: $model.configuration.stabilization.beta) }
                            HStack { Text("Derivative cutoff (Hz)"); DecimalField("Derivative cutoff", value: $model.configuration.stabilization.derivativeCutoffHz) }
                            HStack { Text("Jump limit (surface fraction)"); DecimalField("Jump limit", value: $model.configuration.stabilization.maxJump) }
                        }.padding(.top, 10)
                    }
                }
            }
            SettingsCard {
                PressureEditor(mapping: $model.configuration.mapping, livePressure: model.rawPressure,
                               hasLiveSample: model.running && !model.lastSample.isEmpty)
            }
            SettingsCard {
                VStack(alignment: .leading, spacing: 16) {
                    Toggle("Use pen tilt", isOn: $model.configuration.mapping.tiltEnabled)
                    HStack {
                        Text("Tilt scale").frame(width: 75, alignment: .leading)
                        Slider(value: $model.configuration.mapping.tiltScale, in: 0...2)
                        DecimalField("Tilt scale", value: $model.configuration.mapping.tiltScale)
                            .frame(width: 84)
                    }.disabled(!model.configuration.mapping.tiltEnabled)
                    Text("1 preserves the reported tilt. Pen tilt and eraser recognition also depend on the tablet hardware and drawing app.")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
        }
    }

    private var mapping: some View {
        VStack(spacing: 18) {
            SettingsCard {
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        Text("Active display mapping").font(.headline)
                        Spacer()
                        Button("Identify") { model.identifyMapping() }.disabled(!model.running)
                    }
                    Text(model.mappingLabel).font(.callout)
                    if let status = model.liveMapping, model.running {
                        ScreenLayoutView(status: status) { model.switchMapping("select", displayID: $0) }
                            .disabled(model.busy)
                    }
                    HStack {
                        Button("All displays") { model.switchMapping("all") }
                        Button("Previous display") { model.switchMapping("previous") }
                        Button("Next display") { model.switchMapping("next") }
                    }.disabled(!model.running || model.busy)
                    Text("Click a screen to map the tablet to it. All displays follows the arrangement in System Settings, including gaps. Switching waits until you lift the pen.")
                        .font(.caption).foregroundColor(.secondary)
                    Toggle("Display switching shortcuts", isOn: $model.mappingShortcutsEnabled)
                    Text("Control–Option–Page Down / Page Up switches displays; Control–Option–0 maps all displays. Keep Reflection open. Global shortcuts require Reflection's Accessibility permission.")
                        .font(.caption).foregroundColor(.secondary)
                    Text("Live changes also appear on a connected tablet. Choose the startup display below to save a default.")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            SettingsCard {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Startup destination").font(.headline)
                    Picker("Movement", selection: $model.configuration.mapping.mode) {
                        Text("Absolute position").tag("absolute")
                        Text("Relative movement").tag("relative")
                    }
                    Picker("Display", selection: Binding<String>(
                        get: { model.configuration.mapping.displayUUID
                            ?? model.displays.first(where: { $0.id == model.configuration.mapping.displayID })?.stableID ?? "" },
                        set: {
                            model.configuration.mapping.displayUUID = $0.isEmpty ? nil : $0
                            model.configuration.mapping.displayID = nil
                        })) {
                            Text("All displays (system layout)").tag("")
                            ForEach(model.displays, id: \.id) { display in
                                Text("Display \(display.id) - \(display.pixelWidth) x \(display.pixelHeight)")
                                    .tag(display.stableID)
                            }
                        }.disabled(model.configuration.mapping.target != nil)
                    Toggle("Custom desktop area", isOn: Binding(
                        get: { model.configuration.mapping.target != nil },
                        set: { enabled in
                            model.configuration.mapping.target = enabled
                                ? (model.displays.first(where: \.main)?.bounds
                                    ?? Region(x: 0, y: 0, width: 1440, height: 900)) : nil
                        }))
                    if model.configuration.mapping.target != nil {
                        RegionEditor(region: Binding(
                            get: { model.configuration.mapping.target ?? Region(x: 0, y: 0, width: 1, height: 1) },
                            set: { model.configuration.mapping.target = $0 }), prefix: "Target")
                        Text("Desktop coordinates in Quartz points. Negative origins are allowed for secondary displays.")
                            .font(.caption).foregroundColor(.secondary)
                    }
                    if model.configuration.mapping.mode == "relative" {
                        HStack {
                            Text("Relative sensitivity")
                            Spacer()
                            DecimalField("Relative sensitivity", value: $model.configuration.mapping.relativeSensitivity)
                                .frame(width: 100)
                        }
                    }
                }
            }
            SettingsCard {
                VStack(alignment: .leading, spacing: 16) {
                    HStack {
                        Text("Tablet active area").font(.headline)
                        Spacer()
                        Button("Full tablet") {
                            model.configuration.mapping.source = Region(x: 0, y: 0, width: 1, height: 1)
                        }
                    }
                    RegionEditor(region: $model.configuration.mapping.source, prefix: "Source")
                    Text("Normalized coordinates from 0 to 1 within the tablet input surface.")
                        .font(.caption).foregroundColor(.secondary)
                    Picker("Rotation", selection: $model.configuration.mapping.rotation) {
                        ForEach([0, 90, 180, 270], id: \.self) { value in
                            Text("\(value) degrees").tag(value)
                        }
                    }
                    Toggle("Preserve proportions", isOn: $model.configuration.mapping.preserveAspect)
                    if model.configuration.mapping.preserveAspect {
                        HStack {
                            Text("Tablet width / height")
                            Spacer()
                            DecimalField("Tablet aspect ratio", value: $model.configuration.mapping.sourceAspect)
                                .frame(width: 100)
                        }
                    }
                }
            }
            SettingsCard {
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        Text("Coordinate precision").font(.headline)
                        Spacer()
                        Button("Native precision") {
                            model.configuration.mapping.positionStepsX = 0
                            model.configuration.mapping.positionStepsY = 0
                        }
                    }
                    HStack {
                        integerField("Horizontal steps", value: $model.configuration.mapping.positionStepsX)
                        integerField("Vertical steps", value: $model.configuration.mapping.positionStepsY)
                    }
                    Text("0 keeps native precision. Use 2 to 65,536 steps to quantize the mapped desktop position. This software grid does not change hardware LPI, pressure levels or sampling rate.")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            SettingsCard {
                DisclosureGroup("Advanced mapping JSON") {
                    VStack(alignment: .leading, spacing: 10) {
                        TextEditor(text: $model.mappingJSON).font(.system(.caption, design: .monospaced))
                            .frame(minHeight: 240).border(Color.secondary.opacity(0.2))
                        HStack {
                            Button("Read from editor") { model.loadMappingJSON() }
                            Button("Use JSON") { model.useMappingJSON() }
                        }
                        Text("Only mapping and pen response are included. Use JSON validates the entire mapping before updating the editor.")
                            .font(.caption).foregroundColor(.secondary)
                    }.padding(.top, 12)
                }
            }
        }
    }

    private var profiles: some View {
        VStack(spacing: 18) {
            SettingsCard {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Saved profiles").font(.headline)
                    if model.profiles.isEmpty {
                        Text("No profiles yet. Tune your pen and mapping, then save your first setup.")
                            .foregroundColor(.secondary).padding(.vertical, 15)
                    } else {
                        Picker("Profile", selection: $model.selectedProfile) {
                            Text("Choose a profile").tag(String?.none)
                            ForEach(model.profiles, id: \.self) { name in Text(name).tag(Optional(name)) }
                        }
                    }
                    HStack {
                        Button("Load into editor") { model.loadSelectedProfile() }
                        Button("Update selected") { model.saveProfile(overwrite: true) }
                        Spacer()
                        Button("Delete", role: .destructive) { confirmDelete = true }
                    }.disabled(model.selectedProfile == nil)
                    Text("Loading a profile prepares a draft. Apply it when you are ready to restart the host.")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            SettingsCard {
                VStack(alignment: .leading, spacing: 14) {
                    Text("Save a new profile").font(.headline)
                    HStack {
                        TextField("Profile name", text: $model.profileName).textFieldStyle(.roundedBorder)
                        Button("Save profile") { model.saveProfile() }
                            .disabled(model.profileName.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                    Text("Profiles contain pen response, tilt and coordinate mapping. Pairing keys and connection settings stay with this Mac.")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
        }
    }

    private var connection: some View {
        VStack(spacing: 18) {
            SettingsCard {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Host address").font(.headline)
                    HStack {
                        TextField("Bind address", text: $model.configuration.bind)
                            .textFieldStyle(.roundedBorder).accessibilityLabel("Bind address")
                        TextField("Port", value: $model.configuration.port, format: .number.grouping(.never))
                            .textFieldStyle(.roundedBorder).frame(width: 95).accessibilityLabel("Port")
                    }
                    Text("USB uses 127.0.0.1 with ADB reverse forwarding. For Wi-Fi, listen on your network address or 0.0.0.0, then enter this Mac's local address on the tablet.")
                        .font(.caption).foregroundColor(.secondary)
                    Divider()
                    Text("Pairing key").font(.headline)
                    HStack {
                        if revealToken {
                            TextField("Pairing key", text: $model.configuration.token)
                                .textFieldStyle(.roundedBorder).font(.system(.caption, design: .monospaced))
                        } else {
                            SecureField("Pairing key", text: $model.configuration.token).textFieldStyle(.roundedBorder)
                        }
                        Button("Copy") { model.copyToken() }
                    }
                    Toggle("Show pairing key", isOn: $revealToken).font(.caption)
                    Text("Use the same key on the tablet. Keep it private.")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            SettingsCard {
                VStack(alignment: .leading, spacing: 16) {
                    Text("Next host launch").font(.headline)
                    Toggle("Enable Bluetooth RFCOMM", isOn: $model.bluetooth)
                    Text("Pair both devices in system Bluetooth settings first. The host may request Bluetooth permission when started.")
                        .font(.caption).foregroundColor(.secondary)
                    Toggle("Dry run: receive input without moving the cursor", isOn: $model.dryRun)
                    Toggle("Additional tablet-point events (experimental)", isOn: $model.tabletPointEvents)
                    Text("Additional events can improve pressure precision in some apps; apps consuming both event paths may draw twice. These launch options apply to hosts started from this window.")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
        }
    }

    private var diagnostics: some View {
        VStack(spacing: 18) {
            SettingsCard {
                VStack(alignment: .leading, spacing: 14) {
                    HStack {
                        Text("System access").font(.headline)
                        Spacer()
                        Button("Refresh") { Task { await model.refreshDiagnostics() } }
                    }
                    valueRow("Accessibility", value: model.diagnostics["accessibilityTrusted"] as? Bool == true
                             ? "Granted" : "Permission needed")
                    Text("Allow Reflection in System Settings > Privacy & Security > Accessibility, then restart the host.")
                        .font(.caption).foregroundColor(.secondary)
                    Button("Open Accessibility Settings") { model.openAccessibilitySettings() }
                }
            }
            SettingsCard {
                VStack(alignment: .leading, spacing: 13) {
                    Text("Latest tablet data").font(.headline)
                    sampleRows
                    valueRow("Last disconnect", value: statusText("lastDisconnect", in: model.status))
                    valueRow("Host ownership", value: model.running
                             ? (model.owned ? "Started by this window" : "Started externally") : "Stopped")
                    Text("Closing this app stops a host that it started. A host started from the CLI remains independent.")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            SettingsCard {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text("Displays").font(.headline)
                        Spacer()
                        Button("Refresh displays") { model.refreshDisplays() }
                    }
                    ForEach(model.displays, id: \.id) { display in
                        valueRow("Display \(display.id)\(display.main ? " (main)" : "")",
                                 value: "\(display.pixelWidth) x \(display.pixelHeight) pixels")
                        Text("Desktop: \(Int(display.bounds.width)) x \(Int(display.bounds.height)) points at \(Int(display.bounds.x)), \(Int(display.bounds.y))")
                            .font(.caption).foregroundColor(.secondary)
                    }
                }
            }
            SettingsCard {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Host log").font(.headline)
                    Text(model.hostLog.isEmpty ? "Logs appear here for a host started from this window." : model.hostLog)
                        .font(.system(.caption, design: .monospaced))
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, minHeight: 80, alignment: .topLeading)
                }
            }
        }
    }

    private var sampleRows: some View {
        VStack(spacing: 11) {
            valueRow("Pressure", value: sampleNumber("pressure"))
            valueRow("Received tilt X / Y", value: "\(sampleNumber("tiltX")) / \(sampleNumber("tiltY")) degrees")
                .help("Client-processed values before host mapping. Zero may mean upright, unavailable or disabled input. Check tablet diagnostics for reliability and single-axis recovery.")
            valueRow("Tool", value: toolName)
            valueRow("Phase", value: phaseName)
        }
    }

    private var toolName: String {
        guard let value = (model.lastSample["tool"] as? NSNumber)?.intValue else { return "No sample" }
        return value == 2 ? "Eraser" : "Pen"
    }

    private var phaseName: String {
        guard let value = (model.lastSample["phase"] as? NSNumber)?.intValue else { return "No sample" }
        return [0: "Hover", 1: "Down", 2: "Move", 3: "Up", 4: "Cancel", 5: "Leave"][value] ?? "\(value)"
    }

    private func sampleNumber(_ key: String) -> String {
        guard let value = model.lastSample[key] as? NSNumber else { return "--" }
        return String(format: "%.3f", value.doubleValue)
    }

    private func statusText(_ key: String, in values: [String: Any]) -> String {
        guard let value = values[key], !(value is NSNull) else { return "--" }
        return String(describing: value)
    }

    private func valueRow(_ title: String, value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(title).foregroundColor(.secondary)
            Spacer()
            Text(value).monospacedDigit().textSelection(.enabled)
        }
        .font(.callout)
    }

    private func metric(_ title: String, value: String, symbol: String) -> some View {
        SettingsCard {
            VStack(alignment: .leading, spacing: 12) {
                Image(systemName: symbol).foregroundColor(.accentColor)
                Text(value).font(.system(size: 23, weight: .medium, design: .rounded)).monospacedDigit()
                Text(title).font(.caption).foregroundColor(.secondary)
            }.frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func integerField(_ title: String, value: Binding<Int>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.caption).foregroundColor(.secondary)
            TextField(title, value: value, format: .number.grouping(.never))
                .textFieldStyle(.roundedBorder).accessibilityLabel(title)
        }
    }
}

private struct SettingsCard<Content: View>: View {
    @ViewBuilder let content: Content
    var body: some View {
        content.frame(maxWidth: .infinity, alignment: .leading).padding(20)
            .background(Color(nsColor: .controlBackgroundColor).opacity(0.7),
                        in: RoundedRectangle(cornerRadius: 14))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(Color.secondary.opacity(0.12)))
    }
}

private struct ControlMaterial: ViewModifier {
    @ViewBuilder func body(content: Content) -> some View {
        if #available(macOS 26, *) {
            content.glassEffect(.regular, in: RoundedRectangle(cornerRadius: 11))
        } else {
            content.background(.regularMaterial, in: RoundedRectangle(cornerRadius: 11))
        }
    }
}

private struct RegionEditor: View {
    @Binding var region: Region
    let prefix: String
    var body: some View {
        HStack(spacing: 12) {
            field("X", binding: $region.x)
            field("Y", binding: $region.y)
            field("Width", binding: $region.width)
            field("Height", binding: $region.height)
        }
    }
    private func field(_ label: String, binding: Binding<Double>) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.caption).foregroundColor(.secondary)
            DecimalField("\(prefix) \(label)", value: binding)
        }
    }
}
