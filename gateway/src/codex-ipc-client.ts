import crypto from "node:crypto";
import net from "node:net";
import os from "node:os";
import path from "node:path";
import type { Duplex } from "node:stream";

type IpcSocket = Duplex & {
  writable: boolean;
  destroy(error?: Error): void;
  end(): void;
};

type PendingResponse = {
  resolve: (value: unknown) => void;
  reject: (error: Error) => void;
  timeout: NodeJS.Timeout;
};

type CodexIpcClientOptions = {
  clientType?: string;
  connectSocket?: (pipePath: string) => IpcSocket;
  pipePath?: string;
  requestTimeoutMs?: number;
};

export type CodexIpcRequestOptions = {
  targetClientId?: string;
  timeoutMs?: number;
};

export type CodexIpcRequester = {
  request(method: string, params: unknown, options?: CodexIpcRequestOptions): Promise<unknown>;
  stop(): void;
};

const INITIALIZING_CLIENT_ID = "initializing-client";
const DEFAULT_REQUEST_TIMEOUT_MS = 5_000;
const MAX_FRAME_BYTES = 256 * 1024 * 1024;
const REQUEST_VERSIONS = new Map<string, number>([
  ["thread-follower-start-turn", 1],
  ["thread-follower-steer-turn", 1]
]);

export function defaultCodexIpcPath(platform = process.platform): string {
  if (platform === "win32") {
    return "\\\\.\\pipe\\codex-ipc";
  }
  const uid = process.getuid?.();
  return path.join(os.tmpdir(), "codex-ipc", uid ? `ipc-${uid}.sock` : "ipc.sock");
}

export class CodexIpcClient implements CodexIpcRequester {
  private readonly clientType: string;
  private readonly connectSocket: (pipePath: string) => IpcSocket;
  private readonly pipePath: string;
  private readonly requestTimeoutMs: number;
  private socket: IpcSocket | null = null;
  private connectPromise: Promise<void> | null = null;
  private clientId = INITIALIZING_CLIENT_ID;
  private receiveBuffer = Buffer.alloc(0);
  private nextFrameLength: number | null = null;
  private readonly pendingResponses = new Map<string, PendingResponse>();

  constructor(options: CodexIpcClientOptions = {}) {
    this.clientType = options.clientType ?? "gateway";
    this.connectSocket = options.connectSocket ?? ((pipePath) => net.createConnection(pipePath));
    this.pipePath = options.pipePath ?? defaultCodexIpcPath();
    this.requestTimeoutMs = options.requestTimeoutMs ?? DEFAULT_REQUEST_TIMEOUT_MS;
  }

  async request(
    method: string,
    params: unknown,
    options: CodexIpcRequestOptions = {}
  ): Promise<unknown> {
    await this.connect();
    if (this.clientId === INITIALIZING_CLIENT_ID) {
      throw new Error("ipc_not_initialized");
    }
    return this.sendRequestEnvelope({
      type: "request",
      requestId: crypto.randomUUID(),
      sourceClientId: this.clientId,
      version: REQUEST_VERSIONS.get(method) ?? 0,
      method,
      params,
      ...(options.targetClientId ? { targetClientId: options.targetClientId } : {})
    }, options.timeoutMs);
  }

  async connect(): Promise<void> {
    if (this.socket?.writable && this.clientId !== INITIALIZING_CLIENT_ID) {
      return;
    }
    if (this.connectPromise) {
      return this.connectPromise;
    }

    this.socket = this.connectSocket(this.pipePath);
    this.attachSocket(this.socket);
    const connectPromise = new Promise<void>((resolve, reject) => {
      const socket = this.socket;
      if (!socket) {
        reject(new Error("ipc_socket_missing"));
        return;
      }

      const cleanup = () => {
        socket.off("connect", handleConnect);
        socket.off("error", handleConnectError);
      };
      const handleConnect = () => {
        cleanup();
        void this.initialize().then(resolve, reject);
      };
      const handleConnectError = (error: Error) => {
        cleanup();
        this.disconnect(error);
        reject(error);
      };

      socket.once("connect", handleConnect);
      socket.once("error", handleConnectError);
    }).finally(() => {
      this.connectPromise = null;
    });
    this.connectPromise = connectPromise;
    return connectPromise;
  }

  stop(): void {
    this.disconnect(new Error("ipc_stopped"));
  }

  private async initialize(): Promise<void> {
    const result = await this.sendRequestEnvelope({
      type: "request",
      requestId: crypto.randomUUID(),
      method: "initialize",
      params: { clientType: this.clientType },
      version: 0
    });
    const clientId = readStringProperty(result, "clientId");
    if (!clientId) {
      throw new Error("ipc_initialize_missing_client_id");
    }
    this.clientId = clientId;
  }

  private attachSocket(socket: IpcSocket): void {
    socket.on("data", (chunk: Buffer) => this.handleData(Buffer.from(chunk)));
    socket.on("close", () => this.disconnect(new Error("ipc_connection_closed")));
    socket.on("end", () => this.disconnect(new Error("ipc_connection_ended")));
    socket.on("error", (error: Error) => this.disconnect(error));
  }

  private handleData(chunk: Buffer): void {
    this.receiveBuffer = Buffer.concat([this.receiveBuffer, chunk]);
    for (;;) {
      if (this.nextFrameLength == null) {
        if (this.receiveBuffer.length < 4) {
          return;
        }
        this.nextFrameLength = this.receiveBuffer.readUInt32LE(0);
        this.receiveBuffer = this.receiveBuffer.subarray(4);
        if (this.nextFrameLength > MAX_FRAME_BYTES) {
          this.disconnect(new Error(`ipc_frame_too_large:${this.nextFrameLength}`));
          return;
        }
      }
      if (this.receiveBuffer.length < this.nextFrameLength) {
        return;
      }
      const payload = this.receiveBuffer.subarray(0, this.nextFrameLength).toString("utf8");
      this.receiveBuffer = this.receiveBuffer.subarray(this.nextFrameLength);
      this.nextFrameLength = null;
      this.handleMessage(JSON.parse(payload));
    }
  }

  private handleMessage(message: any): void {
    if (message?.type === "response") {
      const pending = this.pendingResponses.get(String(message.requestId));
      if (!pending) {
        return;
      }
      clearTimeout(pending.timeout);
      this.pendingResponses.delete(String(message.requestId));
      if (message.resultType === "error") {
        pending.reject(new Error(String(message.error ?? "ipc_request_failed")));
        return;
      }
      pending.resolve(message.result);
      return;
    }

    if (message?.type === "client-discovery-request") {
      this.writeFrame({
        type: "client-discovery-response",
        requestId: message.requestId,
        response: { canHandle: false }
      });
      return;
    }

    if (message?.type === "request") {
      this.writeFrame({
        type: "response",
        requestId: message.requestId,
        resultType: "error",
        error: "no-handler-for-request"
      });
    }
  }

  private sendRequestEnvelope(message: Record<string, unknown>, timeoutMs = this.requestTimeoutMs): Promise<unknown> {
    const requestId = String(message.requestId);
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        this.pendingResponses.delete(requestId);
        reject(new Error(`ipc_request_timeout:${String(message.method)}`));
      }, timeoutMs);
      this.pendingResponses.set(requestId, { resolve, reject, timeout });
      try {
        this.writeFrame(message);
      } catch (error) {
        clearTimeout(timeout);
        this.pendingResponses.delete(requestId);
        reject(error instanceof Error ? error : new Error(String(error)));
      }
    });
  }

  private writeFrame(message: unknown): void {
    const socket = this.socket;
    if (!socket?.writable) {
      throw new Error("ipc_not_connected");
    }
    socket.write(encodeFrame(message));
  }

  private disconnect(error: Error): void {
    const socket = this.socket;
    this.socket = null;
    this.clientId = INITIALIZING_CLIENT_ID;
    this.receiveBuffer = Buffer.alloc(0);
    this.nextFrameLength = null;
    for (const [requestId, pending] of this.pendingResponses.entries()) {
      clearTimeout(pending.timeout);
      pending.reject(error);
      this.pendingResponses.delete(requestId);
    }
    if (socket && !socket.destroyed) {
      socket.destroy();
    }
  }
}

function encodeFrame(message: unknown): Buffer {
  const json = JSON.stringify(message);
  const length = Buffer.byteLength(json);
  const frame = Buffer.alloc(4 + length);
  frame.writeUInt32LE(length, 0);
  frame.write(json, 4);
  return frame;
}

function readStringProperty(value: unknown, key: string): string | null {
  if (!value || typeof value !== "object") {
    return null;
  }
  const property = (value as Record<string, unknown>)[key];
  return typeof property === "string" && property.length > 0 ? property : null;
}
