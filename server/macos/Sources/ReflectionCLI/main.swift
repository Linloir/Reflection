import Foundation
import ReflectionCore
import CoreGraphics
import Darwin

let usage = """
Reflection macOS tablet server

Usage: reflection [--config PATH] COMMAND [OPTIONS]

  config init                     Create a private config and random pairing token
  config show [--reveal-token]     Show JSON configuration (token redacted by default)
  config set KEY JSON_VALUE       Set a config field, for example mapping.pressureGamma 1.3
  config apply KEY JSON_VALUE ... Atomically validate and save several fields
  config validate                 Validate configuration
  profiles list                   List saved mapping profiles
  profiles save NAME [--replace]   Save mapping, pressure, tilt and resolution settings
  profiles show|load|delete NAME   Inspect, activate or delete a profile
  pair                            Show the pairing token (sensitive; do not share logs)
  displays                        List display IDs and desktop coordinates as JSON
  doctor                          Report permissions, backend capabilities and config health
  status                          Show status and verify the live host control channel
  stop                            Gracefully stop the host using this configuration
  start [--dry-run] [--verbose] [--bluetooth] [--tablet-point-events]
                                  Run server; optionally publish native Bluetooth RFCOMM
  help                            Show this help

Examples:
  reflection config init
  reflection config set bind '"0.0.0.0"'
  reflection config set mapping.target '{"x":0,"y":0,"width":1920,"height":1080}'
  reflection start --dry-run --verbose

The TCP protocol authenticates both peers and every frame with HMAC-SHA256.
It does not encrypt handwriting data. Use USB or a trusted network.
The Quartz backend requires Accessibility permission and is not a virtual HID device.
"""

func printJSON(_ value: Any) throws {
    let data = try JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys, .fragmentsAllowed])
    print(String(decoding: data, as: UTF8.self))
}

func encodedObject<T: Encodable>(_ value: T) throws -> Any {
    try JSONSerialization.jsonObject(with: JSONEncoder().encode(value))
}

func setNested(_ root: inout [String: Any], path: ArraySlice<String>, value: Any) throws {
    guard let key = path.first else { throw ReflectionError.invalid("Empty config key") }
    if path.count == 1 {
        guard root[key] != nil || ["target", "displayID"].contains(key) else {
            throw ReflectionError.invalid("Unknown config key: \(key)")
        }
        if value is NSNull { root.removeValue(forKey: key) }
        else { root[key] = value }
    } else {
        guard var child = root[key] as? [String: Any] else {
            throw ReflectionError.invalid("Unknown config section: \(key)")
        }
        try setNested(&child, path: path.dropFirst(), value: value)
        root[key] = child
    }
}

var signalSources = [DispatchSourceSignal]()

func main() throws {
    var args = Array(CommandLine.arguments.dropFirst())
    var configURL = ServerConfiguration.defaultURL
    if let i = args.firstIndex(of: "--config") {
        guard i + 1 < args.count else { throw ReflectionError.invalid("--config requires a path") }
        configURL = URL(fileURLWithPath: args[i + 1])
        args.removeSubrange(i...i + 1)
    }
    configURL = configURL.standardizedFileURL.resolvingSymlinksInPath()
    let command = args.first ?? "help"
    let statusURL = configURL.deletingPathExtension().appendingPathExtension("status.json")
    switch command {
    case "help", "--help", "-h": print(usage)
    case "config":
        guard args.count >= 2 else { throw ReflectionError.invalid("config needs init, show, set, or validate") }
        switch args[1] {
        case "init":
            guard !FileManager.default.fileExists(atPath: configURL.path) else {
                throw ReflectionError.invalid("Config already exists; use config set")
            }
            try ServerConfiguration(token: ServerConfiguration.randomToken()).save(to: configURL)
            try printJSON(["created": configURL.path, "token": "Use reflection pair to reveal"])
        case "show":
            let config = try ServerConfiguration.load(from: configURL)
            var object = try encodedObject(config) as! [String: Any]
            if !args.contains("--reveal-token") { object["token"] = "<redacted>" }
            try printJSON(object)
        case "validate":
            _ = try ServerConfiguration.load(from: configURL)
            try printJSON(["valid": true])
        case "set", "apply":
            guard args.count >= 4, args.count % 2 == 0,
                  args[1] != "set" || args.count == 4 else {
                throw ReflectionError.invalid("config set/apply requires KEY JSON_VALUE pairs")
            }
            let config = try ServerConfiguration.load(from: configURL)
            var object = try encodedObject(config) as! [String: Any]
            var keys = [String]()
            for index in stride(from: 2, to: args.count, by: 2) {
                let value = try JSONSerialization.jsonObject(with: Data(args[index + 1].utf8), options: [.fragmentsAllowed])
                try setNested(&object, path: args[index].split(separator: ".").map(String.init)[...], value: value)
                keys.append(args[index])
            }
            let updated = try JSONDecoder().decode(ServerConfiguration.self,
                from: JSONSerialization.data(withJSONObject: object))
            try updated.save(to: configURL)
            try printJSON(["updated": keys, "restartRequired": true])
        default: throw ReflectionError.invalid("Unknown config command")
        }
    case "profiles":
        guard args.count >= 2 else { throw ReflectionError.invalid("profiles needs list, save, show, load, or delete") }
        let store = ProfileStore(configurationURL: configURL)
        switch args[1] {
        case "list":
            guard args.count == 2 else { throw ReflectionError.invalid("profiles list takes no arguments") }
            try printJSON(["profiles": store.list()])
        case "save":
            guard args.count == 3 || (args.count == 4 && args[3] == "--replace") else {
                throw ReflectionError.invalid("profiles save requires NAME [--replace]")
            }
            try store.save(name: args[2], mapping: ServerConfiguration.load(from: configURL).mapping,
                           overwrite: args.contains("--replace"))
            try printJSON(["saved": args[2]])
        case "show", "load", "delete":
            guard args.count == 3 else { throw ReflectionError.invalid("profiles \(args[1]) requires NAME") }
            if args[1] == "show" {
                try printJSON(encodedObject(MappingProfile(mapping: store.load(name: args[2]))))
            } else if args[1] == "delete" {
                try store.delete(name: args[2])
                try printJSON(["deleted": args[2]])
            } else {
                var config = try ServerConfiguration.load(from: configURL)
                config.mapping = try store.load(name: args[2])
                try config.save(to: configURL)
                try printJSON(["loaded": args[2], "restartRequired": true])
            }
        default: throw ReflectionError.invalid("Unknown profiles command")
        }
    case "pair":
        let config = try ServerConfiguration.load(from: configURL)
        try printJSON(["protocol": 1, "port": config.port, "token": config.token])
    case "displays":
        try printJSON(encodedObject(availableDisplays()))
    case "doctor":
        var result: [String: Any] = [
            "platform": "macOS",
            "accessibilityTrusted": QuartzTabletBackend.accessibilityTrusted,
            "backend": "quartz",
            "virtualHID": false,
            "pressure": true,
            "tilt": true,
            "eraser": true,
            "transport": ["tcp", "usb-adb-reverse", "bluetooth-rfcomm"],
            "bluetoothRuntimeChecked": false,
            "bluetoothServiceUUID": BluetoothBridge.serviceUUID,
            "encryption": false,
            "applicationCompatibility": "Quartz tablet-event consumers; application verification required"
        ]
        do {
            _ = try ServerConfiguration.load(from: configURL)
            result["configurationValid"] = true
        } catch {
            result["configurationValid"] = false
            result["configurationError"] = String(describing: error)
        }
        result["displays"] = try encodedObject(availableDisplays())
        try printJSON(result)
    case "status":
        var object: [String: Any] = ["state": "stopped", "snapshot": false]
        if let data = try? Data(contentsOf: statusURL),
           let saved = try JSONSerialization.jsonObject(with: data) as? [String: Any] {
            object = saved
            object["snapshot"] = true
        }
        let running = HostControl.isRunning(configurationURL: configURL)
        object["processAlive"] = running
        object["controlAvailable"] = running
        try printJSON(object)
    case "stop":
        try HostControl.requestStop(configurationURL: configURL)
        try printJSON(["stopRequested": true])
    case "start":
        let allowed = Set(["start", "--dry-run", "--verbose", "--bluetooth", "--tablet-point-events"])
        guard args.allSatisfy(allowed.contains) else { throw ReflectionError.invalid("Unknown start option") }
        var shutdown: (() -> Void)?
        let control = try HostControl(configurationURL: configURL) { shutdown?() }
        let config = try ServerConfiguration.load(from: configURL)
        let displays = try availableDisplays()
        let target: Region
        if let custom = config.mapping.target { target = custom }
        else if let id = config.mapping.displayID {
            guard let display = displays.first(where: { $0.id == id }) else {
                throw ReflectionError.invalid("Configured display is not active")
            }
            target = display.bounds
        } else {
            guard let display = displays.first(where: \.main) else {
                throw ReflectionError.invalid("No main display available")
            }
            target = display.bounds
        }
        let dryRun = args.contains("--dry-run")
        let backend: TabletBackend = dryRun ? RecordingBackend()
            : try QuartzTabletBackend(emitTabletPointEvents: args.contains("--tablet-point-events"))
        let mapper = CoordinateMapper(configuration: config.mapping, target: target,
            initialCursor: CGEvent(source: nil)?.location)
        let server = try TabletServer(configuration: config, backend: backend, mapper: mapper,
            dryRun: dryRun, statusURL: statusURL, verbose: args.contains("--verbose"))
        try server.start { error in
            fputs("reflection: \(error)\n", stderr)
            exit(1)
        }
        let bluetooth = args.contains("--bluetooth")
            ? BluetoothBridge(host: config.bind, port: config.port) : nil
        try bluetooth?.start()
        var stopping = false
        shutdown = {
            guard !stopping else { return }
            stopping = true
            bluetooth?.stop()
            server.stop { exit(0) }
        }
        for signum in [SIGINT, SIGTERM] {
            signal(signum, SIG_IGN)
            let source = DispatchSource.makeSignalSource(signal: signum, queue: .main)
            source.setEventHandler { shutdown?() }
            source.resume()
            signalSources.append(source)
        }
        withExtendedLifetime(control) { RunLoop.main.run() }
    default: throw ReflectionError.invalid("Unknown command: \(command). Run reflection help.")
    }
}

do { try main() }
catch {
    fputs("reflection: \(error)\n", stderr)
    exit(1)
}
