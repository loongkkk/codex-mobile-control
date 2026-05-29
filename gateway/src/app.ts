import express from "express";
import multer from "multer";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, statSync } from "node:fs";
import path from "node:path";

import { isGatewayHttpError } from "./gateway-error";
import type { GatewayEvent, MobileGatewayService } from "./service";

type CreateGatewayAppOptions = {
  service: MobileGatewayService;
  authToken: string;
  mobileDistDir?: string;
  uploadRootDir?: string;
  downloadsDir?: string;
  androidBuildFile?: string;
  resolveLatestApk?: () => Promise<string | null> | string | null;
  runLogsDir?: string;
  sseHeartbeatIntervalMillis?: number;
};

const APK_FILE_PATTERN = /^codex-mobile-control-.*\.apk$/i;
const ANDROID_BUILD_FILE = path.join("android", "app", "build.gradle.kts");
const RUN_LOG_FILE_PATTERN = /^gateway-[\d-]+\.(out|err)\.log$/i;
const MAX_DIAGNOSTIC_LOG_FILES = 8;
const MAX_DIAGNOSTIC_LOG_TAIL_BYTES = 64 * 1024;
const MAX_UPLOAD_FILE_SIZE_BYTES = 64 * 1024 * 1024;
const MAX_UPLOAD_FILES = 16;
const MAX_UPLOAD_FIELDS = 24;
const MAX_UPLOAD_PARTS = 48;
const UPLOAD_TEMP_FILE_TTL_MS = 24 * 60 * 60 * 1000;

type UploadTempCleanupDiagnostics = {
  ttlMs: number;
  deletedFiles: number;
  deletedBytes: number;
  failedFiles: number;
  lastRunAt: string;
};

function asyncHandler(
  handler: (req: express.Request, res: express.Response) => Promise<void>
): express.RequestHandler {
  return (req, res, next) => {
    handler(req, res).catch(next);
  };
}

function readBearerToken(headerValue?: string): string | null {
  if (!headerValue) {
    return null;
  }

  const match = /^Bearer\s+(.+)$/i.exec(headerValue);
  return match ? match[1] : null;
}

function readRequestToken(req: express.Request): string | null {
  const fromHeader = readBearerToken(req.header("authorization") ?? undefined);
  if (fromHeader) {
    return fromHeader;
  }

  const queryToken = req.query.token;
  return typeof queryToken === "string" && queryToken.length > 0 ? queryToken : null;
}

function readThreadId(req: express.Request): string {
  const value = req.params.threadId;
  return Array.isArray(value) ? value[0] : value;
}

function readQueueId(req: express.Request): string {
  const value = req.params.queueId;
  return Array.isArray(value) ? value[0] : value;
}

function setNoStoreHeaders(res: express.Response): void {
  res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, proxy-revalidate");
  res.setHeader("CDN-Cache-Control", "no-store");
  res.setHeader("Pragma", "no-cache");
  res.setHeader("Expires", "0");
}

function parsePositiveIntQuery(value: unknown): number | null {
  if (typeof value !== "string") {
    return null;
  }

  const parsed = Number.parseInt(value, 10);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

async function ensureAuthorized(
  req: express.Request,
  res: express.Response,
  service: MobileGatewayService
): Promise<string | null> {
  const token = readRequestToken(req);
  if (!token) {
    res.status(401).json({ error: "missing_token" });
    return null;
  }

  const authenticated = await service.authenticate(token);
  if (!authenticated) {
    res.status(401).json({ error: "invalid_token" });
    return null;
  }

  return token;
}

export function createGatewayApp({
  service,
  authToken,
  mobileDistDir,
  uploadRootDir: configuredUploadRootDir,
  downloadsDir,
  androidBuildFile,
  resolveLatestApk = () => defaultLatestApkPath(downloadsDir),
  runLogsDir = path.resolve(process.cwd()),
  sseHeartbeatIntervalMillis = 15_000
}: CreateGatewayAppOptions): express.Express {
  const app = express();
  const sseClients = new Set<(eventName: string, data: unknown) => void>();
  const uploadRootDir = path.resolve(configuredUploadRootDir ?? path.resolve(process.cwd(), "mobile-uploads"));
  const uploadTempDir = path.resolve(uploadRootDir, ".incoming");
  const startedAtMs = Date.now();
  const startedAt = new Date(startedAtMs).toISOString();
  mkdirSync(uploadTempDir, { recursive: true });
  let uploadTempCleanup = cleanupExpiredIncomingUploadFiles(uploadTempDir);
  const upload = multer({
    dest: uploadTempDir,
    limits: {
      fileSize: MAX_UPLOAD_FILE_SIZE_BYTES,
      files: MAX_UPLOAD_FILES,
      fields: MAX_UPLOAD_FIELDS,
      parts: MAX_UPLOAD_PARTS
    }
  });
  const requireAuth: express.RequestHandler = (req, res, next) => {
    ensureAuthorized(req, res, service)
      .then((token) => {
        if (token) {
          next();
        }
      })
      .catch(next);
  };

  app.use(express.json());

  app.post("/api/auth/login", asyncHandler(async (req, res) => {
    const token = typeof req.body?.token === "string" ? req.body.token : "";
    const authenticated = await service.authenticate(token);
    res.status(authenticated ? 200 : 401).json({ authenticated });
  }));

  app.get("/api/health", asyncHandler(async (_req, res) => {
    const health = await service.getHealth();
    res.status(health.ok ? 200 : 503).json(health);
  }));

  app.get("/downloads/latest.apk", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const latestApkPath = await resolveLatestApk();
    if (!latestApkPath) {
      res.status(404).json({
        error: "latest_apk_not_found",
        message: "最新版安装包暂不可用"
      });
      return;
    }

    const fileName = path.basename(latestApkPath);
    setNoStoreHeaders(res);
    res.type("application/vnd.android.package-archive");
    res.setHeader("Content-Disposition", `attachment; filename="${fileName}"`);
    res.sendFile(path.resolve(latestApkPath));
  }));

  app.get("/downloads/latest.json", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const latestApkPath = await resolveLatestApk();
    if (!latestApkPath) {
      res.json({ available: false });
      return;
    }

    setNoStoreHeaders(res);
    res.json({
      available: true,
      fileName: path.basename(latestApkPath),
      ...readAndroidBuildVersion(latestApkPath, androidBuildFile),
      downloadUrl: "/downloads/latest.apk"
    });
  }));

  app.get("/api/threads", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const threads = await service.listThreads();
    setNoStoreHeaders(res);
    res.json({ threads });
  }));

  app.get("/api/automations", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const automations = await service.listAutomations();
    res.json({ automations });
  }));

  app.get("/api/diagnostics", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const resolvedRunLogsDir = path.resolve(runLogsDir);
    const runLogs = collectRunLogDiagnostics(resolvedRunLogsDir, authToken);
    const latestApkPath = await resolveLatestApk();
    const runtimeServiceDiagnostics = await service.getRuntimeDiagnostics?.();
    uploadTempCleanup = mergeUploadTempCleanupDiagnostics(
      uploadTempCleanup,
      cleanupExpiredIncomingUploadFiles(uploadTempDir)
    );
    setNoStoreHeaders(res);
    res.json({
      diagnosticsVersion: 2,
      generatedAt: new Date().toISOString(),
      health: await service.getHealth(),
      gateway: {
        cwd: process.cwd(),
        runLogsDir: resolvedRunLogsDir
      },
      runtime: {
        cwd: process.cwd(),
        pid: process.pid,
        nodeVersion: process.version,
        platform: process.platform,
        arch: process.arch,
        startedAt,
        uptimeSeconds: Math.max(0, Math.floor((Date.now() - startedAtMs) / 1000)),
        runLogsDir: resolvedRunLogsDir
      },
      source: collectSourceDiagnostics(process.cwd()),
      release: {
        latestApk: collectLatestApkDiagnostics(latestApkPath, androidBuildFile)
      },
      storage: {
        runLogs: {
          dir: resolvedRunLogsDir,
          files: runLogs.length,
          maxFiles: MAX_DIAGNOSTIC_LOG_FILES,
          tailBytes: MAX_DIAGNOSTIC_LOG_TAIL_BYTES
        },
        uploads: collectUploadStorageDiagnostics(uploadRootDir, uploadTempDir, uploadTempCleanup)
      },
      ...(runtimeServiceDiagnostics ? { service: runtimeServiceDiagnostics } : {}),
      runLogs
    });
  }));

  app.get("/api/threads/:threadId/preview", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const preview = await service.getThreadPreview(readThreadId(req));
    setNoStoreHeaders(res);
    res.json(preview);
  }));

  app.get("/api/threads/:threadId", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const detail = await service.getThreadDetail(readThreadId(req));
    setNoStoreHeaders(res);
    res.json(detail);
  }));

  app.get("/api/threads/:threadId/events", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const cursor = typeof req.query.cursor === "string" ? req.query.cursor : null;
    const result = await service.getThreadEvents(readThreadId(req), cursor);
    setNoStoreHeaders(res);
    res.json(result);
  }));

  app.get("/api/threads/:threadId/markdown-preview", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const filePath = typeof req.query.path === "string" ? req.query.path : "";
    const preview = await service.getMarkdownFilePreview(readThreadId(req), filePath);
    res.json(preview);
  }));

  app.get("/api/threads/:threadId/messages", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const before = typeof req.query.before === "string" ? req.query.before : null;
    const beforeTimestamp =
      typeof req.query.beforeTimestamp === "string" ? req.query.beforeTimestamp : null;
    const after = typeof req.query.after === "string" ? req.query.after : null;
    const afterTimestamp =
      typeof req.query.afterTimestamp === "string" ? req.query.afterTimestamp : null;
    const limit = parsePositiveIntQuery(req.query.limit);
    const result = await service.getThreadMessages(
      readThreadId(req),
      before,
      beforeTimestamp,
      limit,
      after,
      afterTimestamp
    );
    setNoStoreHeaders(res);
    res.json(result);
  }));

  app.get("/api/threads/:threadId/queued-messages", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const messages = await service.getQueuedTextMessages(readThreadId(req));
    setNoStoreHeaders(res);
    res.json({ messages });
  }));

  app.delete("/api/threads/:threadId/queued-messages/:queueId", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const message = await service.cancelQueuedTextMessage(readThreadId(req), readQueueId(req));
    setNoStoreHeaders(res);
    if (!message) {
      res.status(404).json({
        error: "queued_message_not_found",
        message: "排队消息不存在或已开始发送"
      });
      return;
    }
    res.json({ message });
  }));

  app.post("/api/threads/:threadId/queued-messages/:queueId/retry", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const message = await service.retryQueuedTextMessage(readThreadId(req), readQueueId(req));
    setNoStoreHeaders(res);
    if (!message) {
      res.status(404).json({
        error: "queued_message_not_found",
        message: "排队消息不存在或不可重试"
      });
      return;
    }
    res.json({ message });
  }));

  app.post("/api/threads/:threadId/messages", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const result = await service.sendMessage(readThreadId(req), req.body);
    res.status(202).json(result);
  }));

  app.post(
    "/api/threads/:threadId/image-message",
    requireAuth,
    upload.single("image"),
    asyncHandler(async (req, res) => {
      if (!req.file) {
        res.status(400).json({
          error: "image_upload_invalid",
          message: "缺少图片文件"
        });
        return;
      }

      const imageFile = req.file;
      const result = await runWithUploadCleanup(req, () =>
        service.sendImageMessage(readThreadId(req), {
          text: typeof req.body?.text === "string" ? req.body.text : undefined,
          clientMessageId: String(req.body?.clientMessageId ?? ""),
          tempFilePath: imageFile.path,
          originalFileName: imageFile.originalname,
          mimeType: imageFile.mimetype
        })
      );
      res.status(202).json(result);
    })
  );

  app.post(
    "/api/threads/:threadId/image-messages",
    requireAuth,
    upload.array("images"),
    asyncHandler(async (req, res) => {
      const files = Array.isArray(req.files)
        ? req.files
        : Object.values(req.files ?? {}).flat();
      if (files.length === 0) {
        res.status(400).json({
          error: "image_upload_invalid",
          message: "缺少图片文件"
        });
        return;
      }

      const result = await runWithUploadCleanup(req, () =>
        service.sendImageMessages(readThreadId(req), {
          text: typeof req.body?.text === "string" ? req.body.text : undefined,
          clientMessageId: String(req.body?.clientMessageId ?? ""),
          images: files.map((file) => ({
            tempFilePath: file.path,
            originalFileName: file.originalname,
            mimeType: file.mimetype
          }))
        })
      );
      res.status(202).json(result);
    })
  );

  app.post(
    "/api/threads/:threadId/file-messages",
    requireAuth,
    upload.array("files"),
    asyncHandler(async (req, res) => {
      const files = Array.isArray(req.files)
        ? req.files
        : Object.values(req.files ?? {}).flat();
      if (files.length === 0) {
        res.status(400).json({
          error: "file_upload_invalid",
          message: "缺少文件"
        });
        return;
      }

      const result = await runWithUploadCleanup(req, () =>
        service.sendFileMessages(readThreadId(req), {
          text: typeof req.body?.text === "string" ? req.body.text : undefined,
          clientMessageId: String(req.body?.clientMessageId ?? ""),
          files: files.map((file) => ({
            tempFilePath: file.path,
            originalFileName: file.originalname,
            mimeType: file.mimetype
          }))
        })
      );
      res.status(202).json(result);
    })
  );

  app.post(
    "/api/threads/:threadId/attachment-messages",
    requireAuth,
    upload.fields([
      { name: "images" },
      { name: "files" }
    ]),
    asyncHandler(async (req, res) => {
      const groupedFiles = req.files && !Array.isArray(req.files) ? req.files : {};
      const images = groupedFiles.images ?? [];
      const files = groupedFiles.files ?? [];
      if (images.length === 0 && files.length === 0) {
        res.status(400).json({
          error: "attachment_upload_invalid",
          message: "缺少附件"
        });
        return;
      }

      const result = await runWithUploadCleanup(req, () =>
        service.sendAttachmentMessages(readThreadId(req), {
          text: typeof req.body?.text === "string" ? req.body.text : undefined,
          clientMessageId: String(req.body?.clientMessageId ?? ""),
          images: images.map((file) => ({
            tempFilePath: file.path,
            originalFileName: file.originalname,
            mimeType: file.mimetype
          })),
          files: files.map((file) => ({
            tempFilePath: file.path,
            originalFileName: file.originalname,
            mimeType: file.mimetype
          }))
        })
      );
      res.status(202).json(result);
    })
  );

  app.get("/api/uploads/:threadId/:fileName", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const fileName = Array.isArray(req.params.fileName) ? req.params.fileName[0] : req.params.fileName;
    const file = await service.getUploadFile(readThreadId(req), fileName);
    res.type(file.mimeType);
    res.sendFile(file.absolutePath);
  }));

  app.get("/api/alerts", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const cursor = typeof req.query.cursor === "string" ? req.query.cursor : null;
    const result = await service.getAlerts(cursor);
    res.json(result);
  }));

  app.post("/api/realtime/latency-probe", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    const probeId = typeof req.body?.probeId === "string" ? req.body.probeId.trim() : "";
    const sentAt = typeof req.body?.sentAt === "number" ? req.body.sentAt : Number.NaN;
    if (!probeId || !Number.isFinite(sentAt) || sentAt <= 0) {
      res.status(400).json({
        error: "invalid_latency_probe",
        message: "延迟测速参数无效"
      });
      return;
    }

    const event = {
      type: "latency_probe_result",
      probeId,
      sentAt,
      timestamp: new Date().toISOString()
    };
    for (const writeEvent of sseClients) {
      writeEvent("latency_probe_result", event);
    }
    res.status(202).json(event);
  }));

  app.get("/api/stream", asyncHandler(async (req, res) => {
    if (!(await ensureAuthorized(req, res, service))) {
      return;
    }

    res.writeHead(200, {
      "Cache-Control": "no-cache, no-transform",
      Connection: "keep-alive",
      "Content-Type": "text/event-stream",
      "X-Accel-Buffering": "no"
    });
    res.write(": connected\n\n");
    const heartbeat = setInterval(() => {
      res.write(": heartbeat\n\n");
    }, sseHeartbeatIntervalMillis);

    const writeEvent = (eventName: string, event: unknown) => {
      res.write(`event: ${eventName}\n`);
      res.write(`data: ${JSON.stringify(event)}\n\n`);
    };
    sseClients.add(writeEvent);

    const unsubscribe = service.subscribe((event: GatewayEvent) => {
      const eventName = "trigger" in event ? "alert" : "thread_event";
      writeEvent(eventName, event);
    });

    req.on("close", () => {
      clearInterval(heartbeat);
      sseClients.delete(writeEvent);
      unsubscribe();
      res.end();
    });
  }));

  if (mobileDistDir && existsSync(mobileDistDir)) {
    app.use(express.static(mobileDistDir));
    app.get(/^\/(?!api).*/, (_req, res) => {
      res.sendFile(path.join(mobileDistDir, "index.html"));
    });
  }

  app.use(
    (
      error: unknown,
      req: express.Request,
      res: express.Response,
      _next: express.NextFunction
    ) => {
      const message = error instanceof Error ? error.message : "gateway_error";
      if (error instanceof multer.MulterError) {
        cleanupUploadedFiles(req);
        const mappedError = mapMulterError(error);
        res.status(mappedError.status).json({
          error: mappedError.error,
          message: mappedError.message
        });
        return;
      }
      if (isGatewayHttpError(error)) {
        res.status(error.statusCode).json(error.body);
        return;
      }

      const status =
        message.startsWith("thread_not_found")
          ? 404
          : message.includes("send")
            ? 409
            : 503;
      res.status(status).json({
        error: message,
        message
      });
    }
  );

  return app;
}

function mapMulterError(error: multer.MulterError): {
  status: number;
  error: string;
  message: string;
} {
  switch (error.code) {
    case "LIMIT_UNEXPECTED_FILE":
      return {
        status: 400,
        error: "upload_unexpected_field",
        message: "上传包含不支持的文件字段"
      };
    case "LIMIT_FILE_SIZE":
      return {
        status: 413,
        error: "upload_file_too_large",
        message: "上传文件过大"
      };
    case "LIMIT_FILE_COUNT":
    case "LIMIT_PART_COUNT":
    case "LIMIT_FIELD_COUNT":
    case "LIMIT_FIELD_VALUE":
      return {
        status: 413,
        error: "upload_limit_exceeded",
        message: "上传内容超过限制"
      };
    default:
      return {
        status: 400,
        error: "upload_invalid",
        message: error.message || "上传内容无效"
      };
  }
}

async function runWithUploadCleanup<T>(
  req: express.Request,
  operation: () => Promise<T>
): Promise<T> {
  try {
    return await operation();
  } catch (error) {
    cleanupUploadedFiles(req);
    throw error;
  }
}

function cleanupUploadedFiles(req: express.Request): void {
  for (const file of uploadedFiles(req)) {
    if (file.path) {
      rmSync(file.path, { force: true });
    }
  }
}

function uploadedFiles(req: express.Request): Express.Multer.File[] {
  const files: Express.Multer.File[] = [];
  if (req.file) {
    files.push(req.file);
  }
  if (Array.isArray(req.files)) {
    files.push(...req.files);
  } else if (req.files) {
    files.push(...Object.values(req.files).flat());
  }
  return files;
}

function collectUploadStorageDiagnostics(
  rootDir: string,
  incomingDir: string,
  incomingCleanup: UploadTempCleanupDiagnostics
): {
  rootDir: string;
  incomingDir: string;
  incomingFiles: number;
  incomingBytes: number;
  storedFiles: number;
  storedBytes: number;
  incomingCleanup: UploadTempCleanupDiagnostics;
} {
  const incoming = collectDirectoryFileStats(incomingDir);
  const stored = collectDirectoryFileStats(rootDir, new Set([path.resolve(incomingDir)]));
  return {
    rootDir,
    incomingDir,
    incomingFiles: incoming.files,
    incomingBytes: incoming.bytes,
    storedFiles: stored.files,
    storedBytes: stored.bytes,
    incomingCleanup
  };
}

function cleanupExpiredIncomingUploadFiles(
  incomingDir: string,
  nowMs = Date.now()
): UploadTempCleanupDiagnostics {
  let deletedFiles = 0;
  let deletedBytes = 0;
  let failedFiles = 0;

  if (existsSync(incomingDir)) {
    for (const entry of readdirSync(incomingDir, { withFileTypes: true })) {
      if (!entry.isFile()) {
        continue;
      }

      const absolutePath = path.join(incomingDir, entry.name);
      const stats = statSync(absolutePath, { throwIfNoEntry: false });
      if (!stats?.isFile() || nowMs - stats.mtimeMs < UPLOAD_TEMP_FILE_TTL_MS) {
        continue;
      }

      try {
        rmSync(absolutePath, { force: true });
        deletedFiles += 1;
        deletedBytes += stats.size;
      } catch {
        failedFiles += 1;
      }
    }
  }

  return {
    ttlMs: UPLOAD_TEMP_FILE_TTL_MS,
    deletedFiles,
    deletedBytes,
    failedFiles,
    lastRunAt: new Date(nowMs).toISOString()
  };
}

function mergeUploadTempCleanupDiagnostics(
  previous: UploadTempCleanupDiagnostics,
  current: UploadTempCleanupDiagnostics
): UploadTempCleanupDiagnostics {
  return {
    ttlMs: current.ttlMs,
    deletedFiles: previous.deletedFiles + current.deletedFiles,
    deletedBytes: previous.deletedBytes + current.deletedBytes,
    failedFiles: previous.failedFiles + current.failedFiles,
    lastRunAt: current.lastRunAt
  };
}

function collectDirectoryFileStats(
  directory: string,
  excludedDirectories = new Set<string>()
): { files: number; bytes: number } {
  const resolvedDirectory = path.resolve(directory);
  if (excludedDirectories.has(resolvedDirectory) || !existsSync(resolvedDirectory)) {
    return { files: 0, bytes: 0 };
  }

  let files = 0;
  let bytes = 0;
  for (const entry of readdirSync(resolvedDirectory, { withFileTypes: true })) {
    const absolutePath = path.join(resolvedDirectory, entry.name);
    if (entry.isDirectory()) {
      const nested = collectDirectoryFileStats(absolutePath, excludedDirectories);
      files += nested.files;
      bytes += nested.bytes;
      continue;
    }

    if (!entry.isFile()) {
      continue;
    }

    const stats = statSync(absolutePath, { throwIfNoEntry: false });
    if (stats?.isFile()) {
      files += 1;
      bytes += stats.size;
    }
  }

  return { files, bytes };
}

function defaultLatestApkPath(downloadsDir?: string): string | null {
  const latestApkFile = firstExistingFile([
    downloadsDir ? path.resolve(downloadsDir, "latest.apk") : null,
    path.resolve(process.cwd(), "gateway", "downloads", "latest.apk"),
    path.resolve(process.cwd(), "downloads", "latest.apk")
  ]);
  if (latestApkFile) {
    return latestApkFile;
  }

  const candidateDirectories = [
    downloadsDir,
    path.resolve(process.cwd(), "gateway", "downloads"),
    path.resolve(process.cwd(), "downloads"),
    path.resolve(process.cwd(), ".."),
    process.cwd(),
    path.resolve(process.cwd(), "android", "outputs")
  ].filter((value): value is string => typeof value === "string" && value.length > 0);
  let latest: { absolutePath: string; mtimeMs: number; fileName: string } | null = null;

  for (const directory of candidateDirectories) {
    if (!existsSync(directory)) {
      continue;
    }

    for (const fileName of readdirSync(directory)) {
      if (!APK_FILE_PATTERN.test(fileName)) {
        continue;
      }

      const absolutePath = path.join(directory, fileName);
      const stats = statSync(absolutePath, { throwIfNoEntry: false });
      if (!stats?.isFile()) {
        continue;
      }

      if (
        latest == null ||
        stats.mtimeMs > latest.mtimeMs ||
        (stats.mtimeMs == latest.mtimeMs && fileName > latest.fileName)
      ) {
        latest = {
          absolutePath,
          mtimeMs: stats.mtimeMs,
          fileName
        };
      }
    }
  }

  return latest?.absolutePath ?? null;
}

function firstExistingFile(filePaths: Array<string | null>): string | null {
  for (const filePath of filePaths) {
    if (!filePath) {
      continue;
    }

    const stats = statSync(filePath, { throwIfNoEntry: false });
    if (stats?.isFile()) {
      return filePath;
    }
  }

  return null;
}

function collectRunLogDiagnostics(runLogsDir: string, authToken: string): Array<{
  fileName: string;
  size: number;
  modifiedAt: string;
  contentTail: string;
}> {
  if (!existsSync(runLogsDir)) {
    return [];
  }

  return readdirSync(runLogsDir)
    .filter((fileName) => RUN_LOG_FILE_PATTERN.test(fileName))
    .map((fileName) => {
      const absolutePath = path.join(runLogsDir, fileName);
      const stats = statSync(absolutePath, { throwIfNoEntry: false });
      return stats?.isFile()
        ? {
            fileName,
            absolutePath,
            size: stats.size,
            modifiedAt: new Date(stats.mtimeMs).toISOString(),
            mtimeMs: stats.mtimeMs
          }
        : null;
    })
    .filter((entry): entry is {
      fileName: string;
      absolutePath: string;
      size: number;
      modifiedAt: string;
      mtimeMs: number;
    } => entry != null)
    .sort((left, right) => right.mtimeMs - left.mtimeMs)
    .slice(0, MAX_DIAGNOSTIC_LOG_FILES)
    .map(({ fileName, absolutePath, size, modifiedAt }) => ({
      fileName,
      size,
      modifiedAt,
      contentTail: redactSensitiveText(
        readTextTail(absolutePath, MAX_DIAGNOSTIC_LOG_TAIL_BYTES),
        authToken
      )
    }));
}

function collectLatestApkDiagnostics(latestApkPath: string | null, androidBuildFile?: string): {
  available: boolean;
  fileName?: string;
  absolutePath?: string;
  size?: number;
  modifiedAt?: string;
  sha256?: string;
  versionCode?: number;
  versionName?: string;
  downloadUrl?: string;
} {
  if (!latestApkPath) {
    return { available: false };
  }

  const absolutePath = path.resolve(latestApkPath);
  const stats = statSync(absolutePath, { throwIfNoEntry: false });
  if (!stats?.isFile()) {
    return { available: false };
  }

  return {
    available: true,
    fileName: path.basename(absolutePath),
    absolutePath,
    size: stats.size,
    modifiedAt: new Date(stats.mtimeMs).toISOString(),
    sha256: sha256File(absolutePath),
    ...readAndroidBuildVersion(absolutePath, androidBuildFile),
    downloadUrl: "/downloads/latest.apk"
  };
}

function sha256File(filePath: string): string {
  return createHash("sha256").update(readFileSync(filePath)).digest("hex");
}

function collectSourceDiagnostics(cwd: string): {
  cwd: string;
  repoRoot: string | null;
  commit: string | null;
  branch: string | null;
  dirty: boolean | null;
  available: boolean;
} {
  const repoRoot = findGitRoot(cwd);
  if (!repoRoot) {
    return {
      cwd,
      repoRoot: null,
      commit: null,
      branch: null,
      dirty: null,
      available: false
    };
  }

  const commit = runGit(repoRoot, ["rev-parse", "HEAD"]);
  const branch = runGit(repoRoot, ["rev-parse", "--abbrev-ref", "HEAD"]);
  const status = runGit(repoRoot, ["status", "--porcelain", "--untracked-files=no"]);
  return {
    cwd,
    repoRoot,
    commit,
    branch,
    dirty: status == null ? null : status.length > 0,
    available: commit != null
  };
}

function findGitRoot(startDirectory: string): string | null {
  let current = path.resolve(startDirectory);
  while (true) {
    if (existsSync(path.join(current, ".git"))) {
      return current;
    }

    const parent = path.dirname(current);
    if (parent === current) {
      return null;
    }
    current = parent;
  }
}

function runGit(cwd: string, args: string[]): string | null {
  try {
    return execFileSync("git", args, {
      cwd,
      encoding: "utf8",
      stdio: ["ignore", "pipe", "ignore"],
      timeout: 2_000
    }).trim();
  } catch {
    return null;
  }
}

function readTextTail(filePath: string, maxBytes: number): string {
  const buffer = readFileSync(filePath);
  return buffer.subarray(Math.max(0, buffer.length - maxBytes)).toString("utf8");
}

function redactSensitiveText(text: string, authToken: string): string {
  const withoutExactToken = authToken
    ? text.split(authToken).join("[REDACTED_TOKEN]")
    : text;
  return withoutExactToken
    .replace(/Bearer\s+[A-Za-z0-9._~+/=-]+/gi, "Bearer [REDACTED_TOKEN]")
    .replace(/([?&]token=)[^&\s]+/gi, "$1[REDACTED_TOKEN]")
    .replace(/("token"\s*:\s*")[^"]+(")/gi, "$1[REDACTED_TOKEN]$2");
}

function readAndroidBuildVersion(latestApkPath: string, androidBuildFile?: string): {
  versionCode?: number;
  versionName?: string;
} {
  const buildFile = resolveAndroidBuildFile(latestApkPath, androidBuildFile);
  if (!buildFile) {
    return {};
  }

  const source = readFileSync(buildFile, "utf8");
  const versionCode = Number.parseInt(
    /versionCode\s*=\s*(\d+)/.exec(source)?.[1] ?? "",
    10
  );
  const versionName = /versionName\s*=\s*["']([^"']+)["']/.exec(source)?.[1];
  return {
    ...(Number.isFinite(versionCode) ? { versionCode } : {}),
    ...(versionName ? { versionName } : {})
  };
}

function resolveAndroidBuildFile(latestApkPath: string, androidBuildFile?: string): string | null {
  const apkDirectory = path.dirname(path.resolve(latestApkPath));
  const candidateFiles = [
    androidBuildFile ? path.resolve(androidBuildFile) : null,
    path.resolve(process.cwd(), ANDROID_BUILD_FILE),
    path.resolve(process.cwd(), "..", ANDROID_BUILD_FILE),
    path.resolve(apkDirectory, ANDROID_BUILD_FILE),
    path.resolve(apkDirectory, "..", ANDROID_BUILD_FILE)
  ].filter((value): value is string => typeof value === "string" && value.length > 0);

  return candidateFiles.find((file) => existsSync(file)) ?? null;
}
