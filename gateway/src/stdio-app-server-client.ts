import { spawn, type ChildProcessWithoutNullStreams } from "node:child_process";

import type {
  CodexAppServerClient,
  CodexNotification,
  CodexThread,
  CodexUserInput
} from "./mobile-gateway-service";

type PendingRequest = {
  resolve: (value: any) => void;
  reject: (error: Error) => void;
};

type SpawnProcess = typeof spawn;

type StdioAppServerClientOptions = {
  command?: string;
  args?: string[];
  requestTimeoutMs?: number;
  spawnProcess?: SpawnProcess;
};

type ConnectionState = "connected" | "connecting" | "disconnected";

export class StdioCodexAppServerClient implements CodexAppServerClient {
  private static readonly DEFAULT_REQUEST_TIMEOUT_MS = 4_000;
  private process: ChildProcessWithoutNullStreams | null = null;
  private connectPromise: Promise<void> | null = null;
  private connectionState: ConnectionState = "disconnected";
  private requestId = 1;
  private codexHome = "";
  private stdoutBuffer = "";
  private readonly pendingRequests = new Map<number, PendingRequest>();
  private readonly listeners = new Set<(notification: CodexNotification) => void>();

  constructor(private readonly options: StdioAppServerClientOptions = {}) {}

  getConnectionState(): ConnectionState {
    return this.connectionState;
  }

  getCodexHome(): string {
    return this.codexHome;
  }

  subscribe(listener: (notification: CodexNotification) => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  async listThreads(): Promise<CodexThread[]> {
    const response = await this.request<{ data: CodexThread[] }>("thread/list", {
      limit: 50,
      sortDirection: "desc",
      archived: false
    });
    return response.data;
  }

  async listLoadedThreads(): Promise<string[]> {
    const loadedThreadIds: string[] = [];
    let cursor: string | null = null;

    do {
      const loadedResponse: { data: string[]; nextCursor: string | null } = await this.request(
        "thread/loaded/list",
        {
          cursor,
          limit: 100
        }
      );
      loadedThreadIds.push(...loadedResponse.data);
      cursor = loadedResponse.nextCursor;
    } while (cursor);

    return loadedThreadIds;
  }

  async readThread(threadId: string): Promise<CodexThread> {
    const response = await this.request<{ thread: CodexThread }>("thread/read", {
      threadId,
      includeTurns: true
    });
    return response.thread;
  }

  async resumeThread(threadId: string): Promise<void> {
    await this.request<unknown>("thread/resume", {
      threadId,
      persistExtendedHistory: false
    });
  }

  async startTurn(threadId: string, text: string): Promise<{ accepted: boolean; turnId: string }> {
    return this.startTurnWithInput(threadId, [
      {
        type: "text",
        text,
        text_elements: []
      }
    ]);
  }

  async startTurnWithInput(
    threadId: string,
    input: CodexUserInput[]
  ): Promise<{ accepted: boolean; turnId: string }> {
    const response = await this.request<{ turn: { id: string } }>("turn/start", {
      threadId,
      input
    });
    return {
      accepted: true,
      turnId: response.turn.id
    };
  }

  async steerTurnWithInput(
    threadId: string,
    input: CodexUserInput[],
    expectedTurnId: string
  ): Promise<{ accepted: boolean; turnId: string }> {
    const response = await this.request<{ turnId: string }>("turn/steer", {
      threadId,
      input,
      expectedTurnId
    });
    return {
      accepted: true,
      turnId: response.turnId
    };
  }

  async connect(): Promise<void> {
    await this.ensureConnected();
  }

  stop(): void {
    this.process?.kill();
    this.process = null;
    this.connectionState = "disconnected";
  }

  private async ensureConnected(): Promise<void> {
    if (this.connectionState === "connected" && this.process) {
      return;
    }

    if (this.connectPromise) {
      return this.connectPromise;
    }

    this.connectionState = "connecting";
    this.startProcess();
    this.connectPromise = this.request<{
      codexHome?: string;
    }>("initialize", {
      clientInfo: {
        name: "codex-mobile-gateway",
        version: "0.1.0"
      },
      capabilities: null
    }).then((result) => {
      this.codexHome = String(result.codexHome ?? "");
      this.connectionState = "connected";
    }).catch((error) => {
      this.connectionState = "disconnected";
      this.connectPromise = null;
      throw error;
    });

    return this.connectPromise;
  }

  private startProcess(): void {
    if (this.process) {
      return;
    }

    const spawnProcess = this.options.spawnProcess ?? spawn;
    const command = this.options.command ?? "codex";
    const args = this.options.args ?? [
      "app-server",
      "--analytics-default-enabled",
      "--listen",
      "stdio://"
    ];
    this.process = spawnProcess(command, args, {
      shell: process.platform === "win32",
      stdio: "pipe",
      windowsHide: true
    }) as ChildProcessWithoutNullStreams;

    this.process.stdout.on("data", (chunk) => this.handleStdout(String(chunk)));
    this.process.on("exit", () => this.handleExit());
    this.process.on("error", (error) => this.handleProcessError(error));
  }

  private async request<T>(method: string, params: Record<string, unknown>): Promise<T> {
    if (!this.process) {
      await this.ensureConnected();
    }

    if (!this.process) {
      throw new Error("app_server_not_started");
    }

    const id = this.requestId++;
    const timeoutMs = this.options.requestTimeoutMs ?? StdioCodexAppServerClient.DEFAULT_REQUEST_TIMEOUT_MS;
    return new Promise<T>((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pendingRequests.delete(id);
        reject(new Error(`app_server_request_timeout:${method}`));
      }, timeoutMs);
      this.pendingRequests.set(id, {
        resolve: (value) => {
          clearTimeout(timeout);
          resolve(value as T);
        },
        reject: (error) => {
          clearTimeout(timeout);
          reject(error);
        }
      });
      this.process?.stdin.write(JSON.stringify({ id, method, params }) + "\n");
    });
  }

  private handleStdout(chunk: string): void {
    this.stdoutBuffer += chunk;
    let newlineIndex = this.stdoutBuffer.indexOf("\n");
    while (newlineIndex >= 0) {
      const line = this.stdoutBuffer.slice(0, newlineIndex).trim();
      this.stdoutBuffer = this.stdoutBuffer.slice(newlineIndex + 1);
      if (line) {
        this.handleMessage(JSON.parse(line));
      }
      newlineIndex = this.stdoutBuffer.indexOf("\n");
    }
  }

  private handleMessage(payload: any): void {
    if (typeof payload.id === "number") {
      const pending = this.pendingRequests.get(payload.id);
      if (!pending) {
        return;
      }
      this.pendingRequests.delete(payload.id);
      if (payload.error) {
        pending.reject(new Error(JSON.stringify(payload.error)));
        return;
      }
      pending.resolve(payload.result);
      return;
    }

    if (typeof payload.method === "string") {
      for (const listener of this.listeners) {
        listener(payload as CodexNotification);
      }
    }
  }

  private handleExit(): void {
    this.process = null;
    this.connectionState = "disconnected";
    this.rejectPending(new Error("app_server_process_exited"));
  }

  private handleProcessError(error: Error): void {
    this.connectionState = "disconnected";
    this.rejectPending(error);
  }

  private rejectPending(error: Error): void {
    for (const pending of this.pendingRequests.values()) {
      pending.reject(error);
    }
    this.pendingRequests.clear();
  }
}
