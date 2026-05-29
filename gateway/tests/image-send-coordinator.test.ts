import { describe, expect, it, vi } from "vitest";

import { ImageSendCoordinator } from "../src/image-send-coordinator";
import type { DesktopBridge } from "../src/desktop-bridge";

function createBridge(overrides: Partial<DesktopBridge> = {}): DesktopBridge {
  return {
    sendTextToDesktopThread: vi.fn(async () => ({
      ok: true as const,
      confirmation: "observed" as const
    })),
    sendImageToDesktopThread: vi.fn(async () => ({
      ok: true as const,
      confirmation: "keystrokes_sent" as const
    })),
    sendImagesToDesktopThread: vi.fn(async () => ({
      ok: true as const,
      confirmation: "keystrokes_sent" as const
    })),
    sendFilesToDesktopThread: vi.fn(async () => ({
      ok: true as const,
      confirmation: "keystrokes_sent" as const
    })),
    ...overrides
  };
}

describe("ImageSendCoordinator", () => {
  it("returns after the desktop bridge confirms the image send action without waiting for observation", async () => {
    const bridge = createBridge();
    const readThread = vi.fn(async () => {
      throw new Error("readThread should not be called");
    });
    const coordinator = new ImageSendCoordinator({
      bridge,
      readThread
    });

    const response = await coordinator.send({
      threadId: "thread-1",
      title: "Thread",
      clientMessageId: "client-1",
      localImagePath: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png",
      text: "请帮我分析这张图"
    });

    expect(response).toEqual({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-1",
      sendPath: "desktop_bridge",
      confirmation: "keystrokes_sent"
    });
    expect(bridge.sendImageToDesktopThread).toHaveBeenCalledWith({
      threadId: "thread-1",
      title: "Thread",
      clientMessageId: "client-1",
      localImagePath: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png",
      text: "请帮我分析这张图"
    });
    expect(readThread).not.toHaveBeenCalled();
  });

  it("sends multiple images through one desktop bridge action", async () => {
    const bridge = createBridge({
      sendImagesToDesktopThread: vi.fn(async () => ({
        ok: true as const,
        confirmation: "keystrokes_sent" as const
      }))
    });
    const coordinator = new ImageSendCoordinator({
      bridge,
      readThread: vi.fn(async () => {
        throw new Error("readThread should not be called");
      })
    });

    const response = await (coordinator as any).sendMany({
      threadId: "thread-1",
      title: "Thread",
      clientMessageId: "client-batch-1",
      localImagePaths: [
        "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\first.png",
        "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\second.jpg"
      ],
      text: "请帮我分析这两张图"
    });

    expect(response).toEqual({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-batch-1",
      sendPath: "desktop_bridge",
      confirmation: "keystrokes_sent"
    });
    expect(bridge.sendImagesToDesktopThread).toHaveBeenCalledWith({
      threadId: "thread-1",
      title: "Thread",
      clientMessageId: "client-batch-1",
      localImagePaths: [
        "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\first.png",
        "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\second.jpg"
      ],
      text: "请帮我分析这两张图"
    });
  });

  it("throws a structured gateway error when image desktop bridge cannot send", async () => {
    const coordinator = new ImageSendCoordinator({
      bridge: createBridge({
        sendImageToDesktopThread: vi.fn(async () => ({
          ok: false as const,
          reason: "desktop_window_not_found" as const,
          detail: "未找到正在运行的 Codex Desktop 窗口"
        }))
      }),
      readThread: vi.fn(async () => ({ updatedAt: 100, turns: [] })),
      now: () => 1_000,
      sleep: async () => undefined,
      confirmationTimeoutMs: 2_000,
      confirmationPollMs: 10
    });

    await expect(
      coordinator.send({
        threadId: "thread-1",
        title: "Thread",
        clientMessageId: "client-1",
        localImagePath: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png",
        text: "请帮我分析这张图"
      })
    ).rejects.toMatchObject({
      statusCode: 409,
      body: {
        error: "desktop_bridge_unavailable",
        reason: "desktop_window_not_found",
        message: "未找到正在运行的 Codex Desktop 窗口"
      }
    });
  });
});
