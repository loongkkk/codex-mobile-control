# Codex Mobile Control

Codex Mobile Control is a local Gateway plus Android client for monitoring and sending messages to Codex tasks from a phone. It is designed for private self-hosted use on a trusted network: the Gateway runs beside your Codex environment, the Android app logs in with your Gateway address and token, and messages are synchronized through the Gateway.

Codex Mobile Control 是一个本地 Gateway + Android 客户端项目，用于在手机上查看 Codex 任务、发送消息、管理排队消息和接收通知。默认面向个人自部署场景：Gateway 运行在你的电脑上，Android App 通过网关地址和 token 登录。

## Features / 功能

- Android task list, detail view, message composer, attachments, queued messages, and notification controls.
- Gateway API with token authentication, uploads, diagnostics, update source, Socket/SSE realtime events, and Codex send integration.
- Official persistence first send path, with desktop bridge kept as a compatibility fallback.
- Foreground realtime service support on Android for smoother background updates.
- Open-source safety audit for private paths, tokens, APKs, logs, and local-only handoff files.

## Repository Layout / 仓库结构

```text
android/        Native Android app, Kotlin, XML layouts, unit tests
gateway/        Node.js Gateway service, HTTP API, realtime socket, Codex integration
mobile/         Browser/mobile web client built with React and Vite
scripts/        Release and audit helpers
docs/           Configuration, development, architecture, release, and security docs
```

## Quick Start / 快速启动

1. Install Node.js 20+ and npm.
2. Install dependencies:

```bash
npm install
```

3. Create a private Gateway config:

```bash
cp gateway/config.example.json gateway/config.json
```

4. Edit `gateway/config.json`:

```json
{
  "auth": {
    "token": "replace-with-a-long-random-token"
  },
  "server": {
    "host": "0.0.0.0",
    "port": 43124,
    "publicBaseUrl": "http://<your-computer-ip>:43124"
  }
}
```

5. Start the Gateway:

```bash
npm --workspace gateway run dev
```

6. Open the Android app and log in with:

```text
Gateway URL: http://<your-computer-ip>:43124
Access Token: the token in gateway/config.json
```

## Android Build / Android 构建

The Android project lives in `android/`. Use an Android SDK, JDK 17, and Gradle 8.x. If you add or use a Gradle Wrapper in your public fork, prefer the wrapper command:

```bash
cd android
./gradlew :app:assembleDebug
```

Without a wrapper, run the equivalent task with your installed Gradle:

```bash
cd android
gradle :app:assembleDebug
```

## Common Commands / 常用命令

```bash
npm run typecheck
npm test
npm run audit:opensource
npm run release:check
```

## Configuration / 配置

- Gateway runtime config: `gateway/config.json`, copied from `gateway/config.example.json`.
- Release config: `release.config.json`, copied from `release.config.example.json`.
- Local private configs are ignored by git.
- Environment variables remain supported as overrides, but config files are the recommended entry point.

See [docs/configuration.md](docs/configuration.md) for the full schema.

## Documentation / 文档

- [Configuration / 配置](docs/configuration.md)
- [Development / 开发](docs/development.md)
- [Architecture / 架构](docs/architecture.md)
- [Release / 发布](docs/release.md)
- [Security / 安全](docs/security.md)

## License / 许可证

MIT. See [LICENSE](LICENSE).
