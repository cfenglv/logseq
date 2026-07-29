# macOS updater signature baselines

`macos-updater-baseline.json` pins the published ad-hoc
`2.0.1-selfhost.4` arm64 and x64 releases. It exists only to reproduce the expected
`.4 -> .5` signature rejection and must not be used as a release gate.

The release workflow deliberately expects
`macos-updater-signed-baseline.json` only for candidates after
`2.0.1-selfhost.5`. Do not create that manifest until the published `.5`
Developer ID signed and notarized ZIP and metadata can be downloaded and
pinned by SHA-256 for both architectures. Once present, the physical updater
gate derives the stable `.5` designated requirement and tests the candidate
with Squirrel.Mac.
