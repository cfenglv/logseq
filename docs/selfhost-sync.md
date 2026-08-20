# Self-hosted Logseq DB Sync / RTC

This guide deploys the DB Sync server from this repository to Cloudflare and
connects team desktop clients to it. The server stores encrypted graph
snapshots, assets, membership metadata, and the real-time transaction stream.
It is not a traditional file-sync service.

> Status and safety: Logseq DB RTC is still experimental. Start with a
> non-critical graph, keep independent backups, and validate two-device sync
> before moving important data.

## What is included in this repository

The complete reproducible server implementation is version-controlled:

- `deps/db-sync/src/logseq/db_sync/worker*.cljs`: Worker and Durable Object
  entry points.
- `deps/db-sync/src/logseq/db_sync/worker/`: authentication, routing,
  WebSockets, snapshots, assets, and presence.
- `deps/db-sync/worker/migrations/`: ordered D1 schema migrations.
- `deps/db-sync/worker/wrangler.selfhost.example.toml`: safe deployment
  template without account-specific resource IDs.
- `deps/db-sync/test/`: protocol, authorization, compatibility, storage, and
  Node adapter tests.

The private `wrangler.selfhost.toml` file is intentionally ignored by Git
because it contains the deployer's Cloudflare resource IDs. Reproduction uses
the committed example template and substitutes resources created in the new
Cloudflare account.

## Cost and Cloudflare components

The deployment uses:

- **Workers** for HTTP, authentication, and WebSocket routing.
- **Durable Objects (SQLite)** for one graph's real-time session and
  transaction state.
- **D1** for users, graphs, memberships, and other metadata.
- **R2 Standard** for encrypted snapshots and assets.

Cloudflare's free allowances are usually enough for a small team test, but
they do not guarantee permanently zero cost. R2 must use the Standard storage
class. Review current Cloudflare pricing and limits, enable usage
notifications, and set a budget before production use.

## Compatibility guarantees

The self-hosted server keeps the existing unversioned v1 HTTP and WebSocket
contract. In particular:

- Existing clients continue to use the same Worker base URL and v1 routes.
- New clients negotiate v2 atomic snapshot routes and restart the whole
  operation through v1 only when the v2 route returns `404`.
- The optional asset-size header is additive. A new server accepts old clients
  that do not send it, and an old server ignores it.
- Server upgrades do not require changing the Worker name, URL, Durable Object
  class, D1 database, or R2 bucket.
- D1 migrations are additive and are applied before the new Worker is
  deployed.

Do not delete or recreate existing Cloudflare resources during an upgrade.
Keep `name`, `database_id`, `bucket_name`, Durable Object binding, class name,
and migration tag unchanged in the private configuration.

The wire-level contract is documented in
[`docs/agent-guide/db-sync/protocol.md`](agent-guide/db-sync/protocol.md).

## 1. Check out an exact revision

For a repeatable deployment, use a release tag or record the exact commit SHA:

```bash
git clone https://github.com/cfenglv/logseq.git
cd logseq
git checkout selfhost/cloudflare-rtc
git rev-parse HEAD
```

For production, prefer a tested `2.0.1-selfhost.*` tag once it is available
instead of following the moving branch.

## 2. Install the build toolchain

Use the versions pinned by CI:

- Node.js 24
- pnpm 10.33.0
- Java 21
- Clojure CLI

From the repository root:

```bash
pnpm install --frozen-lockfile
pnpm --dir deps/db-sync install --frozen-lockfile
pnpm --dir deps/db-sync test
pnpm --dir deps/db-sync release
```

The isolated `deps/db-sync/pnpm-workspace.yaml` contains the minimal native
build-script allowlist. Do not add `--ignore-workspace`, because that bypasses
the policy. If an upgrade reports `ERR_PNPM_IGNORED_BUILDS`, review the new
dependency first. Approve only the required package inside this isolated
workspace; never disable build-script protection globally.

## 3. Authenticate and create Cloudflare storage

```bash
cd deps/db-sync/worker
pnpm exec wrangler login
pnpm exec wrangler d1 create selfhost-sync-meta
pnpm exec wrangler r2 bucket create selfhost-sync-assets
```

Save the `database_id` printed by the D1 command. The resource names may be
changed, but the private configuration must use the same names on every later
deployment.

## 4. Create the private deployment configuration

```bash
cp wrangler.selfhost.example.toml wrangler.selfhost.toml
```

Edit `wrangler.selfhost.toml`:

- Set `name` to the permanent Worker name.
- Set `bucket_name` to the R2 bucket created above.
- Set `database_name` and `database_id` to the D1 database.
- Keep the `LOGSEQ_SYNC_DO` binding, `SyncDO` class, and `v1` migration tag
  unchanged.
- Keep the three Cognito values unless the client is also rebuilt for a
  different authentication provider.

The current desktop client uses Logseq Cognito authentication. Hosting this
Worker does not by itself create an independent account system.

The template intentionally pins its compatibility date to the revision used by
this fork. Do not change it during an ordinary deployment or rolling upgrade;
update it only as a separately tested runtime migration.

Do not commit `wrangler.selfhost.toml`, `.dev.vars`, access tokens, admin
tokens, or other secrets. Store Worker secrets with `wrangler secret put`.

## 5. Apply the schema and deploy

Still in `deps/db-sync/worker`:

```bash
pnpm exec wrangler d1 migrations apply DB \
  --remote \
  --config wrangler.selfhost.toml

pnpm exec wrangler deploy --config wrangler.selfhost.toml
```

Verify the public health endpoint:

```bash
curl https://YOUR-WORKER.workers.dev/health
```

It should return JSON containing `"ok":true`.

If a custom domain is used, verify HTTPS and WebSocket connectivity from every
team network. A custom domain does not automatically bypass a local firewall,
DNS block, or network policy.

## 6. Configure every Logseq client

1. Install the same tested release of this fork.
2. Sign in to the Logseq account.
3. Open **Settings → Advanced → Sync Server URL**.
4. Enter only the Worker base URL, for example
   `https://selfhost-sync.example.workers.dev`.
5. Save the setting.

Do not append `/health`, `/sync/<graph-id>`, or another path. The client derives
the HTTP routes and:

```text
wss://selfhost-sync.example.workers.dev/sync/<graph-id>
```

Every collaborator must select the same base URL before creating, uploading,
accepting, or downloading a shared graph. Restoring the default URL reconnects
the official service and does not expose graphs stored on the self-hosted
server.

## 7. Create and share a graph

1. The owner creates or opens a DB graph and starts sync.
2. Wait until pending local and server changes are both zero.
3. Invite members from **Collaboration**.
4. Each member configures the same Sync Server URL before accepting and
   downloading the graph.
5. Create one test block on each device and confirm bidirectional delivery
   before migrating important content.

Graph transactions and snapshots are end-to-end encrypted when E2EE is
enabled. The server can still observe account IDs, memberships, timing, traffic
volume, and other operational metadata; this is not an anonymous system.

## 8. Offline use, sleep, and reconnection

The client does not require a permanent connection. Offline edits remain in
the local pending queue. Network recovery, system wake, visibility changes,
and stale-connection detection trigger a reconnect and resume the queue.

A converged debug state looks like:

```clojure
{:pending-local-ops 0, :rtc-state :open}
```

A temporary `:close` state or non-zero pending count is normal. If it does not
converge:

1. Check that `/health` is reachable.
2. Confirm that every client has the exact same base URL.
3. Confirm that the system proxy or direct network permits both HTTPS and WSS.
4. Inspect Worker and Durable Object logs for authorization, `tx/reject`, or
   storage errors.
5. Back up the local graph before attempting repair.

Do not delete the local graph database as the first troubleshooting step.
Preserve **More debug info**, the application logs, and Worker logs.

## 9. Upgrade without changing the URL

Use the same private configuration and resources:

```bash
git fetch origin
git checkout <tested-release-tag-or-commit>

pnpm install --frozen-lockfile
pnpm --dir deps/db-sync install --frozen-lockfile
pnpm --dir deps/db-sync test
pnpm --dir deps/db-sync release

cd deps/db-sync/worker
pnpm exec wrangler d1 migrations apply DB \
  --remote \
  --config wrangler.selfhost.toml
pnpm exec wrangler deploy --config wrangler.selfhost.toml
```

Deploying with the same `name` preserves the `workers.dev` URL. Applying
migrations to the same D1 database preserves graph and membership metadata.
The same Durable Object namespace and R2 bucket preserve transaction,
snapshot, and asset data.

During a rolling upgrade, old and new clients may remain connected at the same
time. Compatibility tests cover legacy v1 WebSocket messages, snapshot
forwarding, v2-to-v1 fallback, and optional asset headers. Even so, make a
backup and test one non-critical shared graph before a team-wide client
upgrade.

### Desktop application updates

Clients on `2.0.1-selfhost.3` or earlier must install
`2.0.1-selfhost.4` manually. Their updater cannot discover selfhost releases
because its architecture channel conflicts with the SemVer prerelease
identifier.

Starting with `.4`, **Settings → General → Check for updates** discovers the
latest non-draft, non-prerelease GitHub Release and selects the current
platform and architecture metadata. Stable selfhost revisions such as
`2.0.1-selfhost.5` and `2.0.1-selfhost.6` are therefore GitHub production
releases even though their SemVer contains `-selfhost.N`; the release workflow
enforces this contract. Dated builds such as
`2.0.1-selfhost.5.nightly.YYYYMMDD` are instead published only under the
rolling `nightly` GitHub prerelease and use a separate GenericProvider feed.
Stable clients never discover or download that feed. Nightly clients can
advance to a later dated nightly, including a higher nightly revision, but
returning from nightly to any stable release, including a higher stable
revision, requires a manual installation. Before any platform downloads an
update, the runtime also verifies the increasing same-track version, expected
stable or rolling tag semantics, and the exact platform-, architecture-, and
version-specific installer filenames.

Use the NSIS installer on Windows and the AppImage on Linux when in-application
installation is required. On macOS, moving from `.4` to `.5` requires manually
replacing the application. Starting with `.5`, the default in-app update flow
automatically checks for and downloads later releases on the same stable or
nightly track. Stable `.5` advances to a higher stable revision such as `.6`;
it is never offered a nightly. After the user clicks **Restart and install**,
a fixed project Ed25519 public key and native replacement helper verify and
install an eligible updater ZIP automatically, independently of the App's
ad-hoc code-signing identity. The release private key is external to the
repository; missing key material fails the macOS release closed. Without an
Apple Developer ID and notarization, macOS may
require **Open Anyway** on the first launch of `.5`. Every new ad-hoc
application bundle may require **Open Anyway** again the first time it is
opened. The updater never changes certificate Trust Settings and never clears
or removes quarantine attributes.

#### Protected project-update signing and publication

The project-update private key is never public and is never stored in the
repository or a normal repository secret. Users and clients do not need this
key. Release maintainers may use either the protected GitHub Environment flow
or the existing local macOS login Keychain flow. Both produce the same signed
metadata, and neither changes the `.5` client's normal `.6+` discovery,
download, verification, or **Restart and install** experience.

For automated stable/beta publication, configure these two GitHub
Environments before dispatching the workflow:

- `selfhost-release-signing`: enable required reviewers and deployment branch
  or tag restrictions for the protected release ref, such as
  `release/2.0.1-selfhost.*`. Store the matching canonical PKCS#8 DER base64
  value only as the Environment secret
  `LOGSEQ_PROJECT_UPDATE_SIGNING_KEY_PKCS8_BASE64`.
- `selfhost-production`: separately enable required reviewers and the same
  deployment branch or tag restrictions. Do not add the project-update private
  key to this Environment; it grants only the publication approval boundary.

Run `workflow_dispatch` from the exact ref named by `git-ref`. Its resolved SHA
must equal the workflow SHA and must already have a successful push rehearsal;
the workflow's push trigger is deliberately limited to
`selfhost/cloudflare-rtc` and `release/2.0.1-selfhost.*`. The signing job also
requires stable/beta, all six desktop platforms, a non-nightly selfhost
version, and the complete candidate preflight. Only that GitHub-hosted macOS
job receives the Environment secret, scoped to its Node signing step. The
script deletes the value from its own environment immediately after copying it
to an in-memory buffer and never writes it to a file, argv, Keychain, artifact,
or log. Missing, malformed, non-PKCS#8, non-Ed25519, or policy/keyId-mismatched
material blocks the release before metadata changes.

The signer records the exact rehearsed commit in the top-level
`SOURCE_REVISION` file and uploads one immutable
`selfhost-finalized-release-assets` artifact.
A separate GitHub-hosted verifier has no signing Environment or secret and
rechecks the exact cross-platform asset set, checksums, and both macOS project
signatures. Only after it passes can the `selfhost-production` job, with
`contents:write`, create the stable or beta GitHub Release. Push, pull request,
nightly, non-selfhost, partial-platform, repository/ref mismatch, and failed or
missing rehearsal runs cannot enter either protected publication path.

The local macOS login Keychain finalizer remains a supported compatibility
alternative and fallback to the automated GitHub Environment Secret path.
Provision the existing PKCS#8 DER base64 value with **Keychain Access** as one
generic password item. Do not put the local copy in a shell variable, command
argument, file, or normal repository secret:

- Keychain: `login`
- Service:
  `com.logseq.selfhost.project-update-signing.ed25519-pkcs8-base64`
- Account: the complete `keyId` from
  `resources/updater/project-signing-policy.json`
- Password: the matching Ed25519 PKCS#8 DER value encoded as canonical base64

The account is deliberately bound to the public-policy `keyId`; rotating the
public key therefore cannot silently reuse the wrong private key. The signer
queries this exact service and account in `login.keychain-db`. It never changes
the Keychain search list, default Keychain, certificate Trust Settings, or
item access policy. Back up the same private key separately using an offline
encrypted medium before publishing `.5`.

To use the local fallback, run the GitHub workflow as a build-only candidate
job, then download and merge all `logseq-*-builds` artifacts into one
directory. On the publisher Mac, from the exact tested source revision,
finalize the directory:

```bash
version="$(cat /absolute/path/to/release-candidates/VERSION)"
source_revision="<exact-successful-push-rehearsal-commit-sha>"

pnpm project-update:finalize-local-macos-candidates -- \
  --dir /absolute/path/to/release-candidates \
  --source-revision "$source_revision" \
  --version "$version"
```

The finalizer refuses CI and non-macOS hosts. It first checks that both macOS
metadata files are unsigned candidates matching their exact ZIPs, reads the
single fixed Keychain identity, signs arm64 and x64 metadata, verifies both
project signatures, verifies the complete cross-platform artifact set, and
rewrites `SHA256SUMS.txt`. A failure restores the original candidate metadata
and checksum file. Only the directory from a successful finalizer run may be
uploaded to the GitHub Release. Before upload, record the exact source SHA and
inspect `git status` to ensure the signing policy has not changed since the
candidate build.

## 10. Reproducibility and release checks

Before publishing a server/client revision:

```bash
pnpm cljs:test

LOGSEQ_STABLE_IDENTS=1 node static/tests.js \
  -r '^(electron\.(db-worker-manager|power-monitor|proxy|updater|updater-config)-test|frontend\.handler\.db-based\.(rtc-background-tasks|sync)-test|frontend\.worker\.(db-core|db-sync|db-sync-sim|db-worker|pipeline|platform-node|state)-test|frontend\.worker\.sync\..*-test|logseq\.cli\.command\.sync-test|logseq\.db-worker\.daemon-test)$' \
  -e fix-me

pnpm --dir deps/db-sync test
pnpm --dir deps/db-sync release
pnpm --dir deps/db-sync test:large-op-128m

cd deps/db-sync/worker
pnpm exec wrangler deploy --dry-run --config wrangler.selfhost.toml
```

Record the commit SHA, test results, Wrangler version, deployed Worker version,
and D1 migration status. A successful build alone does not prove that
multi-device sleep, reconnect, proxy, or rolling-upgrade behavior works; keep a
two-device smoke test in the release checklist.

## 11. macOS Keychain and installation

This fork keeps the official application name, bundle ID, and user-data
location to preserve existing local graphs and settings. A self-built
signature cannot inherit Apple's Keychain access trust from the official
signature:

- A GitHub package without Apple Developer ID notarization may be blocked by
  Gatekeeper the first time each newly installed application bundle is opened.
- Use **Open Anyway** for `.5` if macOS requires it. Every new ad-hoc
  application bundle may require **Open Anyway** again the first time it is
  opened. The updater never changes certificate Trust Settings and never
  clears or removes quarantine attributes.
- The project Ed25519 signature authenticates `.6+` in-app updates, but does
  not make an unnotarized build trusted by Gatekeeper.
- The updater and local pnpm/gulp/Electron build paths expose no
  local-certificate setup or local-signed command. They remain purely ad-hoc
  signed and do not import certificates, add trust roots, or alter the user
  Keychain search list. The optional Developer ID workflow is separate: when
  Apple signing secrets are configured, it may import the certificate only
  into its ephemeral CI runner.
- Denying Keychain access can make Safe Storage, login cookies, or tokens
  unavailable and is not recommended as a routine workaround.
- Removing team-wide Gatekeeper warnings requires an Apple Developer ID
  signature and notarization.

Never run the official build and this fork at the same time. Quit Logseq and
back up graphs before switching builds.
