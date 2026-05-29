import { Duplex } from "node:stream";

import { describe, expect, it } from "vitest";

import { CodexIpcClient } from "../src/codex-ipc-client";

class FakeIpcSocket extends Duplex {
  readonly writes: Buffer[] = [];

  _read(): void {
    // Test pushes frames explicitly.
  }

  _write(chunk: Buffer, _encoding: BufferEncoding, callback: (error?: Error | null) => void): void {
    this.writes.push(Buffer.from(chunk));
    callback();
  }

  emitConnect(): void {
    this.emit("connect");
  }

  sendFrame(message: unknown): void {
    this.push(encodeFrame(message));
  }
}

function encodeFrame(message: unknown): Buffer {
  const json = JSON.stringify(message);
  const frame = Buffer.alloc(4 + Buffer.byteLength(json));
  frame.writeUInt32LE(Buffer.byteLength(json), 0);
  frame.write(json, 4);
  return frame;
}

function decodeFrame(frame: Buffer): any {
  const length = frame.readUInt32LE(0);
  return JSON.parse(frame.subarray(4, 4 + length).toString("utf8"));
}

async function waitForAssertion(assertion: () => void | Promise<void>, timeoutMs = 600): Promise<void> {
  const start = Date.now();
  let lastError: unknown;
  while (Date.now() - start < timeoutMs) {
    try {
      await assertion();
      return;
    } catch (error) {
      lastError = error;
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
  }
  throw lastError;
}

describe("CodexIpcClient", () => {
  it("initializes on codex-ipc and sends versioned follower requests", async () => {
    const socket = new FakeIpcSocket();
    const client = new CodexIpcClient({
      connectSocket: () => socket,
      requestTimeoutMs: 500
    });

    const requestPromise = client.request("thread-follower-start-turn", {
      conversationId: "thread-1",
      turnStartParams: {
        input: [{ type: "text", text: "hello", text_elements: [] }]
      }
    });

    socket.emitConnect();

    await waitForAssertion(() => expect(socket.writes).toHaveLength(1));
    const initialize = decodeFrame(socket.writes[0]);
    expect(initialize).toMatchObject({
      type: "request",
      method: "initialize",
      version: 0,
      params: { clientType: "gateway" }
    });

    socket.sendFrame({
      type: "response",
      requestId: initialize.requestId,
      resultType: "success",
      method: "initialize",
      result: { clientId: "gateway-client-id" }
    });

    await waitForAssertion(() => expect(socket.writes).toHaveLength(2));
    const request = decodeFrame(socket.writes[1]);
    expect(request).toMatchObject({
      type: "request",
      method: "thread-follower-start-turn",
      sourceClientId: "gateway-client-id",
      version: 1,
      params: {
        conversationId: "thread-1",
        turnStartParams: {
          input: [{ type: "text", text: "hello", text_elements: [] }]
        }
      }
    });

    socket.sendFrame({
      type: "response",
      requestId: request.requestId,
      resultType: "success",
      method: "thread-follower-start-turn",
      result: { result: { turn: { id: "turn-ipc" } } }
    });

    await expect(requestPromise).resolves.toEqual({ result: { turn: { id: "turn-ipc" } } });
    client.stop();
  });
});
