# DB-Sync Client-Server Protocol

## Transport
- WebSocket `ws(s)` to `/sync/:graph-id`.
- Client builds URL from config and appends `?token=...` when available.
- Encoding: JSON objects; `tx` payloads are Transit strings.
- Note: keep this document in sync with the current implementation.

## Backward compatibility
- The unversioned WebSocket and HTTP shapes documented here are the v1 compatibility contract.
- Existing v1 clients must continue to authenticate, list/open graphs, exchange RTC messages, upload/download snapshots, and access assets after a server upgrade.
- Protocol evolution must be additive. New request fields are optional, and old requests that omit them retain their existing behavior.
- New response fields are optional. Clients must ignore fields they do not understand, while servers must continue emitting every v1-required field.
- A new feature that needs incompatible semantics must use an explicitly negotiated capability or a separate versioned route. It must not silently change the v1 route.
- Releases must pass the legacy v1 wire-schema and snapshot-forwarding tests for both the Cloudflare Worker and Node adapters.
- New clients try the v2 snapshot routes first and restart the complete operation on v1 only when the v2 route returns 404. Old clients continue using the unchanged v1 routes.

## Client -> Server
- `{"type":"hello","client":"<repo-id>"}`
  - Initial handshake from client.
- `{"type":"presence","editing-block-uuid":"<uuid|null>"}`
  - Update current editing block for presence (omit or null to clear).
- `{"type":"pull","since":<t>}`
  - Request txs after `since` (defaults to 0).
- `{"type":"tx/batch","t-before":<t>,"txs":[{"tx":"<tx-transit>","tx-id":"<uuid?>","outliner-op":"<keyword?>"}, ...]}`
  - Upload a batch of txs based on `t-before` (required).
  - `tx-id` is optional but recommended for per-entry ack/reject mapping.
  - Staged transaction upload is additive and must only be used after a server
    hello advertises `"tx-upload-staged-v1"` in `capabilities`. A modern staged
    entry contains all of `tx-id`, `logical-tx-id`, `upload-session-id`,
    `chunk-index`, `chunk-final?`, and `outliner-op`; partial modern metadata is
    invalid. `chunk-next-index` is not part of this protocol.
  - `chunk-index` is a zero-based wire ordinal, independent of the source tx
    offset and post-offload datom count. A generation starts with ordinal zero
    nonfinal, then advances contiguously by one. The server stages chunks in
    ordinal order and applies their assembled transaction only on the final
    chunk.
  - Every modern wire chunk, including the final chunk, has a `tx-id` distinct
    from the logical pending operation. Its v2 identity is derived from SHA-256
    over `logseq-tx-chunk-v2/<logical-tx-id>/<upload-session-id>/<ordinal>/<final|more>`,
    with UUID version and variant bits set, so an ACK-loss retry reuses the same
    authenticated identity and frozen payload.
  - An empty staged payload is valid as an explicit final terminator for the
    same active session at its expected positive ordinal. This covers an
    indivisible ordinal-zero source group: send the complete group nonfinal,
    then finish with an empty ordinal-one final chunk. After completion, only
    an exact retry of that final entry is accepted as an idempotent no-op: its
    session ID, final ordinal, v2 wire identity, and server-derived final wire
    digest must match the completed record. Empty ordinal-zero, empty nonfinal,
    final-first, out-of-order, replacement-generation, and mutated completed
    retries are rejected before any durable staging write. Ordinary v1
    empty-tx handling is unchanged.
- `{"type":"ping"}`
  - Client keepalive, sent every 30 seconds while the connection is otherwise healthy.
  - Cloudflare Durable Objects reply through `setWebSocketAutoResponse` without waking a hibernating object.

## Server -> Client
- `{"type":"hello","t":<t>,"checksum":"<hex>","checksum-version":"server-db-v2"?,"server-checksum":"<hex>"?,"capabilities":["tx-upload-staged-v1"]?}`
  - Server hello with current t and entity checksum.
  - `capabilities` is additive. `tx-upload-staged-v1` opts a new client into
    the modern staged transaction fields and rules documented above; clients
    that do not observe it must keep using the ordinary v1 batch shape.
  - `checksum` retains the historical v1 value and meaning for old clients.
    Current servers additionally emit the optional, paired
    `checksum-version` and `server-checksum` fields. A client may compare the
    latter only when it implements the exact advertised version; unknown or
    missing versions fall back to the strict v1 comparison.
  - `server-db-v2` uses a typed large-title token containing the exact uploaded
    payload's SHA-256 digest. The logical title and the server placeholder
    therefore compare equal only when they reference the same authenticated
    payload; ordinary user text cannot collide with the token representation.
    New markers contain `payload-format`, `payload-digest-alg`, and
    `payload-digest`. Graphs that still contain a legacy marker without these
    fields omit the v2 pair and retain strict v1 comparison until migrated.
  - The server stores and updates v2 independently without changing the
    historical `checksum`, so older clients continue to work.
- `{"type":"online-users","online-users":[{"user-id":"...","email":"...","username":"...","name":"..."}...]}`
  - Presence update
  - Optional `editing-block-uuid` indicates the block the user is editing.
- `{"type":"pull/ok","t":<t>,"checksum":"<hex>","checksum-version":"server-db-v2"?,"server-checksum":"<hex>"?,"txs":[{"t":<t>,"tx":"<tx-transit>","outliner-op":"<keyword?>"}...]}`
  - Pull response with txs and post-apply entity checksum.
- `{"type":"tx/batch/ok","t":<t>,"checksum":"<hex>","checksum-version":"server-db-v2"?,"server-checksum":"<hex>"?}`
  - Batch accepted; `t` and `checksum` describe the resulting server state.
    `t` remains unchanged when every entry is an idempotent no-op.
- `{"type":"changed","t":<t>}`
  - Broadcast once after a handled `tx/batch` that advanced server state (`t` increased); client should pull.
- `{"type":"tx/reject","reason":"stale","t":<t>}`
  - Client tx is based on stale t.
- `{"type":"tx/reject","reason":"db transact failed","t":<t>,"success-tx-ids":["<uuid>",...],"failed-tx-id":"<uuid>"}`
  - Server-side transact/validation failed for one tx entry in the batch.
  - `success-tx-ids` are entries already applied before the failure.
  - `failed-tx-id` is the entry that failed.
  - Legacy servers may return `data` with rejected tx payload for debugging.
- `{"type":"tx/reject","reason":"empty tx data"|"invalid tx"|"invalid t-before"|"snapshot upload in progress"}`
  - Invalid batch.
- `{"type":"pong"}`
  - Keepalive response. The client replaces the connection after 90 seconds without any server message.
- `{"type":"error","message":"..."}`
  - Invalid/unknown message. Current messages: `"unknown type"`, `"invalid request"`, `"server error"`, `"invalid since"`.

## HTTP API
- Auth: Bearer token via `Authorization: Bearer <token>` or `?token=...`.
- JSON body/response unless noted.
- Auth required for `/graphs`, `/sync/:graph-id/*`, and `/assets/*`. Expect `401` (unauthorized) or `403` (forbidden) on access failure.

### Worker Health
- `GET /health`
  - Worker health check. Response: `{"ok":true}`.

### Graphs (index DO)
- `GET /graphs`
  - List graphs the user owns. Response: `{"graphs":[{graph-id, graph-name, schema-version?, graph-ready-for-use?, created-at, updated-at}...]}`.
- `POST /graphs`
  - Create graph. Body: `{"graph-name":"...","schema-version":"<major>"}` (schema-version optional). Response: `{"graph-id":"...","graph-ready-for-use?":false}`.
  - `graph-ready-for-use?` is persisted in the D1 `graphs` row. Existing graphs default to `true`; bootstrap uploads flip it to `false` until the final snapshot upload request completes.
- `GET /graphs/:graph-id/access`
  - Access check. Response: `{"ok":true}`, `401` (unauthorized), `403` (forbidden), or `404` (not found).
- `GET /graphs/:graph-id/members`
  - Graph members list. Response: `{"members":[{user-id, graph-id, role, invited-by, created-at, email?, username?}...]}`.
- `DELETE /graphs/:graph-id`
  - Delete graph and reset data. Response: `{"graph-id":"...","deleted":true}` or `400` (missing graph id).

### E2EE (index DO)
- `GET /e2ee/user-keys`
  - Fetch current user's RSA key pair. Response: `{"public-key":"<transit>","encrypted-private-key":"<transit>"}` or `{}` when missing.
- `POST /e2ee/user-keys`
  - Upsert current user's RSA key pair. Body: `{"public-key":"<transit>","encrypted-private-key":"<transit>","reset-private-key":false?}`.
  - Response mirrors the stored keys: `{"public-key":"<transit>","encrypted-private-key":"<transit>"}`.
- `GET /e2ee/user-public-key?email=<email>`
  - Fetch a user's RSA public key by email. Response: `{"public-key":"<transit>"}` or `{}` when missing.
- `GET /e2ee/graphs/:graph-id/aes-key`
  - Fetch current user's encrypted graph AES key. Response: `{"encrypted-aes-key":"<transit>"}` or `{}` when missing.
- `POST /e2ee/graphs/:graph-id/aes-key`
  - Upsert current user's encrypted graph AES key. Body: `{"encrypted-aes-key":"<transit>"}`.
  - Response: `{"encrypted-aes-key":"<transit>"}`.
- `POST /e2ee/graphs/:graph-id/grant-access`
  - Manager-only. Upsert encrypted graph AES keys for members.
  - Body: `{"target-user-email+encrypted-aes-key-coll":[{"user/email":"<email>","encrypted-aes-key":"<transit>"}...]}`.
  - Response: `{"ok":true,"missing-users":["<email>", ...]?}`.

### Sync (per-graph DO, via `/sync/:graph-id/...`)
- `GET /sync/:graph-id/health`
  - Health check. Response: `{"ok":true}`.
- `GET /sync/:graph-id/pull?since=<t>`
  - Same as WS pull. Response: `{"type":"pull/ok","t":<t>,"checksum":"<hex>","checksum-version":"server-db-v2"?,"server-checksum":"<hex>"?,"txs":[{"t":<t>,"tx":"<tx-transit>","outliner-op":"<keyword?>"}...]}`.
  - Error response (400): `{"error":"invalid since"}`.
  - Error response (409): `{"error":"graph not ready"}` when bootstrap upload/import has not finished.
- `GET /sync/:graph-id/checksum/large-title-markers`
  - Additive authenticated recovery endpoint for a same-cursor mismatch where
    the legacy checksum matches but `server-db-v2` differs only by offloaded
    large-title marker identity.
  - Response:
    `{"t":<t>,"checksum":"<legacy-hex>","checksum-version":"server-db-v2","server-checksum":"<v2-hex>","large-title-markers":[{"block-uuid":"<uuid>","marker":{"asset-uuid":"<uuid>","asset-type":"txt","payload-format":"utf8-plain-v1|aes-gcm-transit-v1","payload-digest-alg":"sha256-v1","payload-digest":"<sha256>"}}...]}`
  - The marker list is the complete marker state contributing to the advertised
    v2 checksum. Clients bind `t` and both checksums to the triggering response,
    require the local and remote marker entity sets to match, authenticate each
    referenced payload, compare its recovered plaintext with the current local
    logical title, and verify the candidate v2 checksum before changing local
    state. A missing/extra marker or failed verification remains a checksum
    mismatch.
  - Error response (409):
    `{"error":"graph not ready"|"versioned checksum unavailable"}`.
- `POST /sync/:graph-id/tx/batch`
  - Same as WS tx/batch. Body: `{"t-before":<t>,"txs":[{"tx":"<tx-transit>","tx-id":"<uuid?>","outliner-op":"<keyword?>"}, ...]}`.
  - Response: `{"type":"tx/batch/ok","t":<t>,"checksum":"<hex>","checksum-version":"server-db-v2"?,"server-checksum":"<hex>"?}` or `{"type":"tx/reject","reason":...}`.
  - Error response (400): `{"error":"missing body"|"invalid tx"}`.
  - Error response (409): `{"error":"graph not ready"}` when bootstrap upload/import has not finished.
- `GET /sync/:graph-id/snapshot/download`
  - v1 compatibility route. Return the current live framed snapshot stream URL.
  - Response: `{"ok":true,"key":"stream/<graph-id>.snapshot","url":"<origin>/sync/:graph-id/snapshot/stream","content-encoding":"gzip"?}`.
  - Error response (409): `{"error":"graph not ready"}` when bootstrap upload/import has not finished.
- `GET /sync/:graph-id/snapshot/download-v2`
  - Create a frozen snapshot and return its watermark and stream URL.
  - Response: `{"ok":true,"key":"stream/<graph-id>.snapshot","url":"<origin>/sync/:graph-id/snapshot/stream-v2?download-id=<uuid>","t":<t>,"row-count":<n>,"checksum":"<hex>"?,"content-encoding":"gzip"?}`.
  - Error response (429): `{"error":"snapshot download busy; retry later"}` when the bounded frozen-snapshot capacity is in use.
- `GET /sync/:graph-id/snapshot/stream`
  - v1 live stream. It remains available without a download id.
- `GET /sync/:graph-id/snapshot/stream-v2?download-id=<uuid>`
  - Stream the frozen v2 snapshot. Missing, expired, or unknown ids return `410 {"error":"snapshot download expired"}`.
- `DELETE /sync/:graph-id/snapshot/download-v2?download-id=<uuid>`
  - Idempotently release a frozen snapshot that will not be consumed, including before a rolling-deployment fallback to v1.
  - Response: `{"ok":true}`.
- All snapshot stream bodies are framed Transit sqlite `kvs` rows (`[addr, content, addresses]`), optionally gzip-compressed. Servers may emit more, smaller frames without changing the row format.
- `POST /sync/:graph-id/snapshot/upload?reset=true|false&finished=true|false&checksum=<hex>?`
  - v1 compatibility route. It retains the legacy direct-import behavior and exact response shape for old clients.
  - `reset=true` clears the live snapshot before importing the request body. Because v1 has no upload identity, an unfinished multipart session never expires into an automatic takeover: a second reset is rejected until the first upload finishes or an administrator explicitly resets the graph. Non-reset chunks are rejected when no v1 session is active.
  - A successful final request with `finished=true` marks the graph ready.
- `POST /sync/:graph-id/snapshot/upload-v2?reset=true|false&finished=true|false&upload-id=<session-id>&checksum=<16-hex>&row-count=<n>`
  - Atomic v2 bootstrap upload. A non-empty, bounded, stable `upload-id` identifies every request in one upload.
  - `reset=true` creates or replaces an isolated staging session. The matching `finished=true` request requires the logical checksum and exact snapshot row count before atomically replacing the live snapshot.
  - Framed Transit decoding protects each uploaded chunk, and the Worker verifies the staged sqlite row count before activation. The checksum is over the logical graph, so the client verifies it only after decrypting rows and rehydrating offloaded large titles; the Worker stores it for subsequent RTC validation rather than pretending it can recompute plaintext from encrypted/offloaded rows.
  - Chunks from a replaced upload id are rejected and can never mix with the replacement session. A v1 rolling-deployment fallback aborts v2 staging before restarting the complete upload through v1.
  - Request body: binary stream; headers should include `content-type: application/transit+json` and `content-encoding: gzip` when compressed.
  - Response: `{"ok":true,"count":<n>}`.
  - Error response (400): `{"error":"missing body"|"missing graph id"|"invalid upload id"|"invalid checksum"|"invalid row count"}`.
  - Error response (409): `{"error":"snapshot upload session replaced"|"snapshot upload already committed"|"snapshot row count mismatch"}`.
  - Both v1 and v2 upload routes require graph-owner authorization.
- `DELETE /sync/:graph-id/admin/reset`
  - Drop/recreate per-graph tables. Response: `{"ok":true}`.

### Assets
- `GET /assets/:graph-id/:asset-uuid.:ext`
  - Download asset (binary response, `content-type` set, `x-asset-type` header included).
- `PUT /assets/:graph-id/:asset-uuid.:ext`
  - Upload asset (binary body). Size limit ~100MB. Response: `{"ok":true}`.
  - New clients may send `x-logseq-asset-size: <exact UTF-8/binary byte length>` so new servers can stream directly to object storage. The header is optional: new servers retain the v1 buffered path when it is absent, and old servers ignore it.
- `DELETE /assets/:graph-id/:asset-uuid.:ext`
  - Delete asset. Response: `{"ok":true}`.
- Asset error responses: `{"error":"invalid asset path"}` (400), `{"error":"not found"}` (404), `{"error":"asset too large"}` (413), `{"error":"method not allowed"}` (405), `{"error":"missing assets bucket"}` (500).
