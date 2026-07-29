# macOS updater signature baselines

`macos-updater-baseline.json` pins the published ad-hoc
`2.0.1-selfhost.4` arm64 and x64 releases. It exists only to reproduce the expected
`.4 -> .5` signature rejection and must not be used as a release gate.

`2.0.1-selfhost.5` is the one-time manual bootstrap for the fixed project
Ed25519 public key. Candidates after `.5` are gated by their project-signed
metadata and exact ZIP bytes; they do not require a Developer ID or notarized
`.5` baseline. Keep the `.4` fixtures above only for the historical Squirrel
signature regression reproducer.
