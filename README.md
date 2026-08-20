## Self-hosted RTC fork

This branch extends the Logseq DB version with the client, Cloudflare Worker, and desktop release changes needed to run RTC (real-time sync and collaboration) for a self-hosted team. It is not an official Logseq release. Test it with a non-critical graph first and keep independent backups of important graphs.

Compared with upstream Logseq, this fork adds or fixes:

- A configurable Sync Server base URL used by both HTTP and WebSocket traffic.
- System proxy support on macOS, Windows, and Linux, including proxied WebSocket connections.
- Heartbeats and automatic reconnection after sleep, standby, and network changes. Offline edits remain local and resume syncing when connectivity returns.
- Recovery from individual rejected transactions, plus a fix for missing `created-by-ref` user entities that could make a shared graph uneditable.
- Backward-compatible, atomic snapshot upload/download with v2 negotiation and automatic fallback to unchanged v1 endpoints during rolling upgrades.
- Byte-for-byte attachment restoration in browser-backed graphs, including protection against zero-byte LightningFS writes.
- Strict checksum and transaction-cursor validation, bounded-memory retry during continuous editing, and local database restoration if snapshot activation fails.
- Hardened graph access, member revocation, WebSocket error handling, payload limits, and Node server lifecycle cleanup.
- GitHub Actions builds for macOS Intel and Apple Silicon, Windows x64 and ARM64, and Linux x64 and ARM64.
- In-application update discovery for production selfhost releases starting with `2.0.1-selfhost.4`, with architecture-specific metadata and a six-target provider rehearsal.
- Electron 42.4.1, including upstream Safe Storage initialization fixes that reduce unnecessary macOS Keychain prompts.

To configure a client, open **Settings → Advanced → Sync Server URL**, enter the Worker base URL (for example, `https://selfhost-sync.example.workers.dev`), and save it. Do not append `/health`, `/sync/%s`, or another path. Every collaborator must use the same server URL.

See the [self-hosted Logseq DB Sync / RTC guide](docs/selfhost-sync.md) for the full setup process and the [2.0.1-selfhost.4 release notes](docs/releases/2.0.1-selfhost.4.md) for this release. Installers are available from [this fork's Releases](https://github.com/cfenglv/logseq/releases).

> Data compatibility: this fork intentionally keeps the `Logseq` product name and `com.logseq.logseq` application ID so it can continue using existing Logseq user data, settings, authentication state, and local graphs. Do not run official Logseq and this fork at the same time. Quit Logseq and back up your graphs before installing or switching builds. macOS packages that are not signed and notarized with an Apple Developer ID may still trigger Gatekeeper warnings.

### Release verification

Desktop releases use a staged verification process so deterministic source, dependency, packaging, and asset errors are caught before a GitHub Release is created:

1. Run `pnpm desktop:release-preflight:quick` for fast source, version, environment, workflow, and release-configuration checks. CI runs its strict mode and rejects a dirty tracked worktree.
2. Run `pnpm desktop:release-preflight` before pushing to install from frozen lockfiles, execute the RTC and Electron test gates, build the production client, package the current host platform in an isolated `static/` dependency root, and verify the packaged executable and native modules. This full check expects the repository-local `cli/` opam switch to use OCaml 5.4.0, matching CI.
3. Push the exact release commit to `selfhost/cloudflare-rtc`. GitHub Actions automatically rehearses all six desktop targets: macOS Intel and Apple Silicon, Windows x64 and ARM64, and Linux x64 and ARM64. Each job inspects the packaged application, its Electron version, and the architecture of its main executable and native `keytar` module.
4. Start a manual beta or stable build only after that exact commit has a successful push rehearsal. The workflow enforces this by commit SHA.
5. Before a draft is created, the workflow validates the exact installer/update-file set, updater metadata, sizes and SHA-512 digests, and writes a complete `SHA256SUMS.txt`.

This process catches deterministic release failures before publication. External runner, package-registry, or CDN outages can still cause transient CI failures and should be retried only after the logs confirm that the failure is external.

<!-- logo -->
<p align="center">
    <a href="https://logseq.com" alt="Logseq Logo">
    <img src="https://user-images.githubusercontent.com/25513724/220608753-f33db466-af72-4611-b603-411440c15ed0.png?sanatize=true" height="173"/></a>
</p>

<h1 align="center"> Logseq </h1>

<h4 align="center">
    A privacy-first, open-source platform for knowledge management and collaboration
</h4>

<div align="center">
    <a href="https://logseq.com">Home Page</a> |
    <a href="https://blog.logseq.com/">Blog</a> |
    <a href="https://docs.logseq.com/">Documentation</a> |
    <a href="https://logseq.io/p/NX4mc_ggEV">Roadmap</a>
</div>
<br></br>

<p align="center">
    <a href="https://github.com/logseq/logseq/releases/latest/">
        <img src="https://img.shields.io/badge/Download_Logseq-100000?style=for-the-badge&logo=flatpak&logoColor=white&labelColor=002b36&color=85c8c8"
            alt="Download Logseq"/></a>
</p>

<!-- social badges -->
<p align="center">
    <a href="https://discuss.logseq.com">
        <img src="https://img.shields.io/badge/forum-Logseq-blue.svg?&color=%2385c8c8&logo=discourse&style=for-the-badge"
            alt="forum"></a>
    <a href="https://discord.gg/KpN4eHY">
        <img src="https://img.shields.io/discord/725182569297215569?color=%2385c8c8&label=Discord&logo=discord&style=for-the-badge"
            alt="chat on Discord"></a>
    <a href="https://twitter.com/intent/follow?screen_name=logseq">
        <img src="https://img.shields.io/badge/twitter-%40logseq-blue.svg?&color=%2385c8c8&logo=twitter&style=for-the-badge"
            alt="follow on Twitter"></a>
</p>

<!-- dev badges -->
<p align="center">
    <a href="https://github.com/logseq/logseq/graphs/contributors" alt="Contributors">
        <img src="https://img.shields.io/github/contributors/logseq/logseq?color=%2385c8c8&style=for-the-badge"/></a>
    <a href="#-backers" alt="Backers on Open Collective">
        <img src="https://img.shields.io/opencollective/backers/logseq?color=%2385c8c8&style=for-the-badge"/></a>
    <a href="#-sponsors" alt="Sponsors on Open Collective">
        <img src="https://img.shields.io/opencollective/sponsors/logseq?color=%2385c8c8&style=for-the-badge"/></a>
    <a href="https://github.com/logseq/logseq/blob/master/LICENSE.md" alt="Activity">
        <img src="https://img.shields.io/github/license/logseq/logseq?color=%2385c8c8&style=for-the-badge"/></a>
    <a href="https://github.com/logseq/logseq/releases">
        <img src="https://img.shields.io/github/v/release/logseq/logseq?color=%2385c8c8&style=for-the-badge"
            alt="latest release version"></a>
</p>

## Table of Contents

  * [<g-emoji class="g-emoji" alias="database" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/1f680.png">🚀</g-emoji> Database Version](#-database-version)
  * [<g-emoji class="g-emoji" alias="thinking" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/1f914.png">🤔</g-emoji> Why Logseq?](#-why-logseq)
  * [<g-emoji class="g-emoji" alias="eyes" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/1f440.png">👀</g-emoji> How can I use it?](#-how-can-i-use-it)
  * [<g-emoji class="g-emoji" alias="books" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/1f4da.png">📚</g-emoji> Learn more](#-learn-more)
  * [🫶 Support Logseq Development](#-support-logseq-development)
  * [<g-emoji class="g-emoji" alias="bulb" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/1f4a1.png">💡</g-emoji> Feature requests](#-feature-requests)
  * [<g-emoji class="g-emoji" alias="electric_plug" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/1f50c.png">🔌</g-emoji> Plugin API](#-plugin-api)
  * [<g-emoji class="g-emoji" alias="star2" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/1f31f.png">🌟</g-emoji> Contributing to Logseq](#-contributing-to-logseq)
    * [<g-emoji class="g-emoji" alias="hammer_and_wrench" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/1f6e0.png">🛠️</g-emoji> Setting Up a Development Environment](#️-setting-up-a-development-environment)
  * [<g-emoji class="g-emoji" alias="sparkles" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/2728.png">✨</g-emoji> Inspiration](#-inspiration)
* [<g-emoji class="g-emoji" alias="pray" fallback-src="https://github.githubassets.com/images/icons/emoji/unicode/1f64f.png">🙏</g-emoji> Thank You](#-thank-you)

## 🚀 Database Version

The Database version (DB version) of Logseq introduces DB graphs. [See this page](https://github.com/logseq/docs/blob/master/db-version.md) to get an overview of the main features for DB graphs. If you are an existing user, [see changes with the DB version](https://github.com/logseq/docs/blob/master/db-version-changes.md). The DB version has its own new mobile app (on iOS, with Android coming soon)! To participate in the mobile app alpha, [please complete this brief form](https://forms.gle/nfefJv51jUuULbFB9). The DB version also has a new sync approach, RTC (Real Time Collaboration)! You can use it to sync graphs between multiple devices or collaborate with others. To participate in the RTC alpha, [please fill out this form](https://forms.gle/YSyF4WfKPSDuwyjH6).

The DB version is in beta status while the new mobile app and RTC is in alpha. This means that **data loss is possible** so we recommend [automated backups](https://github.com/logseq/docs/blob/master/db-version.md#automated-backup) or [regular SQLite DB backups](https://github.com/logseq/docs/blob/master/db-version.md#graph-export). We recommend you create a dedicated test graph and choose one project that’s not crucial for you.

To get started with the DB version:
* To try the latest web version, go to https://app.logseq.com/.
* To try the latest desktop version, go to https://github.com/logseq/logseq/releases/tag/nightly and download the artifact for your operating system.
* To try the latest by building from the source code
    * Use `test/db` for stable releases. Fewer bugs and slower updates. Update frequency: days or weeks.
    * Use `master` for the latest updates as they are developed. Expect more bugs and faster changes. Update frequency: hours or days.
* To report bugs, please file them at https://github.com/logseq/db-test/issues.
* For feature or enhancement requests, please file them on Discord on the `#db-feedback` channel.
* For discussions:
    *  General ==> see the `#db-chat` channel in Discord.
    *  Sync and RTC ==> see the `#sync-test` channel in Discord.
    *  Mobile ==> see the `#mobile-test` channel in Discord.

## 🤔 Why Logseq?

[Logseq](https://logseq.com) is a **knowledge management** and **collaboration** platform. It focuses on **privacy**, **longevity**, and [**user control**](https://www.gnu.org/philosophy/free-sw.en.html). Logseq offers a range of **powerful tools** for **knowledge management**, **collaboration**, **PDF annotation**, and **task management** with support for multiple file formats, including **Markdown** and **Org-mode**, and **various features** for organizing and structuring your notes.

In addition to its core features, Logseq has a growing ecosystem of **plugins** and **themes** that enable a wide range of workflows and **customization** options. **Mobile apps** are also available, providing access to most of the features of the desktop application. Whether you're a student, a professional, or anyone who values a clear and organized approach to managing your ideas and notes, Logseq is an excellent choice for anyone looking to improve their productivity and streamline their workflow.

![logseq-demo](https://user-images.githubusercontent.com/25513724/221387376-4dc419c2-0d0a-460c-a920-2d211e78b456.gif)

<a href="https://github.com/logseq/logseq/releases/latest/">
        <img src="https://img.shields.io/badge/Download_Logseq-100000?style=for-the-badge&logo=flatpak&logoColor=white&labelColor=002b36&color=85c8c8"
            align="right"
            alt="Download Logseq"/></a>

## 👀 How can I use it?

To start using Logseq, follow these simple steps:

1. [Download](https://github.com/logseq/logseq/releases/latest) the latest version of Logseq
2. Install Logseq on your device and launch the application
3. Start writing ✍️

That's it! You can now enjoy the benefits of using Logseq to streamline your workflow, manage your projects, and stay on top of your goals. Have fun! 🎉

**Linux users**: Use the automated installer script for the best experience:

   ```bash
   # Download and run the installer
   curl -fsSL https://raw.githubusercontent.com/logseq/logseq/master/scripts/install-linux.sh | bash

   # Or install a specific version
   curl -fsSL https://raw.githubusercontent.com/logseq/logseq/master/scripts/install-linux.sh | bash -s -- 0.10.14

   # For user-specific installation (no root required)
   curl -fsSL https://raw.githubusercontent.com/logseq/logseq/master/scripts/install-linux.sh | bash -s -- --user
   ```

## 📚 Learn more

* Website: [logseq.com](https://logseq.com)
* Documentation: [docs.logseq.com](https://docs.logseq.com)
  * FAQ page: [Logseq Docs:  FAQ](https://docs.logseq.com/#/page/faq)
* Blog: [blog.logseq.com](https://blog.logseq.com)
  * Please visit our [About page](https://blog.logseq.com/about) for the latest updates.
* Forum: [discuss.logseq.com](https://discuss.logseq.com) - Where we answer questions, discuss workflows, and share tips
  * FAQ forum section: [Logseq Forum: FAQ](https://discuss.logseq.com/c/faq/6)
* [Awesome Logseq](https://github.com/logseq/awesome-logseq) - Awesome Logseq extensions and resources created by the community <3
* Twitter: [@Logseq](https://twitter.com/logseq)
* Discord: [https://discord.com/invite/KpN4eHY](https://discord.com/invite/KpN4eHY)
  * [中文 Discord](https://discord.gg/xYqcrXWymg)

## 🫶 Support Logseq Development

If you find Logseq useful and want to help us keep the project growing, please consider supporting our contributors on [Open Collective](https://opencollective.com/logseq). Your support shows our contributors that their efforts are appreciated and motivates them to continue their excellent work. Every contribution, no matter how small, helps us keep improving Logseq.

## 💡 Feature requests

We value your input on improving Logseq and making it more useful for you. If you have any ideas or feature requests, please share them in the [Logseq Forum: Feature
Requests](https://discuss.logseq.com/new-topic?category=feature-requests) section.

Your feedback helps us understand our users' needs and prioritize the features that matter most to you. We appreciate your time and effort in sharing your thoughts with us.

We appreciate your support, and we look forward to hearing your ideas!

## 🔌 Plugin API

Logseq provides a plugin API that enables developers to create custom plugins and extend the functionality of Logseq. The plugin API documentation is available at [plugins-doc.logseq.com](https://plugins-doc.logseq.com/), where you can find everything needed to get started with plugin development.

We value your feedback and suggestions on how to improve our documentation. Please do not hesitate to contact us with any comments or questions. Your input helps us to provide a better experience for our users and developers.

Thank you for using Logseq, and we look forward to seeing what you create with our plugin API!

## 🌟 Contributing to Logseq

To start contributing to Logseq, please read [CONTRIBUTING.md](CONTRIBUTING.md).
There are ways to contribute [with code](https://github.com/logseq/logseq/blob/master/CONTRIBUTING.md#code-contributions) and [without code](https://github.com/logseq/logseq/blob/master/CONTRIBUTING.md#-how-can-i-help). We welcome all
contributions, big or small, and we appreciate your time and effort in helping
us improve Logseq. We look forward to your contributions 🚀

### 🛠️ Setting Up a Development Environment

If you want to set up a development environment for the Logseq web or desktop app, please refer to the [Develop Logseq](docs/develop-logseq.md) guide for macOS/Linux users and the [Develop Logseq on Windows](docs/develop-logseq-on-windows.md) guide for Windows users.

In addition to these guides, you can also find other helpful resources in the [docs/](docs/) folder, such as the [Guide for Contributing to Translations](docs/contributing-to-translations.md), the [Docker Web App Guide](docs/docker-web-app-guide.md) and the [mobile development guide](docs/develop-logseq-on-mobile.md)

### 🧰 Logseq CLI (Node)

Logseq CLI documentation is maintained in `docs/cli/logseq-cli.md`.

## ✨ Inspiration

Logseq is inspired by several unique tools and projects, including [Roam Research](https://roamresearch.com/), [Org Mode](https://orgmode.org/), [TiddlyWiki](https://tiddlywiki.com/), [Workflowy](https://workflowy.com/), and [Cuekeeper](https://github.com/talex5/cuekeeper).

We owe a huge debt of gratitude to the developers and creators of these projects, and we hope that Logseq can continue to build on their innovative ideas and make them accessible to a broader audience.

Thank you to all those who inspire us, and we look forward to seeing what the Logseq community will create with this tool!

Logseq is also made possible by the following projects:

* [Clojure & ClojureScript](https://clojure.org/) - A dynamic, functional, general-purpose programming language
* [DataScript](https://github.com/tonsky/datascript) - An immutable database and Datalog query-engine for Clojure,
ClojureScript and JS
* [OCaml](https://ocaml.org/) & [Angstrom](https://github.com/inhabitedtype/angstrom), for the document parser [mldoc](https://github.com/logseq/mldoc)
* [isomorphic-git](https://isomorphic-git.org/) - A pure JavaScript implementation of Git for NodeJS and web browsers
* [SCI](https://github.com/borkdude/sci) - A Small Clojure Interpreter

# 🙏 Thank You

We want to express our sincere gratitude to our [Open Collective](https://opencollective.com/logseq) **sponsors**, **backers**, and **contributors**. Your support and contributions allow us to continue developing and improving Logseq. Thank you for being a part of our community and helping us make Logseq the best it can be!

## 💎 Sponsors

<p align="center">
    <a href="https://opencollective.com/logseq#sponsor"> [Become a sponsor]</a>
</p>
<p align="center">
    <a href="https://opencollective.com/logseq" alt="Sponsors on Open Collective">
        <img src="https://opencollective.com/logseq/tiers/sponsors.svg?avatarHeight=42&width=600"/></a>
</p>

## 🌟 Contributors

<p align="center">
    <a href="https://github.com/logseq/logseq/graphs/contributors">
        <img src="https://contrib.rocks/image?repo=logseq/logseq&max=300&columns=14" width="600"/></a>
</p>

<!-- JetBrains Logo -->
<p align="center">
    <a href="https://jetbrains.com" alt="JetBrains">
        <img src="docs/assets/jetbrains.svg"/></a>
</p>

<!-- ProductHunt Review Button -->
<p align="center">
    <a href="https://www.producthunt.com/posts/logseq?utm_source=badge-review&utm_medium=badge&utm_souce=badge-logseq#discussion-body"
    target="_blank"><img
        src="https://api.producthunt.com/widgets/embed-image/v1/review.svg?post_id=298158&theme=dark"
        align="center"
        alt="Logseq - Your joyful, private digital garden | Product Hunt" style="width: 250px; height: 54px;"
        width="250" height="54"/></a>
</p>
