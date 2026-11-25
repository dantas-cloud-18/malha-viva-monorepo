// swift-tools-version: 6.0
import PackageDescription

let features = [
    "Auth", "Trains", "Stations", "Tracking", "Alerts",
    "Privacy", "Onboarding", "Achievements", "Widgets",
]
let core = ["DomainLayer", "DataLayer", "DesignSystem", "CoreUI"]

let package = Package(
    name: "MalhaVivaKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: (core + features.map { "Feature\($0)" }).map { .library(name: $0, targets: [$0]) },
    targets: [
        .target(name: "DomainLayer"),
        .target(name: "DataLayer", dependencies: ["DomainLayer"]),
        .target(name: "DesignSystem"),
        .target(name: "CoreUI"),
        .testTarget(name: "CoreUITests", dependencies: ["CoreUI"]),
    ] + features.map { name in
        .target(name: "Feature\(name)", dependencies: ["DomainLayer", "CoreUI", "DesignSystem"])
    }
)
