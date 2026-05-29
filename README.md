# Codex Mobile Control

<div align="center">

用手机控制和监控 Codex 任务的本地网关 + Android 客户端。

![Stars](https://img.shields.io/badge/Stars-welcome-ffdd54?style=flat-square)
![Forks](https://img.shields.io/badge/Forks-welcome-8dd6f9?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-green?style=flat-square)
![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20Node.js%20%7C%20Codex-blue?style=flat-square)
![Language](https://img.shields.io/badge/README-%E4%B8%AD%E6%96%87-red?style=flat-square)

</div>

## 目录

- [它做什么](#它做什么)
- [核心工作流](#核心工作流)
- [核心能力](#核心能力)
- [安装与快速启动](#安装与快速启动)
- [Android 构建](#android-构建)
- [项目结构](#项目结构)
- [开发与发布](#开发与发布)
- [配置与文档](#配置与文档)
- [安全说明](#安全说明)
- [许可证](#许可证)

## 它做什么

Codex Mobile Control 让你在手机上查看、跟进和操作本机 Codex 任务。

它由两部分组成：

- Android App：提供任务列表、详情页、消息输入、附件上传、排队消息、通知和诊断导出。
- Gateway：运行在你的电脑上，负责鉴权、读取 Codex 数据、发送消息、处理上传文件，并把实时事件推送给手机端。

这个项目适合个人自部署场景。默认假设 Gateway 和手机处在可信网络内，Android App 使用网关地址和访问 token 登录。

## 核心工作流

```text
┌────────────────────┐
│ Android App         │
│ 任务列表 / 详情 / 发送 │
└─────────┬──────────┘
          │ HTTP API + Bearer Token
          │ Socket / SSE 实时事件
          ▼
┌────────────────────┐
│ Gateway             │
│ 鉴权 / 上传 / 诊断 / 队列 │
└─────────┬──────────┘
          │ 官方持久化链路优先
          │ App Server / IPC
          ▼
┌────────────────────┐
│ Codex 数据源         │
│ 线程 / 消息 / 状态     │
└─────────┬──────────┘
          │ 状态与消息变化
          └──────────────► 回推到 Android App
```

普通发送优先走 Codex 官方持久化链路。桌面桥接保留为兼容兜底，但不是默认推荐路径。

## 核心能力

### 移动端体验

- 查看 Codex 任务列表、运行状态、异常状态和自动化任务。
- 进入详情页阅读消息、发送新输入、上传图片和文件。
- 支持排队消息：任务运行中先缓存，空闲后再发送。
- 支持新消息提醒、结束提醒、前台常驻实时服务和诊断日志导出。

### Gateway 能力

- Token 鉴权和统一 HTTP API。
- 文件上传、诊断包接收、APK 更新源和运行时诊断。
- Socket / SSE 实时推送，兼顾前台和后台刷新体验。
- 本地配置文件优先，环境变量作为覆盖层。

### Codex 同步链路

- 优先使用 Codex App Server / IPC 的官方持久化数据。
- 手机发送后写入同一条消息事实源，方便桌面端、插件端和移动端同步。
- 对只有通知但没有完整消息的场景保留刷新兜底。

### 开源安全

- 私有运行配置不进入仓库。
- APK、日志、上传文件、交接文档和本地分析文档默认忽略。
- 提供 `audit:opensource`，用于扫描敏感路径、密钥类文件和本地私有文件。

## 安装与快速启动

### 1. 安装依赖

需要 Node.js 20+ 和 npm。

```bash
npm install
```

### 2. 创建 Gateway 私有配置

```bash
cp gateway/config.example.json gateway/config.json
```

编辑 `gateway/config.json`，至少配置访问 token 和对手机可访问的网关地址：

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

### 3. 启动 Gateway

```bash
npm --workspace gateway run dev
```

### 4. Android App 登录

在 Android App 登录页填写：

```text
Gateway URL: http://<your-computer-ip>:43124
Access Token: gateway/config.json 中的 token
```

如果只输入裸 IP 或域名，App 会自动补全 `http://` 或 `https://`。

## Android 构建

Android 工程位于 `android/`。需要 JDK 17、Android SDK 和 Gradle 8.x。

如果你的公开 fork 添加了 Gradle Wrapper，优先使用：

```bash
cd android
./gradlew :app:assembleDebug
```

如果没有 Gradle Wrapper，可以使用本机安装的 Gradle：

```bash
cd android
gradle :app:assembleDebug
```

## 项目结构

```text
.
├── android/      Android 原生 App，Kotlin + XML 布局 + 单元测试
├── gateway/      Node.js Gateway，HTTP API、实时推送、Codex 集成
├── mobile/       React + Vite Web/Mobile 调试客户端
├── shared/       前后端共享类型和 API 定义
├── scripts/      发布、开源审计、公开仓库同步脚本
├── docs/         配置、开发、架构、发布和安全文档
├── package.json  npm workspace 入口
└── LICENSE       MIT 许可证
```

## 开发与发布

常用检查命令：

```bash
npm run typecheck
npm test
npm run audit:opensource
npm run release:check
```

Gateway 开发：

```bash
npm --workspace gateway run dev
npm --workspace gateway test
```

Web/Mobile 调试端：

```bash
npm --workspace mobile run dev
npm --workspace mobile test
```

维护公开仓库时，推荐使用固定公开工作区并同步当前干净文件树：

```bash
npm run sync:public
```

发布 APK 前请先准备私有发布配置：

```bash
cp release.config.example.json release.config.json
npm run release:dry-run
```

## 配置与文档

- [配置说明](docs/configuration.md)：Gateway 配置、环境变量覆盖和 Android 登录参数。
- [开发说明](docs/development.md)：本地开发、测试、公开仓库同步流程。
- [架构说明](docs/architecture.md)：Android、Gateway、Codex App Server / IPC 和实时推送数据流。
- [发布说明](docs/release.md)：版本递增、APK 构建、更新源和校验流程。
- [安全说明](docs/security.md)：token、局域网暴露、HTTPS 反代、诊断日志脱敏。

## 安全说明

请不要提交以下内容：

- `gateway/config.json`
- `release.config.json`
- 真实 token、密钥、证书和签名文件
- APK、构建输出、日志和上传文件
- 本地交接文档、分析队列和私人部署记录

如果需要添加个人或团队专属敏感词扫描规则，可以复制：

```bash
cp .open-source-audit.private.example.json .open-source-audit.private.json
```

`.open-source-audit.private.json` 已加入 `.gitignore`，不会进入公开仓库。

## 许可证

本项目基于 MIT 协议开源，详见 [LICENSE](LICENSE)。
