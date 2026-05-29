import { mkdirSync, mkdtempSync, readdirSync, rmSync, utimesSync, writeFileSync } from "node:fs";
import { createHash } from "node:crypto";
import os from "node:os";
import path from "node:path";

import request from "supertest";
import { describe, expect, it, vi } from "vitest";

import type {
  Alert,
  MarkdownFilePreview,
  SendMessageRequest,
  ThreadDetail,
  ThreadEvent,
  ThreadListItem
} from "../../shared/src/api";
import { createGatewayApp } from "../src/app";
import type {
  MobileGatewayRuntimeDiagnostics,
  MobileGatewayService,
  SendAttachmentMessagesPayload,
  SendFileMessagesPayload,
  SendImageMessagePayload,
  SendImageMessagesPayload
} from "../src/service";

const sampleThread: ThreadListItem = {
  threadId: "thread-1",
  title: "开发 Codex 控制App",
  cwd: "D:\\projects\\codex-mobile-control",
  status: "waiting_input",
  updatedAt: "2026-04-22T11:00:00.000Z",
  progressSummary: "等待你确认下一步实现范围",
  needsAttention: true
};

const sampleDetail: ThreadDetail = {
  thread: sampleThread,
  recentMessages: [
    {
      messageId: "m-1",
      threadId: "thread-1",
      role: "user",
      kind: "text",
      text: "请继续实现",
      timestamp: "2026-04-22T11:00:00.000Z"
    }
  ],
  recentEvents: [
    {
      eventId: "e-1",
      threadId: "thread-1",
      kind: "status_changed",
      status: "waiting_input",
      text: "线程正在等待输入",
      timestamp: "2026-04-22T11:00:01.000Z"
    }
  ],
  sendAvailable: true
};

function createServiceStub(overrides: Partial<MobileGatewayService> = {}): MobileGatewayService {
  return {
    authenticate: vi.fn(async (token: string) => token === "secret-token"),
    getHealth: vi.fn(async () => ({
      ok: true,
      sidecarStatus: "connected" as const,
      codexHome: "C:\\Users\\devuser\\.codex"
    })),
    listAutomations: vi.fn(async () => []),
    listThreads: vi.fn(async () => [sampleThread]),
    getThreadPreview: vi.fn(async (threadId: string) => ({
      ...sampleDetail,
      thread: { ...sampleThread, threadId }
    })),
    getThreadDetail: vi.fn(async (threadId: string) => ({
      ...sampleDetail,
      thread: { ...sampleThread, threadId }
    })),
    getThreadMessages: vi.fn(async (_threadId: string) => ({
      messages: sampleDetail.recentMessages,
      nextCursor: null
    })),
    getThreadEvents: vi.fn(async (_threadId: string, cursor?: string | null) => ({
      events: cursor ? [] : sampleDetail.recentEvents,
      nextCursor: "next-event-cursor"
    })),
    getMarkdownFilePreview: vi.fn(async (threadId: string, filePath: string) => ({
      fileName: "notes.md",
      path: filePath,
      content: `# ${threadId}`,
      sizeBytes: Buffer.byteLength(`# ${threadId}`, "utf8")
    } satisfies MarkdownFilePreview)),
    getQueuedTextMessages: vi.fn(async () => []),
    cancelQueuedTextMessage: vi.fn(async () => null),
    retryQueuedTextMessage: vi.fn(async () => null),
    sendMessage: vi.fn(async (threadId: string, payload: SendMessageRequest) => ({
      accepted: true,
      threadId,
      clientMessageId: payload.clientMessageId,
      sendPath: "desktop_bridge" as const,
      confirmation: "observed" as const
    })),
    sendImageMessage: vi.fn(async (threadId: string, payload: SendImageMessagePayload) => ({
      accepted: true,
      threadId,
      clientMessageId: payload.clientMessageId,
      sendPath: "desktop_bridge" as const,
      confirmation: "observed" as const
    })),
    sendImageMessages: vi.fn(async (threadId: string, payload: SendImageMessagesPayload) => ({
      accepted: true,
      threadId,
      clientMessageId: payload.clientMessageId,
      sendPath: "desktop_bridge" as const,
      confirmation: "observed" as const
    })),
    sendFileMessages: vi.fn(async (threadId: string, payload: SendFileMessagesPayload) => ({
      accepted: true,
      threadId,
      clientMessageId: payload.clientMessageId,
      sendPath: "desktop_bridge" as const,
      confirmation: "observed" as const
    })),
    sendAttachmentMessages: vi.fn(async (threadId: string, payload: SendAttachmentMessagesPayload) => ({
      accepted: true,
      threadId,
      clientMessageId: payload.clientMessageId,
      sendPath: "desktop_bridge" as const,
      confirmation: "observed" as const
    })),
    getUploadFile: vi.fn(async (_threadId: string, _fileName: string) => ({
      absolutePath: "C:\\temp\\demo.png",
      mimeType: "image/png"
    })),
    getAlerts: vi.fn(async (cursor?: string | null) => ({
      alerts: cursor
        ? []
        : [
            {
              alertId: "alert-1",
              threadId: "thread-1",
              trigger: "waiting_input",
              title: "线程需要处理",
              body: "开发 Codex 控制App 等待新的输入",
              timestamp: "2026-04-22T11:00:02.000Z"
            } satisfies Alert
          ],
      nextCursor: "next-alert-cursor"
    })),
    subscribe: vi.fn((listener: (event: ThreadEvent | Alert) => void) => {
      listener(sampleDetail.recentEvents[0]);
      return () => undefined;
    }),
    ...overrides
  };
}

describe("createGatewayApp", () => {
  it("serves the latest apk download for authenticated requests", async () => {
    const apkDir = mkdtempSync(path.join(os.tmpdir(), "codex-apk-"));
    const olderApk = path.join(apkDir, "codex-mobile-control-debug-20260424-115058.apk");
    const newerApk = path.join(apkDir, "codex-mobile-control-debug-20260424-120500.apk");
    writeFileSync(olderApk, "older");
    writeFileSync(newerApk, "newer");

    const app = createGatewayApp({
      service: createServiceStub(),
      authToken: "secret-token",
      resolveLatestApk: async () => newerApk
    });

    const response = await request(app)
      .get("/downloads/latest.apk")
      .set("authorization", "Bearer secret-token");

    expect(response.status).toBe(200);
    expect(response.headers["content-type"]).toContain("application/vnd.android.package-archive");
    expect(response.headers["content-disposition"]).toContain(path.basename(newerApk));
    expect(response.headers["cache-control"]).toContain("no-store");
    expect(response.headers["cdn-cache-control"]).toBe("no-store");
    expect(response.text).toBe("newer");
  });

  it("serves latest apk metadata with the Android version", async () => {
    const projectRoot = mkdtempSync(path.join(os.tmpdir(), "codex-apk-metadata-"));
    const gatewayDir = path.join(projectRoot, "gateway");
    const androidAppDir = path.join(projectRoot, "android", "app");
    const latestApk = path.join(projectRoot, "codex-mobile-control-debug-20260427-151500.apk");
    const originalCwd = process.cwd();
    mkdirSync(gatewayDir, { recursive: true });
    mkdirSync(androidAppDir, { recursive: true });
    writeFileSync(latestApk, "apk");
    writeFileSync(
      path.join(androidAppDir, "build.gradle.kts"),
      `
      android {
        defaultConfig {
          versionCode = 123
          versionName = "1.0.23"
        }
      }
      `
    );

    try {
      process.chdir(gatewayDir);
      const app = createGatewayApp({
        service: createServiceStub(),
        authToken: "secret-token",
        resolveLatestApk: async () => latestApk
      });

      const response = await request(app)
        .get("/downloads/latest.json")
        .set("authorization", "Bearer secret-token");

      expect(response.status).toBe(200);
      expect(response.body).toEqual({
        available: true,
        fileName: path.basename(latestApk),
        versionCode: 123,
        versionName: "1.0.23",
        downloadUrl: "/downloads/latest.apk"
      });
      expect(response.headers["cache-control"]).toContain("no-store");
      expect(response.headers["cdn-cache-control"]).toBe("no-store");
    } finally {
      process.chdir(originalCwd);
      rmSync(projectRoot, { recursive: true, force: true });
    }
  });

  it("finds latest apk in project root when gateway starts from gateway directory", async () => {
    const projectRoot = mkdtempSync(path.join(os.tmpdir(), "codex-apk-root-"));
    const gatewayDir = path.join(projectRoot, "gateway");
    const latestApk = path.join(projectRoot, "codex-mobile-control-debug-20260425-233111.apk");
    const originalCwd = process.cwd();
    const originalApkDir = process.env.CODEX_MOBILE_APK_DIR;
    mkdirSync(gatewayDir);
    writeFileSync(latestApk, "root-apk");

    try {
      delete process.env.CODEX_MOBILE_APK_DIR;
      process.chdir(gatewayDir);

      const app = createGatewayApp({
        service: createServiceStub(),
        authToken: "secret-token"
      });

      const response = await request(app)
        .get("/downloads/latest.apk")
        .set("authorization", "Bearer secret-token");

      expect(response.status).toBe(200);
      expect(response.headers["content-disposition"]).toContain(path.basename(latestApk));
      expect(response.text).toBe("root-apk");
    } finally {
      process.chdir(originalCwd);
      if (originalApkDir === undefined) {
        delete process.env.CODEX_MOBILE_APK_DIR;
      } else {
        process.env.CODEX_MOBILE_APK_DIR = originalApkDir;
      }
      rmSync(projectRoot, { recursive: true, force: true });
    }
  });

  it("finds latest.apk in gateway downloads when gateway starts from project root", async () => {
    const tempRoot = mkdtempSync(path.join(os.tmpdir(), "codex-apk-downloads-"));
    const projectRoot = path.join(tempRoot, "project");
    const downloadsDir = path.join(projectRoot, "gateway", "downloads");
    const latestApk = path.join(downloadsDir, "latest.apk");
    const originalCwd = process.cwd();
    const originalApkDir = process.env.CODEX_MOBILE_APK_DIR;
    mkdirSync(downloadsDir, { recursive: true });
    writeFileSync(latestApk, "downloads-latest");

    try {
      delete process.env.CODEX_MOBILE_APK_DIR;
      process.chdir(projectRoot);

      const app = createGatewayApp({
        service: createServiceStub(),
        authToken: "secret-token"
      });

      const response = await request(app)
        .get("/downloads/latest.apk")
        .set("authorization", "Bearer secret-token");

      expect(response.status).toBe(200);
      expect(response.headers["content-disposition"]).toContain("latest.apk");
      expect(response.text).toBe("downloads-latest");
    } finally {
      process.chdir(originalCwd);
      if (originalApkDir === undefined) {
        delete process.env.CODEX_MOBILE_APK_DIR;
      } else {
        process.env.CODEX_MOBILE_APK_DIR = originalApkDir;
      }
      rmSync(tempRoot, { recursive: true, force: true });
    }
  });

  it("serves markdown previews for authenticated thread requests", async () => {
    const service = createServiceStub();
    const app = createGatewayApp({
      service,
      authToken: "secret-token"
    });

    const response = await request(app)
      .get("/api/threads/thread-1/markdown-preview")
      .query({ path: "docs/notes.md" })
      .set("authorization", "Bearer secret-token");

    expect(response.status).toBe(200);
    expect(response.body).toEqual({
      fileName: "notes.md",
      path: "docs/notes.md",
      content: "# thread-1",
      sizeBytes: Buffer.byteLength("# thread-1", "utf8")
    });
    expect(service.getMarkdownFilePreview).toHaveBeenCalledWith("thread-1", "docs/notes.md");
  });

  it("returns 404 when latest apk is unavailable", async () => {
    const app = createGatewayApp({
      service: createServiceStub(),
      authToken: "secret-token",
      resolveLatestApk: async () => null
    });

    const response = await request(app)
      .get("/downloads/latest.apk")
      .set("authorization", "Bearer secret-token");

    expect(response.status).toBe(404);
    expect(response.body).toEqual({
      error: "latest_apk_not_found",
      message: "最新版安装包暂不可用"
    });
  });

  it("rejects invalid login tokens", async () => {
    const app = createGatewayApp({
      service: createServiceStub(),
      authToken: "secret-token"
    });

    const response = await request(app)
      .post("/api/auth/login")
      .send({ token: "wrong-token" });

    expect(response.status).toBe(401);
    expect(response.body).toEqual({ authenticated: false });
  });

  it("returns thread list for authenticated requests", async () => {
    const app = createGatewayApp({
      service: createServiceStub(),
      authToken: "secret-token"
    });

    const response = await request(app)
      .get("/api/threads")
      .set("authorization", "Bearer secret-token");

    expect(response.status).toBe(200);
    expect(response.headers["cache-control"]).toContain("no-store");
    expect(response.body).toEqual({ threads: [sampleThread] });
  });

  it("returns automation list for authenticated requests", async () => {
    const listAutomations = vi.fn(async () => [
      {
        id: "token-revenue",
        name: "Token revenue demos and research",
        kind: "heartbeat",
        status: "PAUSED",
        scheduleSummary: "每5分钟",
        targetThreadId: "thread-1",
        targetThreadTitle: "Chrome",
        cwd: "D:\\code\\browser_agent"
      }
    ]);
    const app = createGatewayApp({
      service: createServiceStub({ listAutomations } as Partial<MobileGatewayService>),
      authToken: "secret-token"
    });

    const response = await request(app)
      .get("/api/automations")
      .set("authorization", "Bearer secret-token");

    expect(response.status).toBe(200);
    expect(response.body).toEqual({
      automations: [
        {
          id: "token-revenue",
          name: "Token revenue demos and research",
          kind: "heartbeat",
          status: "PAUSED",
          scheduleSummary: "每5分钟",
          targetThreadId: "thread-1",
          targetThreadTitle: "Chrome",
          cwd: "D:\\code\\browser_agent"
        }
      ]
    });
    expect(listAutomations).toHaveBeenCalledTimes(1);
  });

  it("returns thread detail with recent messages and events", async () => {
    const app = createGatewayApp({
      service: createServiceStub(),
      authToken: "secret-token"
    });

    const response = await request(app)
      .get("/api/threads/thread-1")
      .set("authorization", "Bearer secret-token");

    expect(response.status).toBe(200);
    expect(response.headers["cache-control"]).toContain("no-store");
    expect(response.body).toEqual({
      ...sampleDetail,
      thread: sampleThread
    });
  });

  it("returns thread preview for authenticated requests", async () => {
    const service = createServiceStub();
    const app = createGatewayApp({
      service,
      authToken: "secret-token"
    });

    const response = await request(app)
      .get("/api/threads/thread-1/preview")
      .set("authorization", "Bearer secret-token");

    expect(response.status).toBe(200);
    expect(response.headers["cache-control"]).toContain("no-store");
    expect(response.body).toEqual({
      ...sampleDetail,
      thread: sampleThread
    });
    expect(service.getThreadPreview).toHaveBeenCalledWith("thread-1");
    expect(service.getThreadDetail).not.toHaveBeenCalled();
  });

  it("returns authenticated diagnostics with gateway run log tails", async () => {
    const runLogsDir = mkdtempSync(path.join(os.tmpdir(), "codex-run-logs-"));
    writeFileSync(
      path.join(runLogsDir, "gateway-123.out.log"),
      `${"older-line\n".repeat(20)}Authorization: Bearer secret-token\nlatest-line?token=secret-token\n`
    );
    const service = createServiceStub();
    const app = createGatewayApp({
      service,
      authToken: "secret-token",
      runLogsDir
    });

    const response = await request(app)
      .get("/api/diagnostics")
      .set("authorization", "Bearer secret-token");

    expect(response.status).toBe(200);
    expect(response.body.diagnosticsVersion).toBe(2);
    expect(response.body.health).toEqual({
      ok: true,
      sidecarStatus: "connected",
      codexHome: "C:\\Users\\devuser\\.codex"
    });
    expect(response.body.gateway.cwd).toBeTruthy();
    expect(response.body.runtime).toMatchObject({
      cwd: process.cwd(),
      pid: process.pid,
      nodeVersion: process.version,
      platform: process.platform,
      arch: process.arch,
      runLogsDir: path.resolve(runLogsDir)
    });
    expect(response.body.runtime.startedAt).toEqual(expect.any(String));
    expect(response.body.runtime.uptimeSeconds).toEqual(expect.any(Number));
    expect(response.body.source).toMatchObject({
      cwd: process.cwd()
    });
    expect(response.body.storage.runLogs).toEqual({
      dir: path.resolve(runLogsDir),
      files: 1,
      maxFiles: 8,
      tailBytes: 64 * 1024
    });
    expect(response.body.runLogs).toHaveLength(1);
    expect(response.body.runLogs[0]).toMatchObject({
      fileName: "gateway-123.out.log"
    });
    expect(response.body.runLogs[0].contentTail).toContain("latest-line");
    expect(response.body.runLogs[0].contentTail).not.toContain("secret-token");
    expect(response.body.runLogs[0].contentTail.length).toBeLessThanOrEqual(64 * 1024);

    rmSync(runLogsDir, { recursive: true, force: true });
  });

  it("returns release identity for the configured latest apk in diagnostics", async () => {
    const projectRoot = mkdtempSync(path.join(os.tmpdir(), "codex-diagnostics-release-"));
    const gatewayDir = path.join(projectRoot, "gateway");
    const androidAppDir = path.join(projectRoot, "android", "app");
    const latestApk = path.join(projectRoot, "gateway", "downloads", "latest.apk");
    const originalCwd = process.cwd();
    mkdirSync(path.dirname(latestApk), { recursive: true });
    mkdirSync(androidAppDir, { recursive: true });
    writeFileSync(latestApk, "diagnostic-apk");
    writeFileSync(
      path.join(androidAppDir, "build.gradle.kts"),
      `
      android {
        defaultConfig {
          versionCode = 50212
          versionName = "5.2.12"
        }
      }
      `
    );

    try {
      process.chdir(gatewayDir);
      const app = createGatewayApp({
        service: createServiceStub(),
        authToken: "secret-token",
        resolveLatestApk: async () => latestApk
      });

      const response = await request(app)
        .get("/api/diagnostics")
        .set("authorization", "Bearer secret-token");

      expect(response.status).toBe(200);
      expect(response.body.release.latestApk).toMatchObject({
        available: true,
        fileName: "latest.apk",
        absolutePath: latestApk,
        size: Buffer.byteLength("diagnostic-apk"),
        sha256: createHash("sha256").update("diagnostic-apk").digest("hex"),
        versionCode: 50212,
        versionName: "5.2.12",
        downloadUrl: "/downloads/latest.apk"
      });
      expect(response.body.release.latestApk.modifiedAt).toEqual(expect.any(String));
      expect(JSON.stringify(response.body)).not.toContain("secret-token");
    } finally {
      process.chdir(originalCwd);
      rmSync(projectRoot, { recursive: true, force: true });
    }
  });

  it("includes runtime service diagnostics in authenticated diagnostics", async () => {
    const service = createServiceStub({
      getRuntimeDiagnostics: vi.fn(async () => ({
        desktopSendQueue: {
          pending: 1,
          queued: [
            {
              id: 2,
              kind: "queued_text",
              threadId: "thread-1",
              clientMessageId: "client-second",
              status: "queued",
              queuedAt: "2026-05-11T01:02:00.000Z"
            }
          ],
          active: {
            id: 1,
            kind: "text",
            threadId: "thread-1",
            clientMessageId: "client-first",
            status: "running",
            queuedAt: "2026-05-11T01:01:59.000Z",
            startedAt: "2026-05-11T01:02:00.000Z"
          },
          lastFinished: null,
          lastFailed: null,
          recent: []
        },
        threadObservations: {
          active: 1,
          items: [
            {
              threadId: "thread-1",
              clientMessageId: "client-first",
              submittedAt: "2026-05-11T01:02:00.000Z",
              deadlineAt: "2026-05-11T01:32:00.000Z",
              remainingMs: 1_800_000,
              inFlight: false,
              rolloutTracking: true,
              rolloutObservedTurnId: "turn-1",
              rolloutObservedStartedAt: "2026-05-11T01:02:01.000Z"
            }
          ],
          lastStopped: null
        }
      } satisfies MobileGatewayRuntimeDiagnostics))
    });
    const app = createGatewayApp({
      service,
      authToken: "secret-token"
    });

    const response = await request(app)
      .get("/api/diagnostics")
      .set("authorization", "Bearer secret-token");

    expect(response.status).toBe(200);
    expect(response.body.service.desktopSendQueue).toMatchObject({
      pending: 1,
      active: {
        clientMessageId: "client-first",
        status: "running"
      },
      queued: [
        {
          clientMessageId: "client-second",
          status: "queued"
        }
      ]
    });
    expect(response.body.service.threadObservations).toMatchObject({
      active: 1,
      items: [
        {
          threadId: "thread-1",
          clientMessageId: "client-first",
          rolloutTracking: true
        }
      ]
    });
    expect(service.getRuntimeDiagnostics).toHaveBeenCalledOnce();
  });

  it("returns default diagnostics from gateway root run logs", async () => {
    const tempCwd = mkdtempSync(path.join(os.tmpdir(), "codex-gateway-cwd-"));
    const previousCwd = process.cwd();
    writeFileSync(
      path.join(tempCwd, "gateway-20260507-191555.err.log"),
      "root-log-line\n"
    );
    process.chdir(tempCwd);
    try {
      const app = createGatewayApp({
        service: createServiceStub(),
        authToken: "secret-token"
      });

      const response = await request(app)
        .get("/api/diagnostics")
        .set("authorization", "Bearer secret-token");

      expect(response.status).toBe(200);
      expect(response.body.gateway.runLogsDir).toBe(tempCwd);
      expect(response.body.runLogs).toHaveLength(1);
      expect(response.body.runLogs[0]).toMatchObject({
        fileName: "gateway-20260507-191555.err.log"
      });
      expect(response.body.runLogs[0].contentTail).toContain("root-log-line");
    } finally {
      process.chdir(previousCwd);
      rmSync(tempCwd, { recursive: true, force: true });
    }
  });

  it("returns upload storage diagnostics with incoming residue counts", async () => {
    const tempCwd = mkdtempSync(path.join(os.tmpdir(), "codex-upload-storage-"));
    const incomingDir = path.join(tempCwd, "mobile-uploads", ".incoming");
    const storedDir = path.join(tempCwd, "mobile-uploads", "thread-1");
    const previousCwd = process.cwd();
    mkdirSync(incomingDir, { recursive: true });
    mkdirSync(storedDir, { recursive: true });
    writeFileSync(path.join(incomingDir, "stale-upload.tmp"), "incoming");
    writeFileSync(path.join(storedDir, "kept.png"), "stored-file");
    process.chdir(tempCwd);
    try {
      const app = createGatewayApp({
        service: createServiceStub(),
        authToken: "secret-token"
      });

      const response = await request(app)
        .get("/api/diagnostics")
        .set("authorization", "Bearer secret-token");

      expect(response.status).toBe(200);
      expect(response.body.storage.uploads).toMatchObject({
        rootDir: path.join(tempCwd, "mobile-uploads"),
        incomingDir,
        incomingFiles: 1,
        incomingBytes: Buffer.byteLength("incoming"),
        storedFiles: 1,
        storedBytes: Buffer.byteLength("stored-file"),
        incomingCleanup: {
          ttlMs: 24 * 60 * 60 * 1000,
          deletedFiles: 0,
          deletedBytes: 0,
          failedFiles: 0,
          lastRunAt: expect.any(String)
        }
      });
    } finally {
      process.chdir(previousCwd);
      rmSync(tempCwd, { recursive: true, force: true });
    }
  });

  it("cleans expired incoming upload files while keeping fresh and stored files", async () => {
    const tempCwd = mkdtempSync(path.join(os.tmpdir(), "codex-upload-ttl-"));
    const incomingDir = path.join(tempCwd, "mobile-uploads", ".incoming");
    const storedDir = path.join(tempCwd, "mobile-uploads", "thread-1");
    const expiredFile = path.join(incomingDir, "expired.tmp");
    const freshFile = path.join(incomingDir, "fresh.tmp");
    const storedFile = path.join(storedDir, "kept.png");
    const previousCwd = process.cwd();
    mkdirSync(incomingDir, { recursive: true });
    mkdirSync(storedDir, { recursive: true });
    writeFileSync(expiredFile, "expired");
    writeFileSync(freshFile, "fresh");
    writeFileSync(storedFile, "stored-file");
    const oldDate = new Date(Date.now() - 2 * 24 * 60 * 60 * 1000);
    utimesSync(expiredFile, oldDate, oldDate);
    process.chdir(tempCwd);
    try {
      const app = createGatewayApp({
        service: createServiceStub(),
        authToken: "secret-token"
      });

      const response = await request(app)
        .get("/api/diagnostics")
        .set("authorization", "Bearer secret-token");

      expect(response.status).toBe(200);
      expect(readdirSync(incomingDir)).toEqual(["fresh.tmp"]);
      expect(readdirSync(storedDir)).toEqual(["kept.png"]);
      expect(response.body.storage.uploads).toMatchObject({
        incomingFiles: 1,
        incomingBytes: Buffer.byteLength("fresh"),
        storedFiles: 1,
        storedBytes: Buffer.byteLength("stored-file"),
        incomingCleanup: {
          ttlMs: 24 * 60 * 60 * 1000,
          deletedFiles: 1,
          deletedBytes: Buffer.byteLength("expired"),
          failedFiles: 0,
          lastRunAt: expect.any(String)
        }
      });
    } finally {
      process.chdir(previousCwd);
      rmSync(tempCwd, { recursive: true, force: true });
    }
  });

  it("returns paged thread messages for authenticated requests", async () => {
    const service = createServiceStub();
    const app = createGatewayApp({
      service,
      authToken: "secret-token"
    });

    const response = await request(app)
      .get("/api/threads/thread-1/messages")
      .query({
        before: "msg-8",
        beforeTimestamp: "2026-04-22T00:08:00.000Z",
        limit: "20"
      })
      .set("authorization", "Bearer secret-token");

    expect(response.status).toBe(200);
    expect(response.headers["cache-control"]).toContain("no-store");
    expect(response.body).toEqual({
      messages: sampleDetail.recentMessages,
      nextCursor: null
    });
    expect(service.getThreadMessages).toHaveBeenCalledWith(
      "thread-1",
      "msg-8",
      "2026-04-22T00:08:00.000Z",
      20,
      null,
      null
    );
  });

  it("passes incremental thread message cursors for authenticated requests", async () => {
    const service = createServiceStub();
    const app = createGatewayApp({
      service,
      authToken: "secret-token"
    });

    const response = await request(app)
      .get("/api/threads/thread-1/messages")
      .query({
        after: "msg-8",
        afterTimestamp: "2026-04-22T00:08:00.000Z",
        limit: "20"
      })
      .set("authorization", "Bearer secret-token");

    expect(response.status).toBe(200);
    expect(service.getThreadMessages).toHaveBeenCalledWith(
      "thread-1",
      null,
      null,
      20,
      "msg-8",
      "2026-04-22T00:08:00.000Z"
    );
  });

  it("sends a text message to an existing thread", async () => {
    const service = createServiceStub();
    const app = createGatewayApp({
      service,
      authToken: "secret-token"
    });

    const payload: SendMessageRequest = {
      text: "继续实现网关",
      clientMessageId: "client-1"
    };

    const response = await request(app)
      .post("/api/threads/thread-1/messages")
      .set("authorization", "Bearer secret-token")
      .send(payload);

    expect(response.status).toBe(202);
    expect(response.body).toEqual({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-1",
      sendPath: "desktop_bridge",
      confirmation: "observed"
    });
    expect(service.sendMessage).toHaveBeenCalledWith("thread-1", payload);
  });

  it("returns desktop bridge send metadata", async () => {
    const service = createServiceStub({
      sendMessage: vi.fn(async (threadId: string, payload: SendMessageRequest) => ({
        accepted: true,
        threadId,
        clientMessageId: payload.clientMessageId,
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      }))
    });
    const app = createGatewayApp({
      service,
      authToken: "secret-token"
    });

    const response = await request(app)
      .post("/api/threads/thread-1/messages")
      .set("authorization", "Bearer secret-token")
      .send({
        text: "继续实现网关",
        clientMessageId: "client-1"
      } satisfies SendMessageRequest);

    expect(response.status).toBe(202);
    expect(response.body).toEqual({
      accepted: true,
      threadId: "thread-1",
      clientMessageId: "client-1",
      sendPath: "desktop_bridge",
      confirmation: "observed"
    });
  });

  it("returns structured desktop bridge errors", async () => {
    const { GatewayHttpError } = await import("../src/gateway-error");
    const service = createServiceStub({
      sendMessage: vi.fn(async () => {
        throw new GatewayHttpError(409, {
          error: "desktop_bridge_unavailable",
          reason: "desktop_window_not_found",
          message: "未找到正在运行的 Codex Desktop 窗口"
        });
      })
    });
    const app = createGatewayApp({
      service,
      authToken: "secret-token"
    });

    const response = await request(app)
      .post("/api/threads/thread-1/messages")
      .set("authorization", "Bearer secret-token")
      .send({
        text: "继续实现网关",
        clientMessageId: "client-1"
      } satisfies SendMessageRequest);

    expect(response.status).toBe(409);
    expect(response.body).toEqual({
      error: "desktop_bridge_unavailable",
      reason: "desktop_window_not_found",
      message: "未找到正在运行的 Codex Desktop 窗口"
    });
  });

  it("streams SSE updates for authenticated listeners", async () => {
    const app = createGatewayApp({
      service: createServiceStub(),
      authToken: "secret-token"
    });

    const response = await request(app)
      .get("/api/stream?token=secret-token")
      .buffer(true)
      .parse((res, callback) => {
        let text = "";
        res.setEncoding("utf8");
        res.on("data", (chunk) => {
          text += chunk;
          if (text.includes("data:")) {
            (
              res as unknown as NodeJS.ReadableStream & { destroy: () => void }
            ).destroy();
            callback(null, text);
          }
        });
      });

    expect(response.status).toBe(200);
    expect(response.headers["content-type"]).toContain("text/event-stream");
    expect(response.body).toContain("event: thread_event");
    expect(response.body).toContain(sampleDetail.recentEvents[0].eventId);
  });

  it("keeps SSE listeners alive with heartbeat comments", async () => {
    const app = createGatewayApp({
      service: createServiceStub({
        subscribe: vi.fn(() => () => undefined)
      }),
      authToken: "secret-token",
      sseHeartbeatIntervalMillis: 10
    });

    const response = await request(app)
      .get("/api/stream?token=secret-token")
      .timeout({ response: 500, deadline: 500 })
      .buffer(true)
      .parse((res, callback) => {
        let text = "";
        res.setEncoding("utf8");
        res.on("data", (chunk) => {
          text += chunk;
          if (text.includes(": heartbeat")) {
            (
              res as unknown as NodeJS.ReadableStream & { destroy: () => void }
            ).destroy();
            callback(null, text);
          }
        });
      });

    expect(response.status).toBe(200);
    expect(response.body).toContain(": connected");
    expect(response.body).toContain(": heartbeat");
  });

  it("accepts realtime latency probes for SSE clients", async () => {
    const app = createGatewayApp({
      service: createServiceStub(),
      authToken: "secret-token"
    });

    const response = await request(app)
      .post("/api/realtime/latency-probe")
      .set("authorization", "Bearer secret-token")
      .send({
        probeId: "probe-1",
        sentAt: 1777699000000
      });

    expect(response.status).toBe(202);
    expect(response.body).toMatchObject({
      type: "latency_probe_result",
      probeId: "probe-1",
      sentAt: 1777699000000
    });
    expect(typeof response.body.timestamp).toBe("string");
  });

  it("requires auth when serving uploaded files", async () => {
    const app = createGatewayApp({
      service: createServiceStub(),
      authToken: "secret-token"
    });

    const response = await request(app).get("/api/uploads/thread-1/demo.png");

    expect(response.status).toBe(401);
    expect(response.body).toEqual({ error: "missing_token" });
  });

  it("rejects unauthenticated multipart uploads before writing incoming files", async () => {
    const tempCwd = mkdtempSync(path.join(os.tmpdir(), "codex-upload-auth-"));
    const incomingDir = path.join(tempCwd, "mobile-uploads", ".incoming");
    const previousCwd = process.cwd();
    process.chdir(tempCwd);
    try {
      const service = createServiceStub();
      const app = createGatewayApp({
        service,
        authToken: "secret-token"
      });

      const response = await request(app)
        .post("/api/threads/thread-1/image-message")
        .attach("image", Buffer.from("fake-image"), "demo.png");

      expect(response.status).toBe(401);
      expect(response.body).toEqual({ error: "missing_token" });
      expect(readdirSync(incomingDir)).toEqual([]);
      expect(service.sendImageMessage).not.toHaveBeenCalled();
    } finally {
      process.chdir(previousCwd);
      rmSync(tempCwd, { recursive: true, force: true });
    }
  });

  it("accepts multipart image-message uploads for authenticated requests", async () => {
    const service = createServiceStub({
      sendImageMessage: vi.fn(async (threadId: string, payload: any) => ({
        accepted: true,
        threadId,
        clientMessageId: payload.clientMessageId,
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      }))
    } as any);
    const app = createGatewayApp({
      service,
      authToken: "secret-token"
    });

    const response = await request(app)
      .post("/api/threads/thread-1/image-message")
      .set("authorization", "Bearer secret-token")
      .field("text", "请帮我分析这张图")
      .field("clientMessageId", "client-image-1")
      .attach("image", Buffer.from("png-data"), {
        filename: "demo.png",
        contentType: "image/png"
      });

    expect(response.status).toBe(202);
    expect((service as any).sendImageMessage).toHaveBeenCalledWith(
      "thread-1",
      expect.objectContaining({
        text: "请帮我分析这张图",
        clientMessageId: "client-image-1",
        originalFileName: "demo.png",
        mimeType: "image/png"
      })
    );
  });

  it("rejects unexpected multipart upload fields before calling the service", async () => {
    const service = createServiceStub({
      sendImageMessage: vi.fn()
    });
    const app = createGatewayApp({
      service,
      authToken: "secret-token"
    });

    const response = await request(app)
      .post("/api/threads/thread-1/image-message")
      .set("authorization", "Bearer secret-token")
      .field("clientMessageId", "client-unexpected-upload")
      .attach("unexpected", Buffer.from("not-an-image"), {
        filename: "unexpected.txt",
        contentType: "text/plain"
      });

    expect(response.status).toBe(400);
    expect(response.body).toMatchObject({
      error: "upload_unexpected_field"
    });
    expect(service.sendImageMessage).not.toHaveBeenCalled();
  });

  it("cleans up incoming upload files when image-message handling fails", async () => {
    const tempCwd = mkdtempSync(path.join(os.tmpdir(), "codex-upload-cleanup-"));
    const incomingDir = path.join(tempCwd, "mobile-uploads", ".incoming");
    const previousCwd = process.cwd();
    process.chdir(tempCwd);
    try {
      const service = createServiceStub({
        sendImageMessage: vi.fn(async () => {
          throw new Error("persist_failed");
        })
      });
      const app = createGatewayApp({
        service,
        authToken: "secret-token"
      });

      const response = await request(app)
        .post("/api/threads/thread-1/image-message")
        .set("authorization", "Bearer secret-token")
        .field("clientMessageId", "client-image-fail")
        .attach("image", Buffer.from("fake-image"), "demo.png");

      expect(response.status).toBe(503);
      expect(response.body.error).toBe("persist_failed");
      expect(readdirSync(incomingDir)).toEqual([]);
    } finally {
      process.chdir(previousCwd);
      rmSync(tempCwd, { recursive: true, force: true });
    }
  });

  it("accepts multipart batch image-message uploads for authenticated requests", async () => {
    const service = createServiceStub({
      sendImageMessages: vi.fn(async (threadId: string, payload: SendImageMessagesPayload) => ({
        accepted: true,
        threadId,
        clientMessageId: payload.clientMessageId,
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      }))
    });
    const app = createGatewayApp({
      service,
      authToken: "secret-token"
    });

    const response = await request(app)
      .post("/api/threads/thread-1/image-messages")
      .set("authorization", "Bearer secret-token")
      .field("text", "请帮我分析这两张图")
      .field("clientMessageId", "client-image-batch-1")
      .attach("images", Buffer.from("first-png-data"), {
        filename: "first.png",
        contentType: "image/png"
      })
      .attach("images", Buffer.from("second-jpg-data"), {
        filename: "second.jpg",
        contentType: "image/jpeg"
      });

    expect(response.status).toBe(202);
    expect(service.sendImageMessages).toHaveBeenCalledWith(
      "thread-1",
      expect.objectContaining({
        text: "请帮我分析这两张图",
        clientMessageId: "client-image-batch-1",
        images: [
          expect.objectContaining({
            originalFileName: "first.png",
            mimeType: "image/png"
          }),
          expect.objectContaining({
            originalFileName: "second.jpg",
            mimeType: "image/jpeg"
          })
        ]
      })
    );
  });

  it("accepts multipart file-message uploads for authenticated requests", async () => {
    const service = createServiceStub({
      sendFileMessages: vi.fn(async (threadId: string, payload: any) => ({
        accepted: true,
        threadId,
        clientMessageId: payload.clientMessageId,
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      }))
    } as any);
    const app = createGatewayApp({
      service,
      authToken: "secret-token"
    });

    const response = await request(app)
      .post("/api/threads/thread-1/file-messages")
      .set("authorization", "Bearer secret-token")
      .field("text", "这是诊断日志")
      .field("clientMessageId", "client-file-batch-1")
      .attach("files", Buffer.from("zip-data"), {
        filename: "diagnostics.zip",
        contentType: "application/zip"
      })
      .attach("files", Buffer.from("log-data"), {
        filename: "gateway.log",
        contentType: "text/plain"
      });

    expect(response.status).toBe(202);
    expect((service as any).sendFileMessages).toHaveBeenCalledWith(
      "thread-1",
      expect.objectContaining({
        text: "这是诊断日志",
        clientMessageId: "client-file-batch-1",
        files: [
          expect.objectContaining({
            originalFileName: "diagnostics.zip",
            mimeType: "application/zip"
          }),
          expect.objectContaining({
            originalFileName: "gateway.log",
            mimeType: "text/plain"
          })
        ]
      })
    );
  });

  it("accepts multipart attachment-message uploads with images and files", async () => {
    const service = createServiceStub({
      sendAttachmentMessages: vi.fn(async (threadId: string, payload: SendAttachmentMessagesPayload) => ({
        accepted: true,
        threadId,
        clientMessageId: payload.clientMessageId,
        sendPath: "desktop_bridge" as const,
        confirmation: "observed" as const
      }))
    });
    const app = createGatewayApp({
      service,
      authToken: "secret-token"
    });

    const response = await request(app)
      .post("/api/threads/thread-1/attachment-messages")
      .set("authorization", "Bearer secret-token")
      .field("text", "图片和日志一起看")
      .field("clientMessageId", "client-attachment-batch-1")
      .attach("images", Buffer.from("png-data"), {
        filename: "screen.png",
        contentType: "image/png"
      })
      .attach("files", Buffer.from("zip-data"), {
        filename: "diagnostics.zip",
        contentType: "application/zip"
      });

    expect(response.status).toBe(202);
    expect(service.sendAttachmentMessages).toHaveBeenCalledWith(
      "thread-1",
      expect.objectContaining({
        text: "图片和日志一起看",
        clientMessageId: "client-attachment-batch-1",
        images: [
          expect.objectContaining({
            originalFileName: "screen.png",
            mimeType: "image/png"
          })
        ],
        files: [
          expect.objectContaining({
            originalFileName: "diagnostics.zip",
            mimeType: "application/zip"
          })
        ]
      })
    );
  });
});
