import { afterEach, describe, expect, it } from "vitest";
import { WebSocketServer, type WebSocket } from "ws";

import { JsonRpcCodexAppServerClient } from "../src/app-server-client";

type JsonRpcMessage = {
  id?: number;
  method?: string;
  params?: Record<string, unknown>;
};

function waitForResult<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
  return Promise.race([
    promise,
    new Promise<T>((_, reject) => {
      setTimeout(() => reject(new Error("test_timeout")), timeoutMs);
    })
  ]);
}

function replyInitialized(socket: WebSocket, id: number): void {
  socket.send(
    JSON.stringify({
      id,
      result: {
        codexHome: "C:\\Users\\devuser\\.codex"
      }
    })
  );
}

describe("JsonRpcCodexAppServerClient", () => {
  let server: WebSocketServer | null = null;

  afterEach(async () => {
    await new Promise<void>((resolve, reject) => {
      if (!server) {
        resolve();
        return;
      }

      for (const client of server.clients) {
        client.terminate();
      }

      server.close((error) => {
        if (error) {
          reject(error);
          return;
        }

        resolve();
      });
      server = null;
    });
  });

  it("reconnects and retries thread/list when the current socket stops replying", async () => {
    let connectionCount = 0;
    server = new WebSocketServer({ port: 0 });
    await new Promise<void>((resolve) => {
      server!.once("listening", () => resolve());
    });

    server.on("connection", (socket) => {
      connectionCount += 1;
      const connectionIndex = connectionCount;

      socket.on("message", (raw) => {
        const message = JSON.parse(String(raw)) as JsonRpcMessage;
        if (message.method === "initialize" && typeof message.id === "number") {
          replyInitialized(socket, message.id);
          return;
        }

        if (message.method === "thread/list" && typeof message.id === "number") {
          if (connectionIndex === 1) {
            return;
          }

          socket.send(
            JSON.stringify({
              id: message.id,
              result: {
                data: [
                  {
                    id: "thread-1",
                    preview: "开发 Codex 控制App",
                    cwd: "D:\\projects\\codex-mobile-control",
                    createdAt: 1776853407,
                    updatedAt: 1776857697,
                    name: "开发 Codex 控制App",
                    status: { type: "idle" },
                    turns: []
                  }
                ],
                nextCursor: null,
                backwardsCursor: null
              }
            })
          );
        }
      });
    });

    const address = server.address();
    if (!address || typeof address === "string") {
      throw new Error("missing_test_server_port");
    }

    const client = new JsonRpcCodexAppServerClient({
      wsUrl: `ws://127.0.0.1:${address.port}/ws`,
      requestTimeoutMs: 50
    });

    await expect(waitForResult(client.listThreads(), 500)).resolves.toEqual([
      expect.objectContaining({
        id: "thread-1",
        name: "开发 Codex 控制App"
      })
    ]);
    expect(connectionCount).toBe(2);
  });

  it("sends structured localImage input through turn/start", async () => {
    let capturedParams: Record<string, unknown> | null = null;
    server = new WebSocketServer({ port: 0 });
    await new Promise<void>((resolve) => {
      server!.once("listening", () => resolve());
    });

    server.on("connection", (socket) => {
      socket.on("message", (raw) => {
        const message = JSON.parse(String(raw)) as JsonRpcMessage;
        if (message.method === "initialize" && typeof message.id === "number") {
          replyInitialized(socket, message.id);
          return;
        }

        if (message.method === "turn/start" && typeof message.id === "number") {
          capturedParams = message.params ?? null;
          socket.send(
            JSON.stringify({
              id: message.id,
              result: {
                turn: { id: "turn-42" }
              }
            })
          );
        }
      });
    });

    const address = server.address();
    if (!address || typeof address === "string") {
      throw new Error("missing_test_server_port");
    }

    const client = new JsonRpcCodexAppServerClient({
      wsUrl: `ws://127.0.0.1:${address.port}/ws`,
      requestTimeoutMs: 200
    });

    await expect(
      client.startTurnWithInput("thread-1", [
        {
          type: "localImage",
          path: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\test.png"
        },
        {
          type: "text",
          text: "看一下这张图",
          text_elements: []
        }
      ])
    ).resolves.toEqual({
      accepted: true,
      turnId: "turn-42"
    });

    expect(capturedParams).toEqual({
      threadId: "thread-1",
      input: [
        {
          type: "localImage",
          path: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\test.png"
        },
        {
          type: "text",
          text: "看一下这张图",
          text_elements: []
        }
      ]
    });
  });

  it("sends guide input through turn/steer with expected active turn id", async () => {
    let capturedParams: Record<string, unknown> | null = null;
    server = new WebSocketServer({ port: 0 });
    await new Promise<void>((resolve) => {
      server!.once("listening", () => resolve());
    });

    server.on("connection", (socket) => {
      socket.on("message", (raw) => {
        const message = JSON.parse(String(raw)) as JsonRpcMessage;
        if (message.method === "initialize" && typeof message.id === "number") {
          replyInitialized(socket, message.id);
          return;
        }

        if (message.method === "turn/steer" && typeof message.id === "number") {
          capturedParams = message.params ?? null;
          socket.send(
            JSON.stringify({
              id: message.id,
              result: {
                turnId: "turn-active"
              }
            })
          );
        }
      });
    });

    const address = server.address();
    if (!address || typeof address === "string") {
      throw new Error("missing_test_server_port");
    }

    const client = new JsonRpcCodexAppServerClient({
      wsUrl: `ws://127.0.0.1:${address.port}/ws`,
      requestTimeoutMs: 200
    });

    await expect(
      client.steerTurnWithInput(
        "thread-1",
        [{ type: "text", text: "补充上下文", text_elements: [] }],
        "turn-active"
      )
    ).resolves.toEqual({
      accepted: true,
      turnId: "turn-active"
    });

    expect(capturedParams).toEqual({
      threadId: "thread-1",
      input: [{ type: "text", text: "补充上下文", text_elements: [] }],
      expectedTurnId: "turn-active"
    });
  });
});
