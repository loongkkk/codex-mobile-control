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
