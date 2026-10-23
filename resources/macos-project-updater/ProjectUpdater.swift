import CryptoKit
import Darwin
import Foundation
#if SELFHOST6_OFFLINE_BRIDGE
import AppKit

private let bridgePayloadName = "__LOGSEQ_BRIDGE_PAYLOAD_NAME__"
private let bridgeFinalVersion = "__LOGSEQ_BRIDGE_FINAL_VERSION__"
private let bridgeFinalSource = "__LOGSEQ_BRIDGE_FINAL_SOURCE__"
private let bridgePayloadSha256 = "__LOGSEQ_BRIDGE_PAYLOAD_SHA256__"
private let bridgePayloadSize: UInt64 = __LOGSEQ_BRIDGE_PAYLOAD_SIZE__
private let bridgeManifestSha256 = "__LOGSEQ_BRIDGE_MANIFEST_SHA256__"
private let bridgeErrorTitleEN = "__LOGSEQ_BRIDGE_ERROR_TITLE_EN__"
private let bridgeErrorDescEN = "__LOGSEQ_BRIDGE_ERROR_DESC_EN__"
private let bridgeErrorTitleZH = "__LOGSEQ_BRIDGE_ERROR_TITLE_ZH__"
private let bridgeErrorDescZH = "__LOGSEQ_BRIDGE_ERROR_DESC_ZH__"
private let bridgeTextEN = ["__LOGSEQ_BRIDGE_VERSION_EN__", "__LOGSEQ_BRIDGE_PREPARING_EN__",
    "__LOGSEQ_BRIDGE_DOWNLOADING_EN__", "__LOGSEQ_BRIDGE_PROGRESS_EN__", "__LOGSEQ_BRIDGE_VERIFYING_EN__",
    "__LOGSEQ_BRIDGE_INSTALLING_EN__", "__LOGSEQ_BRIDGE_RETRY_EN__", "__LOGSEQ_BRIDGE_CLOSE_EN__"]
private let bridgeTextZH = ["__LOGSEQ_BRIDGE_VERSION_ZH__", "__LOGSEQ_BRIDGE_PREPARING_ZH__",
    "__LOGSEQ_BRIDGE_DOWNLOADING_ZH__", "__LOGSEQ_BRIDGE_PROGRESS_ZH__", "__LOGSEQ_BRIDGE_VERIFYING_ZH__",
    "__LOGSEQ_BRIDGE_INSTALLING_ZH__", "__LOGSEQ_BRIDGE_RETRY_ZH__", "__LOGSEQ_BRIDGE_CLOSE_ZH__"]
#endif

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
#if !SELFHOST6_OFFLINE_BRIDGE
    let parentPID: pid_t
#endif
    let relaunch: Bool
    let target: URL
    let verifyOnly: Bool
#if SELFHOST6_UPDATER_TESTING
    let testFailAfterParentExit: Bool
    let testFailAfterSwap: Bool
    let testFailBackupCleanup: Bool
#endif

#if SELFHOST6_OFFLINE_BRIDGE
    init(archive: URL) {
        target = Bundle.main.bundleURL.standardizedFileURL
        let payload = target.appendingPathComponent("Contents/Resources/payload")
        self.archive = archive
        metadata = payload.appendingPathComponent(bridgePayloadName + ".selfhost6.json")
        relaunch = true
        verifyOnly = false
#if SELFHOST6_UPDATER_TESTING
        testFailAfterParentExit = CommandLine.arguments.contains("--test-fail-after-parent-exit")
        testFailAfterSwap = CommandLine.arguments.contains("--test-fail-after-swap")
        testFailBackupCleanup = CommandLine.arguments.contains("--test-fail-backup-cleanup")
#endif
    }
#else
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
            if item == "--test-fail-backup-cleanup" {
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
        testFailBackupCleanup = flags.contains("--test-fail-backup-cleanup")
#endif
    }
#endif
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

#if SELFHOST6_OFFLINE_BRIDGE
private struct BridgeDescriptor: Decodable {
    let signedMetadata: SignedMetadata
}
#endif

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
          !value.targetVersion.isEmpty,
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
    // Intel packages preserve the unsigned runtime used by existing Keychain items.
#if arch(arm64)
    _ = try run("/usr/bin/codesign", ["--verify", "--deep", "--strict", app.path])
#endif
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

private func install(_ arguments: Arguments, beforeSwap: () throws -> Void = {}) throws {
    let manager = FileManager.default
    let metadataData = try Data(contentsOf: arguments.metadata)
#if SELFHOST6_OFFLINE_BRIDGE
    let metadata = try JSONDecoder().decode(BridgeDescriptor.self, from: metadataData).signedMetadata
    guard metadata.targetVersion == bridgeFinalVersion,
          metadata.targetSourceFullSha == bridgeFinalSource,
          metadata.archiveSha256 == bridgePayloadSha256,
          metadata.archiveSize == bridgePayloadSize else {
        try fail("payload identity differs from this fixed bridge")
    }
#else
    let metadata = try JSONDecoder().decode(SignedMetadata.self, from: metadataData)
#endif
    try validateMetadata(metadata, archive: arguments.archive)
    if arguments.verifyOnly { return }

#if !SELFHOST6_OFFLINE_BRIDGE
    try waitForParent(arguments.parentPID)
#endif
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
    try beforeSwap()
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
    } catch {
        if candidateMoved { try? manager.removeItem(at: arguments.target) }
        if previousMoved { try? manager.moveItem(at: backup, to: arguments.target) }
        throw error
    }
    // The final App is installed (and may already be running). Cleanup must not undo it.
    do {
#if SELFHOST6_UPDATER_TESTING
        if arguments.testFailBackupCleanup { try fail("injected failure during backup cleanup") }
#endif
        try manager.removeItem(at: backup)
    } catch {
        FileHandle.standardError.write(Data("selfhost6 updater: backup cleanup: \(error)\n".utf8))
    }
#if !SELFHOST6_OFFLINE_BRIDGE
    try? manager.removeItem(at: arguments.metadata.deletingLastPathComponent())
#endif
}

#if SELFHOST6_OFFLINE_BRIDGE
private func prepareBridge(_ arguments: Arguments) throws -> Int32 {
    guard arguments.target.pathExtension == "app" else { try fail("bridge must run from its App bundle") }
    let parent = arguments.target.deletingLastPathComponent()
    let directory = open(parent.path, O_RDONLY | O_CLOEXEC)
    guard directory >= 0 else { try fail("cannot open bridge installation directory") }
    do {
        guard flock(directory, LOCK_EX | LOCK_NB) == 0 else { try fail("another bridge owns this installation") }
        let sourceResult = arguments.target.path.withCString { logseq_bridge_wait_for_source_helper($0) }
        guard sourceResult == 0 else { try fail("cannot finish source helper handoff") }
        let manifest = arguments.target.appendingPathComponent("Contents/Resources/updater/TARGET_BUILD_MANIFEST.json")
        guard hex(SHA256.hash(data: try Data(contentsOf: manifest))) == bridgeManifestSha256 else {
            try fail("installed App is no longer this bridge")
        }
        return directory
    } catch {
        close(directory)
        throw error
    }
}

private struct BridgeCancelled: Error {}

// Only a running attempt lives in memory. Reopening reads the fixed descriptor and
// complete cache again; no task journal, background downloader or retry queue.
private final class BridgeWindow: NSObject, NSApplicationDelegate, NSWindowDelegate, URLSessionDownloadDelegate {
    private let chinese = Locale.preferredLanguages.first?.hasPrefix("zh") == true
    private var text: [String] { chinese ? bridgeTextZH : bridgeTextEN }
    private let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 400, height: 216),
        styleMask: [.titled, .closable], backing: .buffered, defer: false)
    private let version = NSTextField(labelWithString: "")
    private let stage = NSTextField(wrappingLabelWithString: "")
    private let detail = NSTextField(labelWithString: "")
    private let progress = NSProgressIndicator()
    private let retry = NSButton()
    private let closeButton = NSButton()
    private var session: URLSession?
    private var task: URLSessionDownloadTask?
    private var arguments: Arguments?
    private var lockDirectory: Int32 = -1
    private var busy = false
    private var closing = false
    private var swapping = false
#if SELFHOST6_UPDATER_TESTING
    private var testSignals: [DispatchSourceSignal] = []
    private func testValue(_ name: String) -> String? {
        guard let index = CommandLine.arguments.firstIndex(of: name), index + 1 < CommandLine.arguments.count else { return nil }
        return CommandLine.arguments[index + 1]
    }
    private func record(_ event: String) {
        guard let path = testValue("--test-events"), let handle = FileHandle(forWritingAtPath: path) else { return }
        defer { try? handle.close() }
        handle.seekToEndOfFile()
        handle.write(Data("\(event)\n".utf8))
    }
#endif

    func applicationDidFinishLaunching(_ notification: Notification) {
        window.title = "Logseq"
        window.delegate = self
        window.isReleasedWhenClosed = false
        version.stringValue = text[0].replacingOccurrences(of: "{1}", with: bridgeFinalVersion)
        version.font = .systemFont(ofSize: 15, weight: .semibold)
        stage.font = .systemFont(ofSize: 13)
        detail.font = .monospacedDigitSystemFont(ofSize: 11, weight: .regular)
        detail.textColor = .secondaryLabelColor
        progress.style = .bar
        retry.title = text[6]
        retry.bezelStyle = .rounded
        retry.target = self
        retry.action = #selector(retryUpdate)
        closeButton.title = text[7]
        closeButton.bezelStyle = .rounded
        closeButton.target = self
        closeButton.action = #selector(closeUpdate)
        let buttons = NSStackView(views: [closeButton, retry])
        buttons.orientation = .horizontal
        buttons.spacing = 8
        let stack = NSStackView(views: [version, stage, progress, detail, buttons])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 12
        stack.translatesAutoresizingMaskIntoConstraints = false
        window.contentView!.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: window.contentView!.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: window.contentView!.trailingAnchor, constant: -24),
            stack.topAnchor.constraint(equalTo: window.contentView!.topAnchor, constant: 24),
            progress.widthAnchor.constraint(equalTo: stack.widthAnchor),
            stage.widthAnchor.constraint(equalTo: stack.widthAnchor),
            buttons.trailingAnchor.constraint(equalTo: stack.trailingAnchor),
        ])
        window.center()
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
#if SELFHOST6_UPDATER_TESTING
        // Native control actions are test-only; production has no control socket or URL override.
        for (signalNumber, action) in [(SIGUSR1, #selector(retryUpdate)), (SIGUSR2, #selector(closeUpdate))] {
            signal(signalNumber, SIG_IGN)
            let source = DispatchSource.makeSignalSource(signal: signalNumber, queue: .main)
            source.setEventHandler { [weak self] in self?.perform(action) }
            source.resume()
            testSignals.append(source)
        }
        record("pid=\(getpid())")
        record("window-ready")
#endif
        begin()
    }

    @objc private func retryUpdate() { if !busy && !closing { begin() } }
    @objc private func closeUpdate() { NSApp.terminate(nil) }
    func windowShouldClose(_ sender: NSWindow) -> Bool { closeUpdate(); return false }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        closing = true
        retry.isEnabled = false
        if swapping || busy && task == nil { return .terminateLater }
        session?.invalidateAndCancel()
        if lockDirectory >= 0 { close(lockDirectory); lockDirectory = -1 }
        return .terminateNow
    }

    private func finishClose() {
        if lockDirectory >= 0 { close(lockDirectory); lockDirectory = -1 }
        NSApp.reply(toApplicationShouldTerminate: true)
    }

    private func showStage(_ value: String) {
        stage.stringValue = value
        detail.stringValue = ""
        progress.isHidden = false
        progress.isIndeterminate = true
        progress.startAnimation(nil)
    }

    private func showFailure() {
        busy = false
        task = nil
        session?.finishTasksAndInvalidate()
        session = nil
        swapping = false
        if closing { finishClose(); return }
        progress.stopAnimation(nil)
        progress.isHidden = true
        stage.stringValue = chinese ? bridgeErrorTitleZH : bridgeErrorTitleEN
        detail.stringValue = chinese ? bridgeErrorDescZH : bridgeErrorDescEN
        retry.isHidden = false
        retry.isEnabled = true
#if SELFHOST6_UPDATER_TESTING
        record("failed")
#endif
    }

    private func cacheRoot() throws -> URL {
#if SELFHOST6_UPDATER_TESTING
        if let root = testValue("--test-cache-root") { return URL(fileURLWithPath: root) }
#endif
        return try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true).appendingPathComponent("Logseq/UpdateBridge")
    }

    private func begin() {
        busy = true
        retry.isHidden = true
        showStage(text[1])
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                var cache = try self.cacheRoot()
                cache.appendPathComponent(bridgePayloadSha256)
                let arguments = Arguments(archive: cache.appendingPathComponent(bridgePayloadName))
                if self.lockDirectory < 0 { self.lockDirectory = try prepareBridge(arguments) }
                try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true,
                    attributes: [.posixPermissions: 0o700])
                DispatchQueue.main.async {
                    self.arguments = arguments
                    if self.closing { self.finishClose(); return }
                    if FileManager.default.fileExists(atPath: arguments.archive.path) {
                        self.installCached()
                    } else { self.download() }
                }
            } catch {
                NSLog("selfhost6 bridge: preparation rejected: %@", String(describing: error))
                DispatchQueue.main.async { self.showFailure() }
            }
        }
    }

    private func download() {
        do {
            let location = Bundle.main.bundleURL.appendingPathComponent("Contents/Resources/payload/download-url")
            var address = try String(contentsOf: location, encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
#if SELFHOST6_UPDATER_TESTING
            if let value = testValue("--test-download-url") { address = value }
#else
            guard URL(string: address)?.scheme == "https" else { try fail("bridge download must use HTTPS") }
#endif
            guard let url = URL(string: address) else { try fail("invalid fixed download URL") }
            showStage(text[2])
            let configuration = URLSessionConfiguration.ephemeral
            configuration.urlCredentialStorage = nil
            let session = URLSession(configuration: configuration, delegate: self, delegateQueue: .main)
            self.session = session
            let task = session.downloadTask(with: url)
            self.task = task
            task.resume()
#if SELFHOST6_UPDATER_TESTING
            record("downloading")
#endif
        } catch { showFailure() }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask,
                    didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard !closing else { return }
        progress.isIndeterminate = false
        progress.maxValue = Double(bridgePayloadSize)
        progress.doubleValue = Double(totalBytesWritten)
        detail.stringValue = text[3]
            .replacingOccurrences(of: "{1}", with: ByteCountFormatter.string(fromByteCount: totalBytesWritten, countStyle: .file))
            .replacingOccurrences(of: "{2}", with: ByteCountFormatter.string(fromByteCount: Int64(bridgePayloadSize), countStyle: .file))
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard !closing else { return }
        do {
            guard let response = downloadTask.response as? HTTPURLResponse, response.statusCode == 200,
                  let arguments = arguments else { try fail("download response rejected") }
            let attributes = try FileManager.default.attributesOfItem(atPath: location.path)
            guard (attributes[.size] as? NSNumber)?.uint64Value == bridgePayloadSize else { try fail("download size differs") }
            try FileManager.default.moveItem(at: location, to: arguments.archive)
            task = nil
            session.finishTasksAndInvalidate()
            self.session = nil
#if SELFHOST6_UPDATER_TESTING
            record("download-complete")
            if CommandLine.arguments.contains("--test-pause-before-install") {
                busy = false
                showStage(text[4])
                return
            }
#endif
            installCached()
        } catch { showFailure() }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if error != nil, self.task != nil {
            FileHandle.standardError.write(Data("selfhost6 bridge: download rejected\n".utf8))
            showFailure()
        }
    }

    private func installCached() {
        guard !closing, let arguments = arguments else { return }
        showStage(text[4])
#if SELFHOST6_UPDATER_TESTING
        record("verifying")
#endif
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                try install(arguments, beforeSwap: {
                    try DispatchQueue.main.sync {
                        guard !self.closing else { throw BridgeCancelled() }
                        self.swapping = true
                        self.showStage(self.text[5])
                        self.closeButton.isEnabled = false
#if SELFHOST6_UPDATER_TESTING
                        self.record("installing")
#endif
                    }
                })
                try? FileManager.default.removeItem(at: arguments.archive.deletingLastPathComponent())
                DispatchQueue.main.async {
#if SELFHOST6_UPDATER_TESTING
                    self.record("installed")
#endif
                    self.busy = false
                    self.swapping = false
                    if self.closing { self.finishClose() } else { NSApp.terminate(nil) }
                }
            } catch {
                // A bad complete cache must not trap every later retry. Keep valid
                // bytes for extraction/swap failures; only discard failed byte validation.
                if let failure = error as? Failure, failure.description == "archive bytes do not match signed metadata" {
                    try? FileManager.default.removeItem(at: arguments.archive)
                }
                NSLog("selfhost6 bridge: install rejected: %@", String(describing: error))
                DispatchQueue.main.async {
                    self.closeButton.isEnabled = true
                    self.showFailure()
                }
            }
        }
    }
}

let bridgeApplication = NSApplication.shared
private let bridgeWindow = BridgeWindow()
bridgeApplication.delegate = bridgeWindow
bridgeApplication.setActivationPolicy(.accessory)
bridgeApplication.run()
#else
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
#endif
