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

type AppServerClientOptions = {
  wsUrl: string;
  requestTimeoutMs?: number;
};

type ConnectionState = "connected" | "connecting" | "disconnected";

export class JsonRpcCodexAppServerClient implements CodexAppServerClient {
  private static readonly DEFAULT_REQUEST_TIMEOUT_MS = 4_000;
  private socket: WebSocket | null = null;
  private connectPromise: Promise<void> | null = null;
  private connectionState: ConnectionState = "disconnected";
  private requestId = 1;
  private codexHome = "";
  private readonly pendingRequests = new Map<number, PendingRequest>();
  private readonly listeners = new Set<(notification: CodexNotification) => void>();

  constructor(private readonly options: AppServerClientOptions) {}

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

  private async ensureConnected(): Promise<void> {
    if (this.connectionState === "connected" && this.socket) {
      return;
    }

    if (this.connectPromise) {
      return this.connectPromise;
    }

    this.connectionState = "connecting";
    this.connectPromise = new Promise<void>((resolve, reject) => {
      const socket = new WebSocket(this.options.wsUrl);
      this.socket = socket;

      socket.addEventListener("open", () => {
        if (this.socket !== socket || socket.readyState !== WebSocket.OPEN) {
          return;
        }

        socket.send(
          JSON.stringify({
            id: 1,
            method: "initialize",
            params: {
              clientInfo: {
                name: "codex-mobile-gateway",
                version: "0.1.0"
              },
              capabilities: null
            }
          })
        );
      });

      socket.addEventListener("message", (event) => {
        if (this.socket !== socket) {
          return;
        }

        const payload = JSON.parse(String(event.data));

        if (payload.id === 1 && payload.result) {
          this.codexHome = String(payload.result.codexHome ?? "");
          this.connectionState = "connected";
          this.connectPromise = null;
          resolve();
          return;
        }

        if (typeof payload.id === "number") {
          const pending = this.pendingRequests.get(payload.id);
          if (!pending) {
            return;
          }

          this.pendingRequests.delete(payload.id);
          if (payload.error) {
            pending.reject(new Error(String(payload.error.message ?? "app_server_error")));
            return;
          }

          pending.resolve(payload.result);
          return;
        }

        if (payload.method) {
          this.emitNotification(payload as CodexNotification);
        }
      });

      socket.addEventListener("close", () => {
        if (this.socket !== socket) {
          return;
        }

        const shouldRejectConnect = this.connectionState === "connecting" && this.connectPromise !== null;
        this.connectionState = "disconnected";
        this.socket = null;
        this.rejectPendingRequests(new Error("app_server_closed"));
        if (this.connectPromise) {
          this.connectPromise = null;
          if (shouldRejectConnect) {
            reject(new Error("app_server_closed"));
          }
        }
      });

      socket.addEventListener("error", () => {
        if (this.socket !== socket) {
          return;
        }

        this.connectionState = "disconnected";
      });
    });

    return this.connectPromise;
  }

  private async request<T>(method: string, params: unknown, attempt = 0): Promise<T> {
    try {
      await this.ensureConnected();

      const id = ++this.requestId;
      const payload = { id, method, params };
      return await new Promise<T>((resolve, reject) => {
        const timeout = setTimeout(() => {
          this.pendingRequests.delete(id);
          this.forceReconnect();
          reject(new Error(`app_server_request_timeout:${method}`));
        }, this.options.requestTimeoutMs ?? JsonRpcCodexAppServerClient.DEFAULT_REQUEST_TIMEOUT_MS);

        this.pendingRequests.set(id, {
          resolve: (value) => {
            clearTimeout(timeout);
            resolve(value);
          },
          reject: (error) => {
            clearTimeout(timeout);
            reject(error);
          }
        });

        this.sendRaw(payload);
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      if (
        attempt === 0 &&
        (message === "app_server_closed" ||
          message === "app_server_not_connected" ||
          message.startsWith("app_server_request_timeout:"))
      ) {
        this.forceReconnect();
        return this.request<T>(method, params, attempt + 1);
      }

      throw error;
    }
  }

  private sendRaw(payload: unknown): void {
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) {
      throw new Error("app_server_not_connected");
    }

    this.socket.send(JSON.stringify(payload));
  }

  private forceReconnect(): void {
    const socket = this.socket;
    this.socket = null;
    this.connectionState = "disconnected";
    this.connectPromise = null;
    socket?.close();
  }

  private rejectPendingRequests(error: Error): void {
    for (const pending of this.pendingRequests.values()) {
      pending.reject(error);
    }
    this.pendingRequests.clear();
  }

  private emitNotification(notification: CodexNotification): void {
    for (const listener of this.listeners) {
      listener(notification);
    }
  }
}
