# Configuration / 配置

This project keeps private deployment values out of the public repository. Copy example files, then edit the local ignored files.

本项目用示例配置公开字段结构，用本地私有配置保存真实地址、token、构建机和发布命令。

## Gateway Config / Gateway 配置

Create `gateway/config.json` from the example:

```bash
cp gateway/config.example.json gateway/config.json
```

Example schema:

```json
{
  "auth": {
    "token": "replace-with-a-random-token"
  },
  "server": {
    "host": "0.0.0.0",
    "port": 43124,
    "publicBaseUrl": "http://<your-computer-ip>:43124"
  },
  "storage": {
    "uploadsDir": "mobile-uploads",
    "queueFile": "queued-messages.json",
    "downloadsDir": "downloads",
    "mobileDistDir": "../mobile/dist"
  },
  "codex": {
    "home": "",
    "sendMode": "official_persistence",
    "appServer": {
      "transport": "stdio",
      "command": "",
      "wsUrl": ""
    },
    "ipcSend": true
  },
  "desktopBridge": {
    "pythonCommand": "py",
    "pywinautoPath": ""
  }
}
```

Field notes:

- `auth.token`: Bearer token used by Android, web, and release verification requests.
- `server.host`: Bind address. Use `127.0.0.1` for local-only, `0.0.0.0` for LAN access.
- `server.port`: Gateway HTTP port.
- `server.publicBaseUrl`: URL advertised to clients and update metadata when needed.
- `storage.uploadsDir`: Uploaded images/files from the mobile app.
- `storage.queueFile`: Gateway-side queued message persistence file.
- `storage.downloadsDir`: APK update source directory, normally containing `latest.apk`.
- `storage.mobileDistDir`: Built web client directory served by the Gateway.
- `codex.home`: Codex data directory. Empty means the Gateway uses the current OS user's default Codex home.
- `codex.sendMode`: `official_persistence` is the recommended path. `desktop_bridge` is a legacy compatibility fallback.
- `codex.appServer.transport`: `stdio` or `ws`.
- `codex.appServer.command`: Optional command for launching the app server when using stdio.
- `codex.appServer.wsUrl`: Optional WebSocket URL when using `ws` transport.
- `codex.ipcSend`: Enables the official IPC/persistence send path.
- `desktopBridge.pythonCommand`: Python command for the legacy desktop bridge.
- `desktopBridge.pywinautoPath`: Optional local pywinauto package path.

If `gateway/config.json` does not exist, the Gateway creates one with a random token on first start. If a config exists but misses required defaults, the Gateway adds defaults while preserving existing nested fields.

## Environment Overrides / 环境变量覆盖

Environment variables are still supported for automation and temporary overrides. Config files should remain the normal documented path.

Gateway runtime overrides:

```text
CODEX_MOBILE_CONFIG_FILE
CODEX_MOBILE_TOKEN
CODEX_MOBILE_HOST
CODEX_MOBILE_PORT
CODEX_MOBILE_PUBLIC_BASE_URL
CODEX_MOBILE_UPLOADS_DIR
CODEX_MOBILE_QUEUE_FILE
CODEX_MOBILE_DOWNLOADS_DIR
CODEX_MOBILE_MOBILE_DIST_DIR
CODEX_MOBILE_CODEX_HOME
CODEX_MOBILE_SEND_MODE
CODEX_MOBILE_APP_SERVER_TRANSPORT
CODEX_MOBILE_APP_SERVER_COMMAND
CODEX_MOBILE_APP_SERVER_WS_URL
CODEX_MOBILE_IPC_SEND
CODEX_MOBILE_PYTHON
CODEX_MOBILE_PYWINAUTO_PATH
```

Release overrides are documented in [docs/release.md](release.md).

## Android Login / Android 登录

The Android app no longer ships with a built-in Gateway URL. Enter your own address:

```text
http://<your-computer-ip>:43124
```

The app accepts a bare host or IP and normalizes it by adding `http://` when no scheme is provided.

Android App 不再内置线上网关地址。登录页默认地址为空，只提供通用占位符。
