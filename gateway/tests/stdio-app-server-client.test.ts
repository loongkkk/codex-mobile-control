import { EventEmitter } from "node:events";
import { PassThrough, Writable } from "node:stream";

import { describe, expect, it, vi } from "vitest";

import { StdioCodexAppServerClient } from "../src/stdio-app-server-client";

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

function createFakeAppServerProcess() {
  const writes: string[] = [];
  const child = Object.assign(new EventEmitter(), {
    stdin: new Writable({
      write(chunk, _encoding, callback) {
        writes.push(String(chunk));
        callback();
      }
    }),
    stdout: new PassThrough(),
    stderr: new PassThrough(),
    kill: vi.fn()
  });

  return { child, writes };
}

describe("StdioCodexAppServerClient", () => {
  it("starts the official-style stdio app-server and initializes over JSON lines", async () => {
    const { child, writes } = createFakeAppServerProcess();
    const spawnProcess = vi.fn(() => child as any);
    const client = new StdioCodexAppServerClient({ spawnProcess });

    const connectPromise = client.connect();

    await waitForAssertion(() => expect(writes).toHaveLength(1));
    expect(spawnProcess).toHaveBeenCalledWith(
      "codex",
      ["app-server", "--analytics-default-enabled", "--listen", "stdio://"],
      expect.objectContaining({
        stdio: "pipe",
        windowsHide: true
      })
    );
    expect(JSON.parse(writes[0])).toMatchObject({
      id: 1,
      method: "initialize",
      params: {
        clientInfo: {
          name: "codex-mobile-gateway"
        }
      }
    });

    child.stdout.write(
      `${JSON.stringify({
        id: 1,
        result: {
          codexHome: "C:\\Users\\devuser\\.codex"
        }
      })}\n`
    );

    await connectPromise;
    expect(client.getConnectionState()).toBe("connected");
    expect(client.getCodexHome()).toBe("C:\\Users\\devuser\\.codex");

    const listPromise = client.listThreads();
    await waitForAssertion(() => expect(writes).toHaveLength(2));
    expect(JSON.parse(writes[1])).toMatchObject({
      id: 2,
      method: "thread/list"
    });
    child.stdout.write(
      `${JSON.stringify({
        id: 2,
        result: {
          data: []
        }
      })}\n`
    );
    await expect(listPromise).resolves.toEqual([]);

    client.stop();
    expect(child.kill).toHaveBeenCalled();
  });
});
