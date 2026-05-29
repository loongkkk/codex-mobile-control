import { execFileSync, spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";
import { describe, expect, it } from "vitest";

describe("mobile release script", () => {
  const repoRoot = path.resolve(__dirname, "..", "..");

  it("checks Android version consistency across release files", () => {
    const output = execFileSync(
      process.execPath,
      [path.join(repoRoot, "scripts", "mobile-release.mjs"), "--check"],
      {
        cwd: repoRoot,
        encoding: "utf8"
      }
    );

    expect(output).toMatch(/versionName=\d+\.\d+\.\d+/);
    expect(output).toMatch(/versionCode=\d+/);
  });

  it("updates compact handoff version labels while bumping versions", async () => {
    const releaseScript = await import(
      pathToFileURL(path.join(repoRoot, "scripts", "mobile-release.mjs")).href
    );

    expect(
      releaseScript.replaceVersion(
        "当前发布版本：`5.1.30 / 50130`。",
        { versionName: "5.1.30", versionCode: 50130 },
        { versionName: "5.1.31", versionCode: 50131 }
      )
    ).toContain("当前发布版本：`5.1.31 / 50131`。");
  });

  it("can be imported when node does not provide a script argv entry", () => {
    const scriptUrl = pathToFileURL(path.join(repoRoot, "scripts", "mobile-release.mjs")).href;
    const result = spawnSync(
      process.execPath,
      [
        "--input-type=module",
        "--eval",
        `process.argv.splice(1); await import(${JSON.stringify(scriptUrl)}); console.log("import-ok");`
      ],
      {
        cwd: repoRoot,
        encoding: "utf8"
      }
    );

    expect(result.stderr).toBe("");
    expect(result.status).toBe(0);
    expect(result.stdout).toContain("import-ok");
  });

  it("prints the full Android release pipeline in dry-run mode", () => {
    const output = execFileSync(
      process.execPath,
      [
        path.join(repoRoot, "scripts", "mobile-release.mjs"),
        "--release",
        "--dry-run",
        "--skip-bump",
        "--remote-host",
        "android-builder",
        "--remote-android-dir",
        "/tmp/codex-mobile/android",
        "--gradle",
        "/opt/gradle/bin/gradle",
        "--android-sdk",
        "/opt/android-sdk"
      ],
      {
        cwd: repoRoot,
        encoding: "utf8"
      }
    );

    expect(output).toContain("remote Gradle assembleDebug");
    expect(output).toContain("sync Android sources");
    expect(output).toContain("verify APK badging");
    expect(output).toContain("copy latest.apk to update source");
    expect(output).toContain("latest.json");
    expect(output).toContain("sha256");
    expect(output).toContain("diagnostics runtime identity");
  });

  it("reads release options from a public config file", async () => {
    const releaseScript = await import(
      pathToFileURL(path.join(repoRoot, "scripts", "mobile-release.mjs")).href
    );
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-release-config-"));
    const configFile = path.join(tempDir, "release.config.json");
    fs.writeFileSync(
      configFile,
      JSON.stringify(
        {
          android: {
            localDir: "android",
            remoteHost: "android-builder",
            remoteDir: "/tmp/codex-mobile/android",
            gradle: "/opt/gradle/bin/gradle",
            androidSdk: "/opt/android-sdk"
          },
          gateway: {
            url: "http://gateway.example.test:43124",
            apkDir: "gateway/downloads",
            restartCommand: "npm --workspace gateway run dev"
          }
        },
        null,
        2
      )
    );

    try {
      const options = releaseScript.releaseOptionsFromArgv(["--config", configFile]);

      expect(options.remoteHost).toBe("android-builder");
      expect(options.remoteAndroidDir).toBe("/tmp/codex-mobile/android");
      expect(options.gradle).toBe("/opt/gradle/bin/gradle");
      expect(options.androidSdk).toBe("/opt/android-sdk");
      expect(options.gatewayUrl).toBe("http://gateway.example.test:43124");
      expect(options.localLatestApk).toBe(path.join(repoRoot, "gateway", "downloads", "latest.apk"));
      expect(options.restartCommand).toBe("npm --workspace gateway run dev");
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("does not use personal remote build defaults for releases", () => {
    const result = spawnSync(
      process.execPath,
      [
        path.join(repoRoot, "scripts", "mobile-release.mjs"),
        "--release",
        "--dry-run",
        "--skip-bump",
        "--config",
        path.join(repoRoot, "missing-release.config.json")
      ],
      {
        cwd: repoRoot,
        encoding: "utf8"
      }
    );

    expect(result.status).not.toBe(0);
    expect(`${result.stdout}\n${result.stderr}`).toContain("Missing release configuration");
    expect(`${result.stdout}\n${result.stderr}`).not.toContain("Ubuntu" + "_22_04");
    expect(`${result.stdout}\n${result.stderr}`).not.toContain("/home/" + "example" + "user");
  });

  it("validates gateway diagnostics runtime identity and release apk hash", async () => {
    const releaseScript = await import(
      pathToFileURL(path.join(repoRoot, "scripts", "mobile-release.mjs")).href
    );

    const summary = releaseScript.assertGatewayDiagnosticsMatchesRelease(
      {
        diagnosticsVersion: 2,
        runtime: {
          cwd: path.join(repoRoot, "gateway"),
          pid: 1234,
          startedAt: "2026-05-11T01:00:00.000Z"
        },
        source: {
          repoRoot,
          commit: "abc123",
          branch: "main",
          dirty: true,
          available: true
        },
        release: {
          latestApk: {
            available: true,
            absolutePath: path.join(repoRoot, "gateway", "downloads", "latest.apk"),
            sha256: "release-hash",
            versionCode: 50212,
            versionName: "5.2.12"
          }
        }
      },
      {
        expectedVersion: { versionCode: 50212, versionName: "5.2.12" },
        localApkSha256: "release-hash",
        repoRoot,
        sourceCommit: "abc123"
      }
    );

    expect(summary).toMatchObject({
      repoRoot,
      commit: "abc123",
      dirty: true,
      startedAt: "2026-05-11T01:00:00.000Z",
      latestApkSha256: "release-hash"
    });
  });

  it("rejects gateway diagnostics from the old repo root", async () => {
    const releaseScript = await import(
      pathToFileURL(path.join(repoRoot, "scripts", "mobile-release.mjs")).href
    );

    expect(() =>
      releaseScript.assertGatewayDiagnosticsMatchesRelease(
        {
          diagnosticsVersion: 2,
          runtime: {
            cwd: "D:\\projects\\codex-mobile-control\\gateway",
            pid: 1234,
            startedAt: "2026-05-11T01:00:00.000Z"
          },
          source: {
            repoRoot: "D:\\projects\\codex-mobile-control",
            commit: "abc123",
            dirty: false,
            available: true
          },
          release: {
            latestApk: {
              available: true,
              sha256: "release-hash",
              versionCode: 50212,
              versionName: "5.2.12"
            }
          }
        },
        {
          expectedVersion: { versionCode: 50212, versionName: "5.2.12" },
          localApkSha256: "release-hash",
          repoRoot,
          sourceCommit: "abc123"
        }
      )
    ).toThrow(/repo root mismatch/);
  });
});
