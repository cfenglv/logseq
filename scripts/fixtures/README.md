# macOS updater signature baselines

`macos-updater-baseline.json` pins the published ad-hoc
`2.0.1-selfhost.4` arm64 and x64 releases. It exists only to reproduce the expected
`.4 -> .5` signature rejection and must not be used as a release gate.

Moving from `2.0.1-selfhost.4` to `2.0.1-selfhost.5` requires manually
replacing the application to bootstrap the fixed project Ed25519 public key.
After stable `.5`, the default in-app update flow automatically checks for and
downloads only higher stable revisions such as `.6`; after the user clicks
**Restart and install**, it verifies and installs them automatically. Dated
nightly builds use an isolated rolling prerelease and update only to later
nightlies. Stable clients never discover a nightly, and nightly-to-stable
transitions, including to a higher stable revision, are manual. Eligible
candidates after `.5` are gated by their
project-signed metadata and exact ZIP bytes; they do not require a Developer ID
or notarized `.5` baseline. Without those Apple
credentials, every new ad-hoc application bundle may require
**Open Anyway** again the first time it is opened. The updater never changes
certificate Trust Settings and never clears or removes quarantine attributes.
Keep the `.4` fixtures above only
for the historical Squirrel signature regression reproducer.
