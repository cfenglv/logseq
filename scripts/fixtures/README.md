# macOS updater signature baselines

`macos-updater-baseline.json` pins the published ad-hoc
`2.0.1-selfhost.4` arm64 and x64 releases. It exists only to reproduce the expected
`.4 -> .5` signature rejection and must not be used as a release gate.

Moving from `2.0.1-selfhost.4` to `2.0.1-selfhost.5` requires manually
replacing the application to bootstrap the fixed project Ed25519 public key.
After `.5`, the app still checks for and downloads `.6+` releases; choosing
**Restart and install** verifies and installs them automatically. Candidates
after `.5` are gated by their project-signed metadata and exact ZIP bytes; they
do not require a Developer ID or notarized `.5` baseline. Without those Apple
credentials, Gatekeeper may require **Open Anyway** on the first launch of
`.5` and may ask again after later updates. The updater never changes Trust
Settings or removes quarantine attributes. Keep the `.4` fixtures above only
for the historical Squirrel signature regression reproducer.
