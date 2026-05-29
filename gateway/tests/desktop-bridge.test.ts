import { describe, expect, it, vi } from "vitest";

import { DefaultDesktopBridge } from "../src/desktop-bridge";
import { createCodexThreadDeepLink, DesktopDeepLinkNavigator } from "../src/desktop-deep-link-navigator";
import { DesktopInstallResolver } from "../src/desktop-install-resolver";

describe("desktop bridge helpers", () => {
  it("creates the Codex Desktop thread deep link", () => {
    expect(createCodexThreadDeepLink("019db9d0-a5d8-7253-ae74-543f12413806"))
      .toBe("codex://threads/019db9d0-a5d8-7253-ae74-543f12413806");
  });

  it("resolves running Codex main process paths before installed app paths", async () => {
    const resolver = new DesktopInstallResolver({
      platform: "win32",
      execFile: vi.fn(async () => ({
        stdout: JSON.stringify([
          {
            ProcessId: 22056,
            ExecutablePath: "C:\\Program Files\\WindowsApps\\OpenAI.Codex_26.417.5275.0_x64__2p2nqsd0c76g0\\app\\Codex.exe",
            CommandLine:
              "\"C:\\Program Files\\WindowsApps\\OpenAI.Codex_26.417.5275.0_x64__2p2nqsd0c76g0\\app\\Codex.exe\""
          }
        ])
      })),
      readdir: vi.fn(),
      stat: vi.fn()
    });

    await expect(resolver.resolve()).resolves.toEqual({
      ok: true,
      executablePath:
        "C:\\Program Files\\WindowsApps\\OpenAI.Codex_26.417.5275.0_x64__2p2nqsd0c76g0\\app\\Codex.exe",
      runningProcessId: 22056
    });
  });

  it("uses a Windows path filter that matches the main Codex executable", async () => {
    const resolver = new DesktopInstallResolver({
      platform: "win32",
      execFile: vi.fn(async (_file, args) => {
        const command = args[2] ?? "";
        if (!command.includes("-like '*\\app\\Codex.exe'")) {
          return { stdout: "[]" };
        }

        return {
          stdout: JSON.stringify([
            {
              ProcessId: 22056,
              ExecutablePath:
                "C:\\Program Files\\WindowsApps\\OpenAI.Codex_26.417.5275.0_x64__2p2nqsd0c76g0\\app\\Codex.exe",
              CommandLine:
                "\"C:\\Program Files\\WindowsApps\\OpenAI.Codex_26.417.5275.0_x64__2p2nqsd0c76g0\\app\\Codex.exe\""
            }
          ])
        };
      }),
      readdir: vi.fn(async () => []),
      stat: vi.fn()
    });

    await expect(resolver.resolve()).resolves.toEqual({
      ok: true,
      executablePath:
        "C:\\Program Files\\WindowsApps\\OpenAI.Codex_26.417.5275.0_x64__2p2nqsd0c76g0\\app\\Codex.exe",
      runningProcessId: 22056
    });
  });

  it("retries transient desktop resolve misses before reporting not installed", async () => {
    const execFile = vi.fn()
      .mockResolvedValueOnce({ stdout: "[]" })
      .mockResolvedValueOnce({
        stdout: JSON.stringify([
          {
            ProcessId: 22056,
            ExecutablePath:
              "C:\\Program Files\\WindowsApps\\OpenAI.Codex_26.417.5275.0_x64__2p2nqsd0c76g0\\app\\Codex.exe",
            CommandLine:
              "\"C:\\Program Files\\WindowsApps\\OpenAI.Codex_26.417.5275.0_x64__2p2nqsd0c76g0\\app\\Codex.exe\""
          }
        ])
      });
    const sleep = vi.fn(async () => undefined);
    const resolver = new DesktopInstallResolver({
      platform: "win32",
      execFile,
      readdir: vi.fn(async () => {
        throw new Error("WindowsApps busy");
      }),
      stat: vi.fn(),
      sleep,
      retryDelayMs: 1
    });

    await expect(resolver.resolve()).resolves.toEqual({
      ok: true,
      executablePath:
        "C:\\Program Files\\WindowsApps\\OpenAI.Codex_26.417.5275.0_x64__2p2nqsd0c76g0\\app\\Codex.exe",
      runningProcessId: 22056
    });
    expect(execFile).toHaveBeenCalledTimes(2);
    expect(sleep).toHaveBeenCalledTimes(1);
  });

  it("uses the last resolved executable path when transient lookups fail", async () => {
    const executablePath =
      "C:\\Program Files\\WindowsApps\\OpenAI.Codex_26.417.5275.0_x64__2p2nqsd0c76g0\\app\\Codex.exe";
    const resolver = new DesktopInstallResolver({
      platform: "win32",
      execFile: vi.fn()
        .mockResolvedValueOnce({
          stdout: JSON.stringify([
            {
              ProcessId: 22056,
              ExecutablePath: executablePath,
              CommandLine: `"${executablePath}"`
            }
          ])
        })
        .mockRejectedValueOnce(new Error("CIM unavailable")),
      readdir: vi.fn(async () => {
        throw new Error("WindowsApps unavailable");
      }),
      stat: vi.fn(),
      maxResolveAttempts: 1,
      retryDelayMs: 1,
      sleep: vi.fn(async () => undefined)
    });

    await expect(resolver.resolve()).resolves.toEqual({
      ok: true,
      executablePath,
      runningProcessId: 22056
    });
    await expect(resolver.resolve()).resolves.toEqual({
      ok: true,
      executablePath,
      runningProcessId: null
    });
  });

  it("includes resolver diagnostics when no desktop install can be found", async () => {
    const resolver = new DesktopInstallResolver({
      platform: "win32",
      execFile: vi.fn(async () => {
        throw new Error("CIM timeout");
      }),
      readdir: vi.fn(async () => {
        throw new Error("WindowsApps denied");
      }),
      stat: vi.fn(),
      maxResolveAttempts: 1,
      retryDelayMs: 1,
      sleep: vi.fn(async () => undefined)
    });

    await expect(resolver.resolve()).resolves.toEqual({
      ok: false,
      reason: "desktop_not_installed",
      detail:
        "未找到 Codex Desktop 安装路径（进程查询失败: CIM timeout；安装目录扫描失败: WindowsApps denied）"
    });
  });

  it("launches the deep link with Codex.exe", async () => {
    const execFile = vi.fn(async () => ({ stdout: "" }));
    const navigator = new DesktopDeepLinkNavigator({ execFile });

    await expect(
      navigator.openThread({
        executablePath: "C:\\Codex\\Codex.exe",
        threadId: "thread-1"
      })
    ).resolves.toEqual({ ok: true });

    expect(execFile).toHaveBeenCalledWith(
      "C:\\Codex\\Codex.exe",
      ["codex://threads/thread-1"],
      expect.objectContaining({ timeout: 3_000 })
    );
  });

  it("returns navigation failure when deep link process fails", async () => {
    const navigator = new DesktopDeepLinkNavigator({
      execFile: vi.fn(async () => {
        throw new Error("launch failed");
      })
    });

    await expect(
      navigator.openThread({
        executablePath: "C:\\Codex\\Codex.exe",
        threadId: "thread-1"
      })
    ).resolves.toEqual({
      ok: false,
      reason: "desktop_navigation_failed",
      detail: "无法通过 codex:// 打开桌面线程: launch failed；系统协议唤起也失败: launch failed"
    });
  });

  it("falls back to the system protocol launcher when Codex.exe deep link invocation fails", async () => {
    const execFile = vi.fn()
      .mockRejectedValueOnce(new Error("direct launch failed"))
      .mockResolvedValueOnce({ stdout: "" });
    const navigator = new DesktopDeepLinkNavigator({ execFile });

    await expect(
      navigator.openThread({
        executablePath: "C:\\Codex\\Codex.exe",
        threadId: "thread-1"
      })
    ).resolves.toEqual({ ok: true });

    expect(execFile).toHaveBeenNthCalledWith(
      1,
      "C:\\Codex\\Codex.exe",
      ["codex://threads/thread-1"],
      expect.objectContaining({ timeout: 3_000, windowsHide: true })
    );
    expect(execFile).toHaveBeenNthCalledWith(
      2,
      "cmd.exe",
      ["/d", "/s", "/c", "start", "", "codex://threads/thread-1"],
      expect.objectContaining({ timeout: 3_000, windowsHide: true })
    );
  });

  it("composes resolver navigator and injector", async () => {
    const injector = {
      sendText: vi.fn(async () => ({ ok: true as const })),
      sendImage: vi.fn(async () => ({ ok: true as const })),
      sendImages: vi.fn(async () => ({ ok: true as const })),
      sendFiles: vi.fn(async () => ({ ok: true as const }))
    };
    const bridge = new DefaultDesktopBridge({
      resolver: {
        resolve: vi.fn(async () => ({
          ok: true as const,
          executablePath: "C:\\Codex\\Codex.exe",
          runningProcessId: 22056
        }))
      },
      navigator: {
        openThread: vi.fn(async () => ({ ok: true as const }))
      },
      injector,
      sleep: async () => undefined,
      navigationDelayMs: 1
    });

    await expect(
      bridge.sendTextToDesktopThread({
        threadId: "thread-1",
        title: "Thread",
        text: "hello",
        clientMessageId: "client-1"
      })
    ).resolves.toEqual({
      ok: true,
      confirmation: "keystrokes_sent"
    });
    expect(injector.sendText).toHaveBeenCalledWith({
      text: "hello",
      runningProcessId: 22056,
      guide: false
    });
  });

  it("waits longer after opening a desktop thread before injecting text by default", async () => {
    const injector = {
      sendText: vi.fn(async () => ({ ok: true as const })),
      sendImage: vi.fn(async () => ({ ok: true as const })),
      sendImages: vi.fn(async () => ({ ok: true as const })),
      sendFiles: vi.fn(async () => ({ ok: true as const }))
    };
    const sleep = vi.fn(async () => undefined);
    const bridge = new DefaultDesktopBridge({
      resolver: {
        resolve: vi.fn(async () => ({
          ok: true as const,
          executablePath: "C:\\Codex\\Codex.exe",
          runningProcessId: 22056
        }))
      },
      navigator: {
        openThread: vi.fn(async () => ({ ok: true as const }))
      },
      injector,
      sleep
    });

    await bridge.sendTextToDesktopThread({
      threadId: "thread-1",
      title: "Thread",
      text: "hello",
      clientMessageId: "client-1"
    });

    expect(sleep).toHaveBeenCalledWith(2_500);
    expect(injector.sendText).toHaveBeenCalledTimes(1);
  });

  it("passes guide text sends to the injector", async () => {
    const injector = {
      sendText: vi.fn(async () => ({ ok: true as const })),
      sendImage: vi.fn(async () => ({ ok: true as const })),
      sendImages: vi.fn(async () => ({ ok: true as const })),
      sendFiles: vi.fn(async () => ({ ok: true as const }))
    };
    const bridge = new DefaultDesktopBridge({
      resolver: {
        resolve: vi.fn(async () => ({
          ok: true as const,
          executablePath: "C:\\Codex\\Codex.exe",
          runningProcessId: 22056
        }))
      },
      navigator: {
        openThread: vi.fn(async () => ({ ok: true as const }))
      },
      injector,
      sleep: async () => undefined,
      navigationDelayMs: 1
    });

    await expect(
      bridge.sendTextToDesktopThread({
        threadId: "thread-1",
        title: "Thread",
        text: "guide",
        clientMessageId: "client-guide",
        guide: true
      } as any)
    ).resolves.toEqual({
      ok: true,
      confirmation: "keystrokes_sent"
    });
    expect(injector.sendText).toHaveBeenCalledWith({
      text: "guide",
      runningProcessId: 22056,
      guide: true
    });
  });

  it("composes resolver navigator and injector for image sends", async () => {
    const bridge = new DefaultDesktopBridge({
      resolver: {
        resolve: vi.fn(async () => ({
          ok: true as const,
          executablePath: "C:\\Codex\\Codex.exe",
          runningProcessId: 22056
        }))
      },
      navigator: {
        openThread: vi.fn(async () => ({ ok: true as const }))
      },
      injector: {
        sendText: vi.fn(async () => ({ ok: true as const })),
        sendImage: vi.fn(async () => ({ ok: true as const })),
        sendImages: vi.fn(async () => ({ ok: true as const })),
        sendFiles: vi.fn(async () => ({ ok: true as const }))
      },
      sleep: async () => undefined,
      navigationDelayMs: 1
    });

    await expect(
      bridge.sendImageToDesktopThread({
        threadId: "thread-1",
        title: "Thread",
        localImagePath: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png",
        text: "请帮我分析这张图",
        clientMessageId: "client-image-1"
      })
    ).resolves.toEqual({
      ok: true,
      confirmation: "keystrokes_sent"
    });
  });

  it("composes resolver navigator and injector for batch image sends", async () => {
    const injector = {
      sendText: vi.fn(async () => ({ ok: true as const })),
      sendImage: vi.fn(async () => ({ ok: true as const })),
      sendImages: vi.fn(async () => ({ ok: true as const })),
      sendFiles: vi.fn(async () => ({ ok: true as const }))
    };
    const bridge = new DefaultDesktopBridge({
      resolver: {
        resolve: vi.fn(async () => ({
          ok: true as const,
          executablePath: "C:\\Codex\\Codex.exe",
          runningProcessId: 22056
        }))
      },
      navigator: {
        openThread: vi.fn(async () => ({ ok: true as const }))
      },
      injector,
      sleep: async () => undefined,
      navigationDelayMs: 1
    });

    await expect(
      bridge.sendImagesToDesktopThread({
        threadId: "thread-1",
        title: "Thread",
        localImagePaths: [
          "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\first.png",
          "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\second.jpg"
        ],
        text: "请帮我分析这两张图",
        clientMessageId: "client-image-batch-1"
      })
    ).resolves.toEqual({
      ok: true,
      confirmation: "keystrokes_sent"
    });
    expect(injector.sendImages).toHaveBeenCalledWith({
      imagePaths: [
        "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\first.png",
        "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\second.jpg"
      ],
      text: "请帮我分析这两张图",
      runningProcessId: 22056
    });
  });
});
