# Security / 安全

Codex Mobile Control is intended for trusted self-hosted use. Treat the Gateway as a local control surface for your Codex environment.

Codex Mobile Control 面向可信网络内的个人自部署。Gateway 等价于你的 Codex 控制入口，需要谨慎暴露。

## Token

- Use a long random token in `gateway/config.json`.
- Do not commit `gateway/config.json`.
- Rotate the token if a phone, log bundle, or config backup may have leaked it.
- Android stores the token locally.

## Network Exposure / 网络暴露

- Prefer LAN-only access.
- Bind to `127.0.0.1` if only local reverse proxy access is needed.
- Bind to `0.0.0.0` only when phones on the LAN need direct access.
- If exposing outside a trusted LAN, put the Gateway behind HTTPS and an authenticated reverse proxy.

## HTTP and HTTPS

The Android app accepts plain HTTP for local networks. For untrusted networks, use HTTPS through a reverse proxy and set `server.publicBaseUrl` accordingly.

## Diagnostics and Logs / 诊断与日志

Diagnostics may contain:

- Gateway URL and runtime metadata.
- Thread titles and message snippets.
- File paths.
- Error messages.
- Redacted token values.

Before sharing logs publicly, inspect them and remove sensitive task content, private hostnames, local paths, and uploaded file names.

## Files and Uploads / 文件上传

- Uploaded files are stored under `storage.uploadsDir`.
- Temporary incoming files are cleaned automatically by the Gateway.
- Do not track `gateway/mobile-uploads/`.
- Avoid uploading secrets unless the Codex task explicitly needs them.

## Desktop Bridge Fallback / 桌面桥接回退

`desktop_bridge` may automate desktop UI input and depends on OS focus and desktop state. Prefer `official_persistence` for reliability and safety.

## Open-Source Audit / 开源审计

Run before publishing:

```bash
npm run audit:opensource
```

The audit scans tracked files for local-only files, generated artifacts, known private path patterns, known private host patterns, and secret-like binary extensions.

Project-specific private patterns should not be hard-coded in the public audit script. Put them in `.open-source-audit.private.json`, copied from `.open-source-audit.private.example.json`, and keep that file ignored.

不要把个人域名、用户名、构建机、设备 ID 或本机路径硬编码进公开审计脚本。请放到本地私有的 `.open-source-audit.private.json`。
