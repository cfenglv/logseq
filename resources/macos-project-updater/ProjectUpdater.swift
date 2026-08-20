import CryptoKit
import Darwin
import Foundation

private let algorithm = "ed25519-selfhost-release-v1"
private let payloadDomain = "logseq-selfhost-official-architecture-update-v1"
private let releaseLine = "selfhost-official-architecture-v1"
private let bundleIdentity = "com.logseq.logseq"
private let keyId = "__LOGSEQ_SELFHOST6_UPDATE_KEY_ID__"
private let publicKeyBase64 = "__LOGSEQ_SELFHOST6_UPDATE_PUBLIC_KEY_BASE64__"

private struct Failure: Error, CustomStringConvertible {
    let description: String
}

private func fail(_ message: String) throws -> Never {
    throw Failure(description: message)
}

private struct Arguments {
    let archive: URL
    let metadata: URL
    let parentPID: pid_t
    let relaunch: Bool
    let target: URL
    let verifyOnly: Bool
#if SELFHOST6_UPDATER_TESTING
    let testFailAfterParentExit: Bool
    let testFailAfterSwap: Bool
#endif

    init(_ argv: [String]) throws {
        var values: [String: String] = [:]
        var flags = Set<String>()
        let valueNames = Set(["--archive", "--metadata", "--parent-pid", "--relaunch", "--target"])
        var index = 0
        while index < argv.count {
            let item = argv[index]
            if item == "--verify-only" {
                guard flags.insert(item).inserted else { try fail("duplicate \(item)") }
                index += 1
                continue
            }
#if SELFHOST6_UPDATER_TESTING
            if item == "--test-fail-after-parent-exit" {
                guard flags.insert(item).inserted else { try fail("duplicate \(item)") }
                index += 1
                continue
            }
            if item == "--test-fail-after-swap" {
                guard flags.insert(item).inserted else { try fail("duplicate \(item)") }
                index += 1
                continue
            }
#endif
            guard valueNames.contains(item), index + 1 < argv.count, values[item] == nil else {
                try fail("invalid argument near \(item)")
            }
            values[item] = argv[index + 1]
            index += 2
        }
        func required(_ name: String) throws -> String {
            guard let value = values[name], !value.isEmpty else { try fail("missing \(name)") }
            return value
        }
        archive = URL(fileURLWithPath: try required("--archive")).standardizedFileURL
        metadata = URL(fileURLWithPath: try required("--metadata")).standardizedFileURL
        target = URL(fileURLWithPath: try required("--target")).standardizedFileURL
        guard target.path.hasSuffix(".app"), archive.path.hasSuffix(".zip") else {
            try fail("target must be an App bundle and archive must be a ZIP")
        }
        guard let pid = Int32(try required("--parent-pid")), pid > 1 else {
            try fail("parent pid must be greater than one")
        }
        parentPID = pid
        let relaunchValue = try required("--relaunch")
        guard relaunchValue == "true" || relaunchValue == "false" else {
            try fail("relaunch must be true or false")
        }
        relaunch = relaunchValue == "true"
        verifyOnly = flags.contains("--verify-only")
#if SELFHOST6_UPDATER_TESTING
        testFailAfterParentExit = flags.contains("--test-fail-after-parent-exit")
        testFailAfterSwap = flags.contains("--test-fail-after-swap")
#endif
    }
}

private struct SignedMetadata: Codable {
    let schemaVersion: Int
    let algorithm: String
    let keyId: String
    let releaseLineId: String
    let targetSourceFullSha: String
    let targetVersion: String
    let platform: String
    let arch: String
    let bundleIdentity: String
    let immutableObjectKey: String
    let archiveSize: UInt64
    let archiveSha256: String
    let archiveSha512: String
    let targetBuildManifestSha256: String
    let readableActivationFormats: [String]
    let readableClientOpsFormats: [String]
    let activationWriteFormat: String
    let clientOpsWriteFormat: String
    let signature: String

    enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema-version"
        case algorithm
        case keyId = "key-id"
        case releaseLineId = "release-line-id"
        case targetSourceFullSha = "target-source-full-sha"
        case targetVersion = "target-version"
        case platform, arch
        case bundleIdentity = "bundle-identity"
        case immutableObjectKey = "immutable-object-key"
        case archiveSize = "archive-size"
        case archiveSha256 = "archive-sha256"
        case archiveSha512 = "archive-sha512"
        case targetBuildManifestSha256 = "target-build-manifest-sha256"
        case readableActivationFormats = "readable-activation-formats"
        case readableClientOpsFormats = "readable-client-ops-formats"
        case activationWriteFormat = "activation-write-format"
        case clientOpsWriteFormat = "client-ops-write-format"
        case signature
    }
}

private struct TargetManifest: Codable {
    let schemaVersion: Int
    let targetSourceFullSha: String
    let targetVersion: String
    let releaseLineId: String
    let platform: String
    let arch: String
    let bundleIdentity: String
    let signingKeyIdentity: String
    let readableActivationFormats: [String]
    let readableClientOpsFormats: [String]
    let activationWriteFormat: String
    let clientOpsWriteFormat: String

    enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema-version"
        case targetSourceFullSha = "target-source-full-sha"
        case targetVersion = "target-version"
        case releaseLineId = "release-line-id"
        case platform, arch
        case bundleIdentity = "bundle-identity"
        case signingKeyIdentity = "signing-key-identity"
        case readableActivationFormats = "readable-activation-formats"
        case readableClientOpsFormats = "readable-client-ops-formats"
        case activationWriteFormat = "activation-write-format"
        case clientOpsWriteFormat = "client-ops-write-format"
    }
}

private func json(_ value: Any) throws -> String {
    let data = try JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed, .withoutEscapingSlashes])
    guard let result = String(data: data, encoding: .utf8) else { try fail("cannot encode signed payload") }
    return result
}

private func canonicalPayload(_ value: SignedMetadata) throws -> Data {
    let fields: [(String, Any)] = [
        ("schema-version", value.schemaVersion),
        ("algorithm", value.algorithm),
        ("key-id", value.keyId),
        ("release-line-id", value.releaseLineId),
        ("target-source-full-sha", value.targetSourceFullSha),
        ("target-version", value.targetVersion),
        ("platform", value.platform),
        ("arch", value.arch),
        ("bundle-identity", value.bundleIdentity),
        ("immutable-object-key", value.immutableObjectKey),
        ("archive-size", value.archiveSize),
        ("archive-sha256", value.archiveSha256),
        ("archive-sha512", value.archiveSha512),
        ("target-build-manifest-sha256", value.targetBuildManifestSha256),
        ("readable-activation-formats", value.readableActivationFormats),
        ("readable-client-ops-formats", value.readableClientOpsFormats),
        ("activation-write-format", value.activationWriteFormat),
        ("client-ops-write-format", value.clientOpsWriteFormat),
    ]
    let text = try ([payloadDomain] + fields.map { "\($0.0)=\(try json($0.1))" } + [""]).joined(separator: "\n")
    return Data(text.utf8)
}

private func hex<D: Sequence>(_ digest: D) -> String where D.Element == UInt8 {
    digest.map { String(format: "%02x", $0) }.joined()
}

private func fileDigests(_ url: URL) throws -> (UInt64, String, String) {
    let handle = try FileHandle(forReadingFrom: url)
    defer { try? handle.close() }
    var sha256 = SHA256()
    var sha512 = SHA512()
    var size: UInt64 = 0
    while let data = try handle.read(upToCount: 1024 * 1024), !data.isEmpty {
        size += UInt64(data.count)
        sha256.update(data: data)
        sha512.update(data: data)
    }
    return (size, hex(sha256.finalize()), hex(sha512.finalize()))
}

@discardableResult
private func run(_ executable: String, _ arguments: [String]) throws -> String {
    let process = Process()
    let output = Pipe()
    process.executableURL = URL(fileURLWithPath: executable)
    process.arguments = arguments
    process.standardOutput = output
    process.standardError = output
    try process.run()
    process.waitUntilExit()
    let text = String(data: output.fileHandleForReading.readDataToEndOfFile(), encoding: .utf8) ?? ""
    guard process.terminationStatus == 0 else { try fail("\(executable) failed: \(text)") }
    return text
}

private func helperArch() -> String {
#if arch(arm64)
    return "arm64"
#elseif arch(x86_64)
    return "x64"
#else
    return "unsupported"
#endif
}

private func validateMetadata(_ value: SignedMetadata, archive: URL) throws {
    guard value.schemaVersion == 1,
          value.algorithm == algorithm,
          value.keyId == keyId,
          value.releaseLineId == releaseLine,
          value.targetVersion == "2.0.1-selfhost.7",
          value.platform == "darwin",
          value.arch == helperArch(),
          value.bundleIdentity == bundleIdentity,
          value.targetSourceFullSha.range(of: "^[0-9a-f]{40}$", options: .regularExpression) != nil,
          value.archiveSha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
          value.archiveSha512.range(of: "^[0-9a-f]{128}$", options: .regularExpression) != nil,
          value.targetBuildManifestSha256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
          value.immutableObjectKey.contains(releaseLine),
          value.immutableObjectKey.contains(value.targetSourceFullSha),
          value.immutableObjectKey.contains(value.archiveSha256),
          !value.readableActivationFormats.isEmpty,
          !value.readableClientOpsFormats.isEmpty else {
        try fail("signed update metadata is invalid")
    }
    guard let signature = Data(base64Encoded: value.signature), signature.count == 64,
          let rawKey = Data(base64Encoded: publicKeyBase64), rawKey.count == 32 else {
        try fail("signed update key or signature encoding is invalid")
    }
    let publicKey = try Curve25519.Signing.PublicKey(rawRepresentation: rawKey)
    guard publicKey.isValidSignature(signature, for: try canonicalPayload(value)) else {
        try fail("signed update metadata signature is invalid")
    }
    let digests = try fileDigests(archive)
    guard digests.0 == value.archiveSize,
          digests.1 == value.archiveSha256,
          digests.2 == value.archiveSha512 else {
        try fail("archive bytes do not match signed metadata")
    }
}

private func validateExtractedApp(_ app: URL, metadata: SignedMetadata) throws {
    let manifestURL = app.appendingPathComponent("Contents/Resources/updater/TARGET_BUILD_MANIFEST.json")
    let manifestData = try Data(contentsOf: manifestURL)
    guard hex(SHA256.hash(data: manifestData)) == metadata.targetBuildManifestSha256 else {
        try fail("target build manifest digest mismatch")
    }
    let manifest = try JSONDecoder().decode(TargetManifest.self, from: manifestData)
    guard manifest.schemaVersion == 1,
          manifest.targetSourceFullSha == metadata.targetSourceFullSha,
          manifest.targetVersion == metadata.targetVersion,
          manifest.releaseLineId == metadata.releaseLineId,
          manifest.platform == metadata.platform,
          manifest.arch == metadata.arch,
          manifest.bundleIdentity == metadata.bundleIdentity,
          manifest.signingKeyIdentity == metadata.keyId,
          manifest.readableActivationFormats == metadata.readableActivationFormats,
          manifest.readableClientOpsFormats == metadata.readableClientOpsFormats,
          manifest.activationWriteFormat == metadata.activationWriteFormat,
          manifest.clientOpsWriteFormat == metadata.clientOpsWriteFormat else {
        try fail("target build manifest differs from signed metadata")
    }
    guard let bundle = Bundle(url: app),
          bundle.bundleIdentifier == bundleIdentity,
          bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String == metadata.targetVersion else {
        try fail("extracted App bundle identity does not match target metadata")
    }
    _ = try run("/usr/bin/codesign", ["--verify", "--deep", "--strict", app.path])
}

private func waitForParent(_ pid: pid_t) throws {
    let deadline = Date().addingTimeInterval(60)
    while kill(pid, 0) == 0 {
        if Date() >= deadline { try fail("parent App did not exit before install deadline") }
        usleep(100_000)
    }
    guard errno == ESRCH else { try fail("cannot determine parent App state") }
}

private func launch(_ app: URL) throws {
    _ = try run("/usr/bin/open", ["-n", app.path])
}

private func install(_ arguments: Arguments) throws {
    let manager = FileManager.default
    let metadataData = try Data(contentsOf: arguments.metadata)
    let metadata = try JSONDecoder().decode(SignedMetadata.self, from: metadataData)
    try validateMetadata(metadata, archive: arguments.archive)
    if arguments.verifyOnly { return }

    try waitForParent(arguments.parentPID)
#if SELFHOST6_UPDATER_TESTING
    if arguments.testFailAfterParentExit { try fail("injected failure after parent exit") }
#endif

    let parent = arguments.target.deletingLastPathComponent()
    let staging = parent.appendingPathComponent(".logseq-update-\(UUID().uuidString)")
    let backup = parent.appendingPathComponent(".logseq-previous-\(UUID().uuidString).app")
    try manager.createDirectory(at: staging, withIntermediateDirectories: false)
    defer { try? manager.removeItem(at: staging) }
    _ = try run("/usr/bin/ditto", ["-x", "-k", arguments.archive.path, staging.path])
    let candidate = staging.appendingPathComponent("Logseq.app")
    guard manager.fileExists(atPath: candidate.path) else { try fail("archive does not contain Logseq.app") }
    try validateExtractedApp(candidate, metadata: metadata)
    var previousMoved = false
    var candidateMoved = false
    do {
        try manager.moveItem(at: arguments.target, to: backup)
        previousMoved = true
        try manager.moveItem(at: candidate, to: arguments.target)
        candidateMoved = true
#if SELFHOST6_UPDATER_TESTING
        if arguments.testFailAfterSwap { try fail("injected failure after swap") }
#endif
        if arguments.relaunch { try launch(arguments.target) }
        try manager.removeItem(at: backup)
    } catch {
        if candidateMoved { try? manager.removeItem(at: arguments.target) }
        if previousMoved { try? manager.moveItem(at: backup, to: arguments.target) }
        throw error
    }
    try? manager.removeItem(at: arguments.metadata.deletingLastPathComponent())
}

do {
    let arguments = try Arguments(Array(CommandLine.arguments.dropFirst()))
    do {
        try install(arguments)
    } catch {
        if !arguments.verifyOnly,
           arguments.relaunch,
           (try? waitForParent(arguments.parentPID)) != nil,
           FileManager.default.fileExists(atPath: arguments.target.path) {
            try? launch(arguments.target)
        }
        throw error
    }
    exit(EXIT_SUCCESS)
} catch {
    FileHandle.standardError.write(Data("selfhost6 updater: \(error)\n".utf8))
    exit(EXIT_FAILURE)
}
