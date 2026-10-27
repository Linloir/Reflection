import Foundation
import ReflectionCore

private final class USBCommandFixture {
    var devices: [String: String] = [:]
    var identities: [String: String] = [:]
    var commands: [[String]] = []
    var rejectProvision = false
    var provisionReply: String?
    var failList = false

    func run(_ arguments: [String]) -> String? {
        commands.append(arguments)
        if arguments == ["devices", "-l"] {
            if failList { return nil }
            return "List of devices attached\n" + devices.keys.sorted().map {
                "\($0) device usb:1 model:\(devices[$0]!) transport_id:1"
            }.joined(separator: "\n") + "\nwireless:5555 device model:Network_Tablet\n"
        }
        guard arguments.count > 2, arguments[0] == "-s", devices[arguments[1]] != nil else { return nil }
        if arguments.contains("path") { return "package:/data/app/reflection/base.apk\n" }
        if arguments[2] == "reverse" { return "" }
        if arguments.contains("pairing-identity"), let id = identities[arguments[1]] {
            return "Broadcast completed: result=0, data=\"{\\\"id\\\":\\\"\(id)\\\"}\"\n"
        }
        if arguments.contains("provision-usb") {
            if let provisionReply { return provisionReply }
            return rejectProvision ? "Broadcast completed: result=1" : "Broadcast completed: result=0"
        }
        return nil
    }
}

func usbSelectionChecks() throws -> Int {
    var count = 0
    func require(_ condition: Bool, _ name: String) throws {
        guard condition else { throw ReflectionError.invalid("USB selection check: \(name)") }
        count += 1
    }
    func rejects(_ name: String, _ operation: () throws -> Void) throws {
        do { try operation() }
        catch { count += 1; return }
        throw ReflectionError.invalid("USB selection unexpectedly succeeded: \(name)")
    }
    let directory = FileManager.default.temporaryDirectory.appendingPathComponent("reflection-usb-checks-\(UUID().uuidString)")
    try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: directory) }
    let configuration = ServerConfiguration(token: String(repeating: "24", count: 32))
    let configURL = directory.appendingPathComponent("config.json")
    let discovery = HostDiscoveryService(configuration: configuration, configurationURL: configURL)
    let fixture = USBCommandFixture()
    let first = "001122" + String(repeating: "a", count: 26)
    let second = "334455" + String(repeating: "b", count: 26)
    fixture.devices = ["serial-a": "BOOX_Note2"]
    fixture.identities = ["serial-a": first, "serial-b": second]
    var selections = 0
    discovery.onSelection = { selections += 1 }
    let usb = USBProvisioningService(configuration: configuration,
        authorize: { try discovery.authorizeUSB($0) },
        discovered: { discovery.updateUSBTablets($0) }, commandExecutor: fixture.run)
    discovery.onUSBSelection = { try usb.selectTablet($0, token: $1) }
    func list() throws -> [DiscoveredTablet] {
        try JSONDecoder().decode([DiscoveredTablet].self, from: discovery.command("list"))
    }
    func trust(_ id: String) throws -> [String: Any] {
        try JSONSerialization.jsonObject(with: discovery.command("trust \(id)")) as! [String: Any]
    }
    usb.refresh()
    let initial = try list()
    try require(initial.count == 1 && initial[0].id == first && initial[0].address == "USB",
                "USB-only tablet appears without a LAN or Bluetooth announcement")
    try require(initial[0].name == "BOOX Note2" && initial[0].code == "004386" && initial[0].trusted,
                "USB name and device code identify the authorized tablet")
    try require(selections == 0, "initial USB enumeration does not claim an explicit choice")
    let responseA = try trust(first)
    let tokenA = HostDiscoveryService.deviceToken(rootToken: configuration.token, deviceID: first)
    let tokenB = HostDiscoveryService.deviceToken(rootToken: configuration.token, deviceID: second)
    try require(responseA["usbSelected"] as? Bool == true && responseA["confirmationCode"] == nil,
                "USB command reports selection without a confirmation-code prompt")
    try require(discovery.authenticationTokens == [tokenA] && selections == 1, "explicit selection admits tablet A")
    fixture.devices["serial-b"] = "Second_Tablet"
    fixture.commands.removeAll()
    usb.refresh()
    let paired = try list()
    try require(paired.count == 2 && paired.contains(where: { $0.id == second }), "tablet B appears while A remains selected")
    try require(discovery.authenticationTokens == [tokenA] && selections == 1, "new attach and periodic refresh preserve A")
    let leases = fixture.commands.filter { $0.contains("provision-usb") }
    try require(leases.count == 2 && leases.allSatisfy { Array($0.suffix(3)) == ["--ez", "select", "false"] },
                "every periodic lease explicitly avoids changing tablet host preference")
    _ = try trust(second)
    try require(discovery.authenticationTokens == [tokenB] && selections == 2, "explicit USB choice switches A to B")
    try require(fixture.commands.last?.suffix(3) == ["--ez", "select", "true"], "host click carries explicit selection intent")
    usb.refresh()
    try require(discovery.authenticationTokens == [tokenB] && selections == 2, "A background lease cannot reclaim B selection")
    fixture.rejectProvision = true
    try rejects("receiver refusal") { _ = try trust(first) }
    try require(discovery.authenticationTokens == [tokenB] && selections == 2, "receiver refusal preserves current selection")
    fixture.rejectProvision = false
    fixture.provisionReply = "Broadcast completed: result=1, data=\"error with result=0\""
    try rejects("failure text cannot masquerade as result zero") { _ = try trust(first) }
    fixture.provisionReply = "Broadcast completed: result=01"
    try rejects("result code requires a complete numeric field") { _ = try trust(first) }
    fixture.provisionReply = "Broadcast completed: result=0, data=\"{host:errorbox.local}\""
    _ = try trust(second)
    try require(discovery.authenticationTokens == [tokenB], "successful status may contain the word error")
    fixture.provisionReply = nil
    fixture.identities["serial-a"] = second
    try rejects("serial now belongs to another identity") { _ = try trust(first) }
    fixture.identities["serial-a"] = first
    fixture.devices.removeValue(forKey: "serial-a")
    try rejects("unplugged device before next poll") { _ = try trust(first) }
    usb.refresh()
    let afterUnplug = try list()
    try require(afterUnplug.count == 1 && afterUnplug[0].id == second, "unplugged tablet leaves Nearby")
    try rejects("unplugged USB has no stale trust path") { _ = try trust(first) }
    fixture.failList = true
    usb.refresh()
    try require(try list().isEmpty, "ADB enumeration failure clears live USB presence")
    fixture.failList = false
    let restarted = HostDiscoveryService(configuration: configuration, configurationURL: configURL)
    let restartedUSB = USBProvisioningService(configuration: configuration,
        authorize: { try restarted.authorizeUSB($0) },
        discovered: { restarted.updateUSBTablets($0) }, commandExecutor: fixture.run)
    fixture.commands.removeAll()
    restartedUSB.refresh()
    let restartedLeases = fixture.commands.filter { $0.contains("provision-usb") }
    try require(restartedLeases.count == 1 && restartedLeases[0].suffix(3) == ["--ez", "select", "false"],
                "host restart never sends an implicit selection")
    try require(Set(restarted.authenticationTokens) == Set([configuration.token, tokenA, tokenB]),
                "USB trust persists across host restart")
    let entered = DispatchSemaphore(value: 0), finished = DispatchSemaphore(value: 0)
    let resultLock = NSLock()
    var received = false
    let control = try HostControl(configurationURL: configURL, pairingCommand: { _ in
        entered.signal()
        Thread.sleep(forTimeInterval: 0.65)
        return Data("{\"usbSelected\":true}".utf8)
    }, onStop: {})
    DispatchQueue.global().async {
        let data = try? HostControl.requestPairing("trust \(first)", configurationURL: configURL)
        resultLock.lock()
        received = data == Data("{\"usbSelected\":true}\n".utf8)
        resultLock.unlock()
        finished.signal()
    }
    try require(entered.wait(timeout: .now() + 2) == .success, "CLI selection reaches asynchronous control handler")
    try require(HostControl.isRunning(configurationURL: configURL), "status responds during USB verification")
    try require(finished.wait(timeout: .now() + 3) == .success, "CLI selection finishes without the old half-second timeout")
    resultLock.lock()
    let receivedResult = received
    resultLock.unlock()
    try require(receivedResult, "CLI receives verified USB selection result")
    withExtendedLifetime(control) {}
    return count
}
