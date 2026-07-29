import CryptoKit
import Darwin
import Foundation

private let bundleIdentifier = "com.logseq.logseq"
private let publicKeyBase64 = "__LOGSEQ_PROJECT_UPDATE_PUBLIC_KEY_BASE64__"
private let payloadDomain = "logseq-selfhost-macos-update-v1"

private struct UpdateFailure: Error, CustomStringConvertible {
    let description: String
}

private func fail(_ message: String) throws -> Never {
    throw UpdateFailure(description: message)
}

private struct Arguments {
    let archive: URL
    let arch: String
    let expectedSha512: String
    let expectedSize: UInt64
    let parentPID: pid_t
    let relaunch: Bool
    let signature: Data
    let target: URL
    let testExitAfterSwap: Bool
    let version: String
    let verifyOnly: Bool

    init(_ argv: [String]) throws {
        var values: [String: String] = [:]
        var flags = Set<String>()
        let allowedValues = Set([
            "--archive", "--arch", "--parent-pid", "--relaunch", "--sha512",
            "--signature", "--size", "--target", "--version",
        ])
        var index = 0
        while index < argv.count {
            let key = argv[index]
            if key == "--verify-only" {
                guard flags.insert(key).inserted else {
                    try fail("duplicate \(key)")
                }
                index += 1
                continue
            }
#if PROJECT_UPDATER_TESTING
            if key == "--test-exit-after-swap" {
                guard flags.insert(key).inserted else {
                    try fail("duplicate \(key)")
                }
                index += 1
                continue
            }
#endif
            guard key.hasPrefix("--"), index + 1 < argv.count else {
                try fail("invalid argument near \(key)")
            }
            guard allowedValues.contains(key) else {
                try fail("unknown argument \(key)")
            }
            guard values[key] == nil else {
                try fail("duplicate \(key)")
            }
            values[key] = argv[index + 1]
            index += 2
        }
        func required(_ key: String) throws -> String {
            guard let value = values[key], !value.isEmpty else {
                try fail("missing \(key)")
            }
            return value
        }
        archive = URL(fileURLWithPath: try required("--archive")).standardizedFileURL
        target = URL(fileURLWithPath: try required("--target")).standardizedFileURL
        arch = try required("--arch")
        version = try required("--version")
        expectedSha512 = try required("--sha512").lowercased()
        guard expectedSha512.range(of: "^[0-9a-f]{128}$", options: .regularExpression) != nil else {
            try fail("--sha512 must be 128 lowercase hex characters")
        }
        guard let size = UInt64(try required("--size")) else {
            try fail("--size must be an unsigned integer")
        }
        expectedSize = size
        guard let pid = Int32(try required("--parent-pid")), pid >= 0 else {
            try fail("--parent-pid must be a non-negative integer")
        }
        parentPID = pid
        let relaunchValue = try required("--relaunch")
        guard relaunchValue == "true" || relaunchValue == "false" else {
            try fail("--relaunch must be true or false")
        }
        relaunch = relaunchValue == "true"
        guard let signatureData = Data(base64Encoded: try required("--signature")),
              signatureData.count == 64 else {
            try fail("--signature must be one base64-encoded Ed25519 signature")
        }
        signature = signatureData
        verifyOnly = flags.contains("--verify-only")
#if PROJECT_UPDATER_TESTING
        testExitAfterSwap = flags.contains("--test-exit-after-swap")
#else
        testExitAfterSwap = false
#endif
        guard arch == "arm64" || arch == "x64" else {
            try fail("--arch must be arm64 or x64")
        }
        _ = try SelfhostVersion(version)
        guard target.path.hasSuffix(".app"), target.path != "/", archive.path != "/" else {
            try fail("unsafe target or archive path")
        }
    }
}

private func little16(_ data: Data, _ offset: Int) throws -> UInt16 {
    guard offset >= 0, offset + 2 <= data.count else { try fail("truncated ZIP structure") }
    return data.withUnsafeBytes {
        UInt16(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt16.self))
    }
}

private func little32(_ data: Data, _ offset: Int) throws -> UInt32 {
    guard offset >= 0, offset + 4 <= data.count else { try fail("truncated ZIP structure") }
    return data.withUnsafeBytes {
        UInt32(littleEndian: $0.loadUnaligned(fromByteOffset: offset, as: UInt32.self))
    }
}

private func checkedArchivePath(_ raw: String) throws -> [Substring] {
    guard !raw.isEmpty,
          !raw.hasPrefix("/"),
          !raw.hasPrefix("\\"),
          !raw.contains("\\"),
          !raw.contains("\0") else {
        try fail("unsafe ZIP entry path \(raw.debugDescription)")
    }
    var components = raw.split(separator: "/", omittingEmptySubsequences: false)
    if components.last == "" { components.removeLast() }
    guard !components.isEmpty,
          components.allSatisfy({ !$0.isEmpty && $0 != "." && $0 != ".." }),
          components.first == "Logseq.app" else {
        try fail("ZIP entry escapes or is outside Logseq.app: \(raw)")
    }
    return components
}

private func validateSymlinkTarget(entry: [Substring], target: String) throws {
    guard !target.isEmpty,
          !target.hasPrefix("/"),
          !target.hasPrefix("\\"),
          !target.contains("\\"),
          !target.contains("\0") else {
        try fail("unsafe absolute or empty symlink target \(target.debugDescription)")
    }
    var resolved = entry.dropLast().map(String.init)
    for component in target.split(separator: "/", omittingEmptySubsequences: false) {
        if component == "" || component == "." { continue }
        if component == ".." {
            guard resolved.count > 1 else {
                try fail("symlink escapes Logseq.app: \(target)")
            }
            resolved.removeLast()
        } else {
            resolved.append(String(component))
        }
    }
    guard resolved.first == "Logseq.app" else {
        try fail("symlink escapes Logseq.app: \(target)")
    }
}

private func validateZipStructure(_ archive: URL) throws {
    let handle = try FileHandle(forReadingFrom: archive)
    defer { try? handle.close() }
    let fileSize = try handle.seekToEnd()
    let tailSize = min(fileSize, UInt64(65_557))
    try handle.seek(toOffset: fileSize - tailSize)
    guard let tail = try handle.read(upToCount: Int(tailSize)) else {
        try fail("cannot read ZIP directory")
    }
    let signature: [UInt8] = [0x50, 0x4b, 0x05, 0x06]
    var eocd: Int?
    if tail.count >= 22 {
        for offset in stride(from: tail.count - 22, through: 0, by: -1) {
            if Array(tail[offset..<(offset + 4)]) == signature {
                eocd = offset
                break
            }
        }
    }
    guard let eocd,
          eocd + 22 <= tail.count,
          eocd + 22 + Int(try little16(tail, eocd + 20)) == tail.count else {
        try fail("ZIP end-of-central-directory is missing or inconsistent")
    }
    let disk = try little16(tail, eocd + 4)
    let directoryDisk = try little16(tail, eocd + 6)
    let entriesOnDisk = try little16(tail, eocd + 8)
    let entryCount = try little16(tail, eocd + 10)
    let directorySize = try little32(tail, eocd + 12)
    let directoryOffset = try little32(tail, eocd + 16)
    guard disk == 0, directoryDisk == 0, entriesOnDisk == entryCount,
          entryCount != UInt16.max,
          directorySize != UInt32.max,
          directoryOffset != UInt32.max,
          UInt64(directoryOffset) + UInt64(directorySize) <= fileSize,
          directorySize <= 64 * 1024 * 1024 else {
        try fail("multi-disk, ZIP64, oversized, or inconsistent ZIP is unsupported")
    }
    try handle.seek(toOffset: UInt64(directoryOffset))
    guard let directory = try handle.read(upToCount: Int(directorySize)),
          directory.count == Int(directorySize) else {
        try fail("truncated ZIP central directory")
    }

    var cursor = 0
    var seen = Set<String>()
    for _ in 0..<Int(entryCount) {
        guard try little32(directory, cursor) == 0x02014b50 else {
            try fail("invalid ZIP central-directory entry")
        }
        let flags = try little16(directory, cursor + 8)
        let method = try little16(directory, cursor + 10)
        let compressedSize = try little32(directory, cursor + 20)
        let uncompressedSize = try little32(directory, cursor + 24)
        let nameLength = Int(try little16(directory, cursor + 28))
        let extraLength = Int(try little16(directory, cursor + 30))
        let commentLength = Int(try little16(directory, cursor + 32))
        let externalAttributes = try little32(directory, cursor + 38)
        let localOffset = try little32(directory, cursor + 42)
        let end = cursor + 46 + nameLength + extraLength + commentLength
        guard end <= directory.count,
              flags & 0x1 == 0,
              compressedSize != UInt32.max,
              uncompressedSize != UInt32.max,
              localOffset != UInt32.max else {
            try fail("truncated or encrypted ZIP entry")
        }
        let nameData = directory[(cursor + 46)..<(cursor + 46 + nameLength)]
        guard let name = String(data: nameData, encoding: .utf8) else {
            try fail("ZIP entry name is not UTF-8")
        }
        let components = try checkedArchivePath(name)
        guard seen.insert(name).inserted else { try fail("duplicate ZIP entry \(name)") }

        try handle.seek(toOffset: UInt64(localOffset))
        guard let local = try handle.read(upToCount: 30),
              local.count == 30,
              try little32(local, 0) == 0x04034b50 else {
            try fail("invalid local header for \(name)")
        }
        let localFlags = try little16(local, 6)
        let localMethod = try little16(local, 8)
        let localNameLength = Int(try little16(local, 26))
        let localExtraLength = Int(try little16(local, 28))
        guard let localNameData = try handle.read(upToCount: localNameLength),
              localNameData.count == localNameLength,
              localNameData == nameData,
              localFlags == flags,
              localMethod == method else {
            try fail("local header disagrees with central directory for \(name)")
        }
        let dataOffset = UInt64(localOffset) + 30 + UInt64(localNameLength + localExtraLength)
        guard dataOffset + UInt64(compressedSize) <= UInt64(directoryOffset) else {
            try fail("ZIP entry data overlaps the central directory for \(name)")
        }

        let unixMode = UInt16(externalAttributes >> 16)
        if unixMode & 0xF000 == 0xA000 {
            guard method == 0,
                  compressedSize == uncompressedSize,
                  uncompressedSize > 0,
                  uncompressedSize <= 4096 else {
                try fail("symlink \(name) must be a small stored ZIP entry")
            }
            try handle.seek(toOffset: dataOffset)
            guard let targetData = try handle.read(upToCount: Int(uncompressedSize)),
                  targetData.count == Int(uncompressedSize),
                  let target = String(data: targetData, encoding: .utf8) else {
                try fail("invalid symlink target for \(name)")
            }
            try validateSymlinkTarget(entry: components, target: target)
        }
        cursor = end
    }
    guard cursor == directory.count else { try fail("unexpected trailing ZIP directory data") }
}

private func canonicalPayload(version: String, arch: String, size: UInt64, sha512: String) -> Data {
    Data("""
    \(payloadDomain)
    bundle-id=\(bundleIdentifier)
    version=\(version)
    arch=\(arch)
    zip-size=\(size)
    zip-sha512=\(sha512)

    """.utf8)
}

private func hex<D: Sequence>(_ digest: D) -> String where D.Element == UInt8 {
    digest.map { String(format: "%02x", $0) }.joined()
}

private func preserveQuarantine(from source: String, to destination: String) throws {
    let name = "com.apple.quarantine"
    let length = getxattr(source, name, nil, 0, 0, XATTR_NOFOLLOW)
    if length < 0 {
        if errno == ENOATTR { return }
        try fail("cannot read source quarantine attribute")
    }
    var value = [UInt8](repeating: 0, count: length)
    guard getxattr(source, name, &value, length, 0, XATTR_NOFOLLOW) == length else {
        try fail("cannot copy source quarantine attribute")
    }
    guard setxattr(destination, name, value, length, 0, XATTR_NOFOLLOW) == 0 else {
        try fail("cannot preserve source quarantine attribute")
    }
}

private func copyAndHashArchive(_ source: URL, to destination: URL, expectedSize: UInt64) throws -> String {
    let sourceFD = open(source.path, O_RDONLY | O_NOFOLLOW)
    guard sourceFD >= 0 else { try fail("cannot open update archive without following symlinks") }
    defer { close(sourceFD) }
    var sourceStat = stat()
    guard fstat(sourceFD, &sourceStat) == 0,
          (sourceStat.st_mode & S_IFMT) == S_IFREG,
          UInt64(sourceStat.st_size) == expectedSize else {
        try fail("update archive is not a regular file of the signed size")
    }
    let destinationFD = open(destination.path, O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW, 0o600)
    guard destinationFD >= 0 else { try fail("cannot create private update archive") }
    defer { close(destinationFD) }

    var hasher = SHA512()
    var buffer = [UInt8](repeating: 0, count: 1024 * 1024)
    var copied: UInt64 = 0
    while true {
        let count = read(sourceFD, &buffer, buffer.count)
        if count == 0 { break }
        guard count > 0 else { try fail("failed while reading update archive") }
        let data = Data(buffer[0..<count])
        hasher.update(data: data)
        var offset = 0
        while offset < count {
            let written = data.withUnsafeBytes {
                write(destinationFD, $0.baseAddress!.advanced(by: offset), count - offset)
            }
            guard written > 0 else { try fail("failed while writing private update archive") }
            offset += written
        }
        copied += UInt64(count)
    }
    guard copied == expectedSize, fsync(destinationFD) == 0 else {
        try fail("private update archive is incomplete")
    }
    try preserveQuarantine(from: source.path, to: destination.path)
    return hex(hasher.finalize())
}

private func run(_ executable: String, _ arguments: [String]) throws -> String {
    let process = Process()
    process.executableURL = URL(fileURLWithPath: executable)
    process.arguments = arguments
    let output = Pipe()
    process.standardOutput = output
    process.standardError = output
    try process.run()
    process.waitUntilExit()
    let data = output.fileHandleForReading.readDataToEndOfFile()
    let text = String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
    guard process.terminationStatus == 0 else {
        try fail("\(executable) failed with exit \(process.terminationStatus): \(text)")
    }
    return text
}

private func validateExtractedApp(_ app: URL, arguments: Arguments, extractionRoot: URL) throws {
    let fm = FileManager.default
    let topLevel = try fm.contentsOfDirectory(atPath: extractionRoot.path)
    guard topLevel == ["Logseq.app"] else { try fail("archive must contain exactly one Logseq.app") }
    guard let info = NSDictionary(contentsOf: app.appendingPathComponent("Contents/Info.plist")),
          info["CFBundleIdentifier"] as? String == bundleIdentifier,
          info["CFBundleShortVersionString"] as? String == arguments.version,
          info["CFBundleVersion"] as? String == arguments.version,
          let executableName = info["CFBundleExecutable"] as? String,
          !executableName.isEmpty,
          executableName != ".",
          executableName != "..",
          !executableName.contains("/"),
          !executableName.contains("\\") else {
        try fail("candidate App identity or version does not match signed manifest")
    }
    let root = app.standardizedFileURL.path + "/"
    let enumerator = fm.enumerator(
        at: app,
        includingPropertiesForKeys: [.isSymbolicLinkKey],
        options: [],
        errorHandler: { _, _ in false }
    )
    while let item = enumerator?.nextObject() as? URL {
        let values = try item.resourceValues(forKeys: [.isSymbolicLinkKey])
        if values.isSymbolicLink == true {
            let target = try fm.destinationOfSymbolicLink(atPath: item.path)
            let resolved = URL(fileURLWithPath: target, relativeTo: item.deletingLastPathComponent())
                .standardizedFileURL.path
            guard resolved.hasPrefix(root) else { try fail("extracted symlink escapes Logseq.app") }
        }
    }
    _ = try run("/usr/bin/codesign", [
        "--verify", "--deep", "--strict", "--all-architectures", app.path,
    ])
    let executable = app.appendingPathComponent("Contents/MacOS/\(executableName)")
    let architectures = try run("/usr/bin/lipo", ["-archs", executable.path])
        .split(separator: " ").map(String.init)
    let accepted = arguments.arch == "x64" ? ["x86_64"] : ["arm64", "arm64e"]
    guard !accepted.allSatisfy({ !architectures.contains($0) }) else {
        try fail("candidate executable does not contain signed architecture \(arguments.arch)")
    }
}

private struct SelfhostVersion: Comparable {
    let components: [Int]

    init(_ version: String) throws {
        let pattern = #"^([0-9]+)\.([0-9]+)\.([0-9]+)-selfhost\.([1-9][0-9]*)$"#
        let regex = try NSRegularExpression(pattern: pattern)
        let range = NSRange(version.startIndex..., in: version)
        guard let match = regex.firstMatch(in: version, range: range) else {
            try fail("unsupported selfhost version \(version)")
        }
        var parsed: [Int] = []
        for index in 1...4 {
            guard let capture = Range(match.range(at: index), in: version),
                  let value = Int(version[capture]) else {
                try fail("unsupported selfhost version \(version)")
            }
            parsed.append(value)
        }
        components = parsed
    }

    static func < (lhs: SelfhostVersion, rhs: SelfhostVersion) -> Bool {
        lhs.components.lexicographicallyPrecedes(rhs.components)
    }
}

private func validateTarget(_ target: URL) throws {
    var targetStat = stat()
    guard lstat(target.path, &targetStat) == 0,
          (targetStat.st_mode & S_IFMT) == S_IFDIR,
          target.resolvingSymlinksInPath().standardizedFileURL == target.standardizedFileURL else {
        try fail("target App must be a real directory without symlinked path components")
    }
    let parent = target.deletingLastPathComponent()
    var parentStat = stat()
    guard lstat(parent.path, &parentStat) == 0,
          (parentStat.st_mode & S_IFMT) == S_IFDIR else {
        try fail("target parent must be a real directory")
    }
}

private func currentVersion(_ target: URL) throws -> String {
    guard let currentInfo = NSDictionary(
        contentsOf: target.appendingPathComponent("Contents/Info.plist")
    ), let currentVersion = currentInfo["CFBundleShortVersionString"] as? String else {
        try fail("current App has no supported version")
    }
    return currentVersion
}

private func requireUpgrade(from current: String, to candidate: String) throws {
    guard try SelfhostVersion(candidate) > SelfhostVersion(current) else {
        try fail("project updater refuses downgrade or same-version replacement")
    }
}

private func waitForParent(_ pid: pid_t) throws {
    guard pid > 0 else { return }
    for _ in 0..<600 {
        if kill(pid, 0) != 0 {
            if errno == ESRCH { return }
            try fail("cannot inspect parent process")
        }
        usleep(100_000)
    }
    try fail("parent application did not exit within 60 seconds")
}

private func atomicExchange(_ target: URL, _ candidate: URL) throws {
    let result = target.path.withCString { targetPath in
        candidate.path.withCString { candidatePath in
            renameatx_np(
                AT_FDCWD,
                targetPath,
                AT_FDCWD,
                candidatePath,
                UInt32(RENAME_SWAP)
            )
        }
    }
    guard result == 0 else {
        let error = String(cString: strerror(errno))
        try fail("atomic App exchange is unavailable or failed: \(error)")
    }
}

private func execute(_ arguments: Arguments) throws {
    guard let publicKeyData = Data(base64Encoded: publicKeyBase64),
          publicKeyData.count == 32 else {
        try fail("project update public key is not configured")
    }
    let publicKey = try Curve25519.Signing.PublicKey(rawRepresentation: publicKeyData)
    let fm = FileManager.default
    try validateTarget(arguments.target)
    let installedVersion = try currentVersion(arguments.target)
    try requireUpgrade(from: installedVersion, to: arguments.version)
    let targetParent = arguments.target.deletingLastPathComponent()
    let stagingRoot = targetParent.appendingPathComponent(
        ".logseq-project-update-\(UUID().uuidString)",
        isDirectory: true
    )
    try fm.createDirectory(
        at: stagingRoot,
        withIntermediateDirectories: false,
        attributes: [.posixPermissions: 0o700]
    )
    defer { try? fm.removeItem(at: stagingRoot) }
    let privateArchive = stagingRoot.appendingPathComponent("update.zip")
    let actualSha512 = try copyAndHashArchive(
        arguments.archive,
        to: privateArchive,
        expectedSize: arguments.expectedSize
    )
    guard actualSha512 == arguments.expectedSha512 else {
        try fail("update archive SHA-512 does not match signed manifest")
    }
    let payload = canonicalPayload(
        version: arguments.version,
        arch: arguments.arch,
        size: arguments.expectedSize,
        sha512: actualSha512
    )
    guard publicKey.isValidSignature(arguments.signature, for: payload) else {
        try fail("Ed25519 project update signature is invalid")
    }
    try validateZipStructure(privateArchive)

    let extractionRoot = stagingRoot.appendingPathComponent("extracted", isDirectory: true)
    try fm.createDirectory(at: extractionRoot, withIntermediateDirectories: false)
    _ = try run("/usr/bin/ditto", ["-x", "-k", privateArchive.path, extractionRoot.path])
    let candidate = extractionRoot.appendingPathComponent("Logseq.app", isDirectory: true)
    try validateExtractedApp(candidate, arguments: arguments, extractionRoot: extractionRoot)

    if arguments.verifyOnly {
        print("[project-updater] VERIFIED \(installedVersion) -> \(arguments.version)")
        return
    }

    try waitForParent(arguments.parentPID)
    try atomicExchange(arguments.target, candidate)
#if PROJECT_UPDATER_TESTING
    if arguments.testExitAfterSwap {
        _exit(86)
    }
#endif
    // After the atomic swap, the old App is at `candidate` inside staging.
    // The deferred staging cleanup removes it; a crash only leaves that hidden
    // copy behind while the new App is already present at the target path.
    if arguments.relaunch {
        _ = try run("/usr/bin/open", [arguments.target.path])
    }
    print("[project-updater] INSTALLED \(installedVersion) -> \(arguments.version)")
}

do {
    let arguments = try Arguments(Array(CommandLine.arguments.dropFirst()))
    try execute(arguments)
} catch {
    fputs("[project-updater] ERROR \(error)\n", stderr)
    exit(1)
}
