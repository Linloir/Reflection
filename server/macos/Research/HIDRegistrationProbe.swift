import Foundation
import CoreHID

// Registration only: no activation, input reports, event injection, or permission changes.
// Optionally pass a binary report descriptor to compare it with a vendor-defined control.
func probe(_ descriptor: Data, name: String) -> Bool {
    if #available(macOS 15.0, *) {
        let properties = HIDVirtualDevice.Properties(
            descriptor: descriptor, vendorID: 0xffff, productID: 0x5266,
            product: "Reflection registration probe \(name)", uniqueID: UUID().uuidString)
        let device = HIDVirtualDevice(properties: properties)
        return withExtendedLifetime(device) { device != nil }
    }
    return false
}

let control = Data([
    0x06, 0x00, 0xff, 0x09, 0x01, 0xa1, 0x01,
    0x15, 0x00, 0x26, 0xff, 0x00, 0x75, 0x08, 0x95, 0x01,
    0x09, 0x02, 0x81, 0x02, 0xc0
])
var result: [String: Any] = [
    "os": ProcessInfo.processInfo.operatingSystemVersionString,
    "controlRegistered": probe(control, name: "control"),
    "activated": false,
    "inputReportsSent": 0
]
if CommandLine.arguments.count > 1 {
    let descriptor = try Data(contentsOf: URL(fileURLWithPath: CommandLine.arguments[1]))
    result["candidateBytes"] = descriptor.count
    result["candidateRegistered"] = probe(descriptor, name: "candidate")
}
let output = try JSONSerialization.data(withJSONObject: result, options: [.sortedKeys])
print(String(decoding: output, as: UTF8.self))
