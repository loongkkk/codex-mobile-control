import { afterEach, describe, expect, it, vi } from "vitest";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import type { SendMessageResponse } from "../../shared/src/api";
import { DesktopSendCoordinator } from "../src/desktop-send-coordinator";
import { GatewayHttpError } from "../src/gateway-error";
import { MobileGatewayRuntimeService } from "../src/mobile-gateway-service";
import type {
  CodexAppServerClient,
  CodexNotification,
  CodexThread,
  LogRepository,
  LogSignal,
  StateRepository,
  ThreadMetadataRecord
} from "../src/mobile-gateway-service";

const baseThread: CodexThread = {
  id: "thread-1",
  preview: "我有个需求，就是写一个手机APP去控制整个codex桌面版",
  cwd: "D:\\projects\\codex-mobile-control",
  updatedAt: 1776857697,
  createdAt: 1776853407,
  name: null,
  status: { type: "notLoaded" },
  turns: []
};

function normalizedAbsolutePath(filePath: string): string {
  return path.resolve(filePath).replace(/^\\\\\?\\/, "").replace(/\\/g, "/");
}

function createAppServerStub(overrides: Partial<CodexAppServerClient> = {}): CodexAppServerClient {
  const detailThread: CodexThread = {
    ...baseThread,
    turns: [
      {
        id: "turn-1",
        status: "completed",
        startedAt: 1776857697,
        completedAt: 1776857699,
        error: null,
        items: [
          {
            type: "userMessage",
            id: "item-user-1",
            content: [{ type: "text", text: "请继续实现", text_elements: [] }]
          },
          {
            type: "plan",
            id: "item-plan-1",
            text: "先实现 Mobile Gateway"
          },
          {
            type: "agentMessage",
            id: "item-agent-1",
            text: "我正在接 Codex sidecar 和 SQLite。",
            phase: null,
            memoryCitation: null
          },
          {
            type: "commandExecution",
            id: "item-cmd-1",
            command: "npm test --workspace @codex-mobile/gateway",
            cwd: "D:\\projects\\codex-mobile-control",
            processId: null,
            source: "agent",
            status: "completed",
            commandActions: [],
            aggregatedOutput: "5 passed",
            exitCode: 0,
            durationMs: 1200
          }
        ],
        durationMs: 2000
      }
    ]
  };

  return {
    getConnectionState: vi.fn(() => "connected" as const),
    getCodexHome: vi.fn(() => "C:\\Users\\devuser\\.codex"),
    listThreads: vi.fn(async () => [baseThread]),
    listLoadedThreads: vi.fn(async () => []),
    readThread: vi.fn(async () => detailThread),
    resumeThread: vi.fn(async (_threadId: string) => undefined),
    startTurn: vi.fn(async (_threadId: string, _text: string) => ({
      accepted: true,
      turnId: "turn-2"
    })),
    startTurnWithInput: vi.fn(async (_threadId: string, _input) => ({
      accepted: true,
      turnId: "turn-2"
    })),
    steerTurnWithInput: vi.fn(async (_threadId: string, _input, expectedTurnId: string) => ({
      accepted: true,
      turnId: expectedTurnId
    })),
    subscribe: vi.fn(() => () => undefined),
    ...overrides
  };
}

function createStateRepositoryStub(
  records: ThreadMetadataRecord[] = [
    {
      threadId: "thread-1",
      title: "开发 Codex 控制App",
      cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
      updatedAt: 1776857697,
      archived: false,
      firstUserMessage: "我有个需求，就是写一个手机APP去控制整个codex桌面版"
    }
  ]
): StateRepository {
  return {
    getThreadMetadata: vi.fn(async () => records)
  };
}

function createLogRepositoryStub(signals: LogSignal[] = []): LogRepository {
  return {
    listRecentSignals: vi.fn(async () => signals),
    pollSignals: vi.fn(async () => ({
      signals: [],
      nextCursor: null
    }))
  };
}

function createDesktopCoordinatorStub(
  response: SendMessageResponse = {
    accepted: true,
    threadId: "thread-1",
    clientMessageId: "client-1",
    sendPath: "desktop_bridge" as const,
    confirmation: "observed" as const
  }
): DesktopSendCoordinator {
  return {
    send: vi.fn(async () => response)
  } as unknown as DesktopSendCoordinator;
}

function waitForResult<T>(promise: Promise<T>, timeoutMs: number): Promise<T> {
  return Promise.race([
    promise,
    new Promise<T>((_resolve, reject) => {
      setTimeout(() => reject(new Error("test_timeout")), timeoutMs);
    })
  ]);
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

describe("MobileGatewayRuntimeService", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("does not read sqlite log signals when mapping app-server threads", async () => {
    const logRepository = createLogRepositoryStub([
      {
        signalId: "log-1",
        threadId: "thread-1",
        status: "waiting_input",
        text: "线程正在等待新的输入",
        timestamp: "2026-04-22T11:00:00.000Z",
        cursor: "11",
        notificationEligible: true
      }
    ]);
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository
    });

    const threads = await service.listThreads();

    expect(logRepository.listRecentSignals).not.toHaveBeenCalled();
    expect(threads).toEqual([
      {
        threadId: "thread-1",
        title: "开发 Codex 控制App",
        cwd: "D:\\projects\\codex-mobile-control",
        status: "idle",
        updatedAt: "2026-04-22T11:34:57.000Z",
        progressSummary: "我有个需求，就是写一个手机APP去控制整个codex桌面版",
        needsAttention: false
      }
    ]);
  });

  it("does not poll sqlite log signals for socket status events", async () => {
    vi.useFakeTimers();
    const logRepository = createLogRepositoryStub();
    vi.mocked(logRepository.pollSignals).mockImplementation(async () => {
      return {
        signals: [
          {
            signalId: "strong-wait",
            threadId: "thread-1",
            status: "waiting_input",
            text: "线程正在等待新的输入",
            timestamp: "2026-04-22T11:01:00.000Z",
            cursor: "strong-wait",
            notificationEligible: true
          }
        ],
        nextCursor: "strong-wait"
      };
    });
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository
    });
    const emitted: Array<{ status?: string; eventId?: string }> = [];
    const unsubscribe = service.subscribe((event) => {
      if ("status" in event) {
        emitted.push({ status: event.status, eventId: event.eventId });
      }
    });

    try {
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();
      await vi.advanceTimersByTimeAsync(1600);

      expect(logRepository.listRecentSignals).not.toHaveBeenCalled();
      expect(logRepository.pollSignals).not.toHaveBeenCalled();
      expect(emitted).toEqual([]);
    } finally {
      unsubscribe();
    }
  });

  it("returns a desktop-visible state snapshot without waiting for slow app-server thread list", async () => {
    const listThreads = vi.fn(() => new Promise<CodexThread[]>(() => undefined));
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({ listThreads }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "desktop-thread",
          title: "Codex 安卓 App 2.0",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777206905,
          archived: false,
          firstUserMessage: "继续优化手机端",
          source: "vscode"
        },
        {
          threadId: "cli-thread",
          title: "你好",
          cwd: "\\\\?\\D:\\codex",
          updatedAt: 1777205351,
          archived: false,
          firstUserMessage: "你好",
          source: "cli"
        },
        {
          threadId: "worker-thread",
          title: "实现 SSE 客户端与仓储",
          cwd: "D:\\projects\\codex-mobile-control",
          updatedAt: 1776909273,
          archived: false,
          firstUserMessage: "你是 Task 3 implementer",
          source: "{\"subagent\":true}",
          agentRole: "worker"
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    const threads = await waitForResult(service.listThreads(), 100);

    expect(threads.map((item) => item.threadId)).toEqual(["desktop-thread"]);
    expect(threads[0]).toMatchObject({
      title: "Codex 安卓 App 2.0",
      cwd: "D:\\projects\\codex-mobile-control",
      status: "idle",
      progressSummary: "继续优化手机端"
    });
    expect(listThreads).toHaveBeenCalledOnce();
  });

  it("keeps newer state snapshot threads when a stale app-server list refreshes immediately", async () => {
    const staleAppServerThread: CodexThread = {
      ...baseThread,
      id: "stale-thread",
      name: "旧线程",
      preview: "旧 app-server 列表",
      updatedAt: 1776857697
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [staleAppServerThread])
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "fresh-state-thread",
          title: "手机刚发送的线程",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1779360760,
          archived: false,
          firstUserMessage: "手机端新消息已经写入 state",
          source: "vscode"
        },
        {
          threadId: "stale-thread",
          title: "旧线程",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1776857697,
          archived: false,
          firstUserMessage: "旧 app-server 列表",
          source: "vscode"
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    const threads = await service.listThreads();

    expect(threads.map((item) => item.threadId)).toEqual([
      "fresh-state-thread",
      "stale-thread"
    ]);
  });

  it("reads markdown previews from the selected thread cwd", async () => {
    const projectRoot = fs.mkdtempSync(path.join(os.tmpdir(), "codex-md-preview-"));
    const docsDir = path.join(projectRoot, "docs");
    fs.mkdirSync(docsDir, { recursive: true });
    const content = "# 预览文档\n\n正文";
    fs.writeFileSync(path.join(docsDir, "notes.md"), content, "utf8");
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "开发 Codex 控制App",
          cwd: projectRoot,
          updatedAt: 1776857697,
          archived: false,
          firstUserMessage: "测试 Markdown 预览"
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const preview = await service.getMarkdownFilePreview("thread-1", "docs/notes.md");

      expect(preview).toEqual({
        fileName: "notes.md",
        path: normalizedAbsolutePath(path.join(docsDir, "notes.md")),
        content,
        sizeBytes: Buffer.byteLength(content, "utf8")
      });
    } finally {
      fs.rmSync(projectRoot, { recursive: true, force: true });
    }
  });

  it("finds a unique markdown preview by basename inside the selected thread cwd", async () => {
    const projectRoot = fs.mkdtempSync(path.join(os.tmpdir(), "codex-md-basename-"));
    const specsDir = path.join(projectRoot, "docs", "superpowers", "specs");
    fs.mkdirSync(specsDir, { recursive: true });
    const content = "# 深层文档";
    fs.writeFileSync(path.join(specsDir, "design.md"), content, "utf8");
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "开发 Codex 控制App",
          cwd: projectRoot,
          updatedAt: 1776857697,
          archived: false,
          firstUserMessage: "测试 Markdown 预览"
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const preview = await service.getMarkdownFilePreview("thread-1", "design.md");

      expect(preview).toEqual({
        fileName: "design.md",
        path: normalizedAbsolutePath(path.join(specsDir, "design.md")),
        content,
        sizeBytes: Buffer.byteLength(content, "utf8")
      });
    } finally {
      fs.rmSync(projectRoot, { recursive: true, force: true });
    }
  });

  it("strips editor line suffix when reading markdown previews", async () => {
    const projectRoot = fs.mkdtempSync(path.join(os.tmpdir(), "codex-md-line-suffix-"));
    const docsDir = path.join(projectRoot, "docs");
    fs.mkdirSync(docsDir, { recursive: true });
    const content = "# 生产调度器设计";
    fs.writeFileSync(path.join(docsDir, "scheduler.md"), content, "utf8");
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "BOSS 生产调度",
          cwd: projectRoot,
          updatedAt: 1776857697,
          archived: false,
          firstUserMessage: "测试 Markdown 行号链接"
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const preview = await service.getMarkdownFilePreview("thread-1", "docs/scheduler.md:1");

      expect(preview).toEqual({
        fileName: "scheduler.md",
        path: normalizedAbsolutePath(path.join(docsDir, "scheduler.md")),
        content,
        sizeBytes: Buffer.byteLength(content, "utf8")
      });
    } finally {
      fs.rmSync(projectRoot, { recursive: true, force: true });
    }
  });

  it("reads json previews from the selected thread cwd", async () => {
    const projectRoot = fs.mkdtempSync(path.join(os.tmpdir(), "codex-json-preview-"));
    const artifactsDir = path.join(projectRoot, "artifacts");
    fs.mkdirSync(artifactsDir, { recursive: true });
    const content = "{\"ok\":true}";
    fs.writeFileSync(path.join(artifactsDir, "sample.json"), content, "utf8");
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "开发 Codex 控制App",
          cwd: projectRoot,
          updatedAt: 1776857697,
          archived: false,
          firstUserMessage: "测试 JSON 预览"
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const preview = await service.getMarkdownFilePreview("thread-1", "sample.json");

      expect(preview).toEqual({
        fileName: "sample.json",
        path: normalizedAbsolutePath(path.join(artifactsDir, "sample.json")),
        content,
        sizeBytes: Buffer.byteLength(content, "utf8")
      });
    } finally {
      fs.rmSync(projectRoot, { recursive: true, force: true });
    }
  });

  it("allows larger json previews from export artifacts", async () => {
    const projectRoot = fs.mkdtempSync(path.join(os.tmpdir(), "codex-large-json-preview-"));
    const artifactsDir = path.join(projectRoot, "artifacts", "exports");
    fs.mkdirSync(artifactsDir, { recursive: true });
    const records = Array.from({ length: 11000 }, (_, index) => ({
      id: index,
      title: `Job ${index}`,
      company: `Company ${index}`,
      description: "large json export preview row"
    }));
    const content = JSON.stringify(records);
    fs.writeFileSync(path.join(artifactsDir, "jobs.json"), content, "utf8");
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "BOSS 采集",
          cwd: projectRoot,
          updatedAt: 1776857697,
          archived: false,
          firstUserMessage: "测试大 JSON 预览"
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      expect(Buffer.byteLength(content, "utf8")).toBeGreaterThan(512 * 1024);

      const preview = await service.getMarkdownFilePreview("thread-1", "artifacts/exports/jobs.json");

      expect(preview.fileName).toBe("jobs.json");
      expect(preview.path).toBe(normalizedAbsolutePath(path.join(artifactsDir, "jobs.json")));
      expect(preview.content).toBe(content);
      expect(preview.sizeBytes).toBe(Buffer.byteLength(content, "utf8"));
    } finally {
      fs.rmSync(projectRoot, { recursive: true, force: true });
    }
  });

  it("rejects markdown previews outside the selected thread cwd", async () => {
    const projectRoot = fs.mkdtempSync(path.join(os.tmpdir(), "codex-md-project-"));
    const outsideRoot = fs.mkdtempSync(path.join(os.tmpdir(), "codex-md-outside-"));
    fs.writeFileSync(path.join(outsideRoot, "secret.md"), "# secret", "utf8");
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "开发 Codex 控制App",
          cwd: projectRoot,
          updatedAt: 1776857697,
          archived: false,
          firstUserMessage: "测试 Markdown 预览"
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      await expect(
        service.getMarkdownFilePreview("thread-1", path.join(outsideRoot, "secret.md"))
      ).rejects.toMatchObject({
        statusCode: 404
      } satisfies Partial<GatewayHttpError>);
    } finally {
      fs.rmSync(projectRoot, { recursive: true, force: true });
      fs.rmSync(outsideRoot, { recursive: true, force: true });
    }
  });

  it("rejects unsupported preview paths", async () => {
    const projectRoot = fs.mkdtempSync(path.join(os.tmpdir(), "codex-md-extension-"));
    fs.writeFileSync(path.join(projectRoot, "notes.csv"), "not supported", "utf8");
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "开发 Codex 控制App",
          cwd: projectRoot,
          updatedAt: 1776857697,
          archived: false,
          firstUserMessage: "测试 Markdown 预览"
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      await expect(service.getMarkdownFilePreview("thread-1", "notes.csv")).rejects.toMatchObject({
        statusCode: 400
      } satisfies Partial<GatewayHttpError>);
    } finally {
      fs.rmSync(projectRoot, { recursive: true, force: true });
    }
  });

  it("marks state snapshot threads as pinned from codex global state", async () => {
    const codexHome = fs.mkdtempSync(path.join(os.tmpdir(), "codex-home-"));
    fs.writeFileSync(
      path.join(codexHome, ".codex-global-state.json"),
      JSON.stringify({ "pinned-thread-ids": ["thread-1"] })
    );
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        getCodexHome: vi.fn(() => codexHome)
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });

    const threads = await service.listThreads();

    expect(threads[0]?.threadId).toBe("thread-1");
    expect(threads[0]?.isPinned).toBe(true);
  });

  it("shows active heartbeat automation summary on completed target threads", async () => {
    const codexHome = fs.mkdtempSync(path.join(os.tmpdir(), "codex-home-"));
    const automationsDir = path.join(codexHome, "automations", "token-revenue");
    const rolloutPath = path.join(codexHome, "rollout-thread-1.jsonl");
    fs.mkdirSync(automationsDir, { recursive: true });
    fs.writeFileSync(
      path.join(automationsDir, "automation.toml"),
      [
        "version = 1",
        'id = "token-revenue"',
        'kind = "heartbeat"',
        'name = "Token revenue demos and research"',
        'status = "ACTIVE"',
        'rrule = "FREQ=MINUTELY;INTERVAL=15;COUNT=32"',
        'target_thread_id = "thread-1"'
      ].join("\n"),
      "utf8"
    );
    fs.writeFileSync(
      rolloutPath,
      JSON.stringify({
        timestamp: "2026-05-12T01:32:24.535Z",
        type: "event_msg",
        payload: {
          type: "task_complete",
          turn_id: "turn-automation"
        }
      }),
      "utf8"
    );
    const listThreads = vi.fn(() => new Promise<CodexThread[]>(() => undefined));
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        getCodexHome: vi.fn(() => codexHome),
        listThreads
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "Chrome",
          cwd: "\\\\?\\D:\\code\\chrome_bot",
          updatedAt: 1778549544,
          archived: false,
          firstUserMessage: "继续八小时任务",
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const threads = await service.listThreads();
      expect(threads[0]).toMatchObject({
        threadId: "thread-1",
        status: "completed",
        progressSummary: "定时任务已开启 · 每15分钟",
        automationActive: true,
        automationSummary: "定时任务已开启 · 每15分钟"
      });
    } finally {
      fs.rmSync(codexHome, { recursive: true, force: true });
    }
  });

  it("lists paused and active automations with target thread titles", async () => {
    const codexHome = fs.mkdtempSync(path.join(os.tmpdir(), "codex-home-"));
    const activeDir = path.join(codexHome, "automations", "active-research");
    const pausedDir = path.join(codexHome, "automations", "paused-research");
    fs.mkdirSync(activeDir, { recursive: true });
    fs.mkdirSync(pausedDir, { recursive: true });
    fs.writeFileSync(
      path.join(activeDir, "automation.toml"),
      [
        "version = 1",
        'id = "active-research"',
        'kind = "heartbeat"',
        'name = "Active research"',
        'status = "ACTIVE"',
        'rrule = "FREQ=MINUTELY;INTERVAL=15;COUNT=32"',
        'target_thread_id = "thread-1"'
      ].join("\n"),
      "utf8"
    );
    fs.writeFileSync(
      path.join(pausedDir, "automation.toml"),
      [
        "version = 1",
        'id = "paused-research"',
        'kind = "heartbeat"',
        'name = "Paused research"',
        'status = "PAUSED"',
        'rrule = "FREQ=MINUTELY;INTERVAL=5;COUNT=96"',
        'target_thread_id = "thread-2"'
      ].join("\n"),
      "utf8"
    );
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        getCodexHome: vi.fn(() => codexHome)
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "Chrome",
          cwd: "D:\\code\\chrome_bot",
          updatedAt: 1778549544,
          archived: false,
          firstUserMessage: "继续八小时任务",
          source: "vscode"
        },
        {
          threadId: "thread-2",
          title: "Token revenue",
          cwd: "D:\\code\\token",
          updatedAt: 1778549545,
          archived: false,
          firstUserMessage: "继续研究",
          source: "vscode"
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const automations = await service.listAutomations();

      expect(automations).toEqual([
        {
          id: "active-research",
          name: "Active research",
          kind: "heartbeat",
          status: "ACTIVE",
          scheduleSummary: "每15分钟",
          targetThreadId: "thread-1",
          targetThreadTitle: "Chrome",
          cwd: "D:\\code\\chrome_bot"
        },
        {
          id: "paused-research",
          name: "Paused research",
          kind: "heartbeat",
          status: "PAUSED",
          scheduleSummary: "每5分钟",
          targetThreadId: "thread-2",
          targetThreadTitle: "Token revenue",
          cwd: "D:\\code\\token"
        }
      ]);
    } finally {
      fs.rmSync(codexHome, { recursive: true, force: true });
    }
  });

  it("uses desktop session index thread names for pinned mobile titles", async () => {
    const codexHome = fs.mkdtempSync(path.join(os.tmpdir(), "codex-home-"));
    fs.writeFileSync(
      path.join(codexHome, ".codex-global-state.json"),
      JSON.stringify({ "pinned-thread-ids": ["codex-thread", "boss-thread"] })
    );
    fs.writeFileSync(
      path.join(codexHome, "session_index.jsonl"),
      [
        JSON.stringify({
          id: "codex-thread",
          thread_name: "Codex 安卓 App 5.0",
          updated_at: "2026-05-06T02:43:12.7710612Z"
        }),
        JSON.stringify({
          id: "boss-thread",
          thread_name: "BOSS直聘安卓HOOK",
          updated_at: "2026-05-07T09:31:10.4466951Z"
        })
      ].join("\n")
    );
    const handoffTitle = [
      "下面这段直接复制到下一个窗口即可：",
      "",
      "```markdown",
      "请先阅读并以这个目录作为唯一主工作区：",
      "",
      "D:\\projects\\codex-mobile-control",
      "```",
      "你先分析一下，不需要任何实际操作，只分析"
    ].join("\n");
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        getCodexHome: vi.fn(() => codexHome),
        listThreads: vi.fn(async () => [])
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "codex-thread",
          title: handoffTitle,
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1778205561,
          archived: false,
          firstUserMessage: handoffTitle
        },
        {
          threadId: "boss-thread",
          title: "分析一下这个项目功能和代码",
          cwd: "\\\\?\\D:\\codex\\phone_zhipin",
          updatedAt: 1778205562,
          archived: false,
          firstUserMessage: "分析一下这个项目功能和代码"
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    const threads = await service.listThreads();

    expect(threads.map((thread) => ({
      threadId: thread.threadId,
      title: thread.title,
      isPinned: thread.isPinned
    }))).toEqual([
      {
        threadId: "codex-thread",
        title: "Codex 安卓 App 5.0",
        isPinned: true
      },
      {
        threadId: "boss-thread",
        title: "BOSS直聘安卓HOOK",
        isPinned: true
      }
    ]);
  });

  it("falls back to rollout preview when app-server detail read times out", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-detail-timeout-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-detail-timeout.jsonl");
    fs.writeFileSync(
      rolloutPath,
      [
        JSON.stringify({
          timestamp: "2026-04-28T11:19:00.000Z",
          type: "response_item",
          payload: {
            type: "message",
            role: "user",
            content: [{ type: "input_text", text: "为什么点进 app 才刷新状态" }]
          }
        }),
        JSON.stringify({
          timestamp: "2026-04-28T11:19:30.000Z",
          type: "response_item",
          payload: {
            type: "message",
            role: "assistant",
            content: [{ type: "output_text", text: "本轮已经处理完。" }]
          }
        })
      ].join("\n"),
      "utf8"
    );
    const readThread = vi.fn(async () => {
      throw new Error("app_server_request_timeout:thread/read");
    });
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({ readThread }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "Codex 安卓 App 2.0",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777375170,
          archived: false,
          firstUserMessage: "为什么点进 app 才刷新状态",
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub([
        {
          signalId: "log-completed",
          threadId: "thread-1",
          status: "completed",
          text: "本轮已完成",
          timestamp: "2026-04-28T11:19:31.000Z",
          cursor: "100"
        }
      ])
    });

    try {
      const detail = await service.getThreadDetail("thread-1");

      expect(readThread).toHaveBeenCalledOnce();
      expect(detail.thread).toMatchObject({
        threadId: "thread-1",
        title: "Codex 安卓 App 2.0",
        status: "idle",
        progressSummary: "为什么点进 app 才刷新状态"
      });
      expect(detail.recentMessages.map((message) => message.text)).toEqual([
        "为什么点进 app 才刷新状态",
        "本轮已经处理完。"
      ]);
      expect(detail.recentEvents).toEqual([]);
      expect(detail.sendAvailable).toBe(true);

      const refreshed = await service.getThreadDetail("thread-1");
      expect(refreshed.recentEvents.filter((event) => event.eventId === "log:log-completed")).toHaveLength(0);
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("uses rollout task_started status when detail falls back to preview", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-detail-running-rollout-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-running.jsonl");
    fs.writeFileSync(
      rolloutPath,
      [
        JSON.stringify({
          timestamp: "2026-05-08T08:42:30.000Z",
          type: "event_msg",
          payload: { type: "task_complete", turn_id: "turn-old" }
        }),
        JSON.stringify({
          timestamp: "2026-05-08T08:45:32.000Z",
          type: "event_msg",
          payload: { type: "task_started", turn_id: "turn-running" }
        }),
        JSON.stringify({
          timestamp: "2026-05-08T08:45:46.000Z",
          type: "response_item",
          payload: {
            type: "message",
            role: "assistant",
            content: [{ type: "output_text", text: "我还在继续处理。" }]
          }
        })
      ].join("\n"),
      "utf8"
    );
    const readThread = vi.fn(async () => {
      throw new Error("app_server_request_timeout:thread/read");
    });
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({ readThread }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "Ubuntu WeChat-Hook",
          cwd: "\\\\?\\D:\\codex\\weixin_bot",
          updatedAt: 1778229750,
          archived: false,
          firstUserMessage: "上一轮已经完成",
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const detail = await service.getThreadDetail("thread-1");

      expect(detail.thread).toMatchObject({
        threadId: "thread-1",
        title: "Ubuntu WeChat-Hook",
        status: "running",
        progressSummary: "开始处理新的输入",
        runningStartedAt: "2026-05-08T08:45:32.000Z",
        updatedAt: "2026-05-08T08:45:32.000Z"
      });
      expect(detail.sendAvailable).toBe(false);
      expect(detail.sendDisabledReason).toBe("线程仍在运行，暂不支持并发发送");
      expect(detail.statusDecision).toMatchObject({
        status: "running",
        source: "event",
        text: "开始处理新的输入",
        timestamp: "2026-05-08T08:45:32.000Z",
        reason: "event status selected from detail candidates"
      });
      expect(detail.sendDecision).toEqual({
        available: false,
        reason: "thread_running",
        source: "statusDecision",
        message: "线程仍在运行，暂不支持并发发送",
        recommendedAction: "queue"
      });
      expect(detail.recentEvents.at(-1)).toMatchObject({
        eventId: "turn-running:running",
        status: "running",
        kind: "turn_started",
        timestamp: "2026-05-08T08:45:32.000Z"
      });
      expect(detail.recentMessages.map((message) => message.text)).toEqual([
        "我还在继续处理。"
      ]);
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("deepens rollout status scan when a long running turn pushes task_started outside the preview tail", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-detail-deep-running-rollout-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-deep-running.jsonl");
    const largeOutputLine = `${JSON.stringify({
      timestamp: "2026-05-13T09:50:00.000Z",
      type: "response_item",
      payload: {
        type: "function_call_output",
        call_id: "call-long-output",
        output: "x".repeat(4 * 1024)
      }
    })}\n`;
    fs.writeFileSync(
      rolloutPath,
      [
        `${JSON.stringify({
          timestamp: "2026-05-13T09:24:19.000Z",
          type: "event_msg",
          payload: { type: "task_complete", turn_id: "turn-old" }
        })}\n`,
        `${JSON.stringify({
          timestamp: "2026-05-13T09:26:41.000Z",
          type: "event_msg",
          payload: { type: "task_started", turn_id: "turn-running" }
        })}\n`,
        largeOutputLine.repeat(620)
      ].join(""),
      "utf8"
    );
    const readThread = vi.fn(async () => {
      throw new Error("app_server_request_timeout:thread/read");
    });
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({ readThread }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "分析 go_pay 闪退原因",
          cwd: "\\\\?\\D:\\code\\go_pay_bot",
          updatedAt: 1778670137,
          archived: false,
          firstUserMessage: "帮我分析 GoPay 闪退",
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const detail = await service.getThreadDetail("thread-1");

      expect(detail.thread).toMatchObject({
        threadId: "thread-1",
        status: "running",
        progressSummary: "开始处理新的输入",
        updatedAt: "2026-05-13T09:26:41.000Z"
      });
      expect(detail.statusDecision).toMatchObject({
        status: "running",
        source: "event",
        sourceId: "turn-running:running"
      });
      expect(detail.recentEvents.at(-1)).toMatchObject({
        eventId: "turn-running:running",
        status: "running",
        kind: "turn_started"
      });
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("uses recent rollout turn_context as a running signal for very long active turns", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-detail-turn-context-running-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-turn-context-running.jsonl");
    const largeOutputLine = `${JSON.stringify({
      timestamp: "2026-05-13T10:20:00.000Z",
      type: "response_item",
      payload: {
        type: "function_call_output",
        call_id: "call-very-long-output",
        output: "x".repeat(8 * 1024)
      }
    })}\n`;
    fs.writeFileSync(
      rolloutPath,
      [
        `${JSON.stringify({
          timestamp: "2026-05-13T09:24:19.000Z",
          type: "event_msg",
          payload: { type: "task_complete", turn_id: "turn-old" }
        })}\n`,
        `${JSON.stringify({
          timestamp: "2026-05-13T09:26:41.000Z",
          type: "event_msg",
          payload: { type: "task_started", turn_id: "turn-running" }
        })}\n`,
        largeOutputLine.repeat(1_100),
        `${JSON.stringify({
          timestamp: "2026-05-13T11:29:21.000Z",
          type: "turn_context",
          payload: { turn_id: "turn-running", cwd: "D:\\code\\go_pay_bot" }
        })}\n`
      ].join(""),
      "utf8"
    );
    const readThread = vi.fn(async () => {
      throw new Error("app_server_request_timeout:thread/read");
    });
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({ readThread }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "分析 go_pay 闪退原因",
          cwd: "\\\\?\\D:\\code\\go_pay_bot",
          updatedAt: 1778673728,
          archived: false,
          firstUserMessage: "帮我分析 GoPay 闪退",
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const detail = await service.getThreadDetail("thread-1");

      expect(detail.thread).toMatchObject({
        threadId: "thread-1",
        status: "running",
        progressSummary: "线程正在运行",
        runningStartedAt: "2026-05-13T09:26:41.000Z",
        updatedAt: "2026-05-13T11:29:21.000Z"
      });
      expect(detail.statusDecision).toMatchObject({
        status: "running",
        source: "event",
        sourceId: "turn-running:running"
      });
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("shows rollout completion without a final assistant message as an error", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-null-final-rollout-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-null-final.jsonl");
    fs.writeFileSync(
      rolloutPath,
      [
        JSON.stringify({
          timestamp: "2026-05-10T08:04:43.000Z",
          type: "event_msg",
          payload: { type: "task_started", turn_id: "turn-null-final" }
        }),
        JSON.stringify({
          timestamp: "2026-05-10T08:07:43.533Z",
          type: "event_msg",
          payload: {
            type: "task_complete",
            turn_id: "turn-null-final",
            last_agent_message: null
          }
        })
      ].join("\n"),
      "utf8"
    );
    const completedThread: CodexThread = {
      ...baseThread,
      id: "thread-boss",
      preview: "可以，把这七点写成计划依次执行",
      cwd: "D:\\code\\job_delivery_analysis",
      updatedAt: 1778400463,
      turns: [
        {
          id: "turn-null-final",
          status: "completed",
          startedAt: 1778400283,
          completedAt: 1778400463,
          error: null,
          items: [],
          durationMs: 180533
        }
      ]
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [completedThread]),
        readThread: vi.fn(async () => completedThread)
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-boss",
          title: "BOSS直聘HOOK 3.0",
          cwd: "\\\\?\\D:\\code\\job_delivery_analysis",
          updatedAt: 1778400463,
          archived: false,
          firstUserMessage: "可以，把这七点写成计划依次执行",
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const detail = await service.getThreadDetail("thread-boss");

      expect(detail.thread).toMatchObject({
        status: "error",
        progressSummary: "线程异常结束，未收到最终回复",
        needsAttention: true
      });
      expect(detail.statusDecision).toMatchObject({
        status: "error",
        source: "event",
        text: "线程异常结束，未收到最终回复",
        timestamp: "2026-05-10T08:07:43.533Z",
        reason: "event status selected from detail candidates"
      });
      expect(detail.statusDecision?.candidates).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            status: "error",
            source: "event",
            selected: true
          }),
          expect.objectContaining({
            status: "completed",
            source: "turn",
            selected: false
          })
        ])
      );
      expect(detail.sendDecision).toEqual({
        available: true,
        reason: "ready",
        source: "statusDecision",
        message: "可以继续发送下一条消息",
        recommendedAction: "send"
      });
      expect(detail.recentEvents.at(-1)).toMatchObject({
        kind: "error",
        status: "error",
        text: "线程异常结束，未收到最终回复",
        timestamp: "2026-05-10T08:07:43.533Z"
      });
      expect((await service.listThreads())[0]).toMatchObject({
        status: "error",
        progressSummary: "线程异常结束，未收到最终回复",
        needsAttention: true
      });
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("uses rollout terminal errors in the thread list before detail is opened", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-list-null-final-rollout-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-list-null-final.jsonl");
    fs.writeFileSync(
      rolloutPath,
      [
        JSON.stringify({
          timestamp: "2026-05-10T08:04:43.000Z",
          type: "event_msg",
          payload: { type: "task_started", turn_id: "turn-null-final" }
        }),
        JSON.stringify({
          timestamp: "2026-05-10T08:07:43.533Z",
          type: "event_msg",
          payload: {
            type: "task_complete",
            turn_id: "turn-null-final",
            last_agent_message: null
          }
        })
      ].join("\n"),
      "utf8"
    );
    const completedThread: CodexThread = {
      ...baseThread,
      id: "thread-boss",
      preview: "可以，把这七点写成计划依次执行",
      cwd: "D:\\code\\job_delivery_analysis",
      updatedAt: 1778400463,
      turns: [
        {
          id: "turn-null-final",
          status: "completed",
          startedAt: 1778400283,
          completedAt: 1778400463,
          error: null,
          items: [],
          durationMs: 180533
        }
      ]
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [completedThread]),
        readThread: vi.fn(async () => completedThread)
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-boss",
          title: "BOSS直聘HOOK 3.0",
          cwd: "\\\\?\\D:\\code\\job_delivery_analysis",
          updatedAt: 1778400463,
          archived: false,
          firstUserMessage: "可以，把这七点写成计划依次执行",
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const threads = await service.listThreads();

      expect(threads[0]).toMatchObject({
        threadId: "thread-boss",
        status: "error",
        progressSummary: "线程异常结束，未收到最终回复",
        needsAttention: true
      });
      expect(await service.getRuntimeDiagnostics()).toMatchObject({
        statusDecisions: {
          recent: expect.arrayContaining([
            expect.objectContaining({
              threadId: "thread-boss",
              context: "list",
              status: "error",
              source: "event",
              text: "线程异常结束，未收到最终回复",
              candidates: expect.arrayContaining([
                expect.objectContaining({
                  status: "error",
                  source: "event",
                  selected: true
                })
              ])
            })
          ])
        }
      });
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("returns rollout preview promptly while app-server detail read is still pending", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-detail-pending-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-detail-pending.jsonl");
    fs.writeFileSync(
      rolloutPath,
      JSON.stringify({
        timestamp: "2026-04-28T11:35:00.000Z",
        type: "response_item",
        payload: {
          type: "message",
          role: "assistant",
          content: [{ type: "output_text", text: "先显示缓存详情。" }]
        }
      }),
      "utf8"
    );
    const readThread = vi.fn(() => new Promise<CodexThread>(() => undefined));
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({ readThread }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "Codex 安卓 App 2.0",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777376100,
          archived: false,
          firstUserMessage: "详情读取慢",
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const detail = await waitForResult(service.getThreadDetail("thread-1"), 900);

      expect(readThread).toHaveBeenCalledOnce();
      expect(detail.thread.title).toBe("Codex 安卓 App 2.0");
      expect(detail.recentMessages.map((message) => message.text)).toEqual([
        "先显示缓存详情。"
      ]);
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("updates stale running state when a slow app-server detail read completes later", async () => {
    const staleRunningThread: CodexThread = {
      ...baseThread,
      updatedAt: 1777376100,
      name: "Codex 安卓 App 2.0",
      status: {
        type: "active",
        activeFlags: []
      }
    };
    const completedTurnThread: CodexThread = {
      ...staleRunningThread,
      turns: [
        {
          id: "turn-slow-completed",
          status: "completed",
          startedAt: 1777376105,
          completedAt: 1777376165,
          error: null,
          items: [
            {
              type: "agentMessage",
              id: "item-slow-completed",
              text: "慢详情读取完成后应该修正状态。",
              phase: null,
              memoryCitation: null
            }
          ],
          durationMs: 60000
        }
      ]
    };
    let resolveReadThread: (thread: CodexThread) => void = () => undefined;
    const readThread = vi.fn(
      () =>
        new Promise<CodexThread>((resolve) => {
          resolveReadThread = resolve;
        })
    );

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [staleRunningThread]),
        readThread
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "Codex 安卓 App 2.0",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777376100,
          archived: false,
          firstUserMessage: "慢详情读取",
          source: "vscode"
        }
      ]),
      logRepository: createLogRepositoryStub([
        {
          signalId: "log-running-slow-detail",
          threadId: "thread-1",
          status: "running",
          text: "正在处理新的请求",
          timestamp: "2026-04-28T11:35:00.000Z",
          cursor: "200"
        }
      ])
    });

    const preview = await waitForResult(service.getThreadDetail("thread-1"), 900);
    expect(preview.thread.status).toBe("idle");
    expect(preview.sendAvailable).toBe(true);

    resolveReadThread(completedTurnThread);

    await waitForAssertion(async () => {
      const events = await service.getThreadEvents("thread-1");
      expect(events.events.at(-1)).toMatchObject({
        kind: "turn_completed",
        status: "completed",
        text: "本轮已完成"
      });
    });

    const threads = await service.listThreads();
    expect(threads[0]).toMatchObject({
      status: "completed",
      progressSummary: "本轮已完成",
      updatedAt: "2026-04-28T11:36:05.000Z"
    });
  });

  it("uses a bounded desktop-visible state query for the fast thread list path", async () => {
    const listThreads = vi.fn(() => new Promise<CodexThread[]>(() => undefined));
    const listDesktopVisibleThreadMetadata = vi.fn(async () => [
      {
        threadId: "desktop-thread",
        title: "Codex 安卓 App 2.0",
        cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
        updatedAt: 1777206905,
        archived: false,
        firstUserMessage: "继续优化手机端",
        source: "vscode"
      }
    ]);
    const getThreadMetadata = vi.fn(async () => {
      throw new Error("unbounded_metadata_query_should_not_run");
    });
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({ listThreads }),
      stateRepository: {
        getThreadMetadata,
        listDesktopVisibleThreadMetadata
      } as StateRepository & {
        listDesktopVisibleThreadMetadata: (limit: number) => Promise<ThreadMetadataRecord[]>;
      },
      logRepository: createLogRepositoryStub()
    });

    const threads = await waitForResult(service.listThreads(), 100);

    expect(threads.map((item) => item.threadId)).toEqual(["desktop-thread"]);
    expect(listDesktopVisibleThreadMetadata).toHaveBeenCalledWith(50);
    expect(getThreadMetadata).not.toHaveBeenCalled();
  });

  it("builds a thread preview from rollout tail and cached metadata composer state", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-preview-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-preview.jsonl");
    fs.writeFileSync(
      rolloutPath,
      [
        JSON.stringify({
          timestamp: "2026-04-26T12:59:00.000Z",
          type: "response_item",
          payload: {
            type: "message",
            role: "user",
            content: [{
              type: "input_text",
              text: "<turn_aborted>The user interrupted the previous turn on purpose.</turn_aborted>"
            }]
          }
        }),
        JSON.stringify({
          timestamp: "2026-04-26T13:00:00.000Z",
          type: "response_item",
          payload: {
            type: "message",
            role: "user",
            content: [{ type: "input_text", text: "旧消息" }]
          }
        }),
        JSON.stringify({
          timestamp: "2026-04-26T13:01:00.000Z",
          type: "response_item",
          payload: {
            type: "message",
            role: "user",
            content: [{ type: "input_text", text: "打开详情要先看到最近消息" }]
          }
        }),
        JSON.stringify({
          timestamp: "2026-04-26T13:02:00.000Z",
          type: "response_item",
          payload: {
            type: "message",
            role: "assistant",
            content: [{
              type: "output_text",
              text: [
                "可以先展示预览，再补完整详情。",
                "",
                "::git-stage{cwd=\"D:/projects/codex-mobile-control\"}"
              ].join("\n")
            }]
          }
        })
      ].join("\n")
    );
    const readThread = vi.fn(async () => {
      throw new Error("thread_read_should_not_run_for_preview");
    });
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({ readThread }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "preview-thread",
          title: "Codex 安卓 App 2.0",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777210800,
          archived: false,
          firstUserMessage: "打开详情要先看到最近消息",
          source: "vscode",
          rolloutPath,
          model: "gpt-5.5",
          reasoningEffort: "xhigh",
          sandboxPolicy: "{\"type\":\"danger-full-access\"}",
          approvalMode: "never"
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const preview = await service.getThreadPreview("preview-thread");

      expect(readThread).not.toHaveBeenCalled();
      expect(preview.thread).toMatchObject({
        threadId: "preview-thread",
        title: "Codex 安卓 App 2.0",
        cwd: "D:\\projects\\codex-mobile-control"
      });
      expect(preview.recentMessages.map((message) => message.text)).toEqual([
        "旧消息",
        "打开详情要先看到最近消息",
        "可以先展示预览，再补完整详情。"
      ]);
      expect(preview.recentMessages.map((message) => message.text).join("\n")).not.toContain("turn_aborted");
      expect(preview.composerState).toEqual({
        permissionLabel: "完全访问权限",
        modelLabel: "GPT-5.5 超高",
        effortLabel: "超高"
      });
      expect(preview.recentEvents).toEqual([]);
      expect(preview.sendAvailable).toBe(true);
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("falls back to the previous waiting state after an optimistic running hint expires", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-04-24T06:00:00.000Z"));

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [{ ...baseThread, status: { type: "idle" as const } }])
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator: createDesktopCoordinatorStub({
        accepted: true,
        threadId: "thread-1",
        clientMessageId: "client-1",
        sendPath: "desktop_bridge",
        confirmation: "keystrokes_sent",
        warning: "desktop_send_confirmation_timeout"
      }),
      sendMode: "desktop_bridge"
    });

    service.handleNotification({
      method: "thread/status/changed",
      params: {
        threadId: "thread-1",
        status: {
          type: "active",
          activeFlags: ["waitingOnUserInput"]
        }
      }
    });

    expect((await service.listThreads())[0]?.status).toBe("waiting_input");

    await service.sendMessage("thread-1", {
      text: "请继续",
      clientMessageId: "client-1"
    });
    expect((await service.listThreads())[0]?.status).toBe("running");

    vi.setSystemTime(new Date("2026-04-24T06:00:45.000Z"));
    expect((await service.listThreads())[0]).toMatchObject({
      status: "waiting_input",
      progressSummary: "线程正在等待输入",
      needsAttention: true
    });
  });

  it("does not keep stale optimistic send events as detail running state after they expire", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-04-24T06:00:00.000Z"));

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [{ ...baseThread, status: { type: "notLoaded" as const } }])
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator: createDesktopCoordinatorStub({
        accepted: true,
        threadId: "thread-1",
        clientMessageId: "client-stale-running",
        sendPath: "desktop_bridge",
        confirmation: "keystrokes_sent",
        warning: "desktop_send_confirmation_timeout"
      }),
      sendMode: "desktop_bridge"
    });

    await service.sendMessage("thread-1", {
      text: "手机端待确认消息",
      clientMessageId: "client-stale-running"
    });
    expect((await service.getThreadDetail("thread-1")).thread.status).toBe("running");

    vi.setSystemTime(new Date("2026-04-24T06:00:45.000Z"));
    const detail = await service.getThreadDetail("thread-1");

    expect(detail.thread).toMatchObject({
      status: "completed",
      progressSummary: "本轮已完成",
      needsAttention: false
    });
    expect(detail.sendAvailable).toBe(true);
  });

  it("builds thread detail with recent messages, recent events and send availability", async () => {
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [
          {
            ...baseThread,
            name: "开发 Codex 控制App",
            status: {
              type: "active" as const,
              activeFlags: ["waitingOnUserInput" as const]
            }
          }
        ])
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.thread.status).toBe("waiting_input");
    expect(detail.recentMessages.map((item) => item.text)).toEqual([
      "请继续实现",
      "我正在接 Codex sidecar 和 SQLite。"
    ]);
    expect(detail.recentEvents.map((item) => item.text)).toEqual([
      "先实现 Mobile Gateway",
      "执行命令: npm test --workspace @codex-mobile/gateway",
      "本轮已完成"
    ]);
    expect(detail.sendAvailable).toBe(true);
  });

  it("returns older thread messages as a backward page before the current first bubble", async () => {
    const pagedThread: CodexThread = {
      ...baseThread,
      turns: Array.from({ length: 8 }, (_unused, index) => {
        const messageNumber = index + 1;
        return {
          id: `turn-${messageNumber}`,
          status: "completed" as const,
          startedAt: 1776857600 + index,
          completedAt: 1776857600 + index,
          error: null,
          durationMs: 1000,
          items: [
            {
              type: "userMessage" as const,
              id: `item-user-${messageNumber}`,
              content: [{
                type: "text" as const,
                text: `消息 ${messageNumber}`,
                text_elements: []
              }]
            }
          ]
        };
      })
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        readThread: vi.fn(async () => pagedThread)
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });

    const detail = await service.getThreadDetail("thread-1");
    const page = await service.getThreadMessages(
      "thread-1",
      "item-user-6",
      "2026-04-22T11:33:25.000Z",
      3
    );
    const timestampFallbackPage = await service.getThreadMessages(
      "thread-1",
      "preview-message-id",
      new Date((1776857600 + 5) * 1000).toISOString(),
      3
    );

    expect(detail.recentMessages.map((item) => item.text)).toEqual([
      "消息 1",
      "消息 2",
      "消息 3",
      "消息 4",
      "消息 5",
      "消息 6",
      "消息 7",
      "消息 8"
    ]);
    expect(page.messages.map((item) => item.text)).toEqual(["消息 3", "消息 4", "消息 5"]);
    expect(page.nextCursor).toBe("item-user-3");
    expect(timestampFallbackPage.messages.map((item) => item.text)).toEqual([
      "消息 3",
      "消息 4",
      "消息 5"
    ]);
  });

  it("builds detail recent messages from full turn history instead of keeping stale cached remnants", async () => {
    const pagedThread: CodexThread = {
      ...baseThread,
      turns: Array.from({ length: 30 }, (_unused, index) => {
        const messageNumber = index + 1;
        return {
          id: `turn-${messageNumber}`,
          status: "completed" as const,
          startedAt: 1776857600 + index,
          completedAt: 1776857600 + index,
          error: null,
          durationMs: 1000,
          items: [
            {
              type: "userMessage" as const,
              id: `item-user-${messageNumber}`,
              content: [{
                type: "text" as const,
                text: `消息 ${messageNumber}`,
                text_elements: []
              }]
            }
          ]
        };
      })
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        readThread: vi.fn(async () => pagedThread)
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });

    (service as any).threadMessages.set("thread-1", [
      {
        messageId: "stale-file-message",
        threadId: "thread-1",
        role: "user",
        kind: "file",
        fileName: "1777731314467-codex-mobile-diagnostics-20260502-141447.zip",
        fileUrl: "/api/uploads/thread-1/1777731314467-codex-mobile-diagnostics-20260502-141447.zip",
        mimeType: "application/zip",
        timestamp: "2026-04-01T14:15:14.470Z"
      }
    ]);

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.recentMessages).toHaveLength(20);
    expect(detail.recentMessages.map((item) => item.text)).toEqual(
      Array.from({ length: 20 }, (_unused, index) => `消息 ${index + 11}`)
    );
    expect(detail.recentMessages.some((item) => item.messageId === "stale-file-message")).toBe(false);
  });

  it("returns newer thread messages as an incremental page after the current last bubble", async () => {
    const pagedThread: CodexThread = {
      ...baseThread,
      turns: Array.from({ length: 5 }, (_unused, index) => {
        const messageNumber = index + 1;
        return {
          id: `turn-${messageNumber}`,
          status: "completed" as const,
          startedAt: 1776857600 + index,
          completedAt: 1776857600 + index,
          error: null,
          durationMs: 1000,
          items: [
            {
              type: "userMessage" as const,
              id: `item-user-${messageNumber}`,
              content: [{
                type: "text" as const,
                text: `消息 ${messageNumber}`,
                text_elements: []
              }]
            }
          ]
        };
      })
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        readThread: vi.fn(async () => pagedThread)
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });

    const page = await service.getThreadMessages(
      "thread-1",
      null,
      null,
      20,
      "item-user-2",
      "2026-04-22T11:33:22.000Z"
    );

    expect(page.messages.map((item) => item.text)).toEqual(["消息 3", "消息 4", "消息 5"]);
    expect(page.nextCursor).toBe("item-user-5");
  });

  it("uses rollout history for message pages without waiting for app-server thread read", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-message-page-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-page.jsonl");
    fs.writeFileSync(
      rolloutPath,
      Array.from({ length: 8 }, (_unused, index) => {
        const messageNumber = index + 1;
        return JSON.stringify({
          timestamp: new Date((1776857600 + index) * 1000).toISOString(),
          type: "response_item",
          payload: {
            type: "message",
            id: `rollout-message-${messageNumber}`,
            role: "user",
            content: [{ type: "input_text", text: `rollout 消息 ${messageNumber}` }]
          }
        });
      }).join("\n"),
      "utf8"
    );
    const readThread = vi.fn(async () => {
      throw new Error("thread_read_should_not_run_for_rollout_message_page");
    });
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({ readThread }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "Codex 安卓 App 2.0",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1776857608,
          archived: false,
          firstUserMessage: "rollout 消息 1",
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const page = await service.getThreadMessages(
        "thread-1",
        "rollout-message-6",
        null,
        3
      );

      expect(readThread).not.toHaveBeenCalled();
      expect(page.messages.map((item) => item.text)).toEqual([
        "rollout 消息 3",
        "rollout 消息 4",
        "rollout 消息 5"
      ]);
      expect(page.nextCursor).toBe("rollout-message-3");
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("uses rollout tail for the initial message page without reading the whole file", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-message-page-tail-"));
    const rolloutPath = path.join(tempDir, "rollout-large-thread-page.jsonl");
    const hugeOldEntry = JSON.stringify({
      timestamp: "2026-04-22T10:00:00.000Z",
      type: "event_msg",
      payload: {
        type: "token_count",
        info: "x".repeat(2 * 1024 * 1024 + 2048)
      }
    });
    fs.writeFileSync(
      rolloutPath,
      [
        hugeOldEntry,
        ...Array.from({ length: 4 }, (_unused, index) => {
          const messageNumber = index + 1;
          return JSON.stringify({
            timestamp: new Date(Date.UTC(2026, 3, 22, 11, messageNumber, 0)).toISOString(),
            type: "response_item",
            payload: {
              type: "message",
              id: `tail-message-${messageNumber}`,
              role: "assistant",
              content: [{ type: "output_text", text: `tail 消息 ${messageNumber}` }]
            }
          });
        })
      ].join("\n"),
      "utf8"
    );
    const readThread = vi.fn(async () => {
      throw new Error("thread_read_should_not_run_for_rollout_tail_page");
    });
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({ readThread }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "Codex 安卓 App 2.0",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1776857608,
          archived: false,
          firstUserMessage: "tail 消息 1",
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });
    const originalReadFile = fs.promises.readFile;
    const blockedReadFiles: string[] = [];
    fs.promises.readFile = (async (filePath: fs.PathLike | fs.promises.FileHandle, ...args: unknown[]) => {
      if (String(filePath) === rolloutPath) {
        blockedReadFiles.push(String(filePath));
        throw new Error("full rollout read blocked");
      }
      return originalReadFile.call(fs.promises, filePath as any, ...(args as any));
    }) as typeof fs.promises.readFile;

    try {
      const page = await service.getThreadMessages("thread-1", null, null, 3);

      expect(readThread).not.toHaveBeenCalled();
      expect(blockedReadFiles).toEqual([]);
      expect(page.messages.map((item) => item.text)).toEqual([
        "tail 消息 2",
        "tail 消息 3",
        "tail 消息 4"
      ]);
      expect(page.nextCursor).toBe("tail-message-2");
    } finally {
      fs.promises.readFile = originalReadFile;
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("filters internal context messages from thread detail", async () => {
    const detailThread: CodexThread = {
      ...baseThread,
      turns: [
        {
          id: "turn-internal",
          status: "completed",
          startedAt: 1776857697,
          completedAt: 1776857699,
          error: null,
          durationMs: 2000,
          items: [
            {
              type: "userMessage",
              id: "item-internal-aborted",
              content: [{
                type: "text",
                text: "<turn_aborted>The user interrupted the previous turn on purpose.</turn_aborted>",
                text_elements: []
              }]
            },
            {
              type: "userMessage",
              id: "item-internal-env",
              content: [{
                type: "text",
                text: "<environment_context><shell>powershell</shell><current_date>2026-04-27</current_date></environment_context>",
                text_elements: []
              }]
            },
            {
              type: "userMessage",
              id: "item-real-user",
              content: [{ type: "text", text: "开启网关", text_elements: [] }]
            }
          ]
        }
      ]
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        readThread: vi.fn(async () => detailThread)
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.recentMessages.map((item) => item.text)).toEqual(["开启网关"]);
  });

  it("filters automation heartbeat and AGENTS context messages from rollout pages", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-internal-message-page-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-internal.jsonl");
    const lines = [
      {
        timestamp: "2026-04-28T10:35:17.527Z",
        type: "response_item",
        payload: {
          type: "message",
          id: "rollout-heartbeat-user",
          role: "user",
          content: [
            {
              type: "input_text",
              text: [
                "<heartbeat>",
                "  <automation_id>oneminutereply</automation_id>",
                "  <current_time_iso>2026-04-28T10:35:17.414Z</current_time_iso>",
                "  <instructions>",
                "Reply exactly `999999` in this thread once, then delete this heartbeat automation.",
                "  </instructions>",
                "</heartbeat>"
              ].join("\n")
            }
          ]
        }
      },
      {
        timestamp: "2026-04-28T10:36:30.884Z",
        type: "response_item",
        payload: {
          type: "message",
          id: "rollout-heartbeat-assistant",
          role: "assistant",
          content: [
            {
              type: "output_text",
              text: [
                "999999",
                "",
                "<heartbeat>",
                "  <automation_id>oneminutereply</automation_id>",
                "  <decision>NOTIFY</decision>",
                "  <message>Sent the one-minute follow-up.</message>",
                "</heartbeat>"
              ].join("\n")
            }
          ]
        }
      },
      {
        timestamp: "2026-04-28T06:13:15.619Z",
        type: "response_item",
        payload: {
          type: "message",
          id: "rollout-agents-context",
          role: "user",
          content: [
            {
              type: "input_text",
              text: [
                "# AGENTS.md instructions for C:\\Users\\devuser\\Documents\\Codex\\2026-04-28\\files-mentioned-by-the-user-pdf",
                "",
                "<INSTRUCTIONS>",
                "# Global Codex rules for Windows",
                "默认使用简体中文与我沟通。",
                "</INSTRUCTIONS>"
              ].join("\n")
            }
          ]
        }
      },
      {
        timestamp: "2026-04-28T10:38:18.033Z",
        type: "response_item",
        payload: {
          type: "message",
          id: "rollout-real-user",
          role: "user",
          content: [{ type: "input_text", text: "测试，回复99666" }]
        }
      }
    ];
    fs.writeFileSync(
      rolloutPath,
      lines.map((line) => JSON.stringify(line)).join("\n"),
      "utf8"
    );
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "模拟",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777372698,
          archived: false,
          firstUserMessage: "测试，回复99666",
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const page = await service.getThreadMessages("thread-1", null, null, 20);

      expect(page.messages.map((item) => item.text)).toEqual([
        "999999",
        "测试，回复99666"
      ]);
      expect(JSON.stringify(page.messages)).not.toContain("<heartbeat>");
      expect(JSON.stringify(page.messages)).not.toContain("AGENTS.md instructions");
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("restores rollout local images and separates the request text", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-rollout-local-images-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-images.jsonl");
    fs.writeFileSync(
      rolloutPath,
      [
        JSON.stringify({
          timestamp: "2026-04-29T06:57:30.107Z",
          type: "response_item",
          payload: {
            type: "message",
            role: "user",
            content: [{
              type: "input_text",
              text: [
                "# Files mentioned by the user:",
                "",
                "## second.jpg: C:/Users/devuser/mobile-uploads/thread-1/second.jpg",
                "",
                "## first.jpg: C:/Users/devuser/mobile-uploads/thread-1/first.jpg",
                "",
                "## My request for Codex:",
                "测试，回复图片内容"
              ].join("\n")
            }]
          }
        }),
        JSON.stringify({
          timestamp: "2026-04-29T06:57:30.108Z",
          type: "event_msg",
          payload: {
            type: "user_message",
            message: [
              "# Files mentioned by the user:",
              "",
              "## second.jpg: C:/Users/devuser/mobile-uploads/thread-1/second.jpg",
              "",
              "## first.jpg: C:/Users/devuser/mobile-uploads/thread-1/first.jpg",
              "",
              "## My request for Codex:",
              "测试，回复图片内容"
            ].join("\n"),
            images: [],
            local_images: [
              "C:\\Users\\devuser\\mobile-uploads\\thread-1\\second.jpg",
              "C:\\Users\\devuser\\mobile-uploads\\thread-1\\first.jpg"
            ],
            text_elements: []
          }
        })
      ].join("\n"),
      "utf8"
    );
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        readThread: vi.fn(async () => {
          throw new Error("app_server_request_timeout:thread/read");
        })
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "模拟",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777445850,
          archived: false,
          firstUserMessage: "测试，回复图片内容",
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const detail = await service.getThreadDetail("thread-1");

      expect(detail.recentMessages.map((item) => item.kind)).toEqual(["image", "image", "text"]);
      expect(detail.recentMessages.map((item) => item.fileName)).toEqual([
        "second.jpg",
        "first.jpg",
        undefined
      ]);
      expect(detail.recentMessages.map((item) => item.imageUrl)).toEqual([
        "/api/uploads/thread-1/second.jpg",
        "/api/uploads/thread-1/first.jpg",
        undefined
      ]);
      expect(detail.recentMessages.map((item) => item.text)).toEqual([
        undefined,
        undefined,
        "测试，回复图片内容"
      ]);
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("splits multi image user messages and keeps only the actual request text", async () => {
    const detailThread: CodexThread = {
      ...baseThread,
      turns: [
        {
          id: "turn-images",
          status: "completed",
          startedAt: 1776857697,
          completedAt: 1776857699,
          error: null,
          durationMs: 2000,
          items: [
            {
              type: "userMessage",
              id: "item-images",
              content: [
                {
                  type: "text",
                  text: [
                    "# Files mentioned by the user:",
                    "",
                    "## first.jpg: C:/Users/devuser/first.jpg",
                    "",
                    "## second.jpg: C:/Users/devuser/second.jpg",
                    "",
                    "## My request for Codex:",
                    "解读图片内容"
                  ].join("\n"),
                  text_elements: []
                },
                { type: "localImage", path: "C:\\Users\\devuser\\first.jpg" },
                { type: "localImage", path: "C:\\Users\\devuser\\second.jpg" }
              ]
            }
          ]
        }
      ]
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        readThread: vi.fn(async () => detailThread)
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.recentMessages).toHaveLength(3);
    expect(detail.recentMessages.map((item) => item.kind)).toEqual(["image", "image", "text"]);
    expect(detail.recentMessages.map((item) => item.fileName)).toEqual([
      "first.jpg",
      "second.jpg",
      undefined
    ]);
    expect(detail.recentMessages.map((item) => item.imageUrl)).toEqual([
      "/api/uploads/thread-1/first.jpg",
      "/api/uploads/thread-1/second.jpg",
      undefined
    ]);
    expect(detail.recentMessages.map((item) => item.text)).toEqual([
      undefined,
      undefined,
      "解读图片内容"
    ]);
    expect(JSON.stringify(detail.recentMessages)).not.toContain("Files mentioned");
    expect(JSON.stringify(detail.recentMessages)).not.toContain("C:/Users/devuser");
  });

  it("replaces a rollout text preview with split full image detail bubbles", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-image-preview-dedupe-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-image.jsonl");
    fs.writeFileSync(
      rolloutPath,
      [
        JSON.stringify({
          timestamp: "2026-04-26T15:41:00.000Z",
          type: "response_item",
          payload: {
            type: "message",
            id: "preview-image-text",
            role: "user",
            content: [{
              type: "input_text",
              text: [
                "# Files mentioned by the user:",
                "",
                "## first.jpg: C:/Users/devuser/first.jpg",
                "",
                "## My request for Codex:",
                "解读图片内容"
              ].join("\n")
            }]
          }
        })
      ].join("\n"),
      "utf8"
    );
    const detailThread: CodexThread = {
      ...baseThread,
      turns: [
        {
          id: "turn-image-detail",
          status: "completed",
          startedAt: 1777218060,
          completedAt: 1777218060,
          error: null,
          durationMs: 1000,
          items: [
            {
              type: "userMessage",
              id: "item-image-detail",
              content: [
                { type: "text", text: "解读图片内容", text_elements: [] },
                { type: "localImage", path: "C:\\Users\\devuser\\first.jpg" }
              ]
            }
          ]
        }
      ]
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        readThread: vi.fn(async () => detailThread)
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "Codex 安卓 App 2.0",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777218060,
          archived: false,
          firstUserMessage: "解读图片内容",
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      await service.getThreadPreview("thread-1");
      const detail = await service.getThreadDetail("thread-1");

      expect(detail.recentMessages).toHaveLength(2);
      expect(detail.recentMessages[0]).toMatchObject({
        kind: "image",
        text: undefined,
        fileName: "first.jpg"
      });
      expect(detail.recentMessages[1]).toMatchObject({
        kind: "text",
        text: "解读图片内容"
      });
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("deduplicates rollout preview messages when full thread detail catches up", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-detail-dedupe-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-detail.jsonl");
    const duplicatedText = "首页这个任务列表每次重新开启APP都会显示空白一段时间，改成用缓存先展示，然后走刷新";
    fs.writeFileSync(
      rolloutPath,
      [
        JSON.stringify({
          timestamp: "2026-04-26T19:43:00.099Z",
          type: "response_item",
          payload: {
            type: "message",
            role: "user",
            content: [{
              type: "input_text",
              text: `2026-04-26T19:43:00.099Z ${duplicatedText}\n<image> </image>`
            }]
          }
        })
      ].join("\n")
    );
    const detailThread: CodexThread = {
      ...baseThread,
      turns: [
        {
          id: "turn-user-detail",
          status: "completed",
          startedAt: 1777232580,
          completedAt: 1777232580,
          error: null,
          durationMs: 1000,
          items: [
            {
              type: "userMessage",
              id: "item-user-detail",
              content: [{ type: "text", text: duplicatedText, text_elements: [] }]
            }
          ]
        }
      ]
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        readThread: vi.fn(async () => detailThread)
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "Codex 安卓 App 2.0",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777232580,
          archived: false,
          firstUserMessage: duplicatedText,
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      await service.getThreadPreview("thread-1");
      const detail = await service.getThreadDetail("thread-1");

      expect(detail.recentMessages.map((item) => item.text)).toEqual([duplicatedText]);
      expect(detail.recentMessages.map((item) => item.messageId)).toEqual(["item-user-detail"]);
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("keeps consecutive assistant detail messages as separate bubbles", async () => {
    const detailThread: CodexThread = {
      ...baseThread,
      turns: [
        {
          id: "turn-assistant-bubbles",
          status: "completed",
          startedAt: 1776857697,
          completedAt: 1776857699,
          error: null,
          durationMs: 2000,
          items: [
            {
              type: "agentMessage",
              id: "item-agent-first",
              text: "第一条 Codex 回复",
              phase: null,
              memoryCitation: null
            },
            {
              type: "agentMessage",
              id: "item-agent-second",
              text: "第二条 Codex 回复",
              phase: null,
              memoryCitation: null
            }
          ]
        }
      ]
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        readThread: vi.fn(async () => detailThread)
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.recentMessages.map((item) => item.text)).toEqual([
      "第一条 Codex 回复",
      "第二条 Codex 回复"
    ]);
    expect(detail.recentMessages.map((item) => item.role)).toEqual(["assistant", "assistant"]);
    expect(detail.recentMessages.map((item) => item.messageId)).toEqual([
      "item-agent-first",
      "item-agent-second"
    ]);
  });

  it("filters desktop git action directives from assistant detail messages", async () => {
    const detailThread: CodexThread = {
      ...baseThread,
      turns: [
        {
          id: "turn-git-directives",
          status: "completed",
          startedAt: 1776857697,
          completedAt: 1776857699,
          error: null,
          durationMs: 2000,
          items: [
            {
              type: "agentMessage",
              id: "item-agent-with-git-directives",
              text: [
                "已提交：bdd959f fix: 修正详情消息时间和顺序。",
                "",
                "::git-stage{cwd=\"D:/projects/codex-mobile-control\"}",
                "::git-commit{cwd=\"D:/projects/codex-mobile-control\"}"
              ].join("\n"),
              phase: null,
              memoryCitation: null
            }
          ]
        }
      ]
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        readThread: vi.fn(async () => detailThread)
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.recentMessages.map((item) => item.text)).toEqual([
      "已提交：bdd959f fix: 修正详情消息时间和顺序。"
    ]);
  });

  it("keeps user messages before later assistant items when Codex gives one turn timestamp", async () => {
    const detailThread: CodexThread = {
      ...baseThread,
      turns: [
        {
          id: "turn-same-timestamp",
          status: "completed",
          startedAt: 1776857697,
          completedAt: 1776857699,
          error: null,
          durationMs: 2000,
          items: [
            {
              type: "agentMessage",
              id: "item-1225",
              text: "开始处理增量刷新",
              phase: null,
              memoryCitation: null
            },
            {
              type: "userMessage",
              id: "item-1222",
              content: [{
                type: "text",
                text: "那优化改为增量刷新",
                text_elements: []
              }]
            }
          ]
        }
      ]
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        readThread: vi.fn(async () => detailThread)
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.recentMessages.map((item) => item.messageId)).toEqual([
      "item-1222",
      "item-1225"
    ]);
    expect(detail.recentMessages.map((item) => item.role)).toEqual(["user", "assistant"]);
  });

  it("keeps the rollout timestamp for a matched user detail message", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-detail-user-timestamp-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-user-timestamp.jsonl");
    const userText = "那优化改为，1，改为增量刷新";
    fs.writeFileSync(
      rolloutPath,
      JSON.stringify({
        timestamp: "2026-04-27T11:14:33.580Z",
        type: "response_item",
        payload: {
          type: "message",
          role: "user",
          content: [{ type: "input_text", text: userText }]
        }
      }),
      "utf8"
    );
    const detailThread: CodexThread = {
      ...baseThread,
      turns: [
        {
          id: "turn-user-timestamp",
          status: "completed",
          startedAt: 1777288473,
          completedAt: 1777290493,
          error: null,
          durationMs: 2020000,
          items: [
            {
              type: "userMessage",
              id: "item-1222",
              content: [{
                type: "text",
                text: userText,
                text_elements: []
              }]
            },
            {
              type: "agentMessage",
              id: "item-1225",
              text: "我会把增量定义成只拉新消息",
              phase: null,
              memoryCitation: null
            }
          ]
        }
      ]
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        readThread: vi.fn(async () => detailThread)
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "Codex 安卓 App 2.0",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777290493,
          archived: false,
          firstUserMessage: userText,
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const detail = await service.getThreadDetail("thread-1");

      expect(detail.recentMessages[0]).toMatchObject({
        messageId: "item-1222",
        role: "user",
        text: userText,
        timestamp: "2026-04-27T11:14:33.580Z"
      });
      expect(detail.recentMessages[1]).toMatchObject({
        messageId: "item-1225",
        role: "assistant",
        timestamp: "2026-04-27T11:48:13.000Z"
      });
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("uses full rollout timestamps when large image entries push the user message out of the preview tail", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-detail-large-rollout-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-large-image.jsonl");
    const userText = "两个问题，排查图片和时间";
    const firstImagePath = path.join(tempDir, "first.jpg");
    const secondImagePath = path.join(tempDir, "second.jpg");
    fs.writeFileSync(
      rolloutPath,
      [
        JSON.stringify({
          timestamp: "2026-04-29T08:04:10.454Z",
          type: "event_msg",
          payload: {
            type: "user_message",
            message: [
              "# Files mentioned by the user:",
              "",
              `## first.jpg: ${firstImagePath}`,
              "",
              `## second.jpg: ${secondImagePath}`,
              "",
              "## My request for Codex:",
              userText
            ].join("\n"),
            local_images: [firstImagePath, secondImagePath],
            text_elements: []
          }
        }),
        JSON.stringify({
          timestamp: "2026-04-29T08:20:00.000Z",
          type: "event_msg",
          payload: {
            type: "token_count",
            info: "x".repeat(2 * 1024 * 1024 + 2048)
          }
        }),
        JSON.stringify({
          timestamp: "2026-04-29T08:38:47.950Z",
          type: "response_item",
          payload: {
            type: "message",
            role: "assistant",
            content: [{ type: "output_text", text: "最终总结" }]
          }
        })
      ].join("\n"),
      "utf8"
    );
    const detailThread: CodexThread = {
      ...baseThread,
      turns: [
        {
          id: "turn-large-rollout",
          status: "completed",
          startedAt: 1777451928,
          completedAt: 1777451928,
          error: null,
          durationMs: 1000,
          items: [
            {
              type: "userMessage",
              id: "item-50",
              content: [
                { type: "text", text: userText, text_elements: [] },
                { type: "localImage", path: firstImagePath },
                { type: "localImage", path: secondImagePath }
              ]
            }
          ]
        }
      ]
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        readThread: vi.fn(async () => detailThread)
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "Codex 安卓 App 3.0",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777451928,
          archived: false,
          firstUserMessage: userText,
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    try {
      const detail = await service.getThreadDetail("thread-1");

      expect(detail.recentMessages.slice(0, 3).map((item) => item.messageId)).toEqual([
        "item-50",
        "item-50:image-2",
        "item-50:text"
      ]);
      expect(detail.recentMessages.at(-1)).toMatchObject({
        role: "assistant",
        text: "最终总结",
        timestamp: "2026-04-29T08:38:47.950Z"
      });
      expect(detail.recentMessages.slice(0, 3).map((item) => item.timestamp)).toEqual([
        "2026-04-29T08:04:10.454Z",
        "2026-04-29T08:04:10.454Z",
        "2026-04-29T08:04:10.454Z"
      ]);
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("serves rollout local images when the upload cache file is missing", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-upload-rollout-fallback-"));
    const rolloutPath = path.join(tempDir, "rollout-thread-image-file.jsonl");
    const localImagePath = path.join(tempDir, "lost-cache.jpg");
    fs.writeFileSync(localImagePath, "jpeg-bytes", "utf8");
    fs.writeFileSync(
      rolloutPath,
      JSON.stringify({
        timestamp: "2026-04-29T08:04:10.454Z",
        type: "event_msg",
        payload: {
          type: "user_message",
          message: "## My request for Codex:\n看图",
          local_images: [localImagePath],
          text_elements: []
        }
      }),
      "utf8"
    );
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "Codex 安卓 App 3.0",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777451928,
          archived: false,
          firstUserMessage: "看图",
          source: "vscode",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub(),
      uploadStore: {
        persistUploadedFile: vi.fn(),
        makeRelativeUrl: vi.fn(
          (threadId: string, fileName: string) => `/api/uploads/${threadId}/${fileName}`
        ),
        resolveStoredFile: vi.fn(async () => {
          throw new Error("upload_file_not_found");
        })
      }
    });

    try {
      const file = await service.getUploadFile("thread-1", "lost-cache.jpg");

      expect(file).toEqual({
        absolutePath: localImagePath,
        mimeType: "image/jpeg"
      });
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("reuses a recent thread list snapshot when detail follows list loading", async () => {
    const listThreads = vi.fn(async () => [baseThread]);
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({ listThreads }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });

    await service.listThreads();
    await service.getThreadDetail("thread-1");

    expect(listThreads).toHaveBeenCalledTimes(1);
  });

  it("includes runtime composer state from the thread session log", async () => {
    const codexHome = fs.mkdtempSync(path.join(os.tmpdir(), "codex-mobile-runtime-"));
    const sessionsDir = path.join(codexHome, "sessions", "2026", "04", "25");
    fs.mkdirSync(sessionsDir, { recursive: true });
    fs.writeFileSync(
      path.join(sessionsDir, "rollout-2026-04-25T22-29-25-thread-1.jsonl"),
      [
        JSON.stringify({
          timestamp: "2026-04-25T14:29:37.495Z",
          type: "session_meta",
          payload: { id: "thread-1" }
        }),
        JSON.stringify({
          timestamp: "2026-04-25T14:30:59.287Z",
          type: "turn_context",
          payload: {
            model: "gpt-5.5",
            effort: "xhigh",
            approval_policy: "never",
            sandbox_policy: { type: "danger-full-access" },
            permission_profile: { type: "disabled" },
            collaboration_mode: {
              settings: {
                model: "gpt-5.5",
                reasoning_effort: "xhigh"
              }
            }
          }
        })
      ].join("\n"),
      "utf8"
    );

    try {
      const service = new MobileGatewayRuntimeService({
        authToken: "secret-token",
        appServer: createAppServerStub({
          getCodexHome: vi.fn(() => codexHome)
        }),
        stateRepository: createStateRepositoryStub(),
        logRepository: createLogRepositoryStub()
      });

      const detail = await service.getThreadDetail("thread-1");

      expect(detail.composerState).toEqual({
        permissionLabel: "完全访问权限",
        modelLabel: "GPT-5.5 超高",
        effortLabel: "超高"
      });
    } finally {
      fs.rmSync(codexHome, { recursive: true, force: true });
    }
  });

  it("builds structured file changes from app-server fileChange items", async () => {
    const threadWithFileChanges = {
      ...baseThread,
      cwd: "D:\\projects\\codex-mobile-control",
      turns: [
        {
          id: "turn-file-changes",
          status: "completed",
          startedAt: 1776857697,
          completedAt: 1776857700,
          error: null,
          durationMs: 3000,
          items: [
            {
              type: "fileChange",
              id: "file-change-test-1",
              status: "completed",
              changes: [
                {
                  path: "D:\\projects\\codex-mobile-control\\gateway\\tests\\mobile-gateway-service.test.ts",
                  kind: { type: "update", move_path: null },
                  diff: "@@ -1,0 +1,70 @@\n+line\n".repeat(70)
                }
              ]
            },
            {
              type: "fileChange",
              id: "file-change-test-2",
              status: "completed",
              changes: [
                {
                  path: "D:\\projects\\codex-mobile-control\\gateway\\tests\\mobile-gateway-service.test.ts",
                  kind: { type: "update", move_path: null },
                  diff: "@@ -1,0 +1,4 @@\n+line\n".repeat(4)
                }
              ]
            },
            {
              type: "fileChange",
              id: "file-change-test-3",
              status: "completed",
              changes: [
                {
                  path: "D:\\projects\\codex-mobile-control\\gateway\\tests\\mobile-gateway-service.test.ts",
                  kind: { type: "update", move_path: null },
                  diff: "@@ -1,0 +1,4 @@\n+line\n".repeat(4)
                }
              ]
            },
            {
              type: "fileChange",
              id: "file-change-src",
              status: "completed",
              changes: [
                {
                  path: "D:\\projects\\codex-mobile-control\\gateway\\src\\mobile-gateway-service.ts",
                  kind: { type: "update", move_path: null },
                  diff: `${"@@ -1,0 +1,26 @@\n+new\n".repeat(26)}-old\n`
                }
              ]
            }
          ]
        }
      ]
    } as CodexThread;

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [threadWithFileChanges]),
        readThread: vi.fn(async () => threadWithFileChanges)
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.fileChanges).toEqual({
      summary: "2 个文件已更改 +104 -1",
      changedFiles: 2,
      added: 104,
      removed: 1,
      items: [
        {
          path: "gateway/tests/mobile-gateway-service.test.ts",
          added: 78,
          removed: 0
        },
        {
          path: "gateway/src/mobile-gateway-service.ts",
          added: 26,
          removed: 1
        }
      ]
    });
  });

  it("keeps previous completed file changes while the latest turn is still running", async () => {
    const threadWithRunningTurnAfterFileChanges = {
      ...baseThread,
      cwd: "D:\\projects\\codex-mobile-control",
      turns: [
        {
          id: "turn-file-changes",
          status: "completed",
          startedAt: 1776857697,
          completedAt: 1776857700,
          error: null,
          durationMs: 3000,
          items: [
            {
              type: "fileChange",
              id: "file-change-src",
              status: "completed",
              changes: [
                {
                  path: "D:\\projects\\codex-mobile-control\\gateway\\src\\mobile-gateway-service.ts",
                  kind: { type: "update", move_path: null },
                  diff: "@@ -1,0 +1,4 @@\n+line\n".repeat(4)
                }
              ]
            }
          ]
        },
        {
          id: "turn-current-message",
          status: "inProgress",
          startedAt: 1776857710,
          completedAt: null,
          error: null,
          durationMs: null,
          items: [
            {
              type: "agentMessage",
              id: "item-current-agent",
              text: "这次只回复说明，没有代码变更。",
              phase: null,
              memoryCitation: null
            }
          ]
        }
      ]
    } as CodexThread;

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [threadWithRunningTurnAfterFileChanges]),
        readThread: vi.fn(async () => threadWithRunningTurnAfterFileChanges)
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.fileChanges).toEqual({
      summary: "1 个文件已更改 +4 -0",
      changedFiles: 1,
      added: 4,
      removed: 0,
      items: [
        {
          path: "gateway/src/mobile-gateway-service.ts",
          added: 4,
          removed: 0
        }
      ]
    });
  });

  it("omits older file changes when a newer completed turn has no file changes", async () => {
    const threadWithCompletedMessageAfterFileChanges = {
      ...baseThread,
      cwd: "D:\\projects\\codex-mobile-control",
      turns: [
        {
          id: "turn-file-changes",
          status: "completed",
          startedAt: 1776857697,
          completedAt: 1776857700,
          error: null,
          durationMs: 3000,
          items: [
            {
              type: "fileChange",
              id: "file-change-src",
              status: "completed",
              changes: [
                {
                  path: "D:\\projects\\codex-mobile-control\\gateway\\src\\mobile-gateway-service.ts",
                  kind: { type: "update", move_path: null },
                  diff: "@@ -1,0 +1,4 @@\n+line\n"
                }
              ]
            }
          ]
        },
        {
          id: "turn-current-message",
          status: "completed",
          startedAt: 1776857710,
          completedAt: 1776857712,
          error: null,
          durationMs: 2000,
          items: [
            {
              type: "agentMessage",
              id: "item-current-agent",
              text: "这次只回复说明，没有代码变更。",
              phase: null,
              memoryCitation: null
            }
          ]
        }
      ]
    } as CodexThread;

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [threadWithCompletedMessageAfterFileChanges]),
        readThread: vi.fn(async () => threadWithCompletedMessageAfterFileChanges)
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.fileChanges).toBeUndefined();
  });

  it("maps localImage user content into image messages", async () => {
    const imageThread: CodexThread = {
      id: "thread-1",
      preview: "开发 Codex 控制App",
      cwd: "D:\\projects\\codex-mobile-control",
      createdAt: 1776853407,
      updatedAt: 1776857697,
      name: "开发 Codex 控制App",
      status: { type: "idle" },
      turns: [
        {
          id: "turn-1",
          status: "completed",
          startedAt: 1776857697,
          completedAt: 1776857700,
          durationMs: 3000,
          error: null,
          items: [
            {
              type: "userMessage",
              id: "item-1",
              content: [
                {
                  type: "localImage",
                  path: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\171000-demo.png"
                },
                {
                  type: "text",
                  text: "看一下这张图",
                  text_elements: []
                }
              ]
            }
          ]
        }
      ]
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        readThread: vi.fn(async () => imageThread)
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      uploadStore: {
        persistUploadedFile: vi.fn(),
        makeRelativeUrl: vi.fn(
          (threadId: string, fileName: string) => `/api/uploads/${threadId}/${fileName}`
        ),
        resolveStoredFile: vi.fn()
      }
    });

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.recentMessages.slice(-2)).toEqual([
      {
        messageId: "item-1",
        threadId: "thread-1",
        role: "user",
        kind: "image",
        imageUrl: "/api/uploads/thread-1/171000-demo.png",
        thumbnailUrl: "/api/uploads/thread-1/171000-demo.png",
        fileName: "171000-demo.png",
        timestamp: "2026-04-22T11:35:00.000Z"
      },
      {
        messageId: "item-1:text",
        threadId: "thread-1",
        role: "user",
        kind: "text",
        text: "看一下这张图",
        timestamp: "2026-04-22T11:35:00.000Z"
      }
    ]);
  });

  it("keeps an active local thread running when a waiting_input log hint has no app-server waiting flag", async () => {
    const activeThreadWithoutWaitingFlags: CodexThread = {
      ...baseThread,
      name: "开发 Codex 控制App",
      status: {
        type: "active",
        activeFlags: []
      }
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [activeThreadWithoutWaitingFlags]),
        readThread: vi.fn(async () => activeThreadWithoutWaitingFlags)
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub([
        {
          signalId: "log-1",
          threadId: "thread-1",
          status: "waiting_input",
          text: "线程正在等待新的输入",
          timestamp: "2026-04-22T11:00:00.000Z",
          cursor: "11"
        }
      ])
    });

    const [threads, detail] = await Promise.all([
      service.listThreads(),
      service.getThreadDetail("thread-1")
    ]);

    expect(threads[0]).toMatchObject({
      status: "running",
      progressSummary: "线程正在运行",
      needsAttention: false
    });
    expect(detail.thread.status).toBe("running");
    expect(detail.sendAvailable).toBe(false);
  });

  it("uses the latest completed turn from thread detail when list status is stale running", async () => {
    const staleRunningThread: CodexThread = {
      ...baseThread,
      updatedAt: 1776939760,
      name: "模拟",
      status: {
        type: "active",
        activeFlags: []
      }
    };

    const completedTurnThread: CodexThread = {
      ...staleRunningThread,
      turns: [
        {
          id: "turn-9",
          status: "completed",
          startedAt: 1776939758,
          completedAt: 1776939781,
          error: null,
          items: [
            {
              type: "agentMessage",
              id: "item-agent-9",
              text: "请直接继续回答下一题。",
              phase: null,
              memoryCitation: null
            }
          ],
          durationMs: 23000
        }
      ]
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [staleRunningThread]),
        readThread: vi.fn(async () => completedTurnThread)
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "模拟",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1776939760,
          archived: false,
          firstUserMessage: "模拟面试"
        }
      ]),
      logRepository: createLogRepositoryStub([
        {
          signalId: "log-running-1",
          threadId: "thread-1",
          status: "running",
          text: "正在处理新的请求",
          timestamp: "2026-04-23T10:22:40.000Z",
          cursor: "1913670"
        }
      ])
    });

    const unsubscribe = service.subscribe(() => undefined);
    await new Promise((resolve) => setTimeout(resolve, 0));
    unsubscribe();

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.thread.status).toBe("completed");
    expect(detail.thread.progressSummary).toBe("本轮已完成");
    expect(detail.thread.updatedAt).toBe("2026-04-23T10:23:01.000Z");
    expect(detail.sendAvailable).toBe(true);
    expect(detail.sendDisabledReason).toBeUndefined();
  });

  it("keeps a terminal error over a near-simultaneous completed turn", async () => {
    const terminalTimestamp = 1778178368;
    const staleRunningThread: CodexThread = {
      ...baseThread,
      updatedAt: terminalTimestamp,
      name: "BOSS直聘安卓HOOK",
      status: {
        type: "active",
        activeFlags: []
      }
    };
    const completedTurnThread: CodexThread = {
      ...staleRunningThread,
      turns: [
        {
          id: "turn-rate-limited",
          status: "completed",
          startedAt: terminalTimestamp - 6,
          completedAt: terminalTimestamp + 0.162,
          error: null,
          items: [],
          durationMs: 6162
        }
      ]
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [staleRunningThread]),
        readThread: vi.fn(async () => completedTurnThread)
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "BOSS直聘安卓HOOK",
          cwd: "\\\\?\\D:\\codex\\phone_zhipin",
          updatedAt: terminalTimestamp,
          archived: false,
          firstUserMessage: "模板为什么会丢"
        }
      ]),
      logRepository: createLogRepositoryStub([
        {
          signalId: "turn-rate-limited:failed",
          threadId: "thread-1",
          status: "error",
          text: "exceeded retry limit, last status: 429 Too Many Requests",
          timestamp: "2026-05-07T18:26:08.000Z",
          cursor: "429"
        }
      ])
    });

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.thread.status).toBe("error");
    expect(detail.thread.progressSummary).toBe(
      "exceeded retry limit, last status: 429 Too Many Requests"
    );
    expect(detail.thread.needsAttention).toBe(true);
    expect(detail.sendAvailable).toBe(true);
    expect(detail.recentEvents.slice(-2)).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          kind: "error",
          status: "error",
          text: "exceeded retry limit, last status: 429 Too Many Requests"
        }),
        expect.objectContaining({
          kind: "turn_completed",
          status: "completed"
        })
      ])
    );
  });

  it("prefers a completed turn over a same-second running log when resolving send availability", async () => {
    const sameSecond = 1777025762;
    const staleRunningThread: CodexThread = {
      ...baseThread,
      updatedAt: sameSecond,
      name: "模拟",
      status: {
        type: "active",
        activeFlags: []
      }
    };
    const completedTurnThread: CodexThread = {
      ...staleRunningThread,
      turns: [
        {
          id: "turn-same-second",
          status: "completed",
          startedAt: sameSecond - 60,
          completedAt: sameSecond,
          error: null,
          items: [
            {
              type: "agentMessage",
              id: "item-same-second",
              text: "6666888",
              phase: null,
              memoryCitation: null
            }
          ],
          durationMs: 60000
        }
      ]
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [staleRunningThread]),
        readThread: vi.fn(async () => completedTurnThread)
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "模拟",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: sameSecond,
          archived: false,
          firstUserMessage: "模拟"
        }
      ]),
      logRepository: createLogRepositoryStub([
        {
          signalId: "log-running-same-second",
          threadId: "thread-1",
          status: "running",
          text: "正在处理新的请求",
          timestamp: "2026-04-24T10:16:02.000Z",
          cursor: "3291329"
        }
      ])
    });

    const unsubscribe = service.subscribe(() => undefined);
    await new Promise((resolve) => setTimeout(resolve, 0));
    unsubscribe();

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.thread.status).toBe("completed");
    expect(detail.thread.progressSummary).toBe("本轮已完成");
    expect(detail.sendAvailable).toBe(true);
    expect(detail.sendDisabledReason).toBeUndefined();
  });

  it("keeps a newer running list status over an older completed turn", async () => {
    const runningTimestamp = 1777148222;
    const completedTimestamp = 1777147699;
    const runningThread: CodexThread = {
      ...baseThread,
      updatedAt: runningTimestamp,
      name: "查看交接文档",
      status: {
        type: "active",
        activeFlags: []
      }
    };
    const threadWithOlderCompletedTurn: CodexThread = {
      ...runningThread,
      turns: [
        {
          id: "turn-older-completed",
          status: "completed",
          startedAt: completedTimestamp - 60,
          completedAt: completedTimestamp,
          error: null,
          items: [
            {
              type: "agentMessage",
              id: "item-older-completed",
              text: "上一轮已经完成",
              phase: null,
              memoryCitation: null
            }
          ],
          durationMs: 60000
        }
      ]
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [runningThread]),
        readThread: vi.fn(async () => threadWithOlderCompletedTurn)
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "查看交接文档",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: runningTimestamp,
          archived: false,
          firstUserMessage: "查看交接文档"
        }
      ]),
      logRepository: createLogRepositoryStub()
    });

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.thread.status).toBe("running");
    expect(detail.sendAvailable).toBe(false);
    expect(detail.sendDisabledReason).toBe("线程仍在运行，暂不支持并发发送");
  });

  it("rejects text sends before desktop bridge when the cached selected thread is running", async () => {
    const runningTimestamp = 1777148222;
    const completedTimestamp = 1777147699;
    const runningThread: CodexThread = {
      ...baseThread,
      updatedAt: runningTimestamp,
      name: "查看交接文档",
      status: {
        type: "active",
        activeFlags: []
      }
    };
    const threadWithOlderCompletedTurn: CodexThread = {
      ...runningThread,
      turns: [
        {
          id: "turn-older-completed",
          status: "completed",
          startedAt: completedTimestamp - 60,
          completedAt: completedTimestamp,
          error: null,
          items: [],
          durationMs: 60000
        }
      ]
    };
    const desktopSendCoordinator = createDesktopCoordinatorStub();

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [runningThread]),
        readThread: vi.fn(async () => threadWithOlderCompletedTurn)
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "查看交接文档",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: runningTimestamp,
          archived: false,
          firstUserMessage: "查看交接文档"
        }
      ]),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator,
      sendMode: "desktop_bridge"
    });

    await service.listThreads();

    await expect(
      service.sendMessage("thread-1", {
        text: "继续",
        clientMessageId: "client-running"
      })
    ).rejects.toMatchObject({
      statusCode: 409,
      body: {
        error: "send_unavailable",
        reason: "running",
        message: "线程仍在运行，暂不支持并发发送"
      }
    });
    expect(desktopSendCoordinator.send).not.toHaveBeenCalled();
  });

  it("allows guide text sends through desktop bridge while the cached selected thread is running", async () => {
    const runningTimestamp = 1777148222;
    const completedTimestamp = 1777147699;
    const runningThread: CodexThread = {
      ...baseThread,
      updatedAt: runningTimestamp,
      name: "查看交接文档",
      status: {
        type: "active",
        activeFlags: []
      }
    };
    const threadWithOlderCompletedTurn: CodexThread = {
      ...runningThread,
      turns: [
        {
          id: "turn-older-completed",
          status: "completed",
          startedAt: completedTimestamp - 60,
          completedAt: completedTimestamp,
          error: null,
          items: [],
          durationMs: 60000
        }
      ]
    };
    const desktopSendCoordinator = createDesktopCoordinatorStub();

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [runningThread]),
        readThread: vi.fn(async () => threadWithOlderCompletedTurn)
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "查看交接文档",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: runningTimestamp,
          archived: false,
          firstUserMessage: "查看交接文档"
        }
      ]),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator,
      sendMode: "desktop_bridge"
    });

    await service.listThreads();

    await expect(
      service.sendMessage("thread-1", {
        text: "补充一下：优先改最小范围",
        clientMessageId: "client-guide",
        guide: true
      } as any)
    ).resolves.toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-guide"
    });
    expect(desktopSendCoordinator.send).toHaveBeenCalledWith({
      threadId: "thread-1",
      title: "查看交接文档",
      text: "补充一下：优先改最小范围",
      clientMessageId: "client-guide",
      guide: true
    });
  });

  it("allows queued text sends through desktop bridge while the cached selected thread is running", async () => {
    const runningTimestamp = 1777148222;
    const completedTimestamp = 1777147699;
    const runningThread: CodexThread = {
      ...baseThread,
      updatedAt: runningTimestamp,
      name: "查看交接文档",
      status: {
        type: "active",
        activeFlags: []
      }
    };
    const threadWithOlderCompletedTurn: CodexThread = {
      ...runningThread,
      turns: [
        {
          id: "turn-older-completed",
          status: "completed",
          startedAt: completedTimestamp - 60,
          completedAt: completedTimestamp,
          error: null,
          items: [],
          durationMs: 60000
        }
      ]
    };
    const desktopSendCoordinator = createDesktopCoordinatorStub();

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [runningThread]),
        readThread: vi.fn(async () => threadWithOlderCompletedTurn)
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "查看交接文档",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: runningTimestamp,
          archived: false,
          firstUserMessage: "查看交接文档"
        }
      ]),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator,
      sendMode: "desktop_bridge"
    });

    await service.listThreads();

    await expect(
      service.sendMessage("thread-1", {
        text: "排队下一条：继续做最小修改",
        clientMessageId: "client-queue",
        queue: true
      })
    ).resolves.toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-queue"
    });
    expect(desktopSendCoordinator.send).toHaveBeenCalledWith({
      threadId: "thread-1",
      title: "查看交接文档",
      text: "排队下一条：继续做最小修改",
      clientMessageId: "client-queue"
    });
  });

  it("revalidates a stale cached running thread before rejecting text sends", async () => {
    const sameSecond = 1777044962;
    const staleRunningThread: CodexThread = {
      ...baseThread,
      updatedAt: sameSecond,
      name: "模拟",
      status: {
        type: "active",
        activeFlags: []
      }
    };
    const completedTurnThread: CodexThread = {
      ...staleRunningThread,
      turns: [
        {
          id: "turn-same-second",
          status: "completed",
          startedAt: sameSecond - 60,
          completedAt: sameSecond,
          error: null,
          items: [],
          durationMs: 60000
        }
      ]
    };
    const desktopSendCoordinator = createDesktopCoordinatorStub();

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [staleRunningThread]),
        readThread: vi.fn(async () => completedTurnThread)
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "模拟",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: sameSecond,
          archived: false,
          firstUserMessage: "模拟"
        }
      ]),
      logRepository: createLogRepositoryStub([
        {
          signalId: "log-running-same-second",
          threadId: "thread-1",
          status: "running",
          text: "正在处理新的请求",
          timestamp: "2026-04-24T10:16:02.000Z",
          cursor: "3291329"
        }
      ]),
      desktopSendCoordinator,
      sendMode: "desktop_bridge"
    });

    await service.listThreads();

    await expect(
      service.sendMessage("thread-1", {
        text: "继续",
        clientMessageId: "client-completed"
      })
    ).resolves.toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-completed"
    });
    expect(desktopSendCoordinator.send).toHaveBeenCalledWith({
      threadId: "thread-1",
      title: "模拟",
      text: "继续",
      clientMessageId: "client-completed"
    });
  });

  it("waits longer for send revalidation when fast detail fallback is stale running", async () => {
    const sameSecond = 1777044962;
    const staleRunningThread: CodexThread = {
      ...baseThread,
      updatedAt: sameSecond,
      name: "模拟",
      status: {
        type: "active",
        activeFlags: []
      }
    };
    const completedTurnThread: CodexThread = {
      ...staleRunningThread,
      turns: [
        {
          id: "turn-send-late-completed",
          status: "completed",
          startedAt: sameSecond - 60,
          completedAt: sameSecond,
          error: null,
          items: [],
          durationMs: 60000
        }
      ]
    };
    const readThread = vi.fn(
      () =>
        new Promise<CodexThread>((resolve) => {
          setTimeout(() => resolve(completedTurnThread), 650);
        })
    );
    const desktopSendCoordinator = createDesktopCoordinatorStub();

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [staleRunningThread]),
        readThread
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "模拟",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: sameSecond,
          archived: false,
          firstUserMessage: "模拟"
        }
      ]),
      logRepository: createLogRepositoryStub([
        {
          signalId: "log-running-same-second",
          threadId: "thread-1",
          status: "running",
          text: "正在处理新的请求",
          timestamp: "2026-04-24T10:16:02.000Z",
          cursor: "3291329"
        }
      ]),
      desktopSendCoordinator,
      sendMode: "desktop_bridge"
    });

    await service.listThreads();

    await expect(
      service.sendMessage("thread-1", {
        text: "继续",
        clientMessageId: "client-late-completed"
      })
    ).resolves.toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-late-completed"
    });
    expect(readThread).toHaveBeenCalledOnce();
    expect(desktopSendCoordinator.send).toHaveBeenCalled();
  });

  it("tries the desktop bridge when a stale running send cannot be revalidated", async () => {
    const sameSecond = 1777044962;
    const staleRunningThread: CodexThread = {
      ...baseThread,
      updatedAt: sameSecond,
      name: "模拟",
      status: {
        type: "active",
        activeFlags: []
      }
    };
    const readThread = vi.fn(async () => {
      throw new Error("app_server_request_timeout:thread/read");
    });
    const desktopSendCoordinator = createDesktopCoordinatorStub();

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [staleRunningThread]),
        readThread
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "模拟",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: sameSecond,
          archived: false,
          firstUserMessage: "模拟"
        }
      ]),
      logRepository: createLogRepositoryStub([
        {
          signalId: "log-stale-running",
          threadId: "thread-1",
          status: "running",
          text: "正在处理新的请求",
          timestamp: "2026-04-24T10:16:02.000Z",
          cursor: "3291329"
        }
      ]),
      desktopSendCoordinator,
      sendMode: "desktop_bridge"
    });

    await service.listThreads();

    await expect(
      service.sendMessage("thread-1", {
        text: "继续",
        clientMessageId: "client-unverified-running"
      })
    ).resolves.toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-unverified-running"
    });
    expect(readThread).toHaveBeenCalledOnce();
    expect(desktopSendCoordinator.send).toHaveBeenCalledWith({
      threadId: "thread-1",
      title: "模拟",
      text: "继续",
      clientMessageId: "client-unverified-running"
    });
  });

  it("uses the completed detail snapshot for send when the list cache was stale running", async () => {
    const sameSecond = 1777044962;
    const staleRunningThread: CodexThread = {
      ...baseThread,
      updatedAt: sameSecond,
      name: "模拟",
      status: {
        type: "active",
        activeFlags: []
      }
    };
    const completedTurnThread: CodexThread = {
      ...staleRunningThread,
      turns: [
        {
          id: "turn-same-second",
          status: "completed",
          startedAt: sameSecond - 60,
          completedAt: sameSecond,
          error: null,
          items: [],
          durationMs: 60000
        }
      ]
    };
    const readThread = vi
      .fn()
      .mockResolvedValueOnce(completedTurnThread)
      .mockRejectedValueOnce(new Error("app_server_request_timeout:thread/read"));
    const desktopSendCoordinator = createDesktopCoordinatorStub();

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [staleRunningThread]),
        readThread
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "模拟",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: sameSecond,
          archived: false,
          firstUserMessage: "模拟"
        }
      ]),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator,
      sendMode: "desktop_bridge"
    });

    await service.listThreads();
    const detail = await service.getThreadDetail("thread-1");

    expect(detail.thread.status).toBe("completed");
    await expect(
      service.sendMessage("thread-1", {
        text: "继续",
        clientMessageId: "client-detail-cache"
      })
    ).resolves.toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-detail-cache"
    });
    expect(readThread).toHaveBeenCalledOnce();
    expect(desktopSendCoordinator.send).toHaveBeenCalled();
  });

  it("deduplicates completed alerts emitted from repeated notifications", async () => {
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });

    const completedNotification: CodexNotification = {
      method: "turn/completed",
      params: {
        threadId: "thread-1",
        turn: {
          id: "turn-9",
          status: "completed",
          startedAt: 1776857697,
          completedAt: 1776857700,
          error: null,
          items: [],
          durationMs: 3000
        }
      }
    };

    service.handleNotification(completedNotification);
    service.handleNotification(completedNotification);

    const alerts = await service.getAlerts(null);

    expect(alerts.alerts).toHaveLength(1);
    expect(alerts.alerts[0]).toMatchObject({
      threadId: "thread-1",
      trigger: "completed"
    });
  });

  it("emits completed alerts for separate turns with the same summary text", async () => {
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });
    const events: any[] = [];
    service.subscribe((event) => events.push(event));

    for (const turnId of ["turn-9", "turn-10"]) {
      service.handleNotification({
        method: "turn/completed",
        params: {
          threadId: "thread-1",
          turn: {
            id: turnId,
            status: "completed",
            startedAt: 1776857697,
            completedAt: turnId === "turn-9" ? 1776857700 : 1776857760,
            error: null,
            items: [],
            durationMs: 3000
          }
        }
      });
    }

    const alerts = await service.getAlerts(null);

    expect(alerts.alerts.map((alert) => alert.alertId)).toEqual([
      "thread-1:completed:turn-9",
      "thread-1:completed:turn-10"
    ]);
    expect(events.filter((event) => event.trigger === "completed")).toHaveLength(2);
  });

  it("does not read sqlite log signals when serving alerts", async () => {
    const logRepository = createLogRepositoryStub([
      {
        signalId: "log-weak-wait",
        threadId: "thread-1",
        status: "waiting_input",
        text: "线程正在等待新的输入",
        timestamp: "2026-04-30T02:13:55.000Z",
        cursor: "11833192",
        notificationEligible: true
      }
    ]);
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository
    });

    const threads = await service.listThreads();
    const alerts = await service.getAlerts(null);

    expect(logRepository.listRecentSignals).not.toHaveBeenCalled();
    expect(threads[0]).toMatchObject({
      status: "idle",
      progressSummary: "我有个需求，就是写一个手机APP去控制整个codex桌面版",
      needsAttention: false
    });
    expect(alerts.alerts).toEqual([]);
  });

  it("ignores strong sqlite log-derived waiting input signals for alerts", async () => {
    const logRepository = createLogRepositoryStub([
      {
        signalId: "log-strong-wait",
        threadId: "thread-1",
        status: "waiting_input",
        text: "线程正在等待新的输入",
        timestamp: "2026-04-30T02:13:55.000Z",
        cursor: "11833192",
        notificationEligible: true
      }
    ]);
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository
    });

    const alerts = await service.getAlerts(null);

    expect(logRepository.listRecentSignals).not.toHaveBeenCalled();
    expect(alerts.alerts).toEqual([]);
  });

  it("emits a server-owned status event and notification when a turn completes", async () => {
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });
    const events: any[] = [];
    service.subscribe((event) => events.push(event));

    const completedNotification: CodexNotification = {
      method: "turn/completed",
      params: {
        threadId: "thread-1",
        turn: {
          id: "turn-9",
          status: "completed",
          startedAt: 1776857697,
          completedAt: 1776857700,
          error: null,
          items: [],
          durationMs: 3000
        }
      }
    };

    service.handleNotification(completedNotification);
    service.handleNotification(completedNotification);

    expect(events.filter((event) => event.kind === "turn_completed")).toHaveLength(1);
    expect(events.filter((event) => event.trigger === "completed")).toHaveLength(1);
    expect(events.find((event) => event.trigger === "completed")).toMatchObject({
      alertId: "thread-1:completed:turn-9",
      threadId: "thread-1",
      title: "本轮已完成",
      body: "已处理 3s"
    });
  });

  it("polls thread detail after a mobile send and emits a completed alert for the new turn", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-04-30T03:48:53.000Z"));

    const oldCompletedTurn = {
      id: "turn-old",
      status: "completed" as const,
      startedAt: 1777517270,
      completedAt: 1777517280,
      error: null,
      items: [],
      durationMs: 10000
    };
    const beforeNewTurn: CodexThread = {
      ...baseThread,
      updatedAt: 1777517333,
      turns: [oldCompletedTurn]
    };
    const newCompletedTurn = {
      id: "turn-mobile-1",
      status: "completed" as const,
      startedAt: 1777517338,
      completedAt: 1777517350,
      error: null,
      items: [],
      durationMs: 12000
    };
    const afterNewTurn: CodexThread = {
      ...beforeNewTurn,
      updatedAt: 1777517350,
      turns: [oldCompletedTurn, newCompletedTurn]
    };
    const readThread = vi
      .fn()
      .mockResolvedValueOnce(beforeNewTurn)
      .mockResolvedValue(afterNewTurn);
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({ readThread }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator: createDesktopCoordinatorStub()
    });
    const events: any[] = [];
    service.subscribe((event) => events.push(event));

    await service.sendMessage("thread-1", {
      text: "继续",
      clientMessageId: "client-mobile-observe"
    });

    await vi.advanceTimersByTimeAsync(2_000);
    expect((await service.getAlerts(null)).alerts).toEqual([]);

    await vi.advanceTimersByTimeAsync(2_000);

    const alerts = await service.getAlerts(null);
    expect(alerts.alerts).toEqual([
      expect.objectContaining({
        alertId: "thread-1:completed:turn-mobile-1",
        threadId: "thread-1",
        trigger: "completed",
        title: "本轮已完成",
        body: "已处理 12s"
      })
    ]);
    expect(events.filter((event) => event.trigger === "completed")).toHaveLength(1);
  });

  it("polls rollout tail after a mobile send and emits a completed alert without reading full thread detail", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-04-30T03:48:53.000Z"));

    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-rollout-observe-"));
    const rolloutPath = path.join(tempDir, "rollout.jsonl");
    const rolloutLine = (entry: unknown) => `${JSON.stringify(entry)}\n`;
    fs.writeFileSync(
      rolloutPath,
      [
        rolloutLine({
          timestamp: "2026-04-30T03:47:50.000Z",
          type: "event_msg",
          payload: { type: "task_started", turn_id: "turn-old" }
        }),
        rolloutLine({
          timestamp: "2026-04-30T03:48:00.000Z",
          type: "event_msg",
          payload: { type: "task_complete", turn_id: "turn-old" }
        })
      ].join(""),
      "utf8"
    );

    try {
      const readThread = vi.fn(async () => {
        throw new Error("thread/read should not be called");
      });
      const service = new MobileGatewayRuntimeService({
        authToken: "secret-token",
        appServer: createAppServerStub({ readThread }),
        stateRepository: createStateRepositoryStub([
          {
            threadId: "thread-1",
            title: "模拟",
            cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
            updatedAt: 1777517333,
            archived: false,
            firstUserMessage: "模拟",
            rolloutPath
          }
        ]),
        logRepository: createLogRepositoryStub(),
        desktopSendCoordinator: createDesktopCoordinatorStub()
      });
      const events: any[] = [];
      service.subscribe((event) => events.push(event));

      await service.sendMessage("thread-1", {
        text: "继续",
        clientMessageId: "client-rollout-observe"
      });

      fs.appendFileSync(
        rolloutPath,
        [
          rolloutLine({
            timestamp: "2026-04-30T03:48:58.000Z",
            type: "event_msg",
            payload: { type: "task_started", turn_id: "turn-mobile-rollout" }
          }),
          rolloutLine({
            timestamp: "2026-04-30T03:49:10.000Z",
            type: "event_msg",
            payload: { type: "task_complete", turn_id: "turn-mobile-rollout" }
          })
        ].join(""),
        "utf8"
      );
      await vi.advanceTimersByTimeAsync(2_000);

      await vi.waitFor(async () => {
        const alerts = await service.getAlerts(null);
        expect(alerts.alerts).toEqual([
          expect.objectContaining({
            alertId: "thread-1:completed:turn-mobile-rollout",
            threadId: "thread-1",
            trigger: "completed",
            title: "本轮已完成",
            body: "已处理 12s"
          })
        ]);
      });
      expect(events.filter((event) => event.trigger === "completed")).toHaveLength(1);
      expect(readThread).not.toHaveBeenCalled();
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("polls rollout tail after a mobile send and emits appended assistant messages", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-04-30T03:48:53.000Z"));

    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-rollout-message-observe-"));
    const rolloutPath = path.join(tempDir, "rollout.jsonl");
    const rolloutLine = (entry: unknown) => `${JSON.stringify(entry)}\n`;
    fs.writeFileSync(
      rolloutPath,
      rolloutLine({
        timestamp: "2026-04-30T03:48:00.000Z",
        type: "event_msg",
        payload: { type: "task_complete", turn_id: "turn-old" }
      }),
      "utf8"
    );

    try {
      const readThread = vi.fn(async () => {
        throw new Error("thread/read should not be called");
      });
      const service = new MobileGatewayRuntimeService({
        authToken: "secret-token",
        appServer: createAppServerStub({ readThread }),
        stateRepository: createStateRepositoryStub([
          {
            threadId: "thread-1",
            title: "模拟",
            cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
            updatedAt: 1777517333,
            archived: false,
            firstUserMessage: "模拟",
            rolloutPath
          }
        ]),
        logRepository: createLogRepositoryStub(),
        desktopSendCoordinator: createDesktopCoordinatorStub()
      });
      const events: any[] = [];
      service.subscribe((event) => events.push(event));

      await service.sendMessage("thread-1", {
        text: "继续",
        clientMessageId: "client-rollout-message"
      });

      fs.appendFileSync(
        rolloutPath,
        rolloutLine({
          timestamp: "2026-04-30T03:48:58.000Z",
          type: "response_item",
          payload: {
            id: "assistant-rollout-1",
            type: "message",
            role: "assistant",
            content: [{ type: "output_text", text: "666999" }]
          }
        }),
        "utf8"
      );
      await vi.advanceTimersByTimeAsync(2_000);

      await vi.waitFor(() => {
        expect(events.filter((event) => event.type === "thread_messages_appended")).toEqual([
          expect.objectContaining({
            threadId: "thread-1",
            messages: [
              expect.objectContaining({
                messageId: "assistant-rollout-1",
                role: "assistant",
                text: "666999"
              })
            ]
          })
        ]);
      });
      expect(readThread).not.toHaveBeenCalled();
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("marks an observed mobile turn as replaced when another rollout turn starts first", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-04-30T03:48:57.000Z"));
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-rollout-replaced-turn-"));
    const rolloutPath = path.join(tempDir, "rollout.jsonl");
    const rolloutLine = (entry: unknown) => `${JSON.stringify(entry)}\n`;
    fs.writeFileSync(rolloutPath, "", "utf8");
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "模拟",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777517333,
          archived: false,
          firstUserMessage: "模拟",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub(),
      sendMode: "official_persistence"
    });
    const events: any[] = [];
    service.subscribe((event) => events.push(event));

    try {
      await service.sendMessage("thread-1", {
        text: "继续",
        clientMessageId: "client-replaced-turn"
      });

      fs.appendFileSync(
        rolloutPath,
        [
          rolloutLine({
            timestamp: "2026-04-30T03:48:58.000Z",
            type: "event_msg",
            payload: {
              type: "task_started",
              turn_id: "turn-from-mobile"
            }
          }),
          rolloutLine({
            timestamp: "2026-04-30T03:49:10.000Z",
            type: "event_msg",
            payload: {
              type: "task_started",
              turn_id: "turn-newer"
            }
          })
        ].join(""),
        "utf8"
      );
      await vi.advanceTimersByTimeAsync(2_000);

      await vi.waitFor(() => {
        expect(events).toEqual(
          expect.arrayContaining([
            expect.objectContaining({
              threadId: "thread-1",
              kind: "error",
              status: "error",
              text: "上一轮未收到结束事件，已被新任务替换"
            })
          ])
        );
      });
      await expect(service.getRuntimeDiagnostics()).resolves.toMatchObject({
        threadObservations: {
          active: 0,
          lastStopped: {
            threadId: "thread-1",
            clientMessageId: "client-replaced-turn",
            rolloutObservedTurnId: "turn-from-mobile",
            stopReason: "replaced",
            finalStatus: "error"
          }
        }
      });
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("uses app-server assistant deltas as rollout message catch-up triggers", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-rollout-delta-catchup-"));
    const rolloutPath = path.join(tempDir, "rollout.jsonl");
    fs.writeFileSync(
      rolloutPath,
      `${JSON.stringify({
        timestamp: "2026-04-30T03:48:58.000Z",
        type: "response_item",
        payload: {
          id: "assistant-message-1",
          type: "message",
          role: "assistant",
          content: [{ type: "output_text", text: "完整回复" }]
        }
      })}\n`,
      "utf8"
    );
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "模拟",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777517333,
          archived: false,
          firstUserMessage: "模拟",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });
    const events: any[] = [];
    service.subscribe((event) => events.push(event));

    try {
      service.handleNotification({
        method: "item/agentMessage/delta",
        params: {
          threadId: "thread-1",
          itemId: "assistant-message-1",
          delta: "不完整"
        }
      });

      await vi.waitFor(() => {
        expect(events.filter((event) => event.type === "thread_messages_appended")).toEqual([
          expect.objectContaining({
            eventId: "thread-1:message:assistant-message-1",
            threadId: "thread-1",
            messages: [
              expect.objectContaining({
                messageId: "assistant-message-1",
                role: "assistant",
                text: "完整回复"
              })
            ]
          })
        ]);
      });
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("emits message alerts for appended assistant rollout messages", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-rollout-message-alert-"));
    const rolloutPath = path.join(tempDir, "rollout.jsonl");
    const turnStartedAt = Date.parse("2026-04-30T03:46:45.000Z") / 1000;
    fs.writeFileSync(
      rolloutPath,
      `${JSON.stringify({
        timestamp: "2026-04-30T03:48:58.000Z",
        type: "response_item",
        payload: {
          id: "assistant-message-alert-1",
          type: "message",
          role: "assistant",
          content: [{ type: "output_text", text: "最新回复内容" }]
        }
      })}\n`,
      "utf8"
    );
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "模拟",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777517333,
          archived: false,
          firstUserMessage: "模拟",
          rolloutPath
        }
      ]),
      logRepository: createLogRepositoryStub()
    });
    const events: any[] = [];
    service.subscribe((event) => events.push(event));

    try {
      service.handleNotification({
        method: "turn/started",
        params: {
          threadId: "thread-1",
          turn: {
            id: "turn-message-alert-1",
            status: "inProgress",
            startedAt: turnStartedAt,
            completedAt: null,
            error: null,
            items: [],
            durationMs: null
          }
        }
      });
      service.handleNotification({
        method: "item/agentMessage/delta",
        params: {
          threadId: "thread-1",
          itemId: "assistant-message-alert-1",
          delta: "不完整"
        }
      });

      await vi.waitFor(async () => {
        const alerts = await service.getAlerts(null);
        expect(alerts.alerts).toEqual([
          expect.objectContaining({
            alertId: "thread-1:message:assistant-message-alert-1",
            threadId: "thread-1",
            trigger: "message",
            title: "有新消息",
            body: "已处理 2m 13s · 最新回复内容"
          })
        ]);
      });
      expect(events.filter((event) => event.trigger === "message")).toHaveLength(1);
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("does not emit app-server assistant deltas directly when rollout has not caught up", async () => {
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub()
    });
    const events: any[] = [];
    service.subscribe((event) => events.push(event));

    service.handleNotification({
      method: "item/agentMessage/delta",
      params: {
        threadId: "thread-1",
        itemId: "assistant-message-1",
        delta: "不完整"
      }
    });

    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(events.filter((event) => event.type === "thread_messages_appended")).toEqual([]);
  });

  it("sends mobile text messages through desktop bridge without app-server turn start", async () => {
    const resumeThread = vi.fn(async (_threadId: string) => undefined);
    const startTurn = vi.fn(async (_threadId: string, _text: string) => ({
      accepted: true,
      turnId: "turn-2"
    }));
    const desktopSendCoordinator = createDesktopCoordinatorStub();

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        resumeThread,
        startTurn
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator,
      sendMode: "desktop_bridge"
    });

    const response = await service.sendMessage("thread-1", {
      text: "请继续推进",
      clientMessageId: "client-1"
    });

    expect(response).toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-1",
      sendPath: "desktop_bridge",
      confirmation: "observed"
    });
    expect(desktopSendCoordinator.send).toHaveBeenCalledWith({
      threadId: "thread-1",
      title: "thread-1",
      text: "请继续推进",
      clientMessageId: "client-1"
    });
    expect(resumeThread).not.toHaveBeenCalled();
    expect(startTurn).not.toHaveBeenCalled();
  });

  it("defaults mobile text messages to official app-server persistence", async () => {
    const resumeThread = vi.fn(async (_threadId: string) => undefined);
    const startTurn = vi.fn(async (_threadId: string, _text: string) => ({
      accepted: true,
      turnId: "turn-2"
    }));
    const desktopSendCoordinator = createDesktopCoordinatorStub();

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        resumeThread,
        startTurn
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator
    });

    const response = await service.sendMessage("thread-1", {
      text: "请继续推进",
      clientMessageId: "client-default-official"
    });

    expect(response).toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-default-official",
      sendPath: "app_server",
      confirmation: "observed"
    });
    expect(resumeThread).toHaveBeenCalledWith("thread-1");
    expect(startTurn).toHaveBeenCalledWith("thread-1", "请继续推进");
    expect(desktopSendCoordinator.send).not.toHaveBeenCalled();
  });

  it("can send mobile text messages through official app-server persistence when enabled", async () => {
    const resumeThread = vi.fn(async (_threadId: string) => undefined);
    const startTurn = vi.fn(async (_threadId: string, _text: string) => ({
      accepted: true,
      turnId: "turn-2"
    }));
    const desktopSendCoordinator = createDesktopCoordinatorStub();

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        resumeThread,
        startTurn
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator,
      sendMode: "official_persistence"
    });

    const response = await service.sendMessage("thread-1", {
      text: "请继续推进",
      clientMessageId: "client-1"
    });

    expect(response).toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-1",
      sendPath: "app_server",
      confirmation: "observed"
    });
    expect(resumeThread).toHaveBeenCalledWith("thread-1");
    expect(startTurn).toHaveBeenCalledWith("thread-1", "请继续推进");
    expect(desktopSendCoordinator.send).not.toHaveBeenCalled();
  });

  it("sends guide text through app-server turn steer when official persistence is enabled", async () => {
    const runningThread: CodexThread = {
      ...baseThread,
      updatedAt: 1777148222,
      name: "查看交接文档",
      status: {
        type: "active",
        activeFlags: []
      },
      turns: [
        {
          id: "turn-running",
          status: "inProgress",
          startedAt: 1777148222,
          completedAt: null,
          error: null,
          items: [],
          durationMs: null
        }
      ]
    };
    const resumeThread = vi.fn(async (_threadId: string) => undefined);
    const steerTurnWithInput = vi.fn(async (_threadId: string, _input, _expectedTurnId: string) => ({
      accepted: true,
      turnId: "turn-running"
    }));
    const desktopSendCoordinator = createDesktopCoordinatorStub();

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [runningThread]),
        readThread: vi.fn(async () => runningThread),
        resumeThread,
        steerTurnWithInput
      } as any),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "查看交接文档",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777148222,
          archived: false,
          firstUserMessage: "查看交接文档"
        }
      ]),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator,
      sendMode: "official_persistence"
    });

    await service.listThreads();

    const response = await service.sendMessage("thread-1", {
      text: "补充一下：优先改最小范围",
      clientMessageId: "client-guide",
      guide: true
    } as any);

    expect(response).toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-guide",
      sendPath: "app_server",
      confirmation: "observed"
    });
    expect(resumeThread).toHaveBeenCalledWith("thread-1");
    expect(steerTurnWithInput).toHaveBeenCalledWith("thread-1", [
      {
        type: "text",
        text: "补充一下：优先改最小范围",
        text_elements: []
      }
    ], "turn-running");
    expect(desktopSendCoordinator.send).not.toHaveBeenCalled();
  });

  it("queues text in Gateway and dispatches through app-server when official persistence is enabled", async () => {
    vi.useFakeTimers();
    const runningThread: CodexThread = {
      ...baseThread,
      updatedAt: 1777148222,
      name: "查看交接文档",
      status: {
        type: "active",
        activeFlags: []
      },
      turns: [
        {
          id: "turn-running",
          status: "inProgress",
          startedAt: 1777148222,
          completedAt: null,
          error: null,
          items: [],
          durationMs: null
        }
      ]
    };
    const completedThread: CodexThread = {
      ...runningThread,
      status: { type: "idle" },
      turns: [
        {
          id: "turn-running",
          status: "completed",
          startedAt: 1777148222,
          completedAt: 1777148282,
          error: null,
          items: [],
          durationMs: 60000
        }
      ]
    };
    let queueCanDispatch = false;
    const resumeThread = vi.fn(async (_threadId: string) => undefined);
    const startTurnWithInput = vi.fn(async (_threadId: string, _input) => ({
      accepted: true,
      turnId: "turn-queued"
    }));
    const desktopSendCoordinator = createDesktopCoordinatorStub();

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        listThreads: vi.fn(async () => [runningThread]),
        readThread: vi.fn(async () => (queueCanDispatch ? completedThread : runningThread)),
        resumeThread,
        startTurnWithInput
      }),
      stateRepository: createStateRepositoryStub([
        {
          threadId: "thread-1",
          title: "查看交接文档",
          cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
          updatedAt: 1777148222,
          archived: false,
          firstUserMessage: "查看交接文档"
        }
      ]),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator,
      sendMode: "official_persistence",
      queueDispatchIntervalMs: 10
    });

    await service.listThreads();

    const response = await service.sendMessage("thread-1", {
      text: "排队下一条：继续做最小修改",
      clientMessageId: "client-queue",
      queue: true
    });
    await vi.runOnlyPendingTimersAsync();

    expect(response).toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-queue",
      sendPath: "app_server",
      confirmation: "observed",
      queued: true,
      queueId: "client-queue"
    });
    expect(await service.getQueuedTextMessages("thread-1")).toEqual([
      expect.objectContaining({
        queueId: "client-queue",
        text: "排队下一条：继续做最小修改",
        status: "PENDING"
      })
    ]);
    expect(resumeThread).not.toHaveBeenCalled();
    expect(startTurnWithInput).not.toHaveBeenCalled();

    queueCanDispatch = true;
    await vi.advanceTimersByTimeAsync(10);

    expect(resumeThread).toHaveBeenCalledWith("thread-1");
    expect(startTurnWithInput).toHaveBeenCalledWith("thread-1", [
      {
        type: "text",
        text: "排队下一条：继续做最小修改",
        text_elements: []
      }
    ]);
    expect(await service.getQueuedTextMessages("thread-1")).toEqual([]);
    expect(desktopSendCoordinator.send).not.toHaveBeenCalled();
  });

  it("queues desktop sends so only one composer flow runs at a time", async () => {
    let releaseFirst: () => void = () => {
      throw new Error("first desktop send was not started");
    };
    const sendOrder: string[] = [];
    const desktopSendCoordinator = {
      send: vi.fn(async (request: { clientMessageId: string; threadId: string }) => {
        sendOrder.push(`start:${request.clientMessageId}`);
        if (request.clientMessageId === "client-first") {
          await new Promise<void>((resolve) => {
            releaseFirst = resolve;
          });
        }
        sendOrder.push(`end:${request.clientMessageId}`);
        return {
          accepted: true,
          threadId: request.threadId,
          clientMessageId: request.clientMessageId,
          sendPath: "desktop_bridge" as const,
          confirmation: "observed" as const
        };
      })
    } as unknown as DesktopSendCoordinator;

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator,
      sendMode: "desktop_bridge"
    });

    const first = service.sendMessage("thread-1", {
      text: "第一条",
      clientMessageId: "client-first"
    });
    await waitForAssertion(() => expect(desktopSendCoordinator.send).toHaveBeenCalledTimes(1));

    const second = service.sendMessage("thread-1", {
      text: "第二条",
      clientMessageId: "client-second"
    });
    await Promise.resolve();

    expect(desktopSendCoordinator.send).toHaveBeenCalledTimes(1);
    expect(sendOrder).toEqual(["start:client-first"]);
    await expect(service.getRuntimeDiagnostics()).resolves.toMatchObject({
      desktopSendQueue: {
        pending: 1,
        active: {
          clientMessageId: "client-first",
          threadId: "thread-1",
          kind: "text",
          status: "running"
        },
        queued: [
          {
            clientMessageId: "client-second",
            threadId: "thread-1",
            kind: "text",
            status: "queued"
          }
        ]
      }
    });

    releaseFirst();
    await first;
    await waitForAssertion(() => expect(desktopSendCoordinator.send).toHaveBeenCalledTimes(2));
    const response = await second;

    expect(response).toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-second",
      sendPath: "desktop_bridge",
      confirmation: "observed"
    });
    expect(sendOrder).toEqual([
      "start:client-first",
      "end:client-first",
      "start:client-second",
      "end:client-second"
    ]);
    await expect(service.getRuntimeDiagnostics()).resolves.toMatchObject({
      desktopSendQueue: {
        pending: 0,
        active: null,
        queued: [],
        lastFinished: {
          clientMessageId: "client-second",
          threadId: "thread-1",
          kind: "text",
          status: "completed"
        },
        lastFailed: null
      }
    });
  });

  it("exposes active mobile send observations in runtime diagnostics", async () => {
    let releaseDesktopSend: () => void = () => {
      throw new Error("desktop send was not started");
    };
    const desktopSendCoordinator = {
      send: vi.fn(async (request: { clientMessageId: string; threadId: string }) => {
        await new Promise<void>((resolve) => {
          releaseDesktopSend = resolve;
        });
        return {
          accepted: true,
          threadId: request.threadId,
          clientMessageId: request.clientMessageId,
          sendPath: "desktop_bridge" as const,
          confirmation: "observed" as const
        };
      })
    } as unknown as DesktopSendCoordinator;

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator,
      sendMode: "desktop_bridge"
    });

    const send = service.sendMessage("thread-1", {
      text: "请继续推进",
      clientMessageId: "client-observed"
    });
    await waitForAssertion(() => expect(desktopSendCoordinator.send).toHaveBeenCalledTimes(1));

    await expect(service.getRuntimeDiagnostics()).resolves.toMatchObject({
      threadObservations: {
        active: 1,
        items: [
          {
            threadId: "thread-1",
            clientMessageId: "client-observed",
            inFlight: false,
            rolloutTracking: false
          }
        ]
      }
    });

    const diagnostics = await service.getRuntimeDiagnostics();
    expect(diagnostics.threadObservations.items[0]?.submittedAt).toMatch(/T/);
    expect(diagnostics.threadObservations.items[0]?.deadlineAt).toMatch(/T/);

    releaseDesktopSend();
    await send;
  });

  it("records recent desktop send request lifecycle in runtime diagnostics", async () => {
    const desktopSendCoordinator = {
      send: vi.fn(async (request: { clientMessageId: string; threadId: string }) => ({
        accepted: true,
        threadId: request.threadId,
        clientMessageId: request.clientMessageId,
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      }))
    } as unknown as DesktopSendCoordinator;

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator,
      sendMode: "desktop_bridge"
    });

    await service.sendMessage("thread-1", {
      text: "请继续推进",
      clientMessageId: "client-recent"
    });

    await expect(service.getRuntimeDiagnostics()).resolves.toMatchObject({
      desktopSendQueue: {
        recent: [
          {
            clientMessageId: "client-recent",
            threadId: "thread-1",
            kind: "text",
            status: "queued"
          },
          {
            clientMessageId: "client-recent",
            threadId: "thread-1",
            kind: "text",
            status: "running"
          },
          {
            clientMessageId: "client-recent",
            threadId: "thread-1",
            kind: "text",
            status: "completed"
          }
        ]
      }
    });
  });

  it("records a mobile event when the desktop bridge send fails", async () => {
    const resumeThread = vi.fn(async (_threadId: string) => undefined);
    const startTurn = vi.fn(async (_threadId: string, _text: string) => ({
      accepted: true,
      turnId: "turn-2"
    }));
    const desktopSendCoordinator = {
      send: vi.fn(async () => {
        throw new Error("desktop_bridge_failed");
      })
    } as unknown as DesktopSendCoordinator;

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        resumeThread,
        startTurn
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator,
      sendMode: "desktop_bridge"
    });

    await expect(
      service.sendMessage("thread-1", {
        text: "请继续推进",
        clientMessageId: "client-1"
      })
    ).rejects.toThrow("desktop_bridge_failed");

    expect(desktopSendCoordinator.send).toHaveBeenCalledWith({
      threadId: "thread-1",
      title: "thread-1",
      text: "请继续推进",
      clientMessageId: "client-1"
    });
    const events = await service.getThreadEvents("thread-1");
    expect(events.events.at(-1)).toMatchObject({
      threadId: "thread-1",
      kind: "error",
      text: "桌面发送失败: desktop_bridge_failed",
      status: "error"
    });
    expect(resumeThread).not.toHaveBeenCalled();
    expect(startTurn).not.toHaveBeenCalled();
  });

  it("keeps the last stopped mobile send observation reason in diagnostics", async () => {
    const desktopSendCoordinator = {
      send: vi.fn(async () => {
        throw new Error("desktop_bridge_failed");
      })
    } as unknown as DesktopSendCoordinator;
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator,
      sendMode: "desktop_bridge"
    });

    await expect(
      service.sendMessage("thread-1", {
        text: "请继续推进",
        clientMessageId: "client-failed-observation"
      })
    ).rejects.toThrow("desktop_bridge_failed");

    const diagnostics = await service.getRuntimeDiagnostics();
    expect(diagnostics.threadObservations.lastStopped).toMatchObject({
      threadId: "thread-1",
      clientMessageId: "client-failed-observation",
      stopReason: "send_failed",
      error: "desktop_bridge_failed"
    });
  });

  it("records confirmation timeout as a mobile event after desktop bridge sends keystrokes", async () => {
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        startTurn: vi.fn(async () => {
          throw new Error("turn_start_failed");
        })
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator: createDesktopCoordinatorStub({
        accepted: true,
        threadId: "thread-1",
        clientMessageId: "client-1",
        sendPath: "desktop_bridge",
        confirmation: "keystrokes_sent",
        warning: "desktop_send_confirmation_timeout"
      }),
      sendMode: "desktop_bridge"
    });

    await service.sendMessage("thread-1", {
      text: "请继续推进",
      clientMessageId: "client-1"
    });

    const events = await service.getThreadEvents("thread-1");
    expect(events.events.at(-1)).toMatchObject({
      threadId: "thread-1",
      kind: "turn_started",
      text: "已发送到桌面，正在等待确认"
    });
  });

  it("keeps the optimistic mobile message when detail is refreshed before desktop logs catch up", async () => {
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        startTurn: vi.fn(async () => {
          throw new Error("turn_start_failed");
        })
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      desktopSendCoordinator: createDesktopCoordinatorStub({
        accepted: true,
        threadId: "thread-1",
        clientMessageId: "client-optimistic-1",
        sendPath: "desktop_bridge",
        confirmation: "keystrokes_sent",
        warning: "desktop_send_confirmation_timeout"
      }),
      sendMode: "desktop_bridge"
    });

    await service.sendMessage("thread-1", {
      text: "手机端待确认消息",
      clientMessageId: "client-optimistic-1"
    });

    const detail = await service.getThreadDetail("thread-1");

    expect(detail.recentMessages.at(-1)).toMatchObject({
      role: "user",
      kind: "text",
      text: "手机端待确认消息"
    });
  });

  it("sends image messages through desktop bridge without app-server localImage input", async () => {
    const resumeThread = vi.fn(async (_threadId: string) => undefined);
    const startTurnWithInput = vi.fn(async (_threadId: string, _input) => ({
      accepted: true,
      turnId: "turn-image-1"
    }));
    const imageSendCoordinator = {
      send: vi.fn(async () => ({
        accepted: true,
        threadId: "thread-1",
        clientMessageId: "client-image-1",
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      })),
      sendMany: vi.fn(async () => ({
        accepted: true,
        threadId: "thread-1",
        clientMessageId: "client-image-batch-1",
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      }))
    };
    const uploadStore = {
      persistUploadedFile: vi.fn(async () => ({
        threadId: "thread-1",
        fileName: "demo.png",
        absolutePath: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png",
        mimeType: "image/png"
      })),
      resolveStoredFile: vi.fn(),
      makeRelativeUrl: vi.fn((threadId: string, fileName: string) => `/api/uploads/${threadId}/${fileName}`)
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        resumeThread,
        startTurnWithInput
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      uploadStore,
      imageSendCoordinator,
      sendMode: "desktop_bridge"
    });

    const response = await service.sendImageMessage("thread-1", {
      text: "请分析这张图",
      clientMessageId: "client-image-1",
      tempFilePath: "C:\\temp\\incoming-image",
      originalFileName: "demo.png",
      mimeType: "image/png"
    });

    expect(response).toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-image-1",
      sendPath: "desktop_bridge",
      confirmation: "observed"
    });
    expect(imageSendCoordinator.send).toHaveBeenCalledWith({
      threadId: "thread-1",
      title: "thread-1",
      clientMessageId: "client-image-1",
      localImagePath: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png",
      text: "请分析这张图"
    });
    expect(resumeThread).not.toHaveBeenCalled();
    expect(startTurnWithInput).not.toHaveBeenCalled();
  });

  it("sends image messages through app-server localImage input when official persistence is enabled", async () => {
    const resumeThread = vi.fn(async (_threadId: string) => undefined);
    const startTurnWithInput = vi.fn(async (_threadId: string, _input) => ({
      accepted: true,
      turnId: "turn-image-1"
    }));
    const imageSendCoordinator = {
      send: vi.fn(async () => ({
        accepted: true,
        threadId: "thread-1",
        clientMessageId: "client-image-1",
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      })),
      sendMany: vi.fn()
    };
    const uploadStore = {
      persistUploadedFile: vi.fn(async () => ({
        threadId: "thread-1",
        fileName: "demo.png",
        absolutePath: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png",
        mimeType: "image/png"
      })),
      resolveStoredFile: vi.fn(),
      makeRelativeUrl: vi.fn((threadId: string, fileName: string) => `/api/uploads/${threadId}/${fileName}`)
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        resumeThread,
        startTurnWithInput
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      uploadStore,
      imageSendCoordinator,
      sendMode: "official_persistence"
    });

    const response = await service.sendImageMessage("thread-1", {
      text: "请分析这张图",
      clientMessageId: "client-image-1",
      tempFilePath: "C:\\temp\\incoming-image",
      originalFileName: "demo.png",
      mimeType: "image/png"
    });

    expect(response).toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-image-1",
      sendPath: "app_server",
      confirmation: "observed"
    });
    expect(resumeThread).toHaveBeenCalledWith("thread-1");
    expect(startTurnWithInput).toHaveBeenCalledWith("thread-1", [
      {
        type: "localImage",
        path: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png"
      },
      {
        type: "text",
        text: "请分析这张图",
        text_elements: []
      }
    ]);
    expect(imageSendCoordinator.send).not.toHaveBeenCalled();
  });

  it("deduplicates an optimistic image with text when detail refresh returns the same image and separate text", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-04-30T06:34:49.000Z"));

    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-image-detail-dedupe-"));
    const rolloutPath = path.join(tempDir, "rollout.jsonl");
    fs.writeFileSync(
      rolloutPath,
      `${JSON.stringify({
        timestamp: "2026-04-30T06:34:49.289Z",
        type: "event_msg",
        payload: {
          type: "user_message",
          message: "查一下为什么现在发送图片会变成两张。",
          local_images: ["D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png"]
        }
      })}\n`,
      "utf8"
    );

    try {
      const imageThread: CodexThread = {
        ...baseThread,
        updatedAt: 1777530889,
        turns: [
          {
            id: "turn-image-detail",
            status: "completed",
            startedAt: 1777530889,
            completedAt: 1777530899,
            error: null,
            durationMs: 10000,
            items: [
              {
                type: "userMessage",
                id: "item-image-detail",
                content: [
                  {
                    type: "localImage",
                    path: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png"
                  },
                  {
                    type: "text",
                    text: "查一下为什么现在发送图片会变成两张。",
                    text_elements: []
                  }
                ]
              }
            ]
          }
        ]
      };
      const uploadStore = {
        persistUploadedFile: vi.fn(async () => ({
          threadId: "thread-1",
          fileName: "demo.png",
          absolutePath: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png",
          mimeType: "image/png"
        })),
        resolveStoredFile: vi.fn(),
        makeRelativeUrl: vi.fn((threadId: string, fileName: string) => `/api/uploads/${threadId}/${fileName}`)
      };
      const imageSendCoordinator = {
        send: vi.fn(async () => ({
          accepted: true,
          threadId: "thread-1",
          clientMessageId: "client-image-dedupe",
          sendPath: "desktop_bridge" as const,
          confirmation: "observed" as const
        })),
        sendMany: vi.fn()
      };
      const service = new MobileGatewayRuntimeService({
        authToken: "secret-token",
        appServer: createAppServerStub({
          readThread: vi.fn(async () => imageThread)
        }),
        stateRepository: createStateRepositoryStub([
          {
            threadId: "thread-1",
            title: "模拟",
            cwd: "\\\\?\\D:\\projects\\codex-mobile-control",
            updatedAt: 1777530889,
            archived: false,
            firstUserMessage: "模拟",
            rolloutPath
          }
        ]),
        logRepository: createLogRepositoryStub(),
        uploadStore,
        imageSendCoordinator
      });

      await service.sendImageMessage("thread-1", {
        text: "查一下为什么现在发送图片会变成两张。",
        clientMessageId: "client-image-dedupe",
        tempFilePath: "C:\\temp\\incoming-image",
        originalFileName: "demo.png",
        mimeType: "image/png"
      });

      const detail = await service.getThreadDetail("thread-1");
      const currentMessages = detail.recentMessages.filter((message) =>
        message.timestamp === "2026-04-30T06:34:49.289Z"
      );

      expect(currentMessages.map((message) => ({
        kind: message.kind,
        text: message.text,
        fileName: message.fileName
      }))).toEqual([
        {
          kind: "image",
          text: undefined,
          fileName: "demo.png"
        },
        {
          kind: "text",
          text: "查一下为什么现在发送图片会变成两张。",
          fileName: undefined
        }
      ]);
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("sends multiple uploaded images through one desktop bridge batch", async () => {
    const imageSendCoordinator = {
      sendMany: vi.fn(async () => ({
        accepted: true,
        threadId: "thread-1",
        clientMessageId: "client-image-batch-1",
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      }))
    };
    const uploadStore = {
      persistUploadedFile: vi.fn(async (options: { originalName: string }) => ({
        threadId: "thread-1",
        fileName: options.originalName,
        absolutePath: `D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\${options.originalName}`,
        mimeType: options.originalName.endsWith(".png") ? "image/png" : "image/jpeg"
      })),
      resolveStoredFile: vi.fn(),
      makeRelativeUrl: vi.fn((threadId: string, fileName: string) => `/api/uploads/${threadId}/${fileName}`)
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      uploadStore,
      imageSendCoordinator: imageSendCoordinator as any,
      sendMode: "desktop_bridge"
    });

    const response = await (service as any).sendImageMessages("thread-1", {
      text: "请分析这两张图",
      clientMessageId: "client-image-batch-1",
      images: [
        {
          tempFilePath: "C:\\temp\\incoming-first",
          originalFileName: "first.png",
          mimeType: "image/png"
        },
        {
          tempFilePath: "C:\\temp\\incoming-second",
          originalFileName: "second.jpg",
          mimeType: "image/jpeg"
        }
      ]
    });

    expect(response).toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-image-batch-1",
      sendPath: "desktop_bridge",
      confirmation: "observed"
    });
    expect(uploadStore.persistUploadedFile).toHaveBeenCalledTimes(2);
    expect(imageSendCoordinator.sendMany).toHaveBeenCalledWith({
      threadId: "thread-1",
      title: "thread-1",
      clientMessageId: "client-image-batch-1",
      localImagePaths: [
        "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\first.png",
        "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\second.jpg"
      ],
      text: "请分析这两张图"
    });
  });

  it("sends multiple uploaded images through app-server localImage inputs when official persistence is enabled", async () => {
    const resumeThread = vi.fn(async (_threadId: string) => undefined);
    const startTurnWithInput = vi.fn(async (_threadId: string, _input) => ({
      accepted: true,
      turnId: "turn-image-batch-1"
    }));
    const imageSendCoordinator = {
      send: vi.fn(),
      sendMany: vi.fn(async () => ({
        accepted: true,
        threadId: "thread-1",
        clientMessageId: "client-image-batch-1",
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      }))
    };
    const uploadStore = {
      persistUploadedFile: vi.fn(async (options: { originalName: string }) => ({
        threadId: "thread-1",
        fileName: options.originalName,
        absolutePath: `D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\${options.originalName}`,
        mimeType: options.originalName.endsWith(".png") ? "image/png" : "image/jpeg"
      })),
      resolveStoredFile: vi.fn(),
      makeRelativeUrl: vi.fn((threadId: string, fileName: string) => `/api/uploads/${threadId}/${fileName}`)
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        resumeThread,
        startTurnWithInput
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      uploadStore,
      imageSendCoordinator: imageSendCoordinator as any,
      sendMode: "official_persistence"
    });

    const response = await (service as any).sendImageMessages("thread-1", {
      text: "请分析这两张图",
      clientMessageId: "client-image-batch-1",
      images: [
        {
          tempFilePath: "C:\\temp\\incoming-first",
          originalFileName: "first.png",
          mimeType: "image/png"
        },
        {
          tempFilePath: "C:\\temp\\incoming-second",
          originalFileName: "second.jpg",
          mimeType: "image/jpeg"
        }
      ]
    });

    expect(response).toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-image-batch-1",
      sendPath: "app_server",
      confirmation: "observed"
    });
    expect(resumeThread).toHaveBeenCalledWith("thread-1");
    expect(startTurnWithInput).toHaveBeenCalledWith("thread-1", [
      {
        type: "localImage",
        path: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\first.png"
      },
      {
        type: "localImage",
        path: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\second.jpg"
      },
      {
        type: "text",
        text: "请分析这两张图",
        text_elements: []
      }
    ]);
    expect(imageSendCoordinator.sendMany).not.toHaveBeenCalled();
  });

  it("sends uploaded files through one desktop bridge batch", async () => {
    const fileSendCoordinator = {
      sendMany: vi.fn(async () => ({
        accepted: true,
        threadId: "thread-1",
        clientMessageId: "client-file-batch-1",
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      }))
    };
    const uploadStore = {
      persistUploadedFile: vi.fn(async (options: { originalName: string; mimeType: string }) => ({
        threadId: "thread-1",
        fileName: options.originalName,
        absolutePath: `D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\${options.originalName}`,
        mimeType: options.mimeType
      })),
      resolveStoredFile: vi.fn(),
      makeRelativeUrl: vi.fn((threadId: string, fileName: string) => `/api/uploads/${threadId}/${fileName}`)
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      uploadStore,
      fileSendCoordinator,
      sendMode: "desktop_bridge"
    } as any);

    const response = await (service as any).sendFileMessages("thread-1", {
      text: "这是诊断日志",
      clientMessageId: "client-file-batch-1",
      files: [
        {
          tempFilePath: "C:\\temp\\incoming-diagnostics",
          originalFileName: "diagnostics.zip",
          mimeType: "application/zip"
        },
        {
          tempFilePath: "C:\\temp\\incoming-gateway-log",
          originalFileName: "gateway.log",
          mimeType: "text/plain"
        }
      ]
    });

    expect(response).toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-file-batch-1",
      sendPath: "desktop_bridge",
      confirmation: "observed"
    });
    expect(uploadStore.persistUploadedFile).toHaveBeenCalledTimes(2);
    expect(fileSendCoordinator.sendMany).toHaveBeenCalledWith({
      threadId: "thread-1",
      title: "thread-1",
      clientMessageId: "client-file-batch-1",
      localFilePaths: [
        "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\diagnostics.zip",
        "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\gateway.log"
      ],
      text: "这是诊断日志"
    });
  });

  it("sends uploaded files through app-server mentions when official persistence is enabled", async () => {
    const resumeThread = vi.fn(async (_threadId: string) => undefined);
    const startTurnWithInput = vi.fn(async (_threadId: string, _input) => ({
      accepted: true,
      turnId: "turn-file-batch-1"
    }));
    const fileSendCoordinator = {
      sendMany: vi.fn(async () => ({
        accepted: true,
        threadId: "thread-1",
        clientMessageId: "client-file-batch-1",
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      }))
    };
    const uploadStore = {
      persistUploadedFile: vi.fn(async (options: { originalName: string; mimeType: string }) => ({
        threadId: "thread-1",
        fileName: options.originalName,
        absolutePath: `D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\${options.originalName}`,
        mimeType: options.mimeType
      })),
      resolveStoredFile: vi.fn(),
      makeRelativeUrl: vi.fn((threadId: string, fileName: string) => `/api/uploads/${threadId}/${fileName}`)
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        resumeThread,
        startTurnWithInput
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      uploadStore,
      fileSendCoordinator,
      sendMode: "official_persistence"
    } as any);

    const response = await (service as any).sendFileMessages("thread-1", {
      text: "这是诊断日志",
      clientMessageId: "client-file-batch-1",
      files: [
        {
          tempFilePath: "C:\\temp\\incoming-diagnostics",
          originalFileName: "diagnostics.zip",
          mimeType: "application/zip"
        },
        {
          tempFilePath: "C:\\temp\\incoming-gateway-log",
          originalFileName: "gateway.log",
          mimeType: "text/plain"
        }
      ]
    });

    expect(response).toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-file-batch-1",
      sendPath: "app_server",
      confirmation: "observed"
    });
    expect(resumeThread).toHaveBeenCalledWith("thread-1");
    expect(startTurnWithInput).toHaveBeenCalledWith("thread-1", [
      {
        type: "mention",
        name: "diagnostics.zip",
        path: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\diagnostics.zip"
      },
      {
        type: "mention",
        name: "gateway.log",
        path: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\gateway.log"
      },
      {
        type: "text",
        text: "这是诊断日志\n\n手机端上传文件：\n- diagnostics.zip\n  D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\diagnostics.zip\n- gateway.log\n  D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\gateway.log",
        text_elements: []
      }
    ]);
    expect(fileSendCoordinator.sendMany).not.toHaveBeenCalled();
  });

  it("adds visible file path text for official persistence file sends", async () => {
    const resumeThread = vi.fn(async (_threadId: string) => undefined);
    const startTurnWithInput = vi.fn(async (_threadId: string, _input) => ({
      accepted: true,
      turnId: "turn-file-visible-1"
    }));
    const uploadStore = {
      persistUploadedFile: vi.fn(async () => ({
        threadId: "thread-1",
        fileName: "diagnostics.zip",
        absolutePath: "D:\\codex_app\\gateway\\mobile-uploads\\thread-1\\diagnostics.zip",
        mimeType: "application/zip"
      })),
      resolveStoredFile: vi.fn(),
      makeRelativeUrl: vi.fn((threadId: string, fileName: string) => `/api/uploads/${threadId}/${fileName}`)
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        resumeThread,
        startTurnWithInput
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      uploadStore,
      sendMode: "official_persistence"
    } as any);

    await (service as any).sendFileMessages("thread-1", {
      clientMessageId: "client-file-visible-1",
      files: [
        {
          tempFilePath: "C:\\temp\\incoming-diagnostics",
          originalFileName: "diagnostics.zip",
          mimeType: "application/zip"
        }
      ]
    });

    expect(startTurnWithInput).toHaveBeenCalledWith("thread-1", [
      {
        type: "mention",
        name: "diagnostics.zip",
        path: "D:\\codex_app\\gateway\\mobile-uploads\\thread-1\\diagnostics.zip"
      },
      {
        type: "text",
        text: "手机端上传文件：\n- diagnostics.zip\n  D:\\codex_app\\gateway\\mobile-uploads\\thread-1\\diagnostics.zip",
        text_elements: []
      }
    ]);
  });

  it("sends uploaded images and files through one desktop bridge batch", async () => {
    const fileSendCoordinator = {
      sendMany: vi.fn(async () => ({
        accepted: true,
        threadId: "thread-1",
        clientMessageId: "client-attachment-batch-1",
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      }))
    };
    const uploadStore = {
      persistUploadedFile: vi.fn(async (options: { originalName: string; mimeType: string }) => ({
        threadId: "thread-1",
        fileName: options.originalName,
        absolutePath: `D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\${options.originalName}`,
        mimeType: options.mimeType
      })),
      resolveStoredFile: vi.fn(),
      makeRelativeUrl: vi.fn((threadId: string, fileName: string) => `/api/uploads/${threadId}/${fileName}`)
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      uploadStore,
      fileSendCoordinator,
      sendMode: "desktop_bridge"
    } as any);

    const response = await (service as any).sendAttachmentMessages("thread-1", {
      text: "图片和日志一起看",
      clientMessageId: "client-attachment-batch-1",
      images: [
        {
          tempFilePath: "C:\\temp\\incoming-screen",
          originalFileName: "screen.png",
          mimeType: "image/png"
        }
      ],
      files: [
        {
          tempFilePath: "C:\\temp\\incoming-diagnostics",
          originalFileName: "diagnostics.zip",
          mimeType: "application/zip"
        }
      ]
    });

    expect(response).toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-attachment-batch-1",
      sendPath: "desktop_bridge",
      confirmation: "observed"
    });
    expect(uploadStore.persistUploadedFile).toHaveBeenCalledTimes(2);
    expect(fileSendCoordinator.sendMany).toHaveBeenCalledWith({
      threadId: "thread-1",
      title: "thread-1",
      clientMessageId: "client-attachment-batch-1",
      localFilePaths: [
        "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\screen.png",
        "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\diagnostics.zip"
      ],
      text: "图片和日志一起看"
    });
    await expect(service.getThreadDetail("thread-1")).resolves.toMatchObject({
      recentMessages: expect.arrayContaining([
        expect.objectContaining({
          kind: "image",
          fileName: "screen.png",
          text: "图片和日志一起看"
        }),
        expect.objectContaining({
          kind: "file",
          fileName: "diagnostics.zip"
        })
      ])
    });
  });

  it("sends uploaded image and file attachments through app-server inputs when official persistence is enabled", async () => {
    const resumeThread = vi.fn(async (_threadId: string) => undefined);
    const startTurnWithInput = vi.fn(async (_threadId: string, _input) => ({
      accepted: true,
      turnId: "turn-attachment-batch-1"
    }));
    const fileSendCoordinator = {
      sendMany: vi.fn(async () => ({
        accepted: true,
        threadId: "thread-1",
        clientMessageId: "client-attachment-batch-1",
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      }))
    };
    const uploadStore = {
      persistUploadedFile: vi.fn(async (options: { originalName: string; mimeType: string }) => ({
        threadId: "thread-1",
        fileName: options.originalName,
        absolutePath: `D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\${options.originalName}`,
        mimeType: options.mimeType
      })),
      resolveStoredFile: vi.fn(),
      makeRelativeUrl: vi.fn((threadId: string, fileName: string) => `/api/uploads/${threadId}/${fileName}`)
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub({
        resumeThread,
        startTurnWithInput
      }),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      uploadStore,
      fileSendCoordinator,
      sendMode: "official_persistence"
    } as any);

    const response = await (service as any).sendAttachmentMessages("thread-1", {
      text: "图片和日志一起看",
      clientMessageId: "client-attachment-batch-1",
      images: [
        {
          tempFilePath: "C:\\temp\\incoming-screen",
          originalFileName: "screen.png",
          mimeType: "image/png"
        }
      ],
      files: [
        {
          tempFilePath: "C:\\temp\\incoming-diagnostics",
          originalFileName: "diagnostics.zip",
          mimeType: "application/zip"
        }
      ]
    });

    expect(response).toMatchObject({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-attachment-batch-1",
      sendPath: "app_server",
      confirmation: "observed"
    });
    expect(resumeThread).toHaveBeenCalledWith("thread-1");
    expect(startTurnWithInput).toHaveBeenCalledWith("thread-1", [
      {
        type: "localImage",
        path: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\screen.png"
      },
      {
        type: "mention",
        name: "diagnostics.zip",
        path: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\diagnostics.zip"
      },
      {
        type: "text",
        text: "图片和日志一起看\n\n手机端上传文件：\n- diagnostics.zip\n  D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\diagnostics.zip",
        text_elements: []
      }
    ]);
    expect(fileSendCoordinator.sendMany).not.toHaveBeenCalled();
  });

  it("stores uploaded file attachments before separate text with one history timestamp", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-04-29T14:19:00.000Z"));
    const fileSendCoordinator = {
      sendMany: vi.fn(async () => ({
        accepted: true,
        threadId: "thread-1",
        clientMessageId: "client-file-batch-1",
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      }))
    };
    const uploadStore = {
      persistUploadedFile: vi.fn(async (options: { originalName: string; mimeType: string }) => ({
        threadId: "thread-1",
        fileName: options.originalName,
        absolutePath: `D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\${options.originalName}`,
        mimeType: options.mimeType
      })),
      resolveStoredFile: vi.fn(),
      makeRelativeUrl: vi.fn((threadId: string, fileName: string) => `/api/uploads/${threadId}/${fileName}`)
    };

    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository: createLogRepositoryStub(),
      uploadStore,
      fileSendCoordinator
    } as any);

    await (service as any).sendFileMessages("thread-1", {
      text: "这是诊断日志",
      clientMessageId: "client-file-batch-1",
      files: [
        {
          tempFilePath: "C:\\temp\\incoming-diagnostics",
          originalFileName: "diagnostics.zip",
          mimeType: "application/zip"
        },
        {
          tempFilePath: "C:\\temp\\incoming-gateway-log",
          originalFileName: "gateway.log",
          mimeType: "text/plain"
        }
      ]
    });

    const detail = await service.getThreadDetail("thread-1");
    const uploadedMessages = detail.recentMessages.filter((message) =>
      message.messageId.startsWith("client-file-batch-1")
    );

    expect(uploadedMessages.map((message) => ({
      id: message.messageId,
      kind: message.kind,
      text: message.text,
      fileName: message.fileName,
      timestamp: message.timestamp
    }))).toEqual([
      {
        id: "client-file-batch-1",
        kind: "file",
        text: undefined,
        fileName: "diagnostics.zip",
        timestamp: "2026-04-29T14:19:00.000Z"
      },
      {
        id: "client-file-batch-1:file-1",
        kind: "file",
        text: undefined,
        fileName: "gateway.log",
        timestamp: "2026-04-29T14:19:00.000Z"
      },
      {
        id: "client-file-batch-1:text",
        kind: "text",
        text: "这是诊断日志",
        fileName: undefined,
        timestamp: "2026-04-29T14:19:00.000Z"
      }
    ]);
  });

  it("does not emit sqlite log-derived waiting_input updates to subscribers", async () => {
    vi.useFakeTimers();
    const listener = vi.fn();
    const logRepository: LogRepository = {
      listRecentSignals: vi.fn(async () => []),
      pollSignals: vi.fn(async () => ({
        signals: [
          {
            signalId: "log-1",
            threadId: "thread-1",
            status: "waiting_input" as const,
            text: "线程正在等待新的输入",
            timestamp: "2026-04-22T11:00:00.000Z",
            cursor: "11",
            notificationEligible: true
          }
        ],
        nextCursor: "11"
      }))
    };
    const service = new MobileGatewayRuntimeService({
      authToken: "secret-token",
      appServer: createAppServerStub(),
      stateRepository: createStateRepositoryStub(),
      logRepository
    });

    const unsubscribe = service.subscribe(listener);
    await vi.advanceTimersByTimeAsync(1600);

    expect(logRepository.listRecentSignals).not.toHaveBeenCalled();
    expect(logRepository.pollSignals).not.toHaveBeenCalled();
    expect(listener).not.toHaveBeenCalled();

    unsubscribe();
  });
});
