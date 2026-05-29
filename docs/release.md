# Release / 发布

Release automation is configured locally. The public repository ships only `release.config.example.json`.

发布流程使用本地私有配置。公开仓库只提供 `release.config.example.json`，真实构建机、SDK、发布目录和重启命令不要提交。

## Release Config / 发布配置

Create a private config:

```bash
cp release.config.example.json release.config.json
```

Example schema:

```json
{
  "android": {
    "localDir": "android",
    "remoteHost": "android-builder",
    "remoteDir": "/tmp/codex-mobile/android",
    "gradle": "/opt/gradle/bin/gradle",
    "androidSdk": "/opt/android-sdk"
  },
  "gateway": {
    "url": "http://127.0.0.1:43124",
    "apkDir": "gateway/downloads",
    "restartCommand": ""
  }
}
```

Fields:

- `android.localDir`: Local Android project directory.
- `android.remoteHost`: SSH host used for Android builds.
- `android.remoteDir`: Remote Android project directory.
- `android.gradle`: Remote Gradle executable.
- `android.androidSdk`: Remote Android SDK root.
- `gateway.url`: Gateway URL used for release verification.
- `gateway.apkDir`: Directory where `latest.apk` is copied.
- `gateway.restartCommand`: Optional command to restart the Gateway after copying `latest.apk`.

## Commands / 命令

Check current version:

```bash
npm run release:check
```

Bump Android patch version and versionCode:

```bash
npm run release:bump
```

Dry run release:

```bash
npm run release:dry-run
```

Build and publish APK:

```bash
npm run release:android
```

The release command requires a complete `release.config.json` or equivalent CLI/environment overrides. Without required fields it fails with a clear `Missing release configuration` error.

## Verification / 校验

Release verification checks:

- Android `versionCode` and `versionName`.
- APK package name.
- `latest.json` version metadata.
- Downloaded APK SHA-256 hash.
- Gateway diagnostics runtime identity and source commit.

## Environment Overrides / 环境变量覆盖

```text
CODEX_MOBILE_RELEASE_CONFIG
CODEX_MOBILE_RELEASE_HOST
CODEX_MOBILE_REMOTE_ANDROID_DIR
CODEX_MOBILE_LOCAL_ANDROID_DIR
CODEX_MOBILE_GRADLE
ANDROID_SDK_ROOT
ANDROID_HOME
CODEX_MOBILE_LOCAL_LATEST_APK
CODEX_MOBILE_GATEWAY_URL
CODEX_MOBILE_GATEWAY_RESTART_COMMAND
```

Environment variables are useful in CI or local scripts, but `release.config.json` is the recommended human-editable entry point.

## Public Repository Note / 公开仓库注意

Do not commit:

- `release.config.json`
- APK files
- signing keys
- build outputs
- run logs
- local upload folders
