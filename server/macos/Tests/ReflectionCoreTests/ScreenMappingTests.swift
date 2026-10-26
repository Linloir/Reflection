import XCTest
@testable import ReflectionCore

final class ScreenMappingTests: XCTestCase {
    private let left = MappingDisplay(id: "left", name: "Left", bounds: Region(x: -1920, y: -200, width: 1920, height: 1080), primary: false)
    private let main = MappingDisplay(id: "main", name: "Main", bounds: Region(x: 0, y: 0, width: 2560, height: 1440), primary: true)

    func testDesktopUnionUsesSystemArrangementAndStableDisplayIdentifiers() throws {
        var mapping = try ScreenMappingState(displays: [main, left])
        XCTAssertEqual(mapping.status.mode, "all")
        XCTAssertEqual(mapping.target, Region(x: -1920, y: -200, width: 4480, height: 1640))
        XCTAssertEqual(mapping.displays.map(\.id), ["left", "main"])
        try mapping.request(1)
        XCTAssertEqual(mapping.status.selected, "left")
        try mapping.request(2)
        XCTAssertEqual(mapping.status.selected, "main")
        try mapping.request(3)
        XCTAssertEqual(mapping.status.mode, "all")
    }

    func testSwitchWaitsUntilOldStrokeUpAndAccumulatesRapidSwitches() throws {
        var mapping = try ScreenMappingState(displays: [left, main], selected: "left")
        mapping.didSubmit(.down)
        try mapping.request(1)
        XCTAssertTrue(mapping.status.pending)
        XCTAssertEqual(mapping.target, left.region)
        mapping.didSubmit(.move)
        XCTAssertEqual(mapping.target, left.region)
        mapping.didSubmit(.up)
        XCTAssertFalse(mapping.status.pending)
        XCTAssertEqual(mapping.target, main.region)
        mapping.didSubmit(.down)
        try mapping.request(1)
        try mapping.request(1)
        XCTAssertFalse(mapping.status.pending)
        XCTAssertEqual(mapping.target, main.region)
    }

    func testDisconnectAppliesPendingSelectionAndTopologyFallbackIsDeferred() throws {
        var mapping = try ScreenMappingState(displays: [left, main], selected: "left")
        mapping.didSubmit(.down)
        try mapping.updateDisplays([main])
        XCTAssertTrue(mapping.status.pending)
        XCTAssertEqual(mapping.status.selected, "left")
        XCTAssertTrue(mapping.status.displays.contains(where: { $0.id == "left" }))
        mapping.release()
        XCTAssertEqual(mapping.status.mode, "all")
        XCTAssertEqual(mapping.target, main.region)
        XCTAssertTrue(mapping.status.fallback)
        XCTAssertFalse(mapping.status.pending)
        XCTAssertEqual(mapping.status.displays.map(\.id), ["main"])
    }

    func testTopologyAndCustomAreaRemainIndependent() throws {
        let custom = Region(x: -100, y: 15, width: 80, height: 60)
        var mapping = try ScreenMappingState(displays: [left, main], custom: custom)
        try mapping.updateDisplays([main])
        XCTAssertEqual(mapping.target, custom)
        XCTAssertEqual(mapping.status.mode, "custom")
        XCTAssertThrowsError(try mapping.request(4, displayID: "missing"))
        XCTAssertEqual(mapping.target, custom)
        try mapping.request(4, displayID: "main")
        XCTAssertEqual(mapping.target, main.region)
    }

    func testPreferredDisplayRestoresAfterReconnectAtStrokeBoundary() throws {
        var mapping = try ScreenMappingState(displays: [left, main], selected: "left")
        try mapping.updateDisplays([main])
        XCTAssertTrue(mapping.status.fallback)
        mapping.didSubmit(.down)
        try mapping.updateDisplays([left, main])
        XCTAssertEqual(mapping.status.mode, "all")
        XCTAssertTrue(mapping.status.pending)
        XCTAssertEqual(mapping.target, main.region)
        mapping.didSubmit(.up)
        XCTAssertEqual(mapping.status.selected, "left")
        XCTAssertFalse(mapping.status.fallback)
        XCTAssertEqual(mapping.target, left.region)

        try mapping.updateDisplays([main])
        try mapping.request(3)
        try mapping.updateDisplays([left, main])
        XCTAssertEqual(mapping.status.mode, "all")
        XCTAssertFalse(mapping.status.fallback)

        var disconnectedAtLaunch = try ScreenMappingState(displays: [main], selected: "left")
        XCTAssertTrue(disconnectedAtLaunch.status.fallback)
        try disconnectedAtLaunch.updateDisplays([left, main])
        XCTAssertEqual(disconnectedAtLaunch.status.selected, "left")
    }

    func testMappingCapabilityRequiresIntegerVersionOne() throws {
        for (json, expected) in [
            ("{\"mappingControl\":1}", true),
            ("{\"mappingControl\":true}", false),
            ("{\"mappingControl\":false}", false),
            ("{\"mappingControl\":1.5}", false),
            ("{\"mappingControl\":\"1\"}", false),
            ("{\"mappingControl\":2}", false),
            ("{}", false)
        ] {
            XCTAssertEqual(MappingRequest.supportsControl(in: Data(json.utf8)), expected, json)
        }
    }

    func testRejectMalformedAndReplayedRequests() throws {
        func request(_ sequence: UInt64 = 1, _ action: UInt8 = 1, _ id: [UInt8] = []) -> Data {
            Data((0..<8).map { UInt8(truncatingIfNeeded: sequence >> (56 - $0 * 8)) } + [action] + id)
        }
        XCTAssertEqual(try MappingRequest.decode(request(), after: 0).action, 1)
        XCTAssertEqual(try MappingRequest.decode(request(2, 4, Array("main".utf8)), after: 1).displayID, "main")
        XCTAssertThrowsError(try MappingRequest.decode(request(), after: 1))
        XCTAssertThrowsError(try MappingRequest.decode(request(0), after: 0))
        XCTAssertThrowsError(try MappingRequest.decode(request(1, 5), after: 0))
        XCTAssertThrowsError(try MappingRequest.decode(request(1, 4), after: 0))
        XCTAssertThrowsError(try MappingRequest.decode(request(1, 3, [65]), after: 0))
        XCTAssertThrowsError(try MappingRequest.decode(request(1, 4, [0xff]), after: 0))
        XCTAssertThrowsError(try MappingRequest.decode(request(1, 4, [65, 10]), after: 0))
        XCTAssertThrowsError(try MappingRequest.decode(request(1, 4, Array(repeating: 65, count: 129)), after: 0))
    }

    func testBoundedStatusAndInvalidLayouts() throws {
        let displays = (0..<16).map { index in
            MappingDisplay(id: "00000000-0000-0000-0000-\(String(format: "%012d", index))", name: "Display \(index)",
                           bounds: Region(x: Double(index) * 1920, y: 0, width: 1920, height: 1080), primary: index == 0)
        }
        let mapping = try ScreenMappingState(displays: displays)
        XCTAssertLessThanOrEqual(try JSONEncoder().encode(mapping.status).count, 4062)
        XCTAssertThrowsError(try ScreenMappingState(displays: []))
        XCTAssertThrowsError(try ScreenMappingState(displays: [main, main]))
        XCTAssertThrowsError(try ScreenMappingState(displays: [MappingDisplay(id: "bad", name: "Bad",
            bounds: Region(x: .infinity, y: 0, width: 10, height: 10), primary: false)]))
    }
}
