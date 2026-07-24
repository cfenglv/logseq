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
pnpm exec wrangler d1 create team-logseq-sync-meta
pnpm exec wrangler r2 bucket create team-logseq-sync-assets
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
   `https://team-logseq-sync.example.workers.dev`.
5. Save the setting.

Do not append `/health`, `/sync/<graph-id>`, or another path. The client derives
the HTTP routes and:

```text
wss://team-logseq-sync.example.workers.dev/sync/<graph-id>
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

## 10. Reproducibility and release checks

Before publishing a server/client revision:

```bash
pnpm cljs:test

LOGSEQ_STABLE_IDENTS=1 node static/tests.js \
  -r '^(electron\.(db-worker-manager|power-monitor|proxy)-test|frontend\.handler\.db-based\.(rtc-background-tasks|sync)-test|frontend\.worker\.(db-core|db-sync|db-sync-sim|db-worker|pipeline|platform-node|state)-test|frontend\.worker\.sync\..*-test|logseq\.cli\.command\.sync-test|logseq\.db-worker\.daemon-test)$' \
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
  Gatekeeper on first launch.
- Denying Keychain access can make Safe Storage, login cookies, or tokens
  unavailable and is not recommended as a routine workaround.
- A stable local signing identity can reduce repeated prompts on one Mac, but
  it does not make other users trust the package automatically.
- Removing team-wide Gatekeeper warnings requires an Apple Developer ID
  signature and notarization.

Never run the official build and this fork at the same time. Quit Logseq and
back up graphs before switching builds.
