// swift-tools-version: 5.9
import PackageDescription
import Foundation

let infoPlist = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
    .appendingPathComponent("Sources/ReflectionCLI/Info.plist").path

let package = Package(
    name: "Reflection",
    platforms: [.macOS(.v12)],
    products: [
        .library(name: "ReflectionCore", targets: ["ReflectionCore"]),
        .executable(name: "reflection", targets: ["ReflectionCLI"]),
        .executable(name: "reflection-core-checks", targets: ["ReflectionCoreChecks"])
    ],
    targets: [
        .target(name: "ReflectionCore"),
        .executableTarget(name: "ReflectionCLI", dependencies: ["ReflectionCore"],
            exclude: ["Info.plist"],
            linkerSettings: [.unsafeFlags(["-Xlinker", "-sectcreate", "-Xlinker", "__TEXT",
                                          "-Xlinker", "__info_plist", "-Xlinker", infoPlist])]),
        .executableTarget(name: "ReflectionCoreChecks", dependencies: ["ReflectionCore"], path: "Checks"),
        .testTarget(name: "ReflectionCoreTests", dependencies: ["ReflectionCore"])
    ]
)
