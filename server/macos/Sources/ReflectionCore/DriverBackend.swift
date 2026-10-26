import Foundation
import CoreGraphics
import Darwin

public enum TabletReport {
    public static func encode(_ mapped: MappedSample, desktop: CGRect) throws -> Data {
        let values: [Double] = [Double(desktop.minX), Double(desktop.minY), Double(desktop.width), Double(desktop.height),
            Double(mapped.point.x), Double(mapped.point.y), mapped.pressure, mapped.tiltX, mapped.tiltY]
        guard values.allSatisfy(\.isFinite),
              desktop.width >= 1, desktop.height >= 1 else {
            throw ReflectionError.invalid("Invalid virtual tablet coordinates")
        }
        var bytes = [UInt8](repeating: 0, count: 10)
        bytes[0] = 1
        let sample = mapped.sample
        if sample.phase == .cancel || sample.phase == .leave { return Data(bytes) }
        let contact = sample.phase == .down || sample.phase == .move
        bytes[1] = 0x10 | (sample.tool == .eraser ? 0x04 : 0) | (sample.buttons & 1 != 0 ? 0x02 : 0)
        if contact { bytes[1] |= sample.tool == .eraser ? 0x08 : 0x01 }
        func put(_ offset: Int, _ fraction: Double, _ maximum: Double) {
            let value = UInt16((min(1, max(0, fraction)) * maximum).rounded())
            bytes[offset] = UInt8(truncatingIfNeeded: value)
            bytes[offset + 1] = UInt8(truncatingIfNeeded: value >> 8)
        }
        put(2, Double((mapped.point.x - desktop.minX) / max(1, desktop.width - 1)), 32767)
        put(4, Double((mapped.point.y - desktop.minY) / max(1, desktop.height - 1)), 32767)
        put(6, contact ? mapped.pressure : 0, 65535)
        bytes[8] = UInt8(bitPattern: Int8(min(90, max(-90, mapped.tiltX)).rounded()))
        bytes[9] = UInt8(bitPattern: Int8(min(90, max(-90, mapped.tiltY)).rounded()))
        return Data(bytes)
    }
    public static func release(_ report: Data, inRange: Bool) -> Data {
        var bytes = Array(report)
        bytes[1] = inRange ? 0x10 | (bytes[1] & 0x04) : 0
        bytes[6] = 0; bytes[7] = 0
        return Data(bytes)
    }
}

private final class DriverProcess {
    let process = Process()
    private let input = Pipe()
    private let output = Pipe()
    private let errors = Pipe()
    private var closed = false
    private var started = false

    init(executable: URL, probe: Bool = false) throws {
        signal(SIGPIPE, SIG_IGN)
        process.executableURL = executable
        process.arguments = [probe ? "--probe" : "--serve"]
        process.standardInput = input
        process.standardOutput = output
        process.standardError = errors
        try process.run()
        started = true
        // Only the child retains these ends; EOF must be observable after a crash.
        try input.fileHandleForReading.close()
        try output.fileHandleForWriting.close()
        try errors.fileHandleForWriting.close()
        do {
            guard try read(4) == Data("RFD1".utf8) else { throw ReflectionError.invalid("Invalid driver handshake") }
        } catch {
            close()
            let detail = String(decoding: errors.fileHandleForReading.readDataToEndOfFile(), as: UTF8.self)
            throw ReflectionError.invalid(detail.isEmpty ? "Virtual HID helper did not start: \(error)" : detail)
        }
    }
    private func read(_ count: Int) throws -> Data {
        var result = Data()
        let deadline = ProcessInfo.processInfo.systemUptime + 3
        while result.count < count {
            var descriptor = pollfd(fd: output.fileHandleForReading.fileDescriptor, events: Int16(POLLIN), revents: 0)
            let remaining = deadline - ProcessInfo.processInfo.systemUptime
            guard remaining > 0 else { throw ReflectionError.invalid("Virtual HID helper timed out") }
            let ready = poll(&descriptor, 1, Int32(remaining * 1000))
            if ready < 0 && errno == EINTR { continue }
            guard ready > 0 else { throw ReflectionError.invalid("Virtual HID helper timed out") }
            guard let chunk = try output.fileHandleForReading.read(upToCount: count - result.count), !chunk.isEmpty else {
                throw ReflectionError.invalid("Virtual HID helper exited")
            }
            result.append(chunk)
        }
        return result
    }
    func submit(_ report: Data) throws {
        guard !closed else { throw ReflectionError.invalid("Virtual HID helper is closed") }
        do {
            try input.fileHandleForWriting.write(contentsOf: report)
            guard try read(1) == Data([0]) else { throw ReflectionError.invalid("Virtual HID report rejected") }
        } catch { close(); throw error }
    }
    func close() {
        guard !closed, started else { return }
        closed = true
        try? input.fileHandleForWriting.close()
        // EOF releases the HID device. Bound teardown if a broken helper stalls.
        let deadline = ProcessInfo.processInfo.systemUptime + 1
        while process.isRunning && ProcessInfo.processInfo.systemUptime < deadline { usleep(10_000) }
        if process.isRunning { process.terminate() }
        let finalDeadline = ProcessInfo.processInfo.systemUptime + 1
        while process.isRunning && ProcessInfo.processInfo.systemUptime < finalDeadline { usleep(10_000) }
        if process.isRunning { kill(process.processIdentifier, SIGKILL) }
        process.waitUntilExit()
    }
    deinit { close() }
}

public enum DriverManager {
    public static var installedBundle: URL {
        FileManager.default.homeDirectoryForCurrentUser
            .appendingPathComponent("Library/Application Support/Reflection/Driver/ReflectionDriver.app")
    }
    public static var installedURL: URL {
        installedBundle.appendingPathComponent("Contents/MacOS/reflection-driver")
    }
    public static var bundledBundle: URL {
        URL(fileURLWithPath: CommandLine.arguments[0]).standardizedFileURL
            .deletingLastPathComponent().appendingPathComponent("ReflectionDriver.app")
    }
    public static var bundledURL: URL {
        bundledBundle.appendingPathComponent("Contents/MacOS/reflection-driver")
    }
    public static var installed: Bool { FileManager.default.isExecutableFile(atPath: installedURL.path) }
    public static func status() -> [String: Any] {
        ["installed": installed, "packageAvailable": FileManager.default.isExecutableFile(atPath: bundledURL.path),
         "backend": "virtual-hid-helper", "availability": installed ? "probe-required" : "not-installed",
         "detail": "Requires an Apple-approved virtual HID entitlement. Run driver probe to verify device creation."]
    }
    public static func probe(_ url: URL? = nil) throws {
        let helper = try DriverProcess(executable: url ?? installedURL, probe: true)
        helper.close()
        guard helper.process.terminationStatus == 0 else { throw ReflectionError.invalid("Virtual HID probe failed") }
    }
    public static func install() throws {
        if installed { try probe(); return }
        guard FileManager.default.isExecutableFile(atPath: bundledURL.path) else {
            throw ReflectionError.invalid("Signed reflection-driver helper is missing beside the host executable")
        }
        // Probe before copying so unsigned or unentitled builds cannot be reported as installed.
        try probe(bundledURL)
        let directory = installedBundle.deletingLastPathComponent()
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
                                                attributes: [.posixPermissions: 0o700])
        let temporary = directory.appendingPathComponent(".driver-\(UUID().uuidString).app")
        defer { try? FileManager.default.removeItem(at: temporary) }
        try FileManager.default.copyItem(at: bundledBundle, to: temporary)
        guard Darwin.rename(temporary.path, installedBundle.path) == 0 else {
            throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
        }
    }
    public static func uninstall() throws {
        if FileManager.default.fileExists(atPath: installedBundle.path) { try FileManager.default.removeItem(at: installedBundle) }
    }
}

public final class HIDTabletBackend: TabletBackend {
    private let helper: DriverProcess
    private let ownership: Int32
    private var desktop: CGRect
    private var last = Data([1, 0, 0, 0, 0, 0, 0, 0, 0, 0])
    public init() throws {
        desktop = try Self.desktopBounds()
        let lock = DriverManager.installedBundle.deletingLastPathComponent().appendingPathComponent(".device.lock")
        let descriptor = open(lock.path, O_RDWR | O_CREAT | O_NOFOLLOW, 0o600)
        guard descriptor >= 0 else { throw ReflectionError.invalid("Virtual HID driver is not installed") }
        guard flock(descriptor, LOCK_EX | LOCK_NB) == 0 else {
            close(descriptor)
            throw ReflectionError.invalid("Another host owns the virtual tablet")
        }
        // Do not pass the ownership lock into the helper process.
        _ = fcntl(descriptor, F_SETFD, FD_CLOEXEC)
        do { helper = try DriverProcess(executable: DriverManager.installedURL) }
        catch { close(descriptor); throw error }
        ownership = descriptor
    }
    private static func desktopBounds() throws -> CGRect {
        let bounds = try availableDisplays().reduce(CGRect.null) { rect, display in
            rect.union(CGRect(x: display.bounds.x, y: display.bounds.y, width: display.bounds.width, height: display.bounds.height))
        }
        guard !bounds.isNull, !bounds.isInfinite else { throw ReflectionError.invalid("No active display for virtual tablet") }
        return bounds
    }
    public func refreshDesktop() throws -> Bool {
        let next = try Self.desktopBounds()
        guard next != desktop else { return false }
        release()
        desktop = next
        return true
    }
    public func submit(_ sample: MappedSample) throws {
        if sample.sample.phase == .cancel || sample.sample.phase == .leave { release(); return }
        if last[1] & 0x10 != 0 && (last[1] & 0x04 != 0) != (sample.sample.tool == .eraser) { release() }
        var report = try TabletReport.encode(sample, desktop: desktop)
        if sample.sample.phase == .up && last[1] & 0x10 != 0 { report.replaceSubrange(2..<6, with: last[2..<6]) }
        try helper.submit(report)
        last = report
    }
    public func release() {
        guard last[1] & 0x10 != 0 else { return }
        try? helper.submit(TabletReport.release(last, inRange: true))
        last = TabletReport.release(last, inRange: false)
        try? helper.submit(last)
    }
    deinit { release(); helper.close(); close(ownership) }
}

/// Keep backend changes on the server's serial input queue.
public final class ManagedTabletBackend: TabletBackend {
    public private(set) var mode: String
    public private(set) var error: String?
    private var backend: TabletBackend
    private let configurationURL: URL
    private let dryRun: Bool
    private let pointEvents: Bool
    public init(configuration: ServerConfiguration, url: URL, dryRun: Bool, pointEvents: Bool) throws {
        var configuration = configuration
        if !dryRun && configuration.inputMode == "driver" && !DriverManager.installed {
            configuration.inputMode = "api"
            try configuration.save(to: url)
        }
        self.mode = configuration.inputMode; self.configurationURL = url
        self.dryRun = dryRun; self.pointEvents = pointEvents
        backend = try Self.create(mode, dryRun: dryRun, pointEvents: pointEvents)
    }
    private static func create(_ mode: String, dryRun: Bool, pointEvents: Bool) throws -> TabletBackend {
        if dryRun { return RecordingBackend() }
        if mode == "driver" { return try HIDTabletBackend() }
        return try QuartzTabletBackend(emitTabletPointEvents: pointEvents)
    }
    public func refresh() -> Bool {
        do {
            let layoutChanged = try (backend as? HIDTabletBackend)?.refreshDesktop() ?? false
            var config = try ServerConfiguration.load(from: configurationURL)
            if !dryRun && config.inputMode == "driver" && !DriverManager.installed {
                config.inputMode = "api"
                try config.save(to: configurationURL)
            }
            guard config.inputMode != mode else { error = nil; return layoutChanged }
            let next = try Self.create(config.inputMode, dryRun: dryRun, pointEvents: pointEvents)
            backend.release()
            backend = next
            mode = config.inputMode
            error = nil
            return true
        } catch { self.error = String(describing: error); return false }
    }
    public var recording: RecordingBackend? { backend as? RecordingBackend }
    public func submit(_ sample: MappedSample) throws { try backend.submit(sample) }
    public func release() { backend.release() }
}
