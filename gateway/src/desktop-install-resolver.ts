import { execFile as execFileCallback } from "node:child_process";
import { readdir as readdirCallback, stat as statCallback } from "node:fs/promises";
import path from "node:path";
import { promisify } from "node:util";

import type { DesktopBridgeUnavailableReason } from "./desktop-bridge";

const execFileDefault = promisify(execFileCallback);

type ProcessRecord = {
  ProcessId?: number;
  ExecutablePath?: string;
  CommandLine?: string;
};

type ResolverDependencies = {
  platform?: NodeJS.Platform;
  execFile?: (
    file: string,
    args: string[],
    options?: { timeout?: number }
  ) => Promise<{ stdout: string }>;
  readdir?: typeof readdirCallback;
  stat?: typeof statCallback;
  sleep?: (ms: number) => Promise<void>;
  maxResolveAttempts?: number;
  retryDelayMs?: number;
};

export type DesktopInstallResolveResult =
  | { ok: true; executablePath: string; runningProcessId: number | null }
  | { ok: false; reason: DesktopBridgeUnavailableReason; detail: string };

type ResolveDiagnostics = {
  processQueryErrors: string[];
  installScanErrors: string[];
};

export class DesktopInstallResolver {
  private readonly platform: NodeJS.Platform;
  private readonly execFile: NonNullable<ResolverDependencies["execFile"]>;
  private readonly readdir: typeof readdirCallback;
  private readonly stat: typeof statCallback;
  private readonly sleep: NonNullable<ResolverDependencies["sleep"]>;
  private readonly maxResolveAttempts: number;
  private readonly retryDelayMs: number;
  private cachedExecutablePath: string | null = null;

  constructor(dependencies: ResolverDependencies = {}) {
    this.platform = dependencies.platform ?? process.platform;
    this.execFile = dependencies.execFile ?? execFileDefault;
    this.readdir = dependencies.readdir ?? readdirCallback;
    this.stat = dependencies.stat ?? statCallback;
    this.sleep = dependencies.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
    this.maxResolveAttempts = dependencies.maxResolveAttempts ?? 3;
    this.retryDelayMs = dependencies.retryDelayMs ?? 250;
  }

  async resolve(): Promise<DesktopInstallResolveResult> {
    if (this.platform !== "win32") {
      return {
        ok: false,
        reason: "unsupported_platform",
        detail: "当前平台暂不支持桌面窗口桥接发送"
      };
    }

    const diagnostics: ResolveDiagnostics = {
      processQueryErrors: [],
      installScanErrors: []
    };
    const maxAttempts = Math.max(1, this.maxResolveAttempts);
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      const resolved = await this.resolveOnce(diagnostics);
      if (resolved) {
        this.cachedExecutablePath = resolved.executablePath;
        return resolved;
      }

      if (attempt < maxAttempts - 1) {
        await this.sleep(this.retryDelayMs);
      }
    }

    if (this.cachedExecutablePath) {
      return {
        ok: true,
        executablePath: this.cachedExecutablePath,
        runningProcessId: null
      };
    }

    return {
      ok: false,
      reason: "desktop_not_installed",
      detail: desktopNotInstalledDetail(diagnostics)
    };
  }

  private async resolveOnce(
    diagnostics: ResolveDiagnostics
  ): Promise<Extract<DesktopInstallResolveResult, { ok: true }> | null> {
    const running = await this.findRunningCodexMainProcess(diagnostics);
    if (running) {
      return {
        ok: true,
        executablePath: running.executablePath,
        runningProcessId: running.processId
      };
    }

    const installed = await this.findInstalledCodexExecutable(diagnostics);
    if (installed) {
      return {
        ok: true,
        executablePath: installed,
        runningProcessId: null
      };
    }

    return null;
  }

  private async findRunningCodexMainProcess(diagnostics: ResolveDiagnostics): Promise<{
    executablePath: string;
    processId: number;
  } | null> {
    try {
      const { stdout } = await this.execFile(
        "powershell.exe",
        [
          "-NoProfile",
          "-Command",
          [
            "$items = Get-CimInstance Win32_Process -Filter \"Name = 'Codex.exe'\" |",
            "Where-Object { $_.ExecutablePath -like '*\\app\\Codex.exe' -and $_.CommandLine -notmatch '--type=' } |",
            "Select-Object ProcessId,ExecutablePath,CommandLine;",
            "$items | ConvertTo-Json -Compress"
          ].join(" ")
        ],
        { timeout: 3_000 }
      );
      const parsed = parseProcessJson(stdout);
      const match = parsed.find((item) => item.ExecutablePath && item.ProcessId);
      return match?.ExecutablePath && match.ProcessId
        ? { executablePath: match.ExecutablePath, processId: Number(match.ProcessId) }
        : null;
    } catch (error) {
      diagnostics.processQueryErrors.push(errorMessage(error));
      return null;
    }
  }

  private async findInstalledCodexExecutable(diagnostics: ResolveDiagnostics): Promise<string | null> {
    const windowsApps = "C:\\Program Files\\WindowsApps";
    try {
      const entries = await this.readdir(windowsApps);
      const candidates = entries
        .filter((entry) => entry.startsWith("OpenAI.Codex_"))
        .map((entry) => path.join(windowsApps, entry, "app", "Codex.exe"));
      const existing = await Promise.all(
        candidates.map(async (candidate) => {
          try {
            const stats = await this.stat(candidate);
            return { candidate, mtimeMs: stats.mtimeMs };
          } catch {
            return null;
          }
        })
      );
      const sorted = existing
        .filter((item): item is { candidate: string; mtimeMs: number } => item !== null)
        .sort((left, right) => right.mtimeMs - left.mtimeMs);
      return sorted[0]?.candidate ?? null;
    } catch (error) {
      diagnostics.installScanErrors.push(errorMessage(error));
      return null;
    }
  }
}

function desktopNotInstalledDetail(diagnostics: ResolveDiagnostics): string {
  const details = [
    lastDiagnostic("进程查询失败", diagnostics.processQueryErrors),
    lastDiagnostic("安装目录扫描失败", diagnostics.installScanErrors)
  ].filter((item): item is string => item !== null);

  if (details.length === 0) {
    return "未找到 Codex Desktop 安装路径";
  }

  return `未找到 Codex Desktop 安装路径（${details.join("；")}）`;
}

function lastDiagnostic(label: string, errors: string[]): string | null {
  const error = errors.at(-1);
  return error ? `${label}: ${error}` : null;
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function parseProcessJson(stdout: string): ProcessRecord[] {
  const trimmed = stdout.trim();
  if (!trimmed) {
    return [];
  }

  const parsed = JSON.parse(trimmed) as ProcessRecord | ProcessRecord[];
  return Array.isArray(parsed) ? parsed : [parsed];
}
