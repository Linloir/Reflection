import AppKit
import Foundation
import ReflectionCore

private struct CommandResult {
    let status: Int32
    let data: Data
}

private struct HostLaunchOptions {
    let bluetooth: Bool
    let dryRun: Bool
    let tabletPointEvents: Bool
}

private enum HostCommand {
    static func run(executable: URL, configuration: URL, arguments: [String]) async throws -> Data {
        let result: CommandResult = try await withCheckedThrowingContinuation { continuation in
            DispatchQueue.global(qos: .utility).async {
                let process = Process()
                let pipe = Pipe()
                process.executableURL = executable
                process.arguments = ["--config", configuration.path] + arguments
                process.standardOutput = pipe
                process.standardError = pipe
                do {
                    try process.run()
                    let timeout = DispatchWorkItem { if process.isRunning { process.terminate() } }
                    DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 6, execute: timeout)
                    let data = pipe.fileHandleForReading.readDataToEndOfFile()
                    process.waitUntilExit()
                    timeout.cancel()
                    continuation.resume(returning: CommandResult(status: process.terminationStatus, data: data))
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
        guard result.status == 0 else {
            throw ReflectionError.invalid(String(decoding: result.data, as: UTF8.self)
                .trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return result.data
    }
}

@MainActor
final class ReflectionAppModel: ObservableObject {
    @Published var configuration = ServerConfiguration(token: "")
    @Published var displays: [DisplayDescription] = []
    @Published var profiles: [String] = []
    @Published var selectedProfile: String?
    @Published var profileName = ""
    @Published var status: [String: Any] = [:]
    @Published var diagnostics: [String: Any] = [:]
    @Published var busy = false
    @Published var running = false
    @Published var error: String?
    @Published var notice = ""
    @Published var hostLog = ""
    @Published var externalConfigurationChange = false
    @Published var bluetooth = false
    @Published var dryRun = false
    @Published var tabletPointEvents = false
    @Published var mappingJSON = ""
    @Published var liveMapping: MappingStatus?
    @Published var calibration: CalibrationStatus?
    @Published var mappingShortcutsEnabled = UserDefaults.standard.object(forKey: "mappingShortcutsEnabled") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(mappingShortcutsEnabled, forKey: "mappingShortcutsEnabled")
            configureMappingShortcuts()
        }
    }

    let configurationURL: URL
    private let executable: URL
    private let profileStore: ProfileStore
    private var ownedHost: Process?
    private var ownedPipe: Pipe?
    private var polling: Task<Void, Never>?
    private var originalData = Data()
    private var originalFileData = Data()
    private var mappingShortcuts: MappingShortcuts?
    private let mappingFeedback = MappingFeedback()
    private var mappingProcessID: Int?

    init() {
        let args = CommandLine.arguments
        let requestedConfiguration: URL
        if let i = args.firstIndex(of: "--config"), i + 1 < args.count {
            requestedConfiguration = URL(fileURLWithPath: args[i + 1])
        } else {
            requestedConfiguration = ServerConfiguration.defaultURL
        }
        configurationURL = requestedConfiguration.standardizedFileURL.resolvingSymlinksInPath()
        let directory = URL(fileURLWithPath: CommandLine.arguments[0]).deletingLastPathComponent()
        let bundled = directory.appendingPathComponent("reflection-host")
        executable = FileManager.default.isExecutableFile(atPath: bundled.path)
            ? bundled : directory.appendingPathComponent("reflection")
        profileStore = ProfileStore(configurationURL: configurationURL)
        reloadConfiguration(createIfMissing: true)
        refreshDisplays()
        refreshProfiles()
        configureMappingShortcuts()
        polling = Task { [weak self] in
            while !Task.isCancelled {
                guard let self else { return }
                await self.refreshStatus()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
        Task { await refreshDiagnostics() }
    }

    var hasChanges: Bool { (try? encoded(configuration)) != originalData }
    var connected: Bool { status["authenticated"] as? Bool ?? false }
    var samples: UInt64 { (status["samples"] as? NSNumber)?.uint64Value ?? 0 }
    var protocolErrors: UInt64 { (status["protocolErrors"] as? NSNumber)?.uint64Value ?? 0 }
    var lastSample: [String: Any] { status["lastSample"] as? [String: Any] ?? [:] }
    var rawPressure: Double { (lastSample["pressure"] as? NSNumber)?.doubleValue ?? 0 }
    var owned: Bool { ownedHost?.isRunning ?? false }
    var connectionLabel: String {
        if busy { return "Updating host" }
        if connected && running { return "Tablet connected" }
        return running ? "Waiting for tablet" : "Host stopped"
    }

    var mappingLabel: String {
        guard let liveMapping, running else { return "Start the host to switch displays live." }
        let label = liveMapping.mode == "all" ? "All displays" : liveMapping.mode == "custom" ? "Custom desktop area"
            : liveMapping.displays.first(where: { $0.id == liveMapping.selected })?.name ?? "Selected display"
        return label + (liveMapping.pending ? " · Switch waits for pen-up" : "")
            + (liveMapping.fallback ? " · Selected display unavailable; using all displays" : "")
    }

    var stabilizationStatus: [String: Any] { status["stabilization"] as? [String: Any] ?? [:] }
    var calibrationActive: Bool { calibration?.active ?? false }

    func calibrate(_ command: String) {
        guard running, !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                let data = try await HostCommand.run(executable: executable, configuration: configurationURL,
                                                     arguments: ["calibration", command])
                let result = try JSONDecoder().decode(CalibrationStatus.self, from: data)
                calibration = result
                if result.result == "success" {
                    let diskData = try Data(contentsOf: configurationURL)
                    let disk = try JSONDecoder().decode(ServerConfiguration.self, from: diskData)
                    if var previousFile = try? JSONDecoder().decode(ServerConfiguration.self, from: originalFileData) {
                        previousFile.stabilization.noise = disk.stabilization.noise
                        externalConfigurationChange = (try? encoded(previousFile)) != (try? encoded(disk))
                    }
                    configuration.stabilization.noise = disk.stabilization.noise
                    if var baseline = try? JSONDecoder().decode(ServerConfiguration.self, from: originalData) {
                        baseline.stabilization.noise = disk.stabilization.noise
                        originalData = try encoded(baseline)
                    }
                    if !externalConfigurationChange { originalFileData = diskData }
                }
                notice = result.reason
                if result.phase == "failed" { error = result.reason }
            } catch { self.error = String(describing: error) }
        }
    }

    private func configureMappingShortcuts() {
        mappingShortcuts?.stop()
        mappingShortcuts = nil
        if mappingShortcutsEnabled {
            mappingShortcuts = MappingShortcuts { [weak self] in self?.switchMapping($0) }
        }
    }

    private func receiveMapping(_ snapshot: MappingStatus) {
        if let previous = liveMapping, snapshot.revision < previous.revision { return }
        let changed = liveMapping.map { $0.mode != snapshot.mode || $0.selected != snapshot.selected || $0.target != snapshot.target } ?? false
        liveMapping = snapshot
        if changed { mappingFeedback.show(snapshot) }
    }

    func switchMapping(_ action: String, displayID: String? = nil) {
        guard running, !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                let arguments = ["mapping", action] + (displayID.map { [$0] } ?? [])
                let data = try await HostCommand.run(executable: executable, configuration: configurationURL, arguments: arguments)
                receiveMapping(try JSONDecoder().decode(MappingStatus.self, from: data))
                notice = mappingLabel
            } catch { self.error = String(describing: error) }
        }
    }

    func identifyMapping() {
        if let liveMapping, running { mappingFeedback.show(liveMapping) }
    }

    private func encoded<T: Encodable>(_ value: T) throws -> Data {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(value)
    }

    func reloadConfiguration(createIfMissing: Bool = false) {
        do {
            if createIfMissing && !FileManager.default.fileExists(atPath: configurationURL.path) {
                try ServerConfiguration(token: ServerConfiguration.randomToken()).save(to: configurationURL)
            }
            configuration = try ServerConfiguration.load(from: configurationURL)
            originalData = try encoded(configuration)
            originalFileData = try Data(contentsOf: configurationURL)
            externalConfigurationChange = false
            loadMappingJSON()
            notice = "Configuration loaded."
        } catch { self.error = String(describing: error) }
    }

    @discardableResult
    func saveConfiguration() -> Bool {
        do {
            if let current = try? Data(contentsOf: configurationURL), current != originalFileData {
                externalConfigurationChange = true
                throw ReflectionError.invalid("The configuration changed outside this window. Reload it before saving.")
            }
            try configuration.save(to: configurationURL)
            originalData = try encoded(configuration)
            originalFileData = try Data(contentsOf: configurationURL)
            externalConfigurationChange = false
            notice = running ? "Saved. Restart the host to apply these settings." : "Configuration saved."
            return true
        } catch {
            self.error = String(describing: error)
            return false
        }
    }

    func refreshDisplays() {
        do { displays = try availableDisplays() }
        catch { self.error = String(describing: error) }
    }

    func refreshProfiles() {
        do {
            profiles = try profileStore.list()
            if let selectedProfile, !profiles.contains(selectedProfile) { self.selectedProfile = nil }
        } catch { self.error = String(describing: error) }
    }

    func saveProfile(overwrite: Bool = false) {
        let name = overwrite ? (selectedProfile ?? "") : profileName
        do {
            try profileStore.save(name: name, mapping: configuration.mapping, overwrite: overwrite)
            refreshProfiles()
            selectedProfile = name
            notice = "Saved profile: \(name)"
        } catch { self.error = String(describing: error) }
    }

    func loadSelectedProfile() {
        guard let selectedProfile else { return }
        do {
            configuration.mapping = try profileStore.load(name: selectedProfile)
            loadMappingJSON()
            notice = "Profile loaded into the editor. Save or apply it when ready."
        } catch { self.error = String(describing: error) }
    }

    func deleteSelectedProfile() {
        guard let selectedProfile else { return }
        do {
            try profileStore.delete(name: selectedProfile)
            self.selectedProfile = nil
            refreshProfiles()
            notice = "Profile deleted. The current configuration is unchanged."
        } catch { self.error = String(describing: error) }
    }

    func loadMappingJSON() {
        mappingJSON = (try? String(decoding: encoded(configuration.mapping), as: UTF8.self)) ?? ""
    }

    func useMappingJSON() {
        do {
            var candidate = configuration
            candidate.mapping = try JSONDecoder().decode(MappingConfiguration.self, from: Data(mappingJSON.utf8))
            try candidate.validate()
            configuration = candidate
            notice = "Mapping JSON loaded into the editor."
        } catch { self.error = String(describing: error) }
    }

    func refreshStatus() async {
        do {
            let data = try await HostCommand.run(executable: executable, configuration: configurationURL, arguments: ["status"])
            status = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
            running = status["processAlive"] as? Bool ?? false
            let pid = (status["pid"] as? NSNumber)?.intValue
            if pid != mappingProcessID { liveMapping = nil; mappingProcessID = pid }
            if running, let mapping = status["mapping"],
               let data = try? JSONSerialization.data(withJSONObject: mapping),
               let snapshot = try? JSONDecoder().decode(MappingStatus.self, from: data) { receiveMapping(snapshot) }
            if !running { liveMapping = nil }
            if let value = status["calibration"],
               let data = try? JSONSerialization.data(withJSONObject: value) {
                calibration = try? JSONDecoder().decode(CalibrationStatus.self, from: data)
            }
        } catch {
            status = [:]
            running = ownedHost?.isRunning ?? false
        }
        if let current = try? Data(contentsOf: configurationURL) {
            externalConfigurationChange = current != originalFileData
        }
    }

    func refreshDiagnostics() async {
        do {
            let data = try await HostCommand.run(executable: executable, configuration: configurationURL, arguments: ["doctor"])
            diagnostics = try JSONSerialization.jsonObject(with: data) as? [String: Any] ?? [:]
            refreshDisplays()
        } catch { self.error = String(describing: error) }
    }

    func start() {
        guard !busy else { return }
        let options = hostLaunchOptions
        busy = true
        Task {
            defer { busy = false }
            await refreshStatus()
            guard !running else { notice = "A host is already running for this configuration."; return }
            do {
                try await validateStartPermissions(options: options)
                guard saveConfiguration() else { return }
                try launchHost(options: options)
            }
            catch { self.error = String(describing: error) }
        }
    }

    func stop() {
        guard !busy else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                try await stopHost()
                notice = "Host stopped and pen state released."
            } catch { self.error = String(describing: error) }
        }
    }

    func applyAndRestart() {
        guard !busy else { return }
        let options = hostLaunchOptions
        busy = true
        Task {
            defer { busy = false }
            do {
                try await validateStartPermissions(options: options)
                guard saveConfiguration() else { return }
                await refreshStatus()
                if running { try await stopHost() }
                try launchHost(options: options)
                notice = "Saved settings applied to the host."
            } catch { self.error = String(describing: error) }
        }
    }

    private var hostLaunchOptions: HostLaunchOptions {
        HostLaunchOptions(bluetooth: bluetooth, dryRun: dryRun, tabletPointEvents: tabletPointEvents)
    }

    private func validateStartPermissions(options: HostLaunchOptions) async throws {
        guard !options.dryRun else { return }
        // Query the same bundled executable and launch identity that will emit input.
        let data = try await HostCommand.run(executable: executable, configuration: configurationURL,
                                             arguments: ["doctor"])
        guard let report = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let trusted = report["accessibilityTrusted"] as? Bool else {
            throw ReflectionError.invalid("Could not verify Accessibility permission. No host was stopped. Refresh Diagnostics before trying again.")
        }
        diagnostics = report
        guard trusted else {
            throw ReflectionError.invalid("Reflection needs Accessibility permission to send pen input. In Diagnostics, open Accessibility Settings and enable Reflection, then try again. A terminal's permission does not automatically grant access to this app. No host was stopped.")
        }
    }

    private func stopHost() async throws {
        _ = try await HostCommand.run(executable: executable, configuration: configurationURL, arguments: ["stop"])
        for _ in 0..<40 {
            await refreshStatus()
            if !running { return }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        throw ReflectionError.invalid("The host has not stopped yet. Check diagnostics before starting another instance.")
    }

    private func launchHost(options: HostLaunchOptions) throws {
        guard FileManager.default.isExecutableFile(atPath: executable.path) else {
            throw ReflectionError.invalid("The native host executable is missing. Rebuild the Reflection app bundle.")
        }
        let process = Process()
        let pipe = Pipe()
        process.executableURL = executable
        var arguments = ["--config", configurationURL.path, "start"]
        if options.bluetooth { arguments.append("--bluetooth") }
        if options.dryRun { arguments.append("--dry-run") }
        if options.tabletPointEvents { arguments.append("--tablet-point-events") }
        process.arguments = arguments
        process.standardOutput = pipe
        process.standardError = pipe
        pipe.fileHandleForReading.readabilityHandler = { [weak self] handle in
            let data = handle.availableData
            guard !data.isEmpty else { handle.readabilityHandler = nil; return }
            let text = String(decoding: data, as: UTF8.self)
            DispatchQueue.main.async {
                guard let self else { return }
                self.hostLog = String((self.hostLog + text).suffix(12_000))
            }
        }
        process.terminationHandler = { [weak self] process in
            DispatchQueue.main.async {
                guard let self, self.ownedHost === process else { return }
                self.ownedHost = nil
                self.ownedPipe = nil
                self.running = false
                if process.terminationStatus != 0 {
                    self.error = self.hostLog.isEmpty ? "The host could not start. Check permissions and connection settings."
                        : String(self.hostLog.suffix(2000))
                }
                Task { await self.refreshStatus() }
            }
        }
        hostLog = ""
        try process.run()
        ownedHost = process
        ownedPipe = pipe
        running = true
        notice = "Host started."
    }

    func terminateOwnedHost() {
        polling?.cancel()
        mappingShortcuts?.stop()
        mappingFeedback.hide()
        if let ownedHost, ownedHost.isRunning { ownedHost.terminate() }
    }

    func copyToken() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(configuration.token, forType: .string)
        notice = "Pairing key copied to the clipboard."
    }

    func openAccessibilitySettings() {
        guard let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") else { return }
        NSWorkspace.shared.open(url)
    }
}
