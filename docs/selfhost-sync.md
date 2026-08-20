# 自建 Logseq DB Sync / RTC

本文说明如何在 Cloudflare 上部署本分支的 DB Sync Worker，并让团队桌面客户端连接它。服务端负责同步加密后的图谱数据和实时事务；它不是传统的文件同步，也不会把多人同时编辑自动合并到官方 Logseq 服务。

## 费用与组件

部署使用以下 Cloudflare 组件：

- Workers：HTTP、鉴权与 WebSocket 入口。
- Durable Objects（SQLite）：每个图谱的实时会话和事务状态。
- D1：用户、图谱和成员关系等元数据。
- R2 Standard：加密图谱快照和资源文件。

Cloudflare 免费额度通常足够小团队测试，但不是“永远零费用”的保证。R2 必须选择 **Standard**；Standard 有免费额度，超出额度后计费，Cloudflare 也可能要求账户先启用计费方式。部署前请在 Cloudflare Dashboard 查看当前用量限制并设置预算告警。

## 1. 本地准备

建议使用与 CI 相同的版本：

- Node.js 24
- pnpm 10.33.0
- Java 21
- Clojure CLI

在仓库根目录执行：

```bash
pnpm install --frozen-lockfile
pnpm --dir deps/db-sync install --frozen-lockfile --ignore-workspace
pnpm --dir deps/db-sync release
```

如果 pnpm 报 `ERR_PNPM_IGNORED_BUILDS`，执行 `pnpm approve-builds`，只批准本仓库需要的 `better-sqlite3`（以及实际列出的可信构建依赖），再重新安装。不要为了绕过错误而全局关闭构建脚本保护。

## 2. 登录 Cloudflare 并创建存储

```bash
cd deps/db-sync/worker
pnpm exec wrangler login
pnpm exec wrangler d1 create selfhost-sync-meta
pnpm exec wrangler r2 bucket create selfhost-sync-assets
```

记录 D1 命令输出的 `database_id`。R2 bucket 名称可以更换，但必须和后面的配置一致。

## 3. 创建部署配置

复制模板，不要直接修改或提交生产配置：

```bash
cp wrangler.selfhost.example.toml wrangler.selfhost.toml
```

编辑 `wrangler.selfhost.toml`：

- `name`：你的 Worker 名称。
- `bucket_name`：刚创建的 R2 bucket。
- `database_name` 和 `database_id`：刚创建的 D1 数据库。
- Durable Object 的 binding、class 和迁移名称保持模板值不变。
- Cognito 三项保持模板值，当前桌面客户端使用 Logseq 账号完成认证。

`wrangler.selfhost.toml` 包含真实资源标识，已作为本地配置使用，不应提交到公开仓库。

## 4. 初始化 D1 并部署

仍在 `deps/db-sync/worker` 目录执行：

```bash
pnpm exec wrangler d1 migrations apply DB --remote --config wrangler.selfhost.toml
pnpm exec wrangler deploy --config wrangler.selfhost.toml
```

部署后测试：

```bash
curl https://YOUR-WORKER.workers.dev/health
```

应返回包含 `"ok":true` 的 JSON。如果准备使用自定义域名，请先确认该域名在团队所有网络中都能访问，并确保 WebSocket 未被反向代理或企业防火墙阻断。

## 5. 配置每一台 Logseq 客户端

1. 安装本 fork 同一版本的桌面客户端。
2. 登录 Logseq 账号。
3. 打开 **Settings → Advanced → Sync Server URL**。
4. 输入 Worker 基础 URL，例如 `https://selfhost-sync.example.workers.dev`。
5. 点击保存。新地址会立即推送给数据库 Worker，无需重启客户端。

只填基础 URL。客户端会自行生成：

- HTTP API：该基础 URL；
- RTC WebSocket：`wss://selfhost-sync.example.workers.dev/sync/<graph-id>`。

所有成员必须在创建、上传或下载共享图谱前确认使用同一个 Sync Server URL。恢复默认值会重新连接官方服务器，不会访问自建服务器上的团队图谱。

## 6. 创建和共享图谱

1. 图谱所有者创建或打开一个 DB 图谱，启动同步并等待 pending local/server changes 都变为 0。
2. 在 Collaboration 中邀请成员。
3. 成员先设置相同的 Sync Server URL，再接受邀请并下载共享图谱。
4. 双方各新增一个测试块，确认另一端收到后再迁移重要内容。

共享图谱中的事务是端到端加密的。服务器仍能看到账号、图谱成员关系、流量和时间等元数据，因此它不是匿名系统。

## 7. 离线、待机和重连

本版本不要求始终在线。离线编辑会进入本地待发送队列；网络恢复、系统唤醒或检测到半开连接后，客户端会重新建立 RTC 连接并继续发送。调试信息中的正常收敛状态为：

```clojure
{:pending-local-ops 0, :rtc-state :open}
```

短暂出现 `:close` 或非零 pending 数量是正常的；长时间不下降时依次检查：

1. `/health` 是否可访问。
2. 客户端 Sync Server URL 是否完全一致。
3. 系统代理是否同时允许 HTTPS 和 WSS。
4. Cloudflare Worker / Durable Object 日志是否出现 `tx/reject`、鉴权或存储错误。

不要在问题发生时删除本地图谱数据库。先退出 Logseq 并备份，再保留 More debug info 和 Worker 日志用于诊断。

## 8. macOS 钥匙串与安装提示

为了原地读取旧内容，本 fork 与官方版共用应用名称、bundle id 和用户数据目录。代价是自建签名无法复制官方签名的钥匙串授权：

- 从 GitHub 下载但未经 Apple 公证的包，macOS 可能阻止首次打开。
- 钥匙串提示中的“拒绝”可能导致 Safe Storage、登录 Cookie 或令牌不可用，不建议作为日常方案。
- 同一台 Mac 的固定本地签名可以减少重复授权，但无法让其他成员自动信任。
- 面向团队彻底消除 Gatekeeper 警告，需要 Apple Developer ID 签名并完成 notarization。

无论使用何种构建，都不要同时运行官方版和本 fork；切换前先退出应用并备份图谱。
