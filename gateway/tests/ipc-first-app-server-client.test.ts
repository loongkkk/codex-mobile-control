import { describe, expect, it, vi } from "vitest";

import { IpcFirstCodexAppServerClient } from "../src/ipc-first-app-server-client";
import type {
  CodexAppServerClient,
  CodexNotification,
  CodexThread,
  CodexUserInput
} from "../src/mobile-gateway-service";

function createDelegate(): CodexAppServerClient {
  return {
    getConnectionState: vi.fn((): "connected" => "connected"),
    getCodexHome: vi.fn(() => "C:\\Users\\devuser\\.codex"),
    listThreads: vi.fn(async () => [] as CodexThread[]),
    listLoadedThreads: vi.fn(async () => []),
    readThread: vi.fn(async () => ({ id: "thread-1" }) as CodexThread),
    resumeThread: vi.fn(async () => undefined),
    startTurn: vi.fn(async () => ({ accepted: true, turnId: "delegate-turn" })),
    startTurnWithInput: vi.fn(async () => ({ accepted: true, turnId: "delegate-turn" })),
    steerTurnWithInput: vi.fn(async () => ({ accepted: true, turnId: "delegate-steer" })),
    subscribe: vi.fn((_listener: (notification: CodexNotification) => void) => () => undefined)
  };
}

const input: CodexUserInput[] = [
  {
    type: "text",
    text: "手机发送",
    text_elements: []
  }
];

describe("IpcFirstCodexAppServerClient", () => {
  it("sends new turns through the current official IPC owner without resuming the fallback app-server", async () => {
    const delegate = createDelegate();
    const logger = {
      info: vi.fn(),
      warn: vi.fn()
    };
    const ipc = {
      request: vi.fn(async () => ({ result: { turn: { id: "ipc-turn" } } })),
      stop: vi.fn()
    };
    const client = new IpcFirstCodexAppServerClient(delegate, ipc, { logger });

    await client.resumeThread("thread-1");
    const result = await client.startTurnWithInput("thread-1", input);

    expect(result).toEqual({ accepted: true, turnId: "ipc-turn" });
    expect(ipc.request).toHaveBeenCalledWith(
      "thread-follower-start-turn",
      {
        conversationId: "thread-1",
        turnStartParams: { input }
      },
      expect.any(Object)
    );
    expect(delegate.resumeThread).not.toHaveBeenCalled();
    expect(delegate.startTurnWithInput).not.toHaveBeenCalled();
    expect(logger.info).toHaveBeenCalledWith(
      "[codex-ipc-send] start turn accepted",
      expect.objectContaining({ threadId: "thread-1", turnId: "ipc-turn" })
    );
  });

  it("falls back to the app-server path when no IPC owner can handle the thread", async () => {
    const delegate = createDelegate();
    const logger = {
      info: vi.fn(),
      warn: vi.fn()
    };
    const ipc = {
      request: vi.fn(async () => {
        throw new Error("no-client-found");
      }),
      stop: vi.fn()
    };
    const client = new IpcFirstCodexAppServerClient(delegate, ipc, { logger });

    await client.resumeThread("thread-1");
    const result = await client.startTurnWithInput("thread-1", input);

    expect(result).toEqual({ accepted: true, turnId: "delegate-turn" });
    expect(delegate.resumeThread).toHaveBeenCalledWith("thread-1");
    expect(delegate.startTurnWithInput).toHaveBeenCalledWith("thread-1", input);
    expect(logger.warn).toHaveBeenCalledWith(
      "[codex-ipc-send] start turn failed; falling back to app-server",
      expect.objectContaining({ threadId: "thread-1", error: "no-client-found" })
    );
  });

  it("sends guide messages through official IPC steer requests", async () => {
    const delegate = createDelegate();
    const logger = {
      info: vi.fn(),
      warn: vi.fn()
    };
    const ipc = {
      request: vi.fn(async () => ({ result: { turnId: "ipc-steer" } })),
      stop: vi.fn()
    };
    const client = new IpcFirstCodexAppServerClient(delegate, ipc, { logger });

    await client.resumeThread("thread-1");
    const result = await client.steerTurnWithInput("thread-1", input, "active-turn");

    expect(result).toEqual({ accepted: true, turnId: "ipc-steer" });
    expect(ipc.request).toHaveBeenCalledWith(
      "thread-follower-steer-turn",
      {
        conversationId: "thread-1",
        input,
        restoreMessage: null,
        attachments: []
      },
      expect.any(Object)
    );
    expect(delegate.resumeThread).not.toHaveBeenCalled();
    expect(delegate.steerTurnWithInput).not.toHaveBeenCalled();
    expect(logger.info).toHaveBeenCalledWith(
      "[codex-ipc-send] steer turn accepted",
      expect.objectContaining({ threadId: "thread-1", turnId: "ipc-steer" })
    );
  });
});
