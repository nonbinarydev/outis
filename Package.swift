// swift-tools-version:5.9
//
// Copyright 2026 The Outis Authors.
//
// Licensed under the Apache License, Version 2.0. See LICENSE for details.

import PackageDescription

let package = Package(
    name: "Outis",
    // The XCFramework's own minimums (Kotlin/Native's default deployment targets); lower ones fail to link.
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "Outis", targets: ["Outis"]),
    ],
    targets: [
        // Built by `./gradlew :core:assembleOutisCoreReleaseXCFramework` and never committed. A release
        // replaces this path with the zipped framework's `url:` and `checksum:`. Its name and path come
        // from core/build.gradle.kts; rename them together.
        .binaryTarget(
            name: "OutisCore",
            path: "core/build/XCFrameworks/release/OutisCore.xcframework"
        ),
        .target(
            name: "Outis",
            dependencies: ["OutisCore"],
            path: "swift/Outis"
        ),
    ]
)
