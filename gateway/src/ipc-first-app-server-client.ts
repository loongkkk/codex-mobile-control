import { CodexIpcClient, type CodexIpcRequester } from "./codex-ipc-client";
import type {
  CodexAppServerClient,
  CodexNotification,
  CodexThread,
  CodexUserInput
} from "./mobile-gateway-service";

type IpcFirstOptions = {
  requestTimeoutMs?: number;
  logger?: Pick<Console, "info" | "warn">;
};

export class IpcFirstCodexAppServerClient implements CodexAppServerClient {
  private readonly requestTimeoutMs: number;
  private readonly logger: Pick<Console, "info" | "warn">;

  constructor(
    private readonly delegate: CodexAppServerClient,
    private readonly ipc: CodexIpcRequester = new CodexIpcClient(),
    options: IpcFirstOptions = {}
  ) {
    this.requestTimeoutMs = options.requestTimeoutMs ?? 5_000;
    this.logger = options.logger ?? console;
  }

  getConnectionState(): "connected" | "connecting" | "disconnected" {
    return this.delegate.getConnectionState();
  }

  getCodexHome(): string {
    return this.delegate.getCodexHome();
  }

  listThreads(): Promise<CodexThread[]> {
    return this.delegate.listThreads();
  }

  listLoadedThreads(): Promise<string[]> {
    return this.delegate.listLoadedThreads();
  }

  readThread(threadId: string): Promise<CodexThread> {
    return this.delegate.readThread(threadId);
  }

  async resumeThread(_threadId: string): Promise<void> {
    // Sending through codex-ipc should let the active Desktop/VSCode owner keep ownership.
    // If IPC cannot handle the send, fallback paths resume the delegate before sending.
  }

  startTurn(threadId: string, text: string): Promise<{ accepted: boolean; turnId: string }> {
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
    try {
      const result = await this.ipc.request(
        "thread-follower-start-turn",
        {
          conversationId: threadId,
          turnStartParams: { input }
        },
        { timeoutMs: this.requestTimeoutMs }
      );
      const turnId = extractTurnId(result);
      if (!turnId) {
        throw new Error("ipc_start_turn_missing_turn_id");
      }
      this.logger.info("[codex-ipc-send] start turn accepted", { threadId, turnId });
      return { accepted: true, turnId };
    } catch (error) {
      this.logger.warn("[codex-ipc-send] start turn failed; falling back to app-server", {
        threadId,
        error: errorMessage(error)
      });
      await this.delegate.resumeThread(threadId);
      return this.delegate.startTurnWithInput(threadId, input);
    }
  }

  async steerTurnWithInput(
    threadId: string,
    input: CodexUserInput[],
    expectedTurnId: string
  ): Promise<{ accepted: boolean; turnId: string }> {
    try {
      const result = await this.ipc.request(
        "thread-follower-steer-turn",
        {
          conversationId: threadId,
          input,
          restoreMessage: null,
          attachments: []
        },
        { timeoutMs: this.requestTimeoutMs }
      );
      const turnId = extractTurnId(result) ?? expectedTurnId;
      this.logger.info("[codex-ipc-send] steer turn accepted", { threadId, turnId });
      return { accepted: true, turnId };
    } catch (error) {
      this.logger.warn("[codex-ipc-send] steer turn failed; falling back to app-server", {
        threadId,
        error: errorMessage(error)
      });
      await this.delegate.resumeThread(threadId);
      return this.delegate.steerTurnWithInput(threadId, input, expectedTurnId);
    }
  }

  subscribe(listener: (notification: CodexNotification) => void): () => void {
    return this.delegate.subscribe(listener);
  }

  stop(): void {
    this.ipc.stop();
    const maybeStoppableDelegate = this.delegate as CodexAppServerClient & { stop?: () => void };
    maybeStoppableDelegate.stop?.();
  }
}

function extractTurnId(value: unknown): string | null {
  if (!value || typeof value !== "object") {
    return null;
  }
  const record = value as Record<string, unknown>;
  const turnId = record.turnId;
  if (typeof turnId === "string" && turnId.length > 0) {
    return turnId;
  }
  const turn = record.turn;
  if (turn && typeof turn === "object") {
    const id = (turn as Record<string, unknown>).id;
    if (typeof id === "string" && id.length > 0) {
      return id;
    }
  }
  return extractTurnId(record.result);
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
