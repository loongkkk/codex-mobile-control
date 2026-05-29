# Development / 开发

This repo contains three main workspaces: Gateway, web/mobile client, and Android.

本仓库主要由 Gateway、Web/Mobile 客户端和 Android App 三部分组成。

## Prerequisites / 前置环境

- Node.js 20+
- npm
- JDK 17 for Android
- Android SDK for Android builds and tests
- Gradle 8.x, or a Gradle Wrapper added by your fork

## Install / 安装依赖

```bash
npm install
```

## Gateway

```bash
cp gateway/config.example.json gateway/config.json
npm --workspace gateway run dev
npm --workspace gateway run typecheck
npm --workspace gateway test
```

The Gateway dev server reads `gateway/config.json` by default. It serves APIs, uploads, update files, realtime Socket/SSE events, and the built web client when `mobile/dist` exists.

## Web/Mobile Client

```bash
npm --workspace mobile run dev
npm --workspace mobile run build
npm --workspace mobile run typecheck
npm --workspace mobile test
```

The browser client is useful for quick UI and API checks. The Android app is the primary mobile client.

## Android

Use JDK 17 and an Android SDK. With a Gradle Wrapper:

```bash
cd android
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

With an installed Gradle:

```bash
cd android
gradle :app:testDebugUnitTest
gradle :app:assembleDebug
```

## Repository Checks / 仓库检查

```bash
npm run typecheck
npm test
npm run audit:opensource
npm run release:check
```

`audit:opensource` scans tracked files only. It fails when local private files, generated APKs, uploads, logs, known private paths, or known private hostnames are tracked.
For personal or company-specific denylist rules, copy `.open-source-audit.private.example.json` to `.open-source-audit.private.json`. The private file is ignored by git and is loaded by the audit script when present.

如需扫描个人域名、内网 IP、本机路径或设备 ID，请把 `.open-source-audit.private.example.json` 复制为 `.open-source-audit.private.json` 后填写。该私有文件已加入 `.gitignore`，不会进入公开仓库。

## Local Files / 本地私有文件

These files are intentionally ignored:

```text
gateway/config.json
release.config.json
NEXT_WINDOW_HANDOFF.md
.codex-analysis.md
.codex-queue.md
gateway/downloads/
gateway/mobile-uploads/
gateway/run-logs/
tmp/
```

Keep operational handoff notes and private deployment settings outside the public repository.

## Public Repository Sync / 公开仓库同步

Maintainers should keep private development history separate from the public GitHub repository. Do not repoint the private repository remote and push its existing history. Instead, keep a stable sibling public worktree such as `../codex-mobile-control` and sync the current clean file tree into it.

维护者应保持私有开发历史和公开 GitHub 仓库分离。不要把私有仓库 remote 改到 GitHub 后直接推送历史。推荐保留一个固定的兄弟目录，例如 `../codex-mobile-control`，每次把当前干净文件树同步过去。

Recommended workflow:

```bash
# private development repo
npm run audit:opensource
npm run typecheck
npm test
npm run sync:public

# public GitHub repo
cd ../codex-mobile-control
git status --short
npm run audit:opensource
npm run typecheck
npm test
npm run release:check
git add .
git commit -m "chore: sync open source release"
git push
```

`npm run sync:public` exports `HEAD` with LF line endings and replaces the public worktree while preserving the public repository's `.git` directory and local `node_modules`. It refuses to run when the public worktree already has uncommitted changes unless `-- --allow-dirty-public` is passed.

`npm run sync:public` 会以 LF 换行导出当前 `HEAD`，替换公开仓库工作区，同时保留公开仓库自己的 `.git` 目录和本地 `node_modules`。如果公开仓库已有未提交改动，脚本会拒绝覆盖；确认要覆盖时再追加 `-- --allow-dirty-public`。
