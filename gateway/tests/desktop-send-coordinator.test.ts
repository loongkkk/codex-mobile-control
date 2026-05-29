import { describe, expect, it, vi } from "vitest";

import { DesktopSendCoordinator } from "../src/desktop-send-coordinator";
import type { DesktopBridge } from "../src/desktop-bridge";

function createBridge(overrides: Partial<DesktopBridge> = {}): DesktopBridge {
  return {
    sendTextToDesktopThread: vi.fn(async () => ({
      ok: true as const,
      confirmation: "keystrokes_sent" as const
    })),
    sendImageToDesktopThread: vi.fn(async () => ({
      ok: true as const,
      confirmation: "observed" as const
    })),
    sendImagesToDesktopThread: vi.fn(async () => ({
      ok: true as const,
      confirmation: "observed" as const
    })),
    sendFilesToDesktopThread: vi.fn(async () => ({
      ok: true as const,
      confirmation: "observed" as const
    })),
    ...overrides
  };
}

describe("DesktopSendCoordinator", () => {
  it("returns after the desktop bridge confirms the send action without waiting for app-server observation", async () => {
    const bridge = createBridge();
    const readThread = vi.fn(async () => {
      throw new Error("readThread should not be called");
    });
    const coordinator = new DesktopSendCoordinator({
      bridge,
      readThread
    });

    const response = await coordinator.send({
      threadId: "thread-1",
      title: "Thread",
      text: "hello",
      clientMessageId: "client-1"
    });

    expect(response).toEqual({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-1",
      sendPath: "desktop_bridge",
      confirmation: "keystrokes_sent"
    });
    expect(bridge.sendTextToDesktopThread).toHaveBeenCalledWith({
      threadId: "thread-1",
      title: "Thread",
      text: "hello",
      clientMessageId: "client-1"
    });
    expect(readThread).not.toHaveBeenCalled();
  });

  it("throws a structured gateway error when desktop bridge cannot send", async () => {
    const coordinator = new DesktopSendCoordinator({
      bridge: createBridge({
        sendTextToDesktopThread: vi.fn(async () => ({
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
        text: "hello",
        clientMessageId: "client-1"
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
