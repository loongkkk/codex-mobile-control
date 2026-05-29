#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, "..");

const releaseFiles = {
  gradle: path.join(repoRoot, "android", "app", "build.gradle.kts")
};

const releaseDefaults = {
  localAndroidDir: process.env.CODEX_MOBILE_LOCAL_ANDROID_DIR ?? path.join(repoRoot, "android"),
  localLatestApk:
    process.env.CODEX_MOBILE_LOCAL_LATEST_APK ??
    path.join(repoRoot, "gateway", "downloads", "latest.apk"),
  gatewayUrl: process.env.CODEX_MOBILE_GATEWAY_URL ?? "http://127.0.0.1:43124",
  packageName: "com.codex.mobilecontrol"
};

function usage() {
  return [
    "Usage: node scripts/mobile-release.mjs [--check] [--bump] [--release] [--dry-run]",
    "",
    "Options:",
    "  --check                  Verify versionCode/versionName are consistent across release files.",
    "  --bump                   Increment versionName patch and versionCode, then sync release files.",
    "  --release                Build remote APK, copy latest.apk, and verify update metadata/hash.",
    "  --skip-bump              With --release, keep the current version instead of bumping first.",
    "  --dry-run                Print planned actions without writing files or running commands.",
    "  --config <path>          Release config JSON. Default: release.config.json when present.",
    "  --remote-host <host>     SSH host for Android builds.",
    "  --remote-android-dir <d> Remote Android project dir.",
    "  --local-android-dir <d>  Local Android project dir to sync before building.",
    "  --gradle <path>          Remote Gradle executable.",
    "  --android-sdk <path>     Remote Android SDK root.",
    "  --local-apk <path>       Local latest.apk destination.",
    "  --gateway-url <url>      Gateway base URL for latest.json/hash verification.",
    "  --restart-command <cmd>  Optional local shell command to restart the Gateway after copying APK.",
    "  --help                   Show this help."
  ].join("\n");
}

function readText(filePath) {
  return fs.readFileSync(filePath, "utf8");
}

function writeText(filePath, text) {
  fs.writeFileSync(filePath, text, "utf8");
}

function readGradleVersion() {
  const source = readText(releaseFiles.gradle);
  const versionCode = Number.parseInt(/versionCode\s*=\s*(\d+)/.exec(source)?.[1] ?? "", 10);
  const versionName = /versionName\s*=\s*"([^"]+)"/.exec(source)?.[1];
  if (!Number.isFinite(versionCode) || !versionName) {
    throw new Error(`Unable to parse Android version from ${releaseFiles.gradle}`);
  }
  return { versionCode, versionName };
}

function nextPatchVersion({ versionCode, versionName }) {
  const parts = versionName.split(".").map((part) => Number.parseInt(part, 10));
  if (parts.length !== 3 || parts.some((part) => !Number.isFinite(part) || part < 0)) {
    throw new Error(`versionName must use major.minor.patch, got ${versionName}`);
  }
  parts[2] += 1;
  return {
    versionCode: versionCode + 1,
    versionName: parts.join(".")
  };
}

function assertContains(source, expected, filePath) {
  if (!source.includes(expected)) {
    throw new Error(`${path.relative(repoRoot, filePath)} missing ${expected}`);
  }
}

function checkVersionConsistency(version = readGradleVersion()) {
  return version;
}

function replaceVersion(source, fromVersion, toVersion) {
  return source
    .replaceAll(`versionCode = ${fromVersion.versionCode}`, `versionCode = ${toVersion.versionCode}`)
    .replaceAll(`versionName = "${fromVersion.versionName}"`, `versionName = "${toVersion.versionName}"`)
    .replaceAll(`versionName = \\"${fromVersion.versionName}\\"`, `versionName = \\"${toVersion.versionName}\\"`)
    .replaceAll(
      `${fromVersion.versionName} / ${fromVersion.versionCode}`,
      `${toVersion.versionName} / ${toVersion.versionCode}`
    );
}

function bumpVersion({ dryRun = false } = {}) {
  const current = checkVersionConsistency();
  const next = nextPatchVersion(current);
  if (!dryRun) {
    for (const filePath of Object.values(releaseFiles)) {
      writeText(filePath, replaceVersion(readText(filePath), current, next));
    }
  }
  return { current, next, dryRun };
}

function valueArg(argv, name, fallback) {
  const index = argv.indexOf(name);
  if (index < 0) {
    return fallback;
  }
  const value = argv[index + 1];
  if (!value || value.startsWith("--")) {
    throw new Error(`${name} requires a value`);
  }
  return value;
}

function hasArg(argv, name) {
  return argv.includes(name);
}

function releaseConfigPathFromArgv(argv) {
  return valueArg(
    argv,
    "--config",
    process.env.CODEX_MOBILE_RELEASE_CONFIG ?? path.join(repoRoot, "release.config.json")
  );
}

function readReleaseConfig(configPath, { explicit = false } = {}) {
  const resolved = path.resolve(configPath);
  if (!fs.existsSync(resolved)) {
    if (explicit) {
      throw new Error(`Missing release configuration: ${resolved}`);
    }
    return {};
  }
  return JSON.parse(readText(resolved));
}

function configObject(value, key) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return undefined;
  }
  const field = value[key];
  return field && typeof field === "object" && !Array.isArray(field) ? field : undefined;
}

function configString(value, key) {
  const field = value?.[key];
  return typeof field === "string" && field.trim() ? field.trim() : undefined;
}

function resolveRepoPath(value) {
  if (!value) {
    return undefined;
  }
  return path.resolve(repoRoot, value);
}

function releaseOptionsFromArgv(argv) {
  const releaseConfig = readReleaseConfig(releaseConfigPathFromArgv(argv), {
    explicit: hasArg(argv, "--config") || Boolean(process.env.CODEX_MOBILE_RELEASE_CONFIG)
  });
  const androidConfig = configObject(releaseConfig, "android");
  const gatewayConfig = configObject(releaseConfig, "gateway");
  const configuredApkDir = configString(gatewayConfig, "apkDir");
  return {
    remoteHost: valueArg(
      argv,
      "--remote-host",
      process.env.CODEX_MOBILE_RELEASE_HOST ?? configString(androidConfig, "remoteHost")
    ),
    remoteAndroidDir: valueArg(
      argv,
      "--remote-android-dir",
      process.env.CODEX_MOBILE_REMOTE_ANDROID_DIR ?? configString(androidConfig, "remoteDir")
    ),
    localAndroidDir: path.resolve(
      valueArg(
        argv,
        "--local-android-dir",
        process.env.CODEX_MOBILE_LOCAL_ANDROID_DIR ??
          resolveRepoPath(configString(androidConfig, "localDir")) ??
          releaseDefaults.localAndroidDir
      )
    ),
    gradle: valueArg(
      argv,
      "--gradle",
      process.env.CODEX_MOBILE_GRADLE ?? configString(androidConfig, "gradle")
    ),
    androidSdk: valueArg(
      argv,
      "--android-sdk",
      process.env.ANDROID_SDK_ROOT ?? process.env.ANDROID_HOME ?? configString(androidConfig, "androidSdk")
    ),
    localLatestApk: path.resolve(
      valueArg(
        argv,
        "--local-apk",
        process.env.CODEX_MOBILE_LOCAL_LATEST_APK ??
          (configuredApkDir ? path.join(resolveRepoPath(configuredApkDir), "latest.apk") : undefined) ??
          releaseDefaults.localLatestApk
      )
    ),
    gatewayUrl: valueArg(
      argv,
      "--gateway-url",
      process.env.CODEX_MOBILE_GATEWAY_URL ?? configString(gatewayConfig, "url") ?? releaseDefaults.gatewayUrl
    ).replace(/\/$/, ""),
    restartCommand: valueArg(
      argv,
      "--restart-command",
      process.env.CODEX_MOBILE_GATEWAY_RESTART_COMMAND ??
        configString(gatewayConfig, "restartCommand") ??
        ""
    )
  };
}

function assertReleaseOptionsReady(options) {
  const missing = [];
  if (!options.remoteHost) {
    missing.push("android.remoteHost");
  }
  if (!options.remoteAndroidDir) {
    missing.push("android.remoteDir");
  }
  if (!options.gradle) {
    missing.push("android.gradle");
  }
  if (!options.androidSdk) {
    missing.push("android.androidSdk");
  }
  if (missing.length > 0) {
    throw new Error(
      `Missing release configuration: ${missing.join(", ")}. ` +
        "Create release.config.json from release.config.example.json or pass CLI options."
    );
  }
}

function shQuote(value) {
  return `'${String(value).replace(/'/g, "'\\''")}'`;
}

function remoteJoin(...parts) {
  return parts.join("/").replace(/\/+/g, "/");
}

function localShellPath(filePath) {
  const normalized = path.resolve(filePath);
  const driveMatch = /^([A-Za-z]):[\\/](.*)$/.exec(normalized);
  if (!driveMatch) {
    return normalized.replace(/\\/g, "/");
  }
  return `/${driveMatch[1].toLowerCase()}/${driveMatch[2].replace(/\\/g, "/")}`;
}

function remoteGradleCommand(options) {
  return (
    `cd ${shQuote(options.remoteAndroidDir)} && ` +
    `ANDROID_SDK_ROOT=${shQuote(options.androidSdk)} ` +
    `ANDROID_HOME=${shQuote(options.androidSdk)} ` +
    `${shQuote(options.gradle)} assembleDebug`
  );
}

function remoteFindLatestApkCommand(options) {
  const outputDir = remoteJoin(options.remoteAndroidDir, "app/build/outputs/apk/debug");
  return [
    `find ${shQuote(outputDir)} -type f -name '*.apk' -printf '%T@ %p\\n'`,
    "sort -nr",
    "head -1",
    "cut -d' ' -f2-"
  ].join(" | ");
}

function remoteFindAaptCommand(options) {
  return [
    `find ${shQuote(remoteJoin(options.androidSdk, "build-tools"))} -type f -name aapt`,
    "sort -V",
    "tail -1"
  ].join(" | ");
}

function commandText(file, args) {
  return [file, ...args.map((arg) => (/\s/.test(arg) ? shQuote(arg) : arg))].join(" ");
}

function execText(file, args, options = {}) {
  return execFileSync(file, args, {
    cwd: repoRoot,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
    ...options
  }).trim();
}

function runCommand(file, args, { dryRun, label }) {
  if (dryRun) {
    console.log(`[dry-run] ${label}: ${commandText(file, args)}`);
    return "";
  }
  console.log(`[run] ${label}`);
  return execText(file, args);
}

function syncAndroidSources(options, dryRun) {
  const sourceEntries = [
    "app",
    "tests",
    "build.gradle.kts",
    "gradle.properties",
    "settings.gradle.kts"
  ].map((entry) => localShellPath(path.join(options.localAndroidDir, entry)));
  runCommand("ssh", [options.remoteHost, `mkdir -p ${shQuote(options.remoteAndroidDir)}`], {
    dryRun,
    label: "ensure remote Android dir"
  });
  runCommand("scp", ["-r", ...sourceEntries, `${options.remoteHost}:${options.remoteAndroidDir}/`], {
    dryRun,
    label: "sync Android sources"
  });
}

function parseBadging(badging) {
  const match = /^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'/m.exec(badging);
  if (!match) {
    throw new Error("Unable to parse APK badging output");
  }
  return {
    packageName: match[1],
    versionCode: Number.parseInt(match[2], 10),
    versionName: match[3]
  };
}

function assertBadgingMatches(badging, expectedVersion, expectedPackageName) {
  const parsed = parseBadging(badging);
  if (parsed.packageName !== expectedPackageName) {
    throw new Error(`APK package mismatch: ${parsed.packageName} !== ${expectedPackageName}`);
  }
  if (parsed.versionCode !== expectedVersion.versionCode) {
    throw new Error(`APK versionCode mismatch: ${parsed.versionCode} !== ${expectedVersion.versionCode}`);
  }
  if (parsed.versionName !== expectedVersion.versionName) {
    throw new Error(`APK versionName mismatch: ${parsed.versionName} !== ${expectedVersion.versionName}`);
  }
  return parsed;
}

function readGatewayToken() {
  const configFile = path.join(repoRoot, "gateway", "config.json");
  const config = JSON.parse(readText(configFile));
  const token = config?.auth?.token ?? config?.token;
  if (typeof token !== "string" || !token.trim()) {
    throw new Error(`Missing token in ${configFile}`);
  }
  return token.trim();
}

function sha256Buffer(buffer) {
  return createHash("sha256").update(buffer).digest("hex");
}

function localApkSha256(filePath) {
  return sha256Buffer(fs.readFileSync(filePath));
}

function comparablePath(filePath) {
  return path.resolve(String(filePath)).replace(/\\/g, "/").replace(/\/+$/, "").toLowerCase();
}

function objectField(value, key) {
  return value && typeof value === "object" && !Array.isArray(value) ? value[key] : undefined;
}

function readLocalSourceCommit() {
  try {
    return execText("git", ["rev-parse", "HEAD"]);
  } catch {
    return null;
  }
}

function assertGatewayDiagnosticsMatchesRelease(
  diagnostics,
  { expectedVersion, localApkSha256, repoRoot: expectedRepoRoot, sourceCommit }
) {
  const diagnosticsVersion = objectField(diagnostics, "diagnosticsVersion");
  if (typeof diagnosticsVersion !== "number" || diagnosticsVersion < 2) {
    throw new Error("Gateway diagnostics missing runtime identity; restart the new Gateway and retry");
  }

  const runtime = objectField(diagnostics, "runtime");
  const source = objectField(diagnostics, "source");
  const release = objectField(diagnostics, "release");
  const latestApk = objectField(release, "latestApk");
  if (!runtime || !source || !latestApk) {
    throw new Error("Gateway diagnostics missing runtime/source/release identity");
  }

  const runtimeCwd = objectField(runtime, "cwd");
  const startedAt = objectField(runtime, "startedAt");
  if (typeof runtimeCwd !== "string" || !runtimeCwd.trim() || typeof startedAt !== "string" || !startedAt.trim()) {
    throw new Error("Gateway diagnostics missing runtime cwd or startedAt");
  }

  if (objectField(source, "available") !== true) {
    throw new Error("Gateway diagnostics source identity unavailable");
  }
  const actualRepoRoot = objectField(source, "repoRoot");
  if (typeof actualRepoRoot !== "string" || comparablePath(actualRepoRoot) !== comparablePath(expectedRepoRoot)) {
    throw new Error(`Gateway diagnostics repo root mismatch: ${actualRepoRoot ?? "missing"} !== ${expectedRepoRoot}`);
  }

  const actualCommit = objectField(source, "commit");
  if (sourceCommit && actualCommit !== sourceCommit) {
    throw new Error(`Gateway diagnostics source commit mismatch: ${actualCommit ?? "missing"} !== ${sourceCommit}`);
  }

  if (objectField(latestApk, "available") !== true) {
    throw new Error("Gateway diagnostics latest APK unavailable");
  }
  if (objectField(latestApk, "sha256") !== localApkSha256) {
    throw new Error(`Gateway diagnostics latest APK sha256 mismatch: ${objectField(latestApk, "sha256") ?? "missing"} !== ${localApkSha256}`);
  }
  if (objectField(latestApk, "versionCode") !== expectedVersion.versionCode) {
    throw new Error(`Gateway diagnostics latest APK versionCode mismatch: ${objectField(latestApk, "versionCode") ?? "missing"} !== ${expectedVersion.versionCode}`);
  }
  if (objectField(latestApk, "versionName") !== expectedVersion.versionName) {
    throw new Error(`Gateway diagnostics latest APK versionName mismatch: ${objectField(latestApk, "versionName") ?? "missing"} !== ${expectedVersion.versionName}`);
  }

  return {
    runtimeCwd,
    startedAt,
    repoRoot: actualRepoRoot,
    commit: actualCommit,
    branch: objectField(source, "branch"),
    dirty: objectField(source, "dirty"),
    latestApkSha256: objectField(latestApk, "sha256"),
    latestApkPath: objectField(latestApk, "absolutePath")
  };
}

async function fetchWithToken(url, token) {
  const response = await fetch(url, {
    headers: {
      authorization: `Bearer ${token}`
    }
  });
  if (!response.ok) {
    throw new Error(`${url} returned ${response.status}`);
  }
  return response;
}

async function verifyGatewayRelease(options, expectedVersion) {
  const token = readGatewayToken();
  const latestJsonUrl = `${options.gatewayUrl}/downloads/latest.json`;
  const latestJson = await (await fetchWithToken(latestJsonUrl, token)).json();
  if (latestJson.versionCode !== expectedVersion.versionCode) {
    throw new Error(`latest.json versionCode mismatch: ${latestJson.versionCode} !== ${expectedVersion.versionCode}`);
  }
  if (latestJson.versionName !== expectedVersion.versionName) {
    throw new Error(`latest.json versionName mismatch: ${latestJson.versionName} !== ${expectedVersion.versionName}`);
  }

  const localHash = localApkSha256(options.localLatestApk);
  const downloadUrl = new URL(latestJson.downloadUrl ?? "/downloads/latest.apk", options.gatewayUrl).toString();
  const downloaded = Buffer.from(await (await fetchWithToken(downloadUrl, token)).arrayBuffer());
  const remoteHash = sha256Buffer(downloaded);
  if (localHash !== remoteHash) {
    throw new Error(`downloaded APK sha256 mismatch: ${remoteHash} !== ${localHash}`);
  }

  const diagnosticsUrl = `${options.gatewayUrl}/api/diagnostics`;
  const diagnostics = await (await fetchWithToken(diagnosticsUrl, token)).json();
  const diagnosticsSummary = assertGatewayDiagnosticsMatchesRelease(diagnostics, {
    expectedVersion,
    localApkSha256: localHash,
    repoRoot,
    sourceCommit: readLocalSourceCommit()
  });
  return { latestJson, localHash, diagnostics: diagnosticsSummary };
}

async function releaseAndroid(options, { dryRun = false, skipBump = false } = {}) {
  assertReleaseOptionsReady(options);
  const versionStep = skipBump ? { current: checkVersionConsistency(), next: checkVersionConsistency() } : bumpVersion({ dryRun });
  const expectedVersion = versionStep.next;

  console.log(
    skipBump
      ? `mobile-release release using ${expectedVersion.versionName}/${expectedVersion.versionCode}`
      : `mobile-release release version ${versionStep.current.versionName}/${versionStep.current.versionCode} -> ${expectedVersion.versionName}/${expectedVersion.versionCode}`
  );
  if (dryRun && !skipBump) {
    console.log("[dry-run] would update Android Gradle and NEXT_WINDOW_HANDOFF.md version fields");
  }

  syncAndroidSources(options, dryRun);

  runCommand("ssh", [options.remoteHost, remoteGradleCommand(options)], {
    dryRun,
    label: "remote Gradle assembleDebug"
  });

  const remoteApk = dryRun
    ? remoteJoin(options.remoteAndroidDir, "app/build/outputs/apk/debug/codex-mobile-control-debug.apk")
    : execText("ssh", [options.remoteHost, remoteFindLatestApkCommand(options)]);
  if (!remoteApk) {
    throw new Error("Unable to resolve remote APK path");
  }
  if (dryRun) {
    console.log(`[dry-run] remote latest APK: ${remoteApk}`);
  }

  const remoteAapt = dryRun ? remoteJoin(options.androidSdk, "build-tools/35.0.0/aapt") : execText("ssh", [options.remoteHost, remoteFindAaptCommand(options)]);
  if (!remoteAapt) {
    throw new Error("Unable to resolve remote aapt path");
  }
  const badgingCommand = `${shQuote(remoteAapt)} dump badging ${shQuote(remoteApk)}`;
  if (dryRun) {
    console.log(`[dry-run] verify APK badging: ssh ${options.remoteHost} ${shQuote(badgingCommand)}`);
  } else {
    const badging = execText("ssh", [options.remoteHost, badgingCommand]);
    const parsed = assertBadgingMatches(badging, expectedVersion, releaseDefaults.packageName);
    console.log(
      `mobile-release badging ok package=${parsed.packageName} versionName=${parsed.versionName} versionCode=${parsed.versionCode}`
    );
  }

  if (dryRun) {
    console.log(`[dry-run] ensure directory: ${path.dirname(options.localLatestApk)}`);
  } else {
    fs.mkdirSync(path.dirname(options.localLatestApk), { recursive: true });
  }
  runCommand("scp", [`${options.remoteHost}:${remoteApk}`, localShellPath(options.localLatestApk)], {
    dryRun,
    label: "copy latest.apk to update source"
  });

  if (options.restartCommand) {
    runCommand(process.platform === "win32" ? "bash" : "sh", ["-lc", options.restartCommand], {
      dryRun,
      label: "restart Gateway"
    });
  } else {
    console.log("mobile-release restart skipped: pass --restart-command or CODEX_MOBILE_GATEWAY_RESTART_COMMAND");
  }

  if (dryRun) {
    console.log(`[dry-run] verify ${options.gatewayUrl}/downloads/latest.json and downloaded APK sha256`);
    console.log(`[dry-run] verify diagnostics runtime identity: ${options.gatewayUrl}/api/diagnostics`);
  } else {
    const verification = await verifyGatewayRelease(options, expectedVersion);
    console.log(
      `mobile-release gateway ok versionName=${verification.latestJson.versionName} ` +
        `versionCode=${verification.latestJson.versionCode} sha256=${verification.localHash}`
    );
    console.log(
      `mobile-release diagnostics ok repoRoot=${verification.diagnostics.repoRoot} ` +
        `commit=${String(verification.diagnostics.commit ?? "unknown").slice(0, 12)} ` +
        `dirty=${verification.diagnostics.dirty} startedAt=${verification.diagnostics.startedAt} ` +
        `latestApkSha256=${verification.diagnostics.latestApkSha256}`
    );
  }

  return expectedVersion;
}

async function main(argv) {
  if (argv.includes("--help")) {
    console.log(usage());
    return;
  }
  const dryRun = argv.includes("--dry-run");
  const shouldBump = argv.includes("--bump");
  const shouldRelease = argv.includes("--release");
  const shouldCheck = argv.includes("--check") || (!shouldBump && !shouldRelease);

  if (shouldRelease) {
    await releaseAndroid(releaseOptionsFromArgv(argv), {
      dryRun,
      skipBump: argv.includes("--skip-bump")
    });
    return;
  }

  if (shouldBump) {
    const result = bumpVersion({ dryRun });
    const mode = dryRun ? "dry-run" : "updated";
    console.log(
      `mobile-release ${mode}: ${result.current.versionName}/${result.current.versionCode} -> ` +
        `${result.next.versionName}/${result.next.versionCode}`
    );
    return;
  }

  if (shouldCheck) {
    const version = checkVersionConsistency();
    console.log(`mobile-release check ok versionName=${version.versionName} versionCode=${version.versionCode}`);
  }
}

const entryPointUrl = process.argv[1] ? pathToFileURL(process.argv[1]).href : null;
if (import.meta.url === entryPointUrl) {
  main(process.argv.slice(2)).catch((error) => {
    console.error(error instanceof Error ? error.message : error);
    process.exitCode = 1;
  });
}

export {
  assertBadgingMatches,
  assertGatewayDiagnosticsMatchesRelease,
  checkVersionConsistency,
  nextPatchVersion,
  parseBadging,
  readGradleVersion,
  releaseAndroid,
  releaseFiles,
  releaseOptionsFromArgv,
  replaceVersion
};
