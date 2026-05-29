import { spawn, type ChildProcess } from "node:child_process";
import { createServer } from "node:net";

type SidecarManagerOptions = {
  port?: number;
};

async function findOpenPort(): Promise<number> {
  return new Promise<number>((resolve, reject) => {
    const server = createServer();
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (!address || typeof address === "string") {
        server.close();
        reject(new Error("unable_to_resolve_port"));
        return;
      }

      const { port } = address;
      server.close(() => resolve(port));
    });
    server.on("error", reject);
  });
}

export class CodexSidecarManager {
  private process: ChildProcess | null = null;
  private port: number | null = null;

  constructor(private readonly options: SidecarManagerOptions = {}) {}

  async ensureStarted(): Promise<{ wsUrl: string; readyUrl: string }> {
    if (this.port && (await this.isReady())) {
      return {
        wsUrl: `ws://127.0.0.1:${this.port}/ws`,
        readyUrl: `http://127.0.0.1:${this.port}/readyz`
      };
    }

    this.port = this.options.port ?? (await findOpenPort());
    const listenUrl = `ws://127.0.0.1:${this.port}`;
    const command = "codex";

    this.process = spawn(command, ["app-server", "--analytics-default-enabled", "--listen", listenUrl], {
      shell: process.platform === "win32",
      stdio: "ignore",
      windowsHide: true
    });

    await this.waitUntilReady();

    return {
      wsUrl: `ws://127.0.0.1:${this.port}/ws`,
      readyUrl: `http://127.0.0.1:${this.port}/readyz`
    };
  }

  async isReady(): Promise<boolean> {
    if (!this.port) {
      return false;
    }

    try {
      const response = await fetch(`http://127.0.0.1:${this.port}/readyz`);
      return response.ok;
    } catch {
      return false;
    }
  }

  stop(): void {
    this.process?.kill();
    this.process = null;
  }

  private async waitUntilReady(): Promise<void> {
    const startedAt = Date.now();
    while (Date.now() - startedAt < 15_000) {
      if (await this.isReady()) {
        return;
      }

      await new Promise((resolve) => setTimeout(resolve, 250));
    }

    throw new Error("sidecar_start_timeout");
  }
}
