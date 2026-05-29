import { execFile as execFileCallback } from "node:child_process";
import { promisify } from "node:util";

import type { DesktopBridgeUnavailableReason } from "./desktop-bridge";

const execFileDefault = promisify(execFileCallback);

type DeepLinkNavigatorDependencies = {
  execFile?: (
    file: string,
    args: string[],
    options?: { timeout?: number; windowsHide?: boolean }
  ) => Promise<{ stdout: string }>;
};

export type DesktopNavigationResult =
  | { ok: true }
  | { ok: false; reason: DesktopBridgeUnavailableReason; detail: string };

export function createCodexThreadDeepLink(threadId: string): string {
  return `codex://threads/${encodeURIComponent(threadId)}`;
}

export class DesktopDeepLinkNavigator {
  private readonly execFile: NonNullable<DeepLinkNavigatorDependencies["execFile"]>;

  constructor(dependencies: DeepLinkNavigatorDependencies = {}) {
    this.execFile = dependencies.execFile ?? execFileDefault;
  }

  async openThread(options: {
    executablePath: string;
    threadId: string;
  }): Promise<DesktopNavigationResult> {
    const deepLink = createCodexThreadDeepLink(options.threadId);

    try {
      await this.execFile(
        options.executablePath,
        [deepLink],
        { timeout: 3_000, windowsHide: true }
      );
      return { ok: true };
    }

    catch (directError) {
      try {
        await this.execFile(
          "cmd.exe",
          ["/d", "/s", "/c", "start", "", deepLink],
          { timeout: 3_000, windowsHide: true }
        );
        return { ok: true };
      } catch (protocolError) {
        const directMessage = errorMessage(directError);
        const protocolMessage = errorMessage(protocolError);
        return {
          ok: false,
          reason: "desktop_navigation_failed",
          detail:
            "无法通过 codex:// 打开桌面线程: "
            + `${directMessage}；系统协议唤起也失败: ${protocolMessage}`
        };
      }
    }
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
