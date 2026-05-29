import type {
  Alert,
  AlertTrigger,
  AlertsResponse,
  MarkdownFilePreview,
  MobileAutomationItem,
  MobileAutomationKind,
  MobileAutomationStatus,
  MobileThreadStatus,
  QueuedTextMessage,
  SendMessageRequest,
  SendMessageResponse,
  ThreadComposerState,
  ThreadDetail,
  ThreadEvent,
  ThreadFileChangeItem,
  ThreadFileChanges,
  ThreadEventsResponse,
  ThreadListItem,
  ThreadMessage,
  ThreadMessagesResponse,
  ThreadSendDecision,
  ThreadStatusDecision,
  ThreadStatusDecisionCandidate
} from "../../shared/src/api";
import fs from "node:fs/promises";
import fsSync from "node:fs";
import path from "node:path";
import { DesktopSendCoordinator } from "./desktop-send-coordinator";
import { UnsupportedDesktopBridge } from "./desktop-bridge";
import { FileSendCoordinator } from "./file-send-coordinator";
import { GatewayHttpError } from "./gateway-error";
import { MemoryGatewayQueueStore, type GatewayQueueStore } from "./gateway-queue-store";
import { ImageSendCoordinator } from "./image-send-coordinator";
import { readMarkdownFilePreview } from "./markdown-preview-resolver";
import type { MobileUploadStore, StoredUploadFile } from "./mobile-upload-store";
import {
  compareDetailStatusCandidates,
  isActiveWaitingForInput,
  logSignalEventKind,
  mapStatusFromCodex,
  shouldKeepExistingHint,
  shouldUseLogSignalForDetailStatus,
  shouldUseLogSignalForStatus,
  shouldUseRuntimeHintForThread,
  statusText
} from "./thread-status-resolver";
import type { DetailStatusCandidate } from "./thread-status-resolver";
import type {
  DesktopSendJobDiagnostics,
  DesktopSendJobKind,
  GatewayEvent,
  HealthStatus,
  MobileGatewayService,
  MobileGatewayRuntimeDiagnostics,
  SendAttachmentMessagesPayload,
  SendFileMessagesPayload,
  SendImageMessagePayload,
  SendImageMessagesPayload,
  StatusDecisionDiagnostics,
  StatusDecisionDiagnosticsContext,
  ThreadObservationStopReason
} from "./service";

export type CodexThreadStatus =
  | { type: "notLoaded" | "idle" | "systemError" }
  | { type: "active"; activeFlags: Array<"waitingOnApproval" | "waitingOnUserInput"> };

export type CodexUserInput =
  | { type: "text"; text: string; text_elements: Array<unknown> }
  | { type: "image"; url: string }
  | { type: "localImage"; path: string }
  | { type: "skill"; name: string; path: string }
  | { type: "mention"; name: string; path: string };

export type CodexThreadItem =
  | { type: "userMessage"; id: string; content: CodexUserInput[] }
  | {
      type: "agentMessage";
      id: string;
      text: string;
      phase: string | null;
      memoryCitation: unknown | null;
    }
  | { type: "plan"; id: string; text: string }
  | {
      type: "commandExecution";
      id: string;
      command: string;
      cwd: string;
      processId: string | null;
      source: string;
      status: string;
      commandActions: Array<unknown>;
      aggregatedOutput: string | null;
      exitCode: number | null;
      durationMs: number | null;
    }
  | {
      type: "fileChange";
      id: string;
      changes: Array<{
        path?: string;
        kind?: { type?: string; move_path?: string | null } | string | null;
        diff?: string | null;
      }>;
      status?: string | null;
    };

export type CodexTurn = {
  id: string;
  items: CodexThreadItem[];
  status: "completed" | "interrupted" | "failed" | "inProgress";
  error: { message: string } | null;
  startedAt: number | null;
  completedAt: number | null;
  durationMs: number | null;
};

export type CodexThread = {
  id: string;
  preview: string;
  cwd: string;
  createdAt: number;
  updatedAt: number;
  name: string | null;
  status: CodexThreadStatus;
  turns: CodexTurn[];
};

export type ThreadMetadataRecord = {
  threadId: string;
  title: string | null;
  cwd: string | null;
  updatedAt: number;
  archived: boolean;
  firstUserMessage: string | null;
  rolloutPath?: string | null;
  model?: string | null;
  reasoningEffort?: string | null;
  sandboxPolicy?: string | null;
  approvalMode?: string | null;
  source?: string | null;
  agentRole?: string | null;
  agentNickname?: string | null;
  agentPath?: string | null;
};

export type LogSignal = {
  signalId: string;
  threadId: string;
  status: MobileThreadStatus;
  text: string;
  timestamp: string;
  cursor: string;
  notificationEligible?: boolean;
};

export type CodexNotification =
  | {
      method: "thread/status/changed";
      params: { threadId: string; status: CodexThreadStatus };
    }
  | {
      method: "turn/started";
      params: { threadId: string; turn: CodexTurn };
    }
  | {
      method: "turn/completed";
      params: { threadId: string; turn: CodexTurn };
    }
  | {
      method: "item/plan/delta";
      params: { threadId: string; itemId: string; delta: string };
    }
  | {
      method: "item/agentMessage/delta";
      params: { threadId: string; itemId: string; delta: string };
    };

export type CodexAppServerClient = {
  getConnectionState(): "connected" | "connecting" | "disconnected";
  getCodexHome(): string;
  listThreads(): Promise<CodexThread[]>;
  listLoadedThreads(): Promise<string[]>;
  readThread(threadId: string): Promise<CodexThread>;
  resumeThread(threadId: string): Promise<void>;
  startTurn(threadId: string, text: string): Promise<{ accepted: boolean; turnId: string }>;
  startTurnWithInput(
    threadId: string,
    input: CodexUserInput[]
  ): Promise<{ accepted: boolean; turnId: string }>;
  steerTurnWithInput(
    threadId: string,
    input: CodexUserInput[],
    expectedTurnId: string
  ): Promise<{ accepted: boolean; turnId: string }>;
  subscribe(listener: (notification: CodexNotification) => void): () => void;
};

export type GatewayQueuedTextMessage = QueuedTextMessage;

export type StateRepository = {
  getThreadMetadata(threadIds: string[]): Promise<ThreadMetadataRecord[]>;
  listDesktopVisibleThreadMetadata?(limit: number): Promise<ThreadMetadataRecord[]>;
};

export type LogRepository = {
  listRecentSignals(threadIds: string[]): Promise<LogSignal[]>;
  pollSignals(cursor?: string | null): Promise<{ signals: LogSignal[]; nextCursor: string | null }>;
};

export type RuntimeThreadHint = {
  status?: MobileThreadStatus;
  progressSummary?: string;
  updatedAt?: string;
  runningStartedAt?: string;
  optimisticUntilMs?: number;
  fallbackStatus?: MobileThreadStatus;
  fallbackProgressSummary?: string;
  fallbackUpdatedAt?: string;
  fallbackRunningStartedAt?: string;
};

type ThreadObservation = {
  clientMessageId: string;
  submittedAtMs: number;
  deadlineMs: number;
  baselineTurnId?: string;
  rolloutPath?: string;
  rolloutOffset?: number;
  rolloutObservedTurnId?: string;
  rolloutObservedStartedAt?: string;
  timer: ReturnType<typeof setTimeout> | null;
  inFlight: boolean;
};

type StopThreadObservationOptions = {
  reason: NonNullable<
    MobileGatewayRuntimeDiagnostics["threadObservations"]["lastStopped"]
  >["stopReason"];
  finalStatus?: MobileThreadStatus;
  error?: string;
};

type RolloutObservationSignal = {
  status: MobileThreadStatus;
  kind: ThreadEvent["kind"];
  text: string;
  timestamp: string;
  sourceId: string;
  turnId?: string;
};

type RolloutObservationRead = {
  signals: RolloutObservationSignal[];
  messages: ThreadMessage[];
  nextOffset: number;
};

type RolloutTailRead = {
  lines: string[];
  truncated: boolean;
};

type RolloutRecentMessagesRead = {
  messages: ThreadMessage[];
  hasEarlier: boolean;
};

type ObservedStatusResult = {
  event: ThreadEvent;
  sourceId: string;
  alertBody?: string;
  stopReason?: ThreadObservationStopReason;
};

type IngestSignalsOptions = {
  emitEvents?: boolean;
  onlyIfChanged?: boolean;
};

type AutomationDefinition = {
  id: string;
  name: string;
  kind: MobileAutomationKind;
  status: MobileAutomationStatus;
  rrule: string | null;
  targetThreadId: string | null;
};

type MobileGatewayRuntimeServiceOptions = {
  authToken: string;
  appServer: CodexAppServerClient;
  stateRepository: StateRepository;
  logRepository: LogRepository;
  sendMode?: MobileGatewaySendMode;
  desktopSendCoordinator?: DesktopSendCoordinator;
  imageSendCoordinator?: Pick<ImageSendCoordinator, "send" | "sendMany">;
  fileSendCoordinator?: Pick<FileSendCoordinator, "sendMany">;
  queueStore?: GatewayQueueStore;
  queueDispatchIntervalMs?: number;
  uploadStore?: Pick<
    MobileUploadStore,
    "persistUploadedFile" | "resolveStoredFile" | "makeRelativeUrl"
  >;
};

export type MobileGatewaySendMode = "desktop_bridge" | "official_persistence";

function normalizeRuntimeThreadHint(hint: RuntimeThreadHint): RuntimeThreadHint {
  return {
    ...hint,
    runningStartedAt:
      hint.status === "running"
        ? hint.runningStartedAt ?? hint.updatedAt
        : undefined
  };
}

const MAX_EVENTS = 50;
const MAX_MESSAGES = 20;
const DEFAULT_MESSAGE_PAGE_LIMIT = 20;
const MAX_MESSAGE_PAGE_LIMIT = 50;
const MAX_ALERTS = 100;
const MAX_STATUS_DECISION_DIAGNOSTICS = 50;
const MAX_DESKTOP_SEND_DIAGNOSTICS = 30;
const CODEX_GLOBAL_STATE_FILE = ".codex-global-state.json";
const CODEX_SESSION_INDEX_FILE = "session_index.jsonl";
const CODEX_AUTOMATIONS_DIR = "automations";
const CODEX_AUTOMATION_FILE = "automation.toml";
const MISSING_FINAL_ASSISTANT_MESSAGE = "线程异常结束，未收到最终回复";
const OPTIMISTIC_RUNNING_HINT_TTL_MS = 30_000;
const OPTIMISTIC_DEDUPE_WINDOW_MS = 2 * 60_000;
const ATTACHMENT_TEXT_DEDUPE_WINDOW_MS = 15 * 60_000;
const THREAD_LIST_CACHE_TTL_MS = 30_000;
const THREAD_LIST_LIMIT = 50;
const ROLLOUT_PREVIEW_TAIL_BYTES = 2 * 1024 * 1024;
const ROLLOUT_STATUS_MAX_SCAN_BYTES = 16 * 1024 * 1024;
const APP_SERVER_DETAIL_READ_GRACE_MS = 500;
const APP_SERVER_SEND_READ_GRACE_MS = 2_500;
const THREAD_OBSERVATION_POLL_INTERVAL_MS = 2_000;
const THREAD_OBSERVATION_MAX_DURATION_MS = 30 * 60_000;
const GATEWAY_QUEUE_DISPATCH_INTERVAL_MS = 2_000;

function normalizeMessagePageLimit(limit: number | null | undefined): number {
  if (!Number.isFinite(limit) || !limit || limit <= 0) {
    return DEFAULT_MESSAGE_PAGE_LIMIT;
  }

  return Math.min(Math.floor(limit), MAX_MESSAGE_PAGE_LIMIT);
}

function toIso(timestampSeconds: number | null | undefined, fallback = Date.now() / 1000): string {
  const seconds = timestampSeconds ?? fallback;
  return new Date(seconds * 1000).toISOString();
}

function toMillis(timestampSeconds: number | null | undefined): number | null {
  return timestampSeconds == null ? null : timestampSeconds * 1000;
}

function normalizeCwd(cwd: string | null | undefined): string {
  return (cwd ?? "").replace(/^\\\\\?\\/, "");
}

function loadAutomationDefinitions(codexHome: string): AutomationDefinition[] {
  const result: AutomationDefinition[] = [];
  const automationsDir = path.join(codexHome, CODEX_AUTOMATIONS_DIR);
  let entries: fsSync.Dirent[];
  try {
    entries = fsSync.readdirSync(automationsDir, { withFileTypes: true });
  } catch {
    return result;
  }

  for (const entry of entries) {
    if (!entry.isDirectory()) {
      continue;
    }
    const automationPath = path.join(automationsDir, entry.name, CODEX_AUTOMATION_FILE);
    let content: string;
    try {
      content = fsSync.readFileSync(automationPath, "utf8");
    } catch {
      continue;
    }

    const id = readTomlString(content, "id") ?? entry.name;
    result.push({
      id,
      name: readTomlString(content, "name") ?? id,
      kind: normalizeAutomationKind(readTomlString(content, "kind")),
      status: normalizeAutomationStatus(readTomlString(content, "status")),
      rrule: readTomlString(content, "rrule"),
      targetThreadId: readTomlString(content, "target_thread_id")
    });
  }

  return result.sort((left, right) => {
    const statusDelta = automationStatusRank(left.status) - automationStatusRank(right.status);
    if (statusDelta !== 0) {
      return statusDelta;
    }
    return left.name.localeCompare(right.name, "zh-CN");
  });
}

function loadActiveAutomationSummaries(codexHome: string): Map<string, string> {
  const result = new Map<string, string>();
  for (const automation of loadAutomationDefinitions(codexHome)) {
    if (automation.status === "ACTIVE" && automation.targetThreadId) {
      result.set(automation.targetThreadId, formatAutomationSummary(automation.rrule));
    }
  }

  return result;
}

function normalizeAutomationKind(value: string | null): MobileAutomationKind {
  const normalized = value?.toLowerCase();
  return normalized === "heartbeat" || normalized === "cron" ? normalized : "unknown";
}

function normalizeAutomationStatus(value: string | null): MobileAutomationStatus {
  const normalized = value?.toUpperCase();
  return normalized === "ACTIVE" || normalized === "PAUSED" ? normalized : "UNKNOWN";
}

function automationStatusRank(status: MobileAutomationStatus): number {
  if (status === "ACTIVE") {
    return 0;
  }
  if (status === "PAUSED") {
    return 1;
  }
  return 2;
}

function readTomlString(content: string, key: string): string | null {
  const escapedKey = key.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = content.match(new RegExp(`^\\s*${escapedKey}\\s*=\\s*"([^"]*)"\\s*$`, "m"));
  return match?.[1] ?? null;
}

function formatAutomationSummary(rrule: string | null): string {
  const schedule = formatAutomationSchedule(rrule);
  return schedule ? `定时任务已开启 · ${schedule}` : "定时任务已开启";
}

function formatAutomationSchedule(rrule: string | null): string | null {
  if (!rrule) {
    return null;
  }
  const parts = new Map(
    rrule
      .split(";")
      .map((part) => part.split("="))
      .filter((part): part is [string, string] => part.length === 2)
      .map(([key, value]) => [key.trim().toUpperCase(), value.trim().toUpperCase()])
  );
  const frequency = parts.get("FREQ");
  const interval = Number.parseInt(parts.get("INTERVAL") ?? "1", 10);
  const safeInterval = Number.isFinite(interval) && interval > 0 ? interval : 1;

  if (frequency === "MINUTELY") {
    return safeInterval === 1 ? "每分钟" : `每${safeInterval}分钟`;
  }
  if (frequency === "HOURLY") {
    return safeInterval === 1 ? "每小时" : `每${safeInterval}小时`;
  }
  if (frequency === "DAILY") {
    return safeInterval === 1 ? "每天" : `每${safeInterval}天`;
  }
  if (frequency === "WEEKLY") {
    return safeInterval === 1 ? "每周" : `每${safeInterval}周`;
  }
  return null;
}

function applyThreadAutomationSummary(
  thread: ThreadListItem,
  automationSummaries: Map<string, string>
): ThreadListItem {
  const summary = automationSummaries.get(thread.threadId);
  if (!summary) {
    return thread;
  }
  if (thread.status === "running" || thread.status === "waiting_input" || thread.status === "error") {
    return {
      ...thread,
      automationActive: true,
      automationSummary: summary
    };
  }
  return {
    ...thread,
    progressSummary: summary,
    automationActive: true,
    automationSummary: summary
  };
}

function normalizeDisplayPath(value: string): string {
  return value.replace(/^\\\\\?\\/, "").replace(/\\/g, "/");
}

function loadPinnedThreadIds(codexHome: string): Set<string> {
  return new Set(loadPinnedThreadIdList(codexHome));
}

function loadPinnedThreadIdList(codexHome: string): string[] {
  if (!codexHome) {
    return [];
  }

  try {
    const raw = fsSync.readFileSync(path.join(codexHome, CODEX_GLOBAL_STATE_FILE), "utf8");
    const state = JSON.parse(raw) as { "pinned-thread-ids"?: unknown };
    const ids = Array.isArray(state["pinned-thread-ids"]) ? state["pinned-thread-ids"] : [];
    return ids.filter((id): id is string => typeof id === "string" && id.length > 0);
  } catch {
    return [];
  }
}

function loadSessionIndexThreadNames(codexHome: string): Map<string, string> {
  const names = new Map<string, string>();
  if (!codexHome) {
    return names;
  }

  try {
    const raw = fsSync.readFileSync(path.join(codexHome, CODEX_SESSION_INDEX_FILE), "utf8");
    for (const line of raw.split(/\r?\n/)) {
      const trimmed = line.trim();
      if (!trimmed) {
        continue;
      }
      const record = JSON.parse(trimmed) as { id?: unknown; thread_name?: unknown };
      const threadId = typeof record.id === "string" ? record.id.trim() : "";
      const threadName = typeof record.thread_name === "string" ? record.thread_name.trim() : "";
      if (threadId && threadName) {
        names.set(threadId, threadName);
      }
    }
  } catch {
    return names;
  }

  return names;
}

function applyPinnedState(item: ThreadListItem, pinnedThreadIds: Set<string>): ThreadListItem {
  if (pinnedThreadIds.has(item.threadId)) {
    return { ...item, isPinned: true };
  }

  if (!item.isPinned) {
    return item;
  }

  const next = { ...item };
  delete next.isPinned;
  return next;
}

function orderPinnedThreadsByDesktopOrder(
  items: ThreadListItem[],
  pinnedThreadIds: string[]
): ThreadListItem[] {
  if (pinnedThreadIds.length === 0) {
    return items;
  }

  const pinnedRank = new Map(pinnedThreadIds.map((threadId, index) => [threadId, index]));
  return [...items].sort((left, right) => {
    const leftRank = pinnedRank.get(left.threadId);
    const rightRank = pinnedRank.get(right.threadId);
    if (leftRank === undefined || rightRank === undefined) {
      return 0;
    }
    return leftRank - rightRank;
  });
}

function mergeThreadListItems(
  baseItems: ThreadListItem[],
  incomingItems: ThreadListItem[]
): ThreadListItem[] {
  const itemByThreadId = new Map<string, ThreadListItem>();
  for (const item of [...baseItems, ...incomingItems]) {
    const existing = itemByThreadId.get(item.threadId);
    if (!existing || parseIsoTimestamp(item.updatedAt) >= parseIsoTimestamp(existing.updatedAt)) {
      itemByThreadId.set(item.threadId, item);
    }
  }

  return [...itemByThreadId.values()]
    .sort((left, right) => parseIsoTimestamp(right.updatedAt) - parseIsoTimestamp(left.updatedAt))
    .slice(0, THREAD_LIST_LIMIT);
}

function formatFileChangePath(filePath: string, cwd: string | null | undefined): string {
  const normalizedPath = normalizeDisplayPath(filePath);
  const normalizedCwd = normalizeDisplayPath(normalizeCwd(cwd));
  const lowerPath = normalizedPath.toLowerCase();
  const lowerCwd = normalizedCwd.toLowerCase().replace(/\/$/, "");

  if (lowerCwd && lowerPath.startsWith(`${lowerCwd}/`)) {
    return normalizedPath.slice(lowerCwd.length + 1);
  }

  const sourceTreeMarkers = ["/gateway/", "/android/", "/mobile/", "/shared/", "/scripts/", "/docs/"];
  const sourceTreeIndex = sourceTreeMarkers
    .map((marker) => lowerPath.lastIndexOf(marker))
    .filter((index) => index >= 0)
    .sort((left, right) => left - right)[0];
  if (sourceTreeIndex !== undefined) {
    return normalizedPath.slice(sourceTreeIndex + 1);
  }

  return normalizedPath.replace(/^[A-Za-z]:\//, "");
}

function getFileChangeKindType(kind: unknown): string | null {
  if (typeof kind === "string") {
    return kind;
  }

  if (kind && typeof kind === "object" && "type" in kind) {
    const kindType = (kind as { type?: unknown }).type;
    return typeof kindType === "string" ? kindType : null;
  }

  return null;
}

function countRawLines(text: string): number {
  if (!text) {
    return 0;
  }

  const lines = text.replace(/\r\n/g, "\n").split("\n");
  return lines.at(-1) === "" ? lines.length - 1 : lines.length;
}

function countDiffStats(diff: string | null | undefined, kind: unknown): Pick<ThreadFileChangeItem, "added" | "removed"> {
  const rawDiff = diff ?? "";
  const lines = rawDiff.replace(/\r\n/g, "\n").split("\n");
  const hasUnifiedDiff = lines.some((line) => line.startsWith("@@") || line.startsWith("diff --git"));

  if (!hasUnifiedDiff) {
    const lineCount = countRawLines(rawDiff);
    const kindType = getFileChangeKindType(kind);
    if (kindType === "add") {
      return { added: lineCount, removed: 0 };
    }
    if (kindType === "delete") {
      return { added: 0, removed: lineCount };
    }
  }

  let added = 0;
  let removed = 0;
  for (const line of lines) {
    if (line.startsWith("+++") || line.startsWith("---")) {
      continue;
    }
    if (line.startsWith("+")) {
      added += 1;
    } else if (line.startsWith("-")) {
      removed += 1;
    }
  }

  return { added, removed };
}

function turnHasCompletedFileChanges(turn: CodexTurn | undefined): boolean {
  return turn?.items.some(
    (item) =>
      item.type === "fileChange" &&
      (item.status == null || item.status === "completed") &&
      item.changes.length > 0
  ) === true;
}

function latestTurnForFileChanges(thread: CodexThread): CodexTurn | undefined {
  const latestTurn = thread.turns.at(-1);
  if (turnHasCompletedFileChanges(latestTurn)) {
    return latestTurn;
  }

  if (latestTurn?.status !== "inProgress") {
    return undefined;
  }

  return [...thread.turns]
    .slice(0, -1)
    .reverse()
    .find((turn) => turn.status === "completed" && turnHasCompletedFileChanges(turn));
}

function extractThreadFileChanges(thread: CodexThread): ThreadFileChanges | undefined {
  const fileChangeTurn = latestTurnForFileChanges(thread);
  if (!fileChangeTurn) {
    return undefined;
  }

  const itemByPath = new Map<string, ThreadFileChangeItem>();
  for (const item of fileChangeTurn.items) {
    if (item.type !== "fileChange" || (item.status != null && item.status !== "completed")) {
      continue;
    }

    for (const change of item.changes) {
      if (!change.path) {
        continue;
      }

      const displayPath = formatFileChangePath(change.path, thread.cwd);
      const stats = countDiffStats(change.diff, change.kind);
      const existing = itemByPath.get(displayPath);
      itemByPath.set(displayPath, {
        path: displayPath,
        added: (existing?.added ?? 0) + stats.added,
        removed: (existing?.removed ?? 0) + stats.removed
      });
    }
  }

  const items = [...itemByPath.values()].filter((item) => item.added > 0 || item.removed > 0);
  if (items.length === 0) {
    return undefined;
  }

  const added = items.reduce((sum, item) => sum + item.added, 0);
  const removed = items.reduce((sum, item) => sum + item.removed, 0);
  return {
    summary: `${items.length} 个文件已更改 +${added} -${removed}`,
    changedFiles: items.length,
    added,
    removed,
    items
  };
}

function objectField(value: unknown, key: string): unknown {
  if (!value || typeof value !== "object") {
    return undefined;
  }

  return (value as Record<string, unknown>)[key];
}

function pickString(...values: unknown[]): string | null {
  for (const value of values) {
    if (typeof value === "string" && value.trim()) {
      return value.trim();
    }
  }

  return null;
}

function formatModelName(model: string): string {
  const normalized = model.trim();
  if (/^gpt-/i.test(normalized)) {
    return normalized
      .replace(/^gpt-/i, "GPT-")
      .replace(/-([a-z])/gi, (_match, letter: string) => ` ${letter.toUpperCase()}`);
  }

  return normalized;
}

function formatEffortLabel(effort: string | null): string | undefined {
  switch (effort?.toLowerCase()) {
    case "xhigh":
      return "超高";
    case "high":
      return "高";
    case "medium":
      return "中";
    case "low":
      return "低";
    default:
      return undefined;
  }
}

function formatPermissionLabel(payload: Record<string, unknown>): string {
  const permissionType = pickString(objectField(objectField(payload, "permission_profile"), "type"))?.toLowerCase();
  const sandboxType = pickString(objectField(objectField(payload, "sandbox_policy"), "type"))?.toLowerCase();
  const approvalPolicy = pickString(objectField(payload, "approval_policy"))?.toLowerCase();

  if (permissionType === "disabled" || sandboxType === "danger-full-access") {
    return "完全访问权限";
  }

  if (sandboxType === "read-only") {
    return "只读访问权限";
  }

  if (sandboxType === "workspace-write") {
    return approvalPolicy === "never" ? "工作区访问权限" : "工作区访问需确认";
  }

  if (approvalPolicy && approvalPolicy !== "never") {
    return "访问需确认";
  }

  return "权限未知";
}

function composerStateFromTurnContext(payload: unknown): ThreadComposerState | undefined {
  if (!payload || typeof payload !== "object") {
    return undefined;
  }

  const payloadObject = payload as Record<string, unknown>;
  const collaborationSettings = objectField(objectField(payloadObject, "collaboration_mode"), "settings");
  const model = pickString(
    objectField(payloadObject, "model"),
    objectField(collaborationSettings, "model")
  );
  const effort = pickString(
    objectField(payloadObject, "effort"),
    objectField(collaborationSettings, "reasoning_effort")
  );
  const effortLabel = formatEffortLabel(effort);
  const modelName = model ? formatModelName(model) : "模型未知";
  const modelLabel = effortLabel ? `${modelName} ${effortLabel}` : modelName;

  return {
    permissionLabel: formatPermissionLabel(payloadObject),
    modelLabel,
    ...(effortLabel ? { effortLabel } : {})
  };
}

function parseJsonObjectOrType(value: string | null | undefined): Record<string, unknown> | undefined {
  const raw = value?.trim();
  if (!raw) {
    return undefined;
  }

  try {
    const parsed = JSON.parse(raw) as unknown;
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      return parsed as Record<string, unknown>;
    }
    if (typeof parsed === "string" && parsed.trim()) {
      return { type: parsed.trim() };
    }
  } catch {
    return { type: raw };
  }

  return undefined;
}

function composerStateFromMetadata(metadata: ThreadMetadataRecord): ThreadComposerState | undefined {
  const sandboxPolicy = parseJsonObjectOrType(metadata.sandboxPolicy);
  const model = pickString(metadata.model);
  const effort = pickString(metadata.reasoningEffort);
  const approvalPolicy = pickString(metadata.approvalMode);

  if (!sandboxPolicy && !model && !effort && !approvalPolicy) {
    return undefined;
  }

  return composerStateFromTurnContext({
    ...(model ? { model } : {}),
    ...(effort ? { effort } : {}),
    ...(sandboxPolicy ? { sandbox_policy: sandboxPolicy } : {}),
    ...(approvalPolicy ? { approval_policy: approvalPolicy } : {})
  });
}

function normalizeRolloutTimestamp(value: unknown): string {
  if (typeof value === "string") {
    const milliseconds = Date.parse(value);
    if (!Number.isNaN(milliseconds)) {
      return new Date(milliseconds).toISOString();
    }
  }

  if (typeof value === "number" && Number.isFinite(value)) {
    return toIso(value);
  }

  return new Date().toISOString();
}

function rolloutMessageRole(value: unknown): ThreadMessage["role"] | null {
  return value === "user" || value === "assistant" || value === "system" ? value : null;
}

function rolloutContentText(content: unknown): string {
  if (!Array.isArray(content)) {
    return "";
  }

  return content
    .map((item) => {
      if (typeof item === "string") {
        return item.trim();
      }
      if (!item || typeof item !== "object") {
        return "";
      }

      const itemObject = item as Record<string, unknown>;
      const type = typeof itemObject.type === "string" ? itemObject.type : "";
      if (!["input_text", "output_text", "text"].includes(type)) {
        return "";
      }

      return typeof itemObject.text === "string" ? itemObject.text.trim() : "";
    })
    .filter(Boolean)
    .join("\n")
    .trim();
}

function userVisibleMessageText(text: string): string {
  const withoutImageTags = text
    .replace(/<image\b[^>]*>\s*<\/image>/gi, "")
    .replace(/&lt;image&gt;\s*&lt;\/image&gt;/gi, "")
    .trim();
  if (isInternalUserContextMessageText(withoutImageTags)) {
    return "";
  }

  const requestMarker = /(?:^|\r?\n)##\s*My request for Codex:\s*/i;
  const requestMatch = requestMarker.exec(withoutImageTags);
  if (requestMatch?.index !== undefined) {
    return withoutImageTags
      .slice(requestMatch.index + requestMatch[0].length)
      .trim();
  }

  if (/^#\s*Files mentioned by the user:/i.test(withoutImageTags)) {
    return "";
  }

  return withoutImageTags;
}

function assistantVisibleMessageText(text: string): string {
  return stripHeartbeatBlocks(text)
    .split(/\r?\n/)
    .filter((line) => !/^\s*::(?:git-[a-z-]+|archive)\{.*\}\s*$/i.test(line))
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();
}

function isInternalControlMessageText(text: string): boolean {
  const trimmed = text.trim();
  return (
    trimmed.startsWith("<turn_aborted>") ||
    trimmed.startsWith("<environment_context>") ||
    trimmed.startsWith("<heartbeat>") ||
    isInternalUserContextMessageText(trimmed)
  );
}

function isInternalUserContextMessageText(text: string): boolean {
  const trimmed = text.trim();
  return (
    /^#?\s*AGENTS\.md instructions for\b/i.test(trimmed) ||
    trimmed.startsWith("<heartbeat>")
  );
}

function stripHeartbeatBlocks(text: string): string {
  return text
    .replace(/(?:^|\r?\n)\s*<heartbeat\b[\s\S]*?<\/heartbeat>\s*/gi, "\n")
    .trim();
}

function parseRolloutResponseMessage(
  entry: Record<string, unknown>,
  threadId: string,
  lineIndex: number
): ThreadMessage | null {
  if (entry.type !== "response_item") {
    return null;
  }

  const payload = objectField(entry, "payload");
  if (!payload || typeof payload !== "object") {
    return null;
  }

  const payloadObject = payload as Record<string, unknown>;
  if (payloadObject.type !== "message") {
    return null;
  }

  const role = rolloutMessageRole(payloadObject.role);
  if (!role) {
    return null;
  }

  const rawText = rolloutContentText(payloadObject.content);
  const text = role === "user" ? userVisibleMessageText(rawText) : assistantVisibleMessageText(rawText);
  if (!text || isInternalControlMessageText(text)) {
    return null;
  }

  const timestamp = normalizeRolloutTimestamp(entry.timestamp);
  const messageId =
    pickString(payloadObject.id, entry.id) ?? `preview:${lineIndex}:${timestamp}`;
  return makeThreadTextMessage(threadId, role, text, timestamp, messageId);
}

function parseRolloutUserEventMessages(
  entry: Record<string, unknown>,
  threadId: string,
  lineIndex: number
): ThreadMessage[] {
  if (entry.type !== "event_msg") {
    return [];
  }

  const payload = objectField(entry, "payload");
  if (!payload || typeof payload !== "object") {
    return [];
  }

  const payloadObject = payload as Record<string, unknown>;
  if (payloadObject.type !== "user_message") {
    return [];
  }

  const rawText = pickString(payloadObject.message) ?? "";
  const text = userVisibleMessageText(rawText);
  const visibleText = text && !isInternalControlMessageText(text) ? text : undefined;
  const localImages = uniqueStrings([
    ...stringArrayField(payloadObject, "local_images"),
    ...stringArrayField(payloadObject, "localImages")
  ]);
  const timestamp = normalizeRolloutTimestamp(entry.timestamp);
  const baseMessageId =
    pickString(payloadObject.id, entry.id) ?? `event:${lineIndex}:${timestamp}`;
  const messages: ThreadMessage[] = [];

  localImages.forEach((localImage, index) => {
    const fileName = localImageFileName(localImage);
    messages.push(
      makeThreadImageMessage(
        threadId,
        "user",
        timestamp,
        `${baseMessageId}:image-${index + 1}`,
        {
          fileName,
          imageUrl: defaultUploadRelativeUrl(threadId, fileName)
        }
      )
    );
  });

  if (visibleText) {
    messages.push(
      makeThreadTextMessage(threadId, "user", visibleText, timestamp, `${baseMessageId}:text`)
    );
  }

  return messages;
}

function parseRolloutMessages(
  line: string,
  threadId: string,
  lineIndex: number
): ThreadMessage[] {
  try {
    const entry = JSON.parse(line) as Record<string, unknown>;
    const eventMessages = parseRolloutUserEventMessages(entry, threadId, lineIndex);
    if (eventMessages.length > 0) {
      return eventMessages;
    }

    const responseMessage = parseRolloutResponseMessage(entry, threadId, lineIndex);
    return responseMessage ? [responseMessage] : [];
  } catch {
    return [];
  }
}

function parseRolloutLocalImagePaths(line: string): string[] {
  try {
    const entry = JSON.parse(line) as Record<string, unknown>;
    if (entry.type !== "event_msg") {
      return [];
    }

    const payload = objectField(entry, "payload");
    if (!payload || typeof payload !== "object") {
      return [];
    }

    const payloadObject = payload as Record<string, unknown>;
    if (payloadObject.type !== "user_message") {
      return [];
    }

    return uniqueStrings([
      ...stringArrayField(payloadObject, "local_images"),
      ...stringArrayField(payloadObject, "localImages")
    ]);
  } catch {
    return [];
  }
}

function stringArrayField(value: Record<string, unknown>, key: string): string[] {
  const field = value[key];
  if (!Array.isArray(field)) {
    return [];
  }

  return field
    .filter((item): item is string => typeof item === "string" && item.trim().length > 0)
    .map((item) => item.trim());
}

function uniqueStrings(values: string[]): string[] {
  const seen = new Set<string>();
  return values.filter((value) => {
    const key = value.toLowerCase();
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

function dedupeRolloutMessages(messages: ThreadMessage[]): ThreadMessage[] {
  const userEventTextMessages = messages.filter((message) =>
    message.messageId.startsWith("event:") &&
    message.role === "user" &&
    message.kind === "text"
  );

  return messages.filter((message) => {
    if (message.messageId.startsWith("event:")) {
      return true;
    }
    if (message.role !== "user" || message.kind !== "text") {
      return true;
    }

    return !userEventTextMessages.some((eventMessage) =>
      sameMessagePayload(message, eventMessage) &&
      Math.abs(parseIsoTimestamp(message.timestamp) - parseIsoTimestamp(eventMessage.timestamp)) <=
        OPTIMISTIC_DEDUPE_WINDOW_MS
    );
  });
}

async function readRolloutTail(
  rolloutPath: string,
  byteLimit = ROLLOUT_PREVIEW_TAIL_BYTES
): Promise<RolloutTailRead> {
  let handle: Awaited<ReturnType<typeof fs.open>> | null = null;
  try {
    handle = await fs.open(rolloutPath, "r");
    const stat = await handle.stat();
    if (!stat.isFile() || stat.size <= 0) {
      return { lines: [], truncated: false };
    }

    const boundedByteLimit = Math.max(1, Math.floor(byteLimit));
    const readLength = Math.min(stat.size, boundedByteLimit);
    const start = Math.max(0, stat.size - readLength);
    const buffer = Buffer.alloc(readLength);
    const result = await handle.read(buffer, 0, readLength, start);
    const content = buffer.subarray(0, result.bytesRead).toString("utf8");
    const lines = content.split(/\r?\n/);
    if (start > 0) {
      lines.shift();
    }
    return {
      lines,
      truncated: start > 0
    };
  } catch {
    return { lines: [], truncated: false };
  } finally {
    await handle?.close();
  }
}

type RolloutEventsRead = {
  events: ThreadEvent[];
  truncated: boolean;
};

async function readRolloutEventsFromTail(
  rolloutPath: string,
  threadId: string,
  byteLimit: number
): Promise<RolloutEventsRead> {
  const tail = await readRolloutTail(rolloutPath, byteLimit);
  return {
    events: parseRolloutEventsFromLines(tail.lines, threadId),
    truncated: tail.truncated
  };
}

async function readRolloutStatusEvents(
  rolloutPath: string,
  threadId: string
): Promise<ThreadEvent[]> {
  let byteLimit = ROLLOUT_PREVIEW_TAIL_BYTES;

  while (true) {
    const read = await readRolloutEventsFromTail(rolloutPath, threadId, byteLimit);
    if (
      !shouldDeepenRolloutRunningStartScan(read.events, read.truncated) ||
      byteLimit >= ROLLOUT_STATUS_MAX_SCAN_BYTES
    ) {
      return read.events;
    }
    byteLimit = Math.min(byteLimit * 2, ROLLOUT_STATUS_MAX_SCAN_BYTES);
  }
}

function shouldDeepenRolloutRunningStartScan(events: ThreadEvent[], truncated: boolean): boolean {
  if (!truncated) {
    return false;
  }
  if (events.length === 0) {
    return true;
  }

  const hasRunningSignal = events.some((event) => event.status === "running" || event.kind === "turn_started");
  const hasRealTurnStart = events.some((event) => event.kind === "turn_started" && event.text === "开始处理新的输入");
  return hasRunningSignal && !hasRealTurnStart;
}

async function readRolloutObservationSignals(
  rolloutPath: string,
  offset: number | undefined
): Promise<RolloutObservationRead | null> {
  let handle: Awaited<ReturnType<typeof fs.open>> | null = null;
  try {
    handle = await fs.open(rolloutPath, "r");
    const stat = await handle.stat();
    if (!stat.isFile()) {
      return null;
    }

    const hasValidOffset =
      typeof offset === "number" && Number.isFinite(offset) && offset >= 0 && offset <= stat.size;
    const start = hasValidOffset ? offset : Math.max(0, stat.size - ROLLOUT_PREVIEW_TAIL_BYTES);
    const readLength = stat.size - start;
    if (readLength <= 0) {
      return {
        signals: [],
        messages: [],
        nextOffset: stat.size
      };
    }

    const buffer = Buffer.alloc(readLength);
    const result = await handle.read(buffer, 0, readLength, start);
    const content = buffer.subarray(0, result.bytesRead).toString("utf8");
    const lines = content.split(/\r?\n/);
    if (!hasValidOffset && start > 0) {
      lines.shift();
    }

    const signals: RolloutObservationSignal[] = [];
    const messages: ThreadMessage[] = [];
    for (const [index, line] of lines.entries()) {
      const trimmed = line.trim();
      if (!trimmed) {
        continue;
      }

      const signal = parseRolloutObservationSignal(trimmed);
      if (signal) {
        signals.push(signal);
      }
      messages.push(...parseRolloutMessages(trimmed, "thread", index));
    }

    return {
      signals,
      messages: dedupeRolloutMessages(messages),
      nextOffset: stat.size
    };
  } catch {
    return null;
  } finally {
    await handle?.close();
  }
}

function parseRolloutObservationSignal(line: string): RolloutObservationSignal | null {
  if (!line) {
    return null;
  }

  try {
    const entry = JSON.parse(line) as Record<string, unknown>;
    const payload = objectField(entry, "payload");
    if (!payload || typeof payload !== "object") {
      return null;
    }

    const payloadObject = payload as Record<string, unknown>;
    const entryType = pickString(entry.type);
    const payloadType = pickString(payloadObject.type);
    const turnId = pickString(payloadObject.turn_id, entry.turn_id) ?? undefined;
    const timestamp = normalizeRolloutTimestamp(entry.timestamp);

    if (entryType === "event_msg" && payloadType === "task_started" && turnId) {
      return {
        status: "running",
        kind: "turn_started",
        text: "开始处理新的输入",
        timestamp,
        sourceId: turnId,
        turnId
      };
    }

    if (entryType === "turn_context" && turnId) {
      return {
        status: "running",
        kind: "turn_started",
        text: statusText("running"),
        timestamp,
        sourceId: turnId,
        turnId
      };
    }

    if (entryType === "event_msg" && payloadType === "task_complete" && turnId) {
      if (hasMissingFinalAssistantMessage(payloadObject)) {
        return {
          status: "error",
          kind: "error",
          text: MISSING_FINAL_ASSISTANT_MESSAGE,
          timestamp,
          sourceId: turnId,
          turnId
        };
      }
      return {
        status: "completed",
        kind: "turn_completed",
        text: "本轮已完成",
        timestamp,
        sourceId: turnId,
        turnId
      };
    }

    if (entryType === "event_msg" && payloadType === "error") {
      return {
        status: "error",
        kind: "error",
        text: pickString(payloadObject.message) ?? "线程执行失败",
        timestamp,
        sourceId: turnId ?? "status",
        turnId
      };
    }

    if (
      entryType === "response_item" &&
      payloadType === "function_call" &&
      pickString(payloadObject.name) === "request_user_input"
    ) {
      return {
        status: "waiting_input",
        kind: "status_changed",
        text: statusText("waiting_input"),
        timestamp,
        sourceId: turnId ?? "request_user_input",
        turnId
      };
    }

    return null;
  } catch {
    return null;
  }
}

function hasMissingFinalAssistantMessage(payload: Record<string, unknown>): boolean {
  return Object.prototype.hasOwnProperty.call(payload, "last_agent_message") &&
    payload.last_agent_message == null;
}

async function readPreviewMessagesFromRollout(
  rolloutPath: string | null | undefined,
  threadId: string
): Promise<ThreadMessage[]> {
  return (await readRecentMessagesFromRollout(rolloutPath, threadId, MAX_MESSAGES)).messages;
}

async function readRecentMessagesFromRollout(
  rolloutPath: string | null | undefined,
  threadId: string,
  limit: number
): Promise<RolloutRecentMessagesRead> {
  if (!rolloutPath) {
    return { messages: [], hasEarlier: false };
  }

  const tail = await readRolloutTail(rolloutPath);
  const messages: ThreadMessage[] = [];
  for (const [index, line] of tail.lines.entries()) {
    const trimmed = line.trim();
    if (!trimmed) {
      continue;
    }

    messages.push(...parseRolloutMessages(trimmed, threadId, index));
  }

  const dedupedMessages = dedupeRolloutMessages(messages);
  const boundedLimit = Math.max(1, Math.min(Math.floor(limit), MAX_MESSAGE_PAGE_LIMIT));
  const pageMessages = dedupedMessages.slice(-boundedLimit);
  return {
    messages: pageMessages,
    hasEarlier: tail.truncated || dedupedMessages.length > pageMessages.length
  };
}

async function readPreviewEventsFromRollout(
  rolloutPath: string | null | undefined,
  threadId: string
): Promise<ThreadEvent[]> {
  if (!rolloutPath) {
    return [];
  }

  return readRolloutStatusEvents(rolloutPath, threadId);
}

function parseRolloutEventsFromLines(lines: string[], threadId: string): ThreadEvent[] {
  const events: ThreadEvent[] = [];
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) {
      continue;
    }

    const signal = parseRolloutObservationSignal(trimmed);
    if (!signal) {
      continue;
    }
    events.push(
      makeThreadEvent(
        threadId,
        signal.kind,
        signal.text,
        signal.timestamp,
        `${signal.sourceId}:${signal.status}`,
        signal.status
      )
    );
  }

  return events.slice(-MAX_EVENTS);
}

async function readMessagesFromRollout(
  rolloutPath: string | null | undefined,
  threadId: string
): Promise<ThreadMessage[]> {
  if (!rolloutPath) {
    return [];
  }

  let content: string;
  try {
    content = await fs.readFile(rolloutPath, "utf8");
  } catch {
    return [];
  }

  const messages: ThreadMessage[] = [];
  const lines = content.split(/\r?\n/);
  for (const [index, line] of lines.entries()) {
    const trimmed = line.trim();
    if (!trimmed) {
      continue;
    }

    messages.push(...parseRolloutMessages(trimmed, threadId, index));
  }

  return dedupeRolloutMessages(messages);
}

async function readLocalImagePathsFromRollout(
  rolloutPath: string | null | undefined
): Promise<string[]> {
  if (!rolloutPath) {
    return [];
  }

  let content: string;
  try {
    content = await fs.readFile(rolloutPath, "utf8");
  } catch {
    return [];
  }

  const localImages: string[] = [];
  for (const line of content.split(/\r?\n/)) {
    const trimmed = line.trim();
    if (!trimmed) {
      continue;
    }

    localImages.push(...parseRolloutLocalImagePaths(trimmed));
  }

  return uniqueStrings(localImages);
}

async function findRolloutLocalImageFile(
  rolloutPath: string | null | undefined,
  fileName: string
): Promise<{ absolutePath: string; mimeType: string } | null> {
  const normalizedFileName = path.basename(fileName.replace(/\\/g, "/"));
  if (!normalizedFileName || normalizedFileName !== fileName) {
    return null;
  }

  const localImagePaths = await readLocalImagePathsFromRollout(rolloutPath);
  for (const localImagePath of localImagePaths.reverse()) {
    if (localImageFileName(localImagePath) !== normalizedFileName) {
      continue;
    }

    const fileStat = await fs.stat(localImagePath).catch(() => null);
    if (!fileStat?.isFile()) {
      continue;
    }

    return {
      absolutePath: localImagePath,
      mimeType: mimeTypeFromImageFileName(localImagePath)
    };
  }

  return null;
}

function mimeTypeFromImageFileName(fileName: string): string {
  const extension = path.extname(fileName).toLowerCase();
  switch (extension) {
    case ".png":
      return "image/png";
    case ".jpg":
    case ".jpeg":
      return "image/jpeg";
    case ".webp":
      return "image/webp";
    case ".gif":
      return "image/gif";
    default:
      return "application/octet-stream";
  }
}

function isUploadFileNotFoundError(error: unknown): boolean {
  return error instanceof Error && error.message === "upload_file_not_found";
}

async function findThreadSessionLogPath(codexHome: string, threadId: string): Promise<string | null> {
  const suffix = `${threadId}.jsonl`;
  const roots = [
    path.join(codexHome, "sessions"),
    path.join(codexHome, "archived_sessions")
  ];

  for (const root of roots) {
    const found = await findFileBySuffix(root, suffix);
    if (found) {
      return found;
    }
  }

  return null;
}

async function findFileBySuffix(directory: string, suffix: string): Promise<string | null> {
  let entries: Array<import("node:fs").Dirent>;
  try {
    entries = await fs.readdir(directory, { withFileTypes: true });
  } catch {
    return null;
  }

  for (const entry of entries) {
    if (entry.isFile() && entry.name.endsWith(suffix)) {
      return path.join(directory, entry.name);
    }
  }

  for (const entry of entries) {
    if (!entry.isDirectory()) {
      continue;
    }
    const found = await findFileBySuffix(path.join(directory, entry.name), suffix);
    if (found) {
      return found;
    }
  }

  return null;
}

async function resolveThreadComposerState(
  codexHome: string,
  threadId: string
): Promise<ThreadComposerState | undefined> {
  const sessionLogPath = await findThreadSessionLogPath(codexHome, threadId);
  if (!sessionLogPath) {
    return undefined;
  }

  let latestTurnContext: unknown;
  try {
    const content = await fs.readFile(sessionLogPath, "utf8");
    for (const line of content.split(/\r?\n/)) {
      if (!line.trim()) {
        continue;
      }
      try {
        const entry = JSON.parse(line) as { type?: unknown; payload?: unknown };
        if (entry.type === "turn_context") {
          latestTurnContext = entry.payload;
        }
      } catch {
        continue;
      }
    }
  } catch {
    return undefined;
  }

  return composerStateFromTurnContext(latestTurnContext);
}

function createListItem(
  thread: CodexThread,
  metadata: ThreadMetadataRecord | undefined,
  hint: RuntimeThreadHint | undefined,
  sessionThreadName?: string
): ThreadListItem {
  const effectiveHint = shouldUseRuntimeHintForThread(thread.status, hint) ? hint : undefined;
  const status = mapStatusFromCodex(thread.status, effectiveHint?.status);
  const runningStartedAt =
    status === "running"
      ? effectiveHint?.runningStartedAt ?? runningStartedAtFromThread(thread)
      : undefined;
  return {
    threadId: thread.id,
    title: createMobileThreadTitle(
      [sessionThreadName, thread.name, metadata?.title, metadata?.firstUserMessage, thread.preview],
      thread.id,
      thread.cwd || metadata?.cwd
    ),
    cwd: normalizeCwd(thread.cwd || metadata?.cwd),
    status,
    updatedAt: effectiveHint?.updatedAt ?? toIso(thread.updatedAt ?? metadata?.updatedAt),
    progressSummary:
      effectiveHint?.progressSummary ??
      (status === "running" ? statusText(status) : undefined) ??
      metadata?.firstUserMessage ??
      thread.preview ??
      statusText(status),
    needsAttention: status === "waiting_input" || status === "error",
    ...(runningStartedAt ? { runningStartedAt } : {})
  };
}

function createListItemFromMetadata(
  metadata: ThreadMetadataRecord,
  hint: RuntimeThreadHint | undefined,
  sessionThreadName?: string
): ThreadListItem {
  const status = hint?.status ?? "idle";
  const runningStartedAt = status === "running" ? hint?.runningStartedAt : undefined;
  return {
    threadId: metadata.threadId,
    title: createMobileThreadTitle(
      [sessionThreadName, metadata.title, metadata.firstUserMessage],
      metadata.threadId,
      metadata.cwd
    ),
    cwd: normalizeCwd(metadata.cwd),
    status,
    updatedAt: hint?.updatedAt ?? toIso(metadata.updatedAt),
    progressSummary: hint?.progressSummary ?? metadata.firstUserMessage ?? statusText(status),
    needsAttention: status === "waiting_input" || status === "error",
    ...(runningStartedAt ? { runningStartedAt } : {})
  };
}

async function resolveListItemWithRolloutStatus(
  item: ThreadListItem,
  rolloutPath: string | null | undefined
): Promise<{ thread: ThreadListItem; statusDecision: ThreadStatusDecision }> {
  if (!rolloutPath) {
    return resolveThreadFromStatusCandidates(item, []);
  }

  const rolloutEvents = await readPreviewEventsFromRollout(rolloutPath, item.threadId);
  const candidate = latestEventStatusCandidate(rolloutEvents);
  return resolveThreadFromStatusCandidates(item, candidate ? [candidate] : []);
}

function createMobileThreadTitle(
  candidates: Array<string | null | undefined>,
  fallback: string,
  cwd: string | null | undefined
): string {
  for (const candidate of candidates) {
    const normalized = normalizeMobileThreadTitleCandidate(candidate, cwd);
    if (normalized) {
      return normalized;
    }
  }
  return fallback;
}

function normalizeMobileThreadTitleCandidate(
  value: string | null | undefined,
  cwd: string | null | undefined
): string | null {
  const trimmed = value?.trim();
  if (!trimmed) {
    return null;
  }

  const postFenceText = textAfterLastMarkdownFence(trimmed);
  const postFenceTitle = firstNonEmptyLine(postFenceText);
  if (postFenceTitle) {
    return postFenceTitle;
  }

  const firstLine = firstNonEmptyLine(trimmed);
  if (!firstLine) {
    return null;
  }
  if (isHandoffBoilerplateTitle(firstLine)) {
    return folderTitleFromCwd(cwd);
  }
  return firstLine;
}

function textAfterLastMarkdownFence(value: string): string | null {
  const fenceIndex = value.lastIndexOf("```");
  if (fenceIndex < 0) {
    return null;
  }
  const afterFence = value.slice(fenceIndex + 3).trim();
  return afterFence || null;
}

function firstNonEmptyLine(value: string | null | undefined): string | null {
  return (
    value
      ?.replace(/\r\n/g, "\n")
      .split("\n")
      .map((line) => line.trim())
      .find((line) => line.length > 0) ?? null
  );
}

function isHandoffBoilerplateTitle(value: string): boolean {
  return /^下面这段.*复制到.*窗口/.test(value) ||
    /^请先阅读并以这个目录作为唯一主工作区/.test(value);
}

function folderTitleFromCwd(cwd: string | null | undefined): string | null {
  const normalized = normalizeCwd(cwd).replace(/\/$/, "");
  return normalized.split("/").at(-1)?.trim() || null;
}

function isDesktopVisibleMetadata(metadata: ThreadMetadataRecord | undefined): boolean {
  if (!metadata) {
    return true;
  }

  if (metadata.archived) {
    return false;
  }

  if (metadata.agentRole || metadata.agentNickname || metadata.agentPath) {
    return false;
  }

  const source = metadata.source?.trim();
  if (source?.includes("\"subagent\"")) {
    return false;
  }

  return !source || source === "vscode";
}

function appendCapped<T>(items: T[], value: T, maxSize: number): T[] {
  const next = [...items, value];
  return next.slice(Math.max(0, next.length - maxSize));
}

async function giveImmediateRefreshAChance(promise: Promise<unknown>, turns = 8): Promise<void> {
  let settled = false;
  void promise.then(() => {
    settled = true;
  });

  for (let index = 0; index < turns && !settled; index += 1) {
    await Promise.resolve();
  }
}

function parseIsoTimestamp(value: string | null | undefined): number {
  if (!value) {
    return Number.NEGATIVE_INFINITY;
  }

  const timestamp = Date.parse(value);
  return Number.isNaN(timestamp) ? Number.NEGATIVE_INFINITY : timestamp;
}

function parseMessageOrdinal(messageId: string): number | null {
  const itemMatch = messageId.match(/(?:^|:)item-(\d+)(?::|$)/);
  if (itemMatch) {
    return Number(itemMatch[1]);
  }

  const previewMatch = messageId.match(/^preview:(\d+):/);
  return previewMatch ? Number(previewMatch[1]) : null;
}

function compareThreadMessages(left: ThreadMessage, right: ThreadMessage): number {
  const timestampDifference = parseIsoTimestamp(left.timestamp) - parseIsoTimestamp(right.timestamp);
  if (timestampDifference !== 0) {
    return timestampDifference;
  }

  const leftOrdinal = parseMessageOrdinal(left.messageId);
  const rightOrdinal = parseMessageOrdinal(right.messageId);
  if (leftOrdinal != null && rightOrdinal != null && leftOrdinal !== rightOrdinal) {
    return leftOrdinal - rightOrdinal;
  }

  return 0;
}

function formatProcessingDuration(durationMs: number): string {
  const totalSeconds = Math.max(0, Math.floor(durationMs / 1000));
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;

  if (hours > 0) {
    return `${hours}h ${minutes}m`;
  }

  if (minutes > 0) {
    return `${minutes}m ${seconds}s`;
  }

  return `${seconds}s`;
}

function completionAlertBody(processingDuration: string | null): string {
  return processingDuration ? `已处理 ${processingDuration}` : "本轮已完成";
}

function turnProcessingDurationLabel(turn: CodexTurn): string | null {
  if (typeof turn.durationMs === "number" && Number.isFinite(turn.durationMs) && turn.durationMs >= 0) {
    return formatProcessingDuration(turn.durationMs);
  }

  const startedAt = toMillis(turn.startedAt);
  const completedAt = toMillis(turn.completedAt);
  if (startedAt == null || completedAt == null || completedAt < startedAt) {
    return null;
  }

  return formatProcessingDuration(completedAt - startedAt);
}

function runningStartedAtFromTurn(turn: CodexTurn | undefined): string | undefined {
  if (!turn || turn.status !== "inProgress") {
    return undefined;
  }

  return toIso(turn.startedAt ?? undefined);
}

function runningStartedAtFromThread(thread: CodexThread): string | undefined {
  return runningStartedAtFromTurn(thread.turns.at(-1));
}

function processingDurationBetweenLabels(startTimestamp: string | null | undefined, endTimestamp: string): string | null {
  const startTime = parseIsoTimestamp(startTimestamp);
  const endTime = parseIsoTimestamp(endTimestamp);
  if (!Number.isFinite(startTime) || !Number.isFinite(endTime) || endTime < startTime) {
    return null;
  }

  return formatProcessingDuration(endTime - startTime);
}

function messageAlertBody(message: ThreadMessage, processingDuration: string | null = null): string {
  const text = message.text?.trim() || message.fileName?.trim() || "";
  const body = text.slice(0, 160) || "当前线程有新消息";
  return processingDuration ? `已处理 ${processingDuration} · ${body}` : body;
}

function latestTurnStatusCandidate(thread: CodexThread): DetailStatusCandidate | null {
  const latestTurn = thread.turns.at(-1);
  if (!latestTurn) {
    return null;
  }

  const timestamp = toIso(latestTurn.completedAt ?? latestTurn.startedAt ?? thread.updatedAt);
  switch (latestTurn.status) {
    case "completed":
      return {
        status: "completed",
        source: "turn",
        sourceId: latestTurn.id,
        text: "本轮已完成",
        timestamp
      };
    case "failed":
      return {
        status: "error",
        source: "turn",
        sourceId: latestTurn.id,
        text: latestTurn.error?.message ?? "线程执行失败",
        timestamp
      };
    case "inProgress":
      const runningStartedAt = runningStartedAtFromTurn(latestTurn);
      const runningTimestamp = runningStartedAt ?? timestamp;
      return {
        status: "running",
        source: "turn",
        sourceId: latestTurn.id,
        text: "开始处理新的输入",
        timestamp,
        runningStartedAt: runningTimestamp
      };
    default:
      return null;
  }
}

function threadStatusCandidate(
  thread: CodexThread,
  hintedStatus?: MobileThreadStatus
): DetailStatusCandidate | null {
  const status = mapStatusFromCodex(thread.status, hintedStatus);
  if (status === hintedStatus && thread.status.type !== "active" && thread.status.type !== "systemError") {
    return null;
  }

  return {
    status,
    source: "thread_status",
    text: statusText(status),
    timestamp: toIso(thread.updatedAt)
  };
}

function isStaleOptimisticRunningEvent(event: ThreadEvent, now = Date.now()): boolean {
  if (event.status !== "running" || event.kind !== "turn_started") {
    return false;
  }

  const isMobileOptimisticEvent =
    event.eventId.endsWith(":start") || event.eventId.endsWith(":image-start");
  if (!isMobileOptimisticEvent) {
    return false;
  }

  return now - parseIsoTimestamp(event.timestamp) > OPTIMISTIC_RUNNING_HINT_TTL_MS;
}

function latestEventStatusCandidate(
  events: ThreadEvent[],
  options: { ignoreWaitingAndCompleted?: boolean } = {}
): DetailStatusCandidate | null {
  const runningStartedAt = runningStartedAtFromEvents(events);
  const latestStatusEvent = events
    .filter((event): event is ThreadEvent & { status: MobileThreadStatus } => {
      if (!event.status || isStaleOptimisticRunningEvent(event)) {
        return false;
      }
      if (
        options.ignoreWaitingAndCompleted &&
        (event.status === "waiting_input" || event.status === "completed")
      ) {
        return false;
      }
      return true;
    })
    .map((event) => ({
      status: event.status,
      source: "event" as const,
      sourceId: event.eventId,
      text: event.text,
      timestamp: event.timestamp,
      ...(event.status === "running" && runningStartedAt ? { runningStartedAt } : {})
    }))
    .sort(compareDetailStatusCandidates)[0];

  return latestStatusEvent ?? null;
}

function runningStartedAtFromEvents(events: ThreadEvent[]): string | undefined {
  const indexedEvents = events
    .map((event, index) => ({
      event,
      index,
      timestampMillis: parseIsoTimestamp(event.timestamp)
    }))
    .sort((left, right) => {
      const timestampDifference = left.timestampMillis - right.timestampMillis;
      return timestampDifference !== 0 ? timestampDifference : left.index - right.index;
    });
  const latestCompletion = indexedEvents
    .filter(({ event }) => event.status === "completed" || event.kind === "turn_completed")
    .at(-1);
  const activeRunningEvents = indexedEvents.filter(({ event, timestampMillis, index }) => {
    if (event.status !== "running" && event.kind !== "turn_started") {
      return false;
    }
    if (!latestCompletion) {
      return true;
    }
    if (timestampMillis !== latestCompletion.timestampMillis) {
      return timestampMillis > latestCompletion.timestampMillis;
    }
    return index > latestCompletion.index;
  });

  return (
    activeRunningEvents.find(({ event }) => event.kind === "turn_started" && event.text === "开始处理新的输入")
      ?.event.timestamp ??
    activeRunningEvents.find(({ event }) => event.text !== "线程正在运行" && !event.eventId.startsWith("client-"))
      ?.event.timestamp ??
    activeRunningEvents[0]?.event.timestamp
  );
}

function resolveDetailThread(
  listItem: ThreadListItem,
  thread: CodexThread,
  recentEvents: ThreadEvent[]
): { thread: ThreadListItem; statusDecision: ThreadStatusDecision } {
  const turnCandidate = latestTurnStatusCandidate(thread);
  const shouldIgnoreSettledLogHints =
    thread.status.type === "active" &&
    !isActiveWaitingForInput(thread.status) &&
    (!turnCandidate || turnCandidate.status === "running");
  const eventCandidate = latestEventStatusCandidate(recentEvents, {
    ignoreWaitingAndCompleted: shouldIgnoreSettledLogHints
  });
  const candidates = [eventCandidate, turnCandidate, threadStatusCandidate(thread, listItem.status)]
    .filter((candidate): candidate is DetailStatusCandidate => candidate !== null);

  return resolveThreadFromStatusCandidates(listItem, candidates);
}

function resolveThreadItemFromStatusCandidate(
  listItem: ThreadListItem,
  newestCandidate: DetailStatusCandidate | null
): ThreadListItem {
  return resolveThreadFromStatusCandidates(
    listItem,
    newestCandidate ? [newestCandidate] : []
  ).thread;
}

function resolveThreadFromStatusCandidates(
  listItem: ThreadListItem,
  candidates: DetailStatusCandidate[]
): { thread: ThreadListItem; statusDecision: ThreadStatusDecision } {
  const baseCandidate = listStatusCandidate(listItem);
  const newestCandidate = [...candidates].sort(compareDetailStatusCandidates)[0] ?? null;
  let selectedCandidate = baseCandidate;

  if (newestCandidate && shouldApplyStatusCandidate(listItem, newestCandidate)) {
    selectedCandidate = newestCandidate;
  }

  const runningStartedAt = runningStartedAtForSelectedCandidate(
    selectedCandidate,
    candidates,
    listItem
  );
  const detailThread =
    selectedCandidate.source === "list"
      ? {
          ...listItem,
          runningStartedAt
        }
      : {
          ...listItem,
          status: selectedCandidate.status,
          updatedAt: selectedCandidate.timestamp,
          progressSummary: selectedCandidate.text,
          needsAttention:
            selectedCandidate.status === "waiting_input" ||
            selectedCandidate.status === "error",
          runningStartedAt
        };

  return {
    thread: detailThread,
    statusDecision: makeStatusDecision(baseCandidate, candidates, selectedCandidate)
  };
}

function runningStartedAtForSelectedCandidate(
  selectedCandidate: DetailStatusCandidate,
  candidates: DetailStatusCandidate[],
  listItem: ThreadListItem
): string | undefined {
  if (selectedCandidate.status !== "running") {
    return undefined;
  }

  return (
    selectedCandidate.runningStartedAt ??
    candidates.find((candidate) => candidate.status === "running" && candidate.runningStartedAt)
      ?.runningStartedAt ??
    (listItem.status === "running" ? listItem.runningStartedAt : undefined) ??
    selectedCandidate.timestamp
  );
}

function shouldApplyStatusCandidate(
  listItem: ThreadListItem,
  newestCandidate: DetailStatusCandidate
): boolean {
  if (listItem.status === "waiting_input" && newestCandidate.status === "completed") {
    return false;
  }

  const listTimestamp = parseIsoTimestamp(listItem.updatedAt);
  const candidateTimestamp = parseIsoTimestamp(newestCandidate.timestamp);
  if (candidateTimestamp >= listTimestamp) {
    return true;
  }

  return (
    newestCandidate.status === "running" &&
    (listItem.status === "idle" || listItem.status === "completed")
  );
}

function listStatusCandidate(listItem: ThreadListItem): DetailStatusCandidate {
  return {
    status: listItem.status,
    source: "list",
    text: listItem.progressSummary || statusText(listItem.status),
    timestamp: listItem.updatedAt,
    sourceId: listItem.threadId,
    ...(listItem.runningStartedAt ? { runningStartedAt: listItem.runningStartedAt } : {})
  };
}

function makeStatusDecision(
  baseCandidate: DetailStatusCandidate,
  candidates: DetailStatusCandidate[],
  selectedCandidate: DetailStatusCandidate
): ThreadStatusDecision {
  const allCandidates = [baseCandidate, ...candidates]
    .map((candidate): ThreadStatusDecisionCandidate => ({
      status: candidate.status,
      source: candidate.source,
      text: candidate.text,
      timestamp: candidate.timestamp,
      selected: sameStatusCandidate(candidate, selectedCandidate),
      ...(candidate.sourceId ? { sourceId: candidate.sourceId } : {})
    }));

  return {
    status: selectedCandidate.status,
    source: selectedCandidate.source,
    text: selectedCandidate.text,
    timestamp: selectedCandidate.timestamp,
    reason:
      selectedCandidate.source === "list"
        ? "list status kept over detail candidates"
        : `${selectedCandidate.source} status selected from detail candidates`,
    candidates: allCandidates,
    ...(selectedCandidate.sourceId ? { sourceId: selectedCandidate.sourceId } : {})
  };
}

function sameStatusCandidate(
  left: DetailStatusCandidate,
  right: DetailStatusCandidate
): boolean {
  return (
    left.status === right.status &&
    left.source === right.source &&
    left.timestamp === right.timestamp &&
    left.text === right.text &&
    left.sourceId === right.sourceId
  );
}

function makeSendDecision(statusDecision: ThreadStatusDecision): ThreadSendDecision {
  switch (statusDecision.status) {
    case "running":
      return {
        available: false,
        reason: "thread_running",
        source: "statusDecision",
        message: "线程仍在运行，暂不支持并发发送",
        recommendedAction: "queue"
      };
    case "offline":
      return {
        available: false,
        reason: "gateway_offline",
        source: "statusDecision",
        message: "当前无法连接到 Codex sidecar",
        recommendedAction: "reconnect"
      };
    default:
      return {
        available: true,
        reason: "ready",
        source: "statusDecision",
        message: "可以继续发送下一条消息",
        recommendedAction: "send"
      };
  }
}

function makeThreadTextMessage(
  threadId: string,
  role: ThreadMessage["role"],
  text: string,
  timestamp: string,
  messageId: string
): ThreadMessage {
  return {
    messageId,
    threadId,
    role,
    kind: "text",
    text,
    timestamp
  };
}

function makeThreadImageMessage(
  threadId: string,
  role: ThreadMessage["role"],
  timestamp: string,
  messageId: string,
  options: { text?: string; fileName: string; imageUrl: string }
): ThreadMessage {
  return {
    messageId,
    threadId,
    role,
    kind: "image",
    text: options.text,
    imageUrl: options.imageUrl,
    thumbnailUrl: options.imageUrl,
    fileName: options.fileName,
    timestamp
  };
}

function makeThreadFileMessage(
  threadId: string,
  role: ThreadMessage["role"],
  timestamp: string,
  messageId: string,
  options: { text?: string; fileName: string; fileUrl: string; mimeType: string }
): ThreadMessage {
  return {
    messageId,
    threadId,
    role,
    kind: "file",
    text: options.text,
    fileUrl: options.fileUrl,
    fileName: options.fileName,
    mimeType: options.mimeType,
    timestamp
  };
}

function buildOfficialPersistenceVisibleText(
  text: string | undefined,
  files: Array<Pick<StoredUploadFile, "absolutePath" | "fileName">>
): string | undefined {
  const trimmedText = text?.trim();
  const fileLines = files.map((file) => `- ${file.fileName}\n  ${file.absolutePath}`);
  const fileSummary = fileLines.length > 0 ? `手机端上传文件：\n${fileLines.join("\n")}` : "";
  return [trimmedText, fileSummary].filter(Boolean).join("\n\n") || undefined;
}

function localImageFileName(filePath: string): string {
  return path.basename(filePath.replace(/\\/g, "/"));
}

function defaultUploadRelativeUrl(threadId: string, fileName: string): string {
  return `/api/uploads/${encodeURIComponent(threadId)}/${encodeURIComponent(fileName)}`;
}

function makeThreadEvent(
  threadId: string,
  kind: ThreadEvent["kind"],
  text: string,
  timestamp: string,
  eventId: string,
  status?: MobileThreadStatus
): ThreadEvent {
  return {
    eventId,
    threadId,
    kind,
    text,
    timestamp,
    status
  };
}

function makeSendUnavailableError(detail: ThreadDetail): GatewayHttpError {
  return new GatewayHttpError(409, {
    error: "send_unavailable",
    reason: detail.thread.status,
    message: detail.sendDisabledReason ?? "send_unavailable"
  });
}

function makeSendUnavailableErrorFromStatus(status: MobileThreadStatus): GatewayHttpError {
  return new GatewayHttpError(409, {
    error: "send_unavailable",
    reason: status,
    message:
      status === "running"
        ? "线程仍在运行，暂不支持并发发送"
        : status === "offline"
          ? "当前无法连接到 Codex sidecar"
          : "send_unavailable"
  });
}

function isAppServerThreadReadTimeout(error: unknown): boolean {
  return errorMessage(error).startsWith("app_server_request_timeout:thread/read");
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

function copyDesktopSendJob(job: DesktopSendJobDiagnostics): DesktopSendJobDiagnostics {
  return { ...job };
}

function copyThreadObservation(
  threadId: string,
  observation: ThreadObservation,
  nowMs: number
): MobileGatewayRuntimeDiagnostics["threadObservations"]["items"][number] {
  return {
    threadId,
    clientMessageId: observation.clientMessageId,
    submittedAt: new Date(observation.submittedAtMs).toISOString(),
    deadlineAt: new Date(observation.deadlineMs).toISOString(),
    remainingMs: Math.max(0, observation.deadlineMs - nowMs),
    inFlight: observation.inFlight,
    rolloutTracking: Boolean(observation.rolloutPath),
    ...(observation.baselineTurnId ? { baselineTurnId: observation.baselineTurnId } : {}),
    ...(observation.rolloutObservedTurnId
      ? { rolloutObservedTurnId: observation.rolloutObservedTurnId }
      : {}),
    ...(observation.rolloutObservedStartedAt
      ? { rolloutObservedStartedAt: observation.rolloutObservedStartedAt }
      : {})
  };
}

function copyStatusDecisionDiagnostics(
  diagnostics: StatusDecisionDiagnostics
): StatusDecisionDiagnostics {
  return {
    ...diagnostics,
    candidates: diagnostics.candidates.map((candidate) => ({ ...candidate }))
  };
}

function copyStoppedThreadObservation(
  threadId: string,
  observation: ThreadObservation,
  nowMs: number,
  stop: StopThreadObservationOptions
): NonNullable<MobileGatewayRuntimeDiagnostics["threadObservations"]["lastStopped"]> {
  return {
    ...copyThreadObservation(threadId, observation, nowMs),
    stoppedAt: new Date(nowMs).toISOString(),
    stopReason: stop.reason,
    ...(stop.finalStatus ? { finalStatus: stop.finalStatus } : {}),
    ...(stop.error ? { error: stop.error } : {})
  };
}

function sameMessagePayload(left: ThreadMessage, right: ThreadMessage): boolean {
  if (left.role !== right.role) {
    return false;
  }

  const leftText = normalizeMessageTextForDedupe(left.text);
  const rightText = normalizeMessageTextForDedupe(right.text);
  if (left.kind === right.kind && isAttachmentMessage(left)) {
    return sameAttachmentFile(left, right) && (leftText === rightText || !leftText || !rightText);
  }

  if (leftText !== rightText) {
    return false;
  }
  if (left.kind === right.kind) {
    return true;
  }

  return Boolean(leftText && isAttachmentTextPair(left, right));
}

function sameAttachmentFile(left: ThreadMessage, right: ThreadMessage): boolean {
  if (left.kind !== right.kind) {
    return false;
  }

  if (left.kind !== "image" && left.kind !== "file") {
    return false;
  }

  return Boolean(left.fileName && left.fileName === right.fileName);
}

function isAttachmentTextPair(left: ThreadMessage, right: ThreadMessage): boolean {
  return (
    (left.kind === "text" && isAttachmentMessage(right)) ||
    (right.kind === "text" && isAttachmentMessage(left))
  );
}

function isAttachmentMessage(message: ThreadMessage): boolean {
  if (message.kind === "image") {
    return Boolean(message.imageUrl || message.fileName);
  }
  if (message.kind === "file") {
    return Boolean(message.fileUrl || message.fileName);
  }
  return false;
}

function optimisticDedupeWindowMs(left: ThreadMessage, right: ThreadMessage): number {
  return isAttachmentTextPair(left, right)
    ? ATTACHMENT_TEXT_DEDUPE_WINDOW_MS
    : OPTIMISTIC_DEDUPE_WINDOW_MS;
}

function mergedCachedMessage(
  candidate: ThreadMessage,
  cached: ThreadMessage
): ThreadMessage {
  const base =
    candidate.kind === "text" && isAttachmentMessage(cached)
      ? { ...candidate, kind: cached.kind }
      : candidate;
  return {
    ...base,
    text: candidate.text ?? cached.text,
    imageUrl: base.imageUrl ?? cached.imageUrl,
    thumbnailUrl: base.thumbnailUrl ?? cached.thumbnailUrl,
    fileUrl: base.fileUrl ?? cached.fileUrl,
    fileName: base.fileName ?? cached.fileName,
    mimeType: base.mimeType ?? cached.mimeType
  };
}

function normalizeMessageTextForDedupe(text: string | null | undefined): string {
  return userVisibleMessageText(
    (text ?? "")
      .trim()
      .replace(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?Z\s+/, "")
  );
}

function shouldSkipCachedMessage(
  cached: ThreadMessage,
  candidate: ThreadMessage
): boolean {
  if (cached.messageId === candidate.messageId) {
    return true;
  }

  if (!sameMessagePayload(cached, candidate)) {
    return false;
  }

  return (
    Math.abs(parseIsoTimestamp(cached.timestamp) - parseIsoTimestamp(candidate.timestamp)) <=
    optimisticDedupeWindowMs(cached, candidate)
  );
}

function clearAttachmentTextDuplicatedByStandaloneText(messages: ThreadMessage[]): ThreadMessage[] {
  return messages.map((message) => {
    if (!isAttachmentMessage(message) || !message.text?.trim()) {
      return message;
    }

    const text = normalizeMessageTextForDedupe(message.text);
    if (!text) {
      return message;
    }

    const hasStandaloneText = messages.some((candidate) => {
      if (candidate === message || candidate.role !== message.role || candidate.kind !== "text") {
        return false;
      }

      return (
        normalizeMessageTextForDedupe(candidate.text) === text &&
        Math.abs(parseIsoTimestamp(candidate.timestamp) - parseIsoTimestamp(message.timestamp)) <=
          ATTACHMENT_TEXT_DEDUPE_WINDOW_MS
      );
    });

    return hasStandaloneText ? { ...message, text: undefined } : message;
  });
}

function alignMessagesWithKnownTimestamps(
  messages: ThreadMessage[],
  timestampSources: ThreadMessage[]
): ThreadMessage[] {
  if (timestampSources.length === 0) {
    return messages;
  }

  const usedSourceIndexes = new Set<number>();
  return messages.map((message) => {
    const sourceIndex = timestampSources.findIndex((candidate, index) => {
      return !usedSourceIndexes.has(index) && sameMessagePayload(candidate, message);
    });
    if (sourceIndex < 0) {
      return message;
    }

    usedSourceIndexes.add(sourceIndex);
    return {
      ...message,
      timestamp: timestampSources[sourceIndex].timestamp
    };
  });
}

function mergeRecentMessages(
  computedMessages: ThreadMessage[],
  cachedMessages: ThreadMessage[]
): ThreadMessage[] {
  const merged = [...computedMessages];
  for (const cachedMessage of cachedMessages) {
    const matchIndex = merged.findIndex((candidate) =>
      shouldSkipCachedMessage(cachedMessage, candidate)
    );
    if (matchIndex >= 0) {
      merged[matchIndex] = mergedCachedMessage(merged[matchIndex], cachedMessage);
      continue;
    }

    merged.push(cachedMessage);
  }

  return clearAttachmentTextDuplicatedByStandaloneText(merged)
    .sort(compareThreadMessages)
    .slice(-MAX_MESSAGES);
}

function pageThreadMessages(
  sourceMessages: ThreadMessage[],
  before?: string | null,
  beforeTimestamp?: string | null,
  limit?: number | null,
  after?: string | null,
  afterTimestamp?: string | null
): ThreadMessagesResponse {
  const messages = [...sourceMessages].sort(compareThreadMessages);
  const pageLimit = normalizeMessagePageLimit(limit);

  if (after || afterTimestamp) {
    let startIndex = 0;

    if (after) {
      const afterIndex = messages.findIndex((message) => message.messageId === after);
      if (afterIndex >= 0) {
        startIndex = afterIndex + 1;
      }
    }

    if (startIndex === 0 && afterTimestamp) {
      const timestamp = parseIsoTimestamp(afterTimestamp);
      const timestampIndex = messages.findIndex(
        (message) => parseIsoTimestamp(message.timestamp) > timestamp
      );
      startIndex = timestampIndex >= 0 ? timestampIndex : messages.length;
    }

    const pageMessages = messages.slice(startIndex, startIndex + pageLimit);
    return {
      messages: pageMessages,
      nextCursor: pageMessages.at(-1)?.messageId ?? after ?? null
    };
  }

  let endIndex = messages.length;

  if (before) {
    const beforeIndex = messages.findIndex((message) => message.messageId === before);
    if (beforeIndex >= 0) {
      endIndex = beforeIndex;
    }
  }

  if (endIndex === messages.length && beforeTimestamp) {
    const timestamp = parseIsoTimestamp(beforeTimestamp);
    const timestampIndex = messages.findIndex(
      (message) => parseIsoTimestamp(message.timestamp) >= timestamp
    );
    if (timestampIndex >= 0) {
      endIndex = timestampIndex;
    }
  }

  const startIndex = Math.max(0, endIndex - pageLimit);
  const pageMessages = messages.slice(startIndex, endIndex);

  return {
    messages: pageMessages,
    nextCursor: startIndex > 0 ? pageMessages[0]?.messageId ?? null : null
  };
}

function sameEventPayload(left: ThreadEvent, right: ThreadEvent): boolean {
  return (
    left.kind === right.kind &&
    left.status === right.status &&
    left.text === right.text
  );
}

function shouldSkipCachedEvent(cached: ThreadEvent, candidate: ThreadEvent): boolean {
  if (cached.eventId === candidate.eventId) {
    return true;
  }

  if (!sameEventPayload(cached, candidate)) {
    return false;
  }

  return (
    Math.abs(parseIsoTimestamp(cached.timestamp) - parseIsoTimestamp(candidate.timestamp)) <=
    OPTIMISTIC_DEDUPE_WINDOW_MS
  );
}

function mergeRecentEvents(
  computedEvents: ThreadEvent[],
  cachedEvents: ThreadEvent[]
): ThreadEvent[] {
  const merged = [...computedEvents];
  for (const cachedEvent of cachedEvents) {
    if (merged.some((candidate) => shouldSkipCachedEvent(cachedEvent, candidate))) {
      continue;
    }

    merged.push(cachedEvent);
  }

  return merged
    .sort((left, right) => parseIsoTimestamp(left.timestamp) - parseIsoTimestamp(right.timestamp))
    .slice(-MAX_EVENTS);
}

export class MobileGatewayRuntimeService implements MobileGatewayService {
  private static readonly SIGNAL_POLL_INTERVAL_MS = 1500;
  private readonly authToken: string;
  private readonly appServer: CodexAppServerClient;
  private readonly stateRepository: StateRepository;
  private readonly logRepository: LogRepository;
  private readonly sendMode: MobileGatewaySendMode;
  private readonly desktopSendCoordinator: DesktopSendCoordinator;
  private readonly imageSendCoordinator: Pick<ImageSendCoordinator, "send" | "sendMany">;
  private readonly fileSendCoordinator: Pick<FileSendCoordinator, "sendMany">;
  private readonly queueStore: GatewayQueueStore;
  private readonly queueDispatchIntervalMs: number;
  private readonly uploadStore: Pick<
    MobileUploadStore,
    "persistUploadedFile" | "resolveStoredFile" | "makeRelativeUrl"
  >;
  private readonly listeners = new Set<(event: GatewayEvent) => void>();
  private readonly threadHints = new Map<string, RuntimeThreadHint>();
  private readonly threadEvents = new Map<string, ThreadEvent[]>();
  private readonly threadMessages = new Map<string, ThreadMessage[]>();
  private readonly emittedMessageIdsByThread = new Map<string, Set<string>>();
  private readonly composerStateCache = new Map<string, ThreadComposerState>();
  private readonly threadObservations = new Map<string, ThreadObservation>();
  private desktopSendQueue: Promise<unknown> = Promise.resolve();
  private desktopSendJobSequence = 0;
  private readonly queuedDesktopSendJobs = new Map<number, DesktopSendJobDiagnostics>();
  private activeDesktopSendJob: DesktopSendJobDiagnostics | null = null;
  private lastFinishedDesktopSendJob: DesktopSendJobDiagnostics | null = null;
  private lastFailedDesktopSendJob: DesktopSendJobDiagnostics | null = null;
  private recentDesktopSendJobs: DesktopSendJobDiagnostics[] = [];
  private recentStatusDecisions: StatusDecisionDiagnostics[] = [];
  private lastStoppedThreadObservation: MobileGatewayRuntimeDiagnostics["threadObservations"]["lastStopped"] =
    null;
  private gatewayQueuedTextMessages: GatewayQueuedTextMessage[] = [];
  private gatewayQueueLoadPromise: Promise<void> | null = null;
  private gatewayQueueDispatchTimer: ReturnType<typeof setTimeout> | null = null;
  private gatewayQueueDispatchInFlight = false;
  private readonly alerts: Alert[] = [];
  private readonly alertKeys = new Set<string>();
  private readonly lastAlertByThread = new Map<string, Alert>();
  private threadListCache: { createdAtMs: number; items: ThreadListItem[] } | null = null;
  private threadListRefreshInFlight = false;
  private threadListRefreshPromise: Promise<void> | null = null;
  private signalCursor: string | null = null;
  private signalPollTimer: ReturnType<typeof setInterval> | null = null;
  private signalPollInFlight = false;

  constructor(options: MobileGatewayRuntimeServiceOptions) {
    this.authToken = options.authToken;
    this.appServer = options.appServer;
    this.stateRepository = options.stateRepository;
    this.logRepository = options.logRepository;
    this.sendMode = options.sendMode ?? "official_persistence";
    this.queueStore = options.queueStore ?? new MemoryGatewayQueueStore();
    this.queueDispatchIntervalMs =
      options.queueDispatchIntervalMs ?? GATEWAY_QUEUE_DISPATCH_INTERVAL_MS;
    this.desktopSendCoordinator =
      options.desktopSendCoordinator ??
      new DesktopSendCoordinator({
        bridge: new UnsupportedDesktopBridge(),
        readThread: (threadId) => this.appServer.readThread(threadId)
      });
    this.imageSendCoordinator =
      options.imageSendCoordinator ??
      new ImageSendCoordinator({
        bridge: new UnsupportedDesktopBridge(),
        readThread: (threadId) => this.appServer.readThread(threadId)
      });
    this.fileSendCoordinator =
      options.fileSendCoordinator ??
      new FileSendCoordinator({
        bridge: new UnsupportedDesktopBridge(),
        readThread: (threadId) => this.appServer.readThread(threadId)
      });
    this.uploadStore =
      options.uploadStore ??
      {
        persistUploadedFile: async () => {
          throw new Error("image_upload_failed");
        },
        resolveStoredFile: async () => {
          throw new Error("upload_file_not_found");
        },
        makeRelativeUrl: (threadId: string, fileName: string) =>
          `/api/uploads/${encodeURIComponent(threadId)}/${encodeURIComponent(fileName)}`
      };
    void this.ensureGatewayQueueLoaded().then(() => this.scheduleGatewayQueueDispatch());
    this.appServer.subscribe((notification) => this.handleNotification(notification));
  }

  async authenticate(token: string): Promise<boolean> {
    return token === this.authToken;
  }

  async getHealth(): Promise<HealthStatus> {
    const sidecarStatus = this.appServer.getConnectionState();
    return {
      ok: sidecarStatus === "connected",
      sidecarStatus,
      codexHome: this.appServer.getCodexHome()
    };
  }

  async getRuntimeDiagnostics(): Promise<MobileGatewayRuntimeDiagnostics> {
    const nowMs = Date.now();
    const threadObservationItems = Array.from(this.threadObservations.entries()).map(
      ([threadId, observation]) => copyThreadObservation(threadId, observation, nowMs)
    );
    return {
      desktopSendQueue: {
        pending: this.queuedDesktopSendJobs.size,
        queued: Array.from(this.queuedDesktopSendJobs.values()).map(copyDesktopSendJob),
        active: this.activeDesktopSendJob ? copyDesktopSendJob(this.activeDesktopSendJob) : null,
        lastFinished: this.lastFinishedDesktopSendJob
          ? copyDesktopSendJob(this.lastFinishedDesktopSendJob)
          : null,
        lastFailed: this.lastFailedDesktopSendJob
          ? copyDesktopSendJob(this.lastFailedDesktopSendJob)
          : null,
        recent: this.recentDesktopSendJobs.map(copyDesktopSendJob)
      },
      threadObservations: {
        active: threadObservationItems.length,
        items: threadObservationItems,
        lastStopped: this.lastStoppedThreadObservation
      },
      statusDecisions: {
        recent: this.recentStatusDecisions.map(copyStatusDecisionDiagnostics),
        maxItems: MAX_STATUS_DECISION_DIAGNOSTICS
      }
    };
  }

  async listAutomations(): Promise<MobileAutomationItem[]> {
    const codexHome = this.appServer.getCodexHome();
    const definitions = loadAutomationDefinitions(codexHome);
    if (definitions.length === 0) {
      return [];
    }

    const targetThreadIds = Array.from(
      new Set(
        definitions
          .map((automation) => automation.targetThreadId)
          .filter((threadId): threadId is string => Boolean(threadId))
      )
    );
    const metadataRecords = targetThreadIds.length > 0
      ? await this.stateRepository.getThreadMetadata(targetThreadIds)
      : [];
    const metadataByThreadId = new Map(
      metadataRecords.map((metadata) => [metadata.threadId, metadata])
    );
    const sessionThreadNames = loadSessionIndexThreadNames(codexHome);

    return definitions.map((automation) => {
      const metadata = automation.targetThreadId
        ? metadataByThreadId.get(automation.targetThreadId)
        : undefined;
      const targetThreadTitle = automation.targetThreadId
        ? metadata?.title ?? sessionThreadNames.get(automation.targetThreadId)
        : undefined;
      return {
        id: automation.id,
        name: automation.name,
        kind: automation.kind,
        status: automation.status,
        scheduleSummary: formatAutomationSchedule(automation.rrule) ?? "未设置周期",
        ...(automation.targetThreadId ? { targetThreadId: automation.targetThreadId } : {}),
        ...(targetThreadTitle ? { targetThreadTitle } : {}),
        ...(metadata?.cwd ? { cwd: normalizeCwd(metadata.cwd) } : {})
      };
    });
  }

  async listThreads(): Promise<ThreadListItem[]> {
    const cachedItems = this.getCachedThreadList();
    if (cachedItems) {
      return cachedItems;
    }

    const metadataItems = await this.listThreadsFromStateSnapshot();
    if (metadataItems.length > 0) {
      this.threadListCache = { createdAtMs: Date.now(), items: metadataItems };
      const refreshPromise = this.refreshThreadListFromAppServerInBackground();
      await giveImmediateRefreshAChance(refreshPromise);
      return this.threadListCache?.items ?? metadataItems;
    }

    const appServerItems = await this.loadThreadListFromAppServer();
    this.threadListCache = { createdAtMs: Date.now(), items: appServerItems };
    return appServerItems;
  }

  private async listThreadsFromStateSnapshot(): Promise<ThreadListItem[]> {
    const metadataRecords = await this.listDesktopVisibleMetadataRecords();
    const codexHome = this.appServer.getCodexHome();
    const pinnedThreadIdList = loadPinnedThreadIdList(codexHome);
    const pinnedThreadIds = new Set(pinnedThreadIdList);
    const sessionThreadNames = loadSessionIndexThreadNames(codexHome);
    const automationSummaries = loadActiveAutomationSummaries(codexHome);
    const visibleRecords = metadataRecords
      .filter(isDesktopVisibleMetadata)
      .sort((left, right) => right.updatedAt - left.updatedAt)
      .slice(0, THREAD_LIST_LIMIT);

    const items = await Promise.all(
      visibleRecords.map(async (metadata) => {
        const resolved = await resolveListItemWithRolloutStatus(
          createListItemFromMetadata(
            metadata,
            this.getResolvedHint(metadata.threadId),
            sessionThreadNames.get(metadata.threadId)
          ),
          metadata.rolloutPath
        );
        this.rememberStatusDecision(metadata.threadId, "list", resolved.statusDecision);
        return applyThreadAutomationSummary(
          applyPinnedState(resolved.thread, pinnedThreadIds),
          automationSummaries
        );
      })
    );

    return orderPinnedThreadsByDesktopOrder(
      items,
      pinnedThreadIdList
    );
  }

  private async listDesktopVisibleMetadataRecords(): Promise<ThreadMetadataRecord[]> {
    if (this.stateRepository.listDesktopVisibleThreadMetadata) {
      return this.stateRepository.listDesktopVisibleThreadMetadata(THREAD_LIST_LIMIT);
    }

    return this.stateRepository.getThreadMetadata([]);
  }

  private refreshThreadListFromAppServerInBackground(): Promise<void> {
    if (this.threadListRefreshInFlight) {
      return this.threadListRefreshPromise ?? Promise.resolve();
    }

    this.threadListRefreshInFlight = true;
    this.threadListRefreshPromise = this.loadThreadListFromAppServer()
      .then((items) => {
        if (items.length > 0) {
          this.threadListCache = {
            createdAtMs: Date.now(),
            items: mergeThreadListItems(this.threadListCache?.items ?? [], items)
          };
        }
      })
      .catch(() => undefined)
      .finally(() => {
        this.threadListRefreshInFlight = false;
        this.threadListRefreshPromise = null;
      });
    return this.threadListRefreshPromise;
  }

  private async loadThreadListFromAppServer(): Promise<ThreadListItem[]> {
    const threads = await this.appServer.listThreads();
    const threadIds = threads.map((thread) => thread.id);
    const metadataRecords = await this.stateRepository.getThreadMetadata(threadIds);
    const codexHome = this.appServer.getCodexHome();
    const pinnedThreadIdList = loadPinnedThreadIdList(codexHome);
    const pinnedThreadIds = new Set(pinnedThreadIdList);
    const sessionThreadNames = loadSessionIndexThreadNames(codexHome);
    const automationSummaries = loadActiveAutomationSummaries(codexHome);

    const metadataMap = new Map(metadataRecords.map((item) => [item.threadId, item]));

    const items = await Promise.all(
      threads
        .filter((thread) => isDesktopVisibleMetadata(metadataMap.get(thread.id)))
        .map(async (thread) => {
          const metadata = metadataMap.get(thread.id);
          const resolved = await resolveListItemWithRolloutStatus(
            createListItem(
              thread,
              metadata,
              this.getResolvedHint(thread.id),
              sessionThreadNames.get(thread.id)
            ),
            metadata?.rolloutPath
          );
          this.rememberStatusDecision(thread.id, "list", resolved.statusDecision);
          return applyThreadAutomationSummary(
            applyPinnedState(resolved.thread, pinnedThreadIds),
            automationSummaries
          );
        })
    );

    return orderPinnedThreadsByDesktopOrder(
      items,
      pinnedThreadIdList
    );
  }

  private getCachedComposerState(
    threadId: string,
    metadata?: ThreadMetadataRecord
  ): ThreadComposerState | undefined {
    const cached = this.composerStateCache.get(threadId);
    if (cached) {
      return cached;
    }

    if (!metadata) {
      return undefined;
    }

    const fromMetadata = composerStateFromMetadata(metadata);
    if (fromMetadata) {
      this.composerStateCache.set(threadId, fromMetadata);
    }

    return fromMetadata;
  }

  private rememberComposerState(
    threadId: string,
    composerState: ThreadComposerState | undefined
  ): ThreadComposerState | undefined {
    if (composerState) {
      this.composerStateCache.set(threadId, composerState);
    }
    return composerState;
  }

  async getThreadPreview(threadId: string): Promise<ThreadDetail> {
    const metadataRecords = await this.stateRepository.getThreadMetadata([threadId]);
    const metadata = metadataRecords[0];
    if (metadata && !isDesktopVisibleMetadata(metadata)) {
      throw new Error(`thread_not_found:${threadId}`);
    }

    const codexHome = this.appServer.getCodexHome();
    const pinnedThreadIds = loadPinnedThreadIds(codexHome);
    const sessionThreadNames = loadSessionIndexThreadNames(codexHome);
    let thread = metadata
      ? applyPinnedState(
          createListItemFromMetadata(
            metadata,
            this.getResolvedHint(threadId),
            sessionThreadNames.get(threadId)
          ),
          pinnedThreadIds
        )
      : undefined;
    if (!thread) {
      thread = (await this.listThreads()).find((item) => item.threadId === threadId);
    }
    if (!thread) {
      throw new Error(`thread_not_found:${threadId}`);
    }

    const [previewMessages, previewEvents] = await Promise.all([
      readPreviewMessagesFromRollout(metadata?.rolloutPath, threadId),
      readPreviewEventsFromRollout(metadata?.rolloutPath, threadId)
    ]);
    const recentMessages = mergeRecentMessages(
      previewMessages,
      this.threadMessages.get(threadId) ?? []
    );
    this.threadMessages.set(threadId, recentMessages);

    const recentEvents = mergeRecentEvents(previewEvents, this.threadEvents.get(threadId) ?? []);
    this.threadEvents.set(threadId, recentEvents);
    const resolvedDetail = resolveThreadFromStatusCandidates(
      thread,
      [latestEventStatusCandidate(recentEvents)].filter(
        (candidate): candidate is DetailStatusCandidate => candidate !== null
      )
    );
    const detailThread = applyThreadAutomationSummary(
      resolvedDetail.thread,
      loadActiveAutomationSummaries(codexHome)
    );
    this.rememberStatusDecision(threadId, "preview", resolvedDetail.statusDecision);
    this.rememberResolvedDetailThread(detailThread);
    const composerState = this.getCachedComposerState(threadId, metadata);
    const sendDecision = makeSendDecision(resolvedDetail.statusDecision);

    return {
      thread: detailThread,
      recentMessages,
      recentEvents,
      composerState,
      queuedTextMessages: await this.getQueuedTextMessages(threadId),
      statusDecision: resolvedDetail.statusDecision,
      sendDecision,
      sendAvailable: sendDecision.available,
      sendDisabledReason: sendDecision.available ? undefined : sendDecision.message
    };
  }

  private buildThreadMessages(thread: CodexThread, turns: CodexTurn[]): ThreadMessage[] {
    const messages: ThreadMessage[] = [];

    for (const turn of turns) {
      const timestamp = toIso(turn.completedAt ?? turn.startedAt ?? thread.updatedAt);
      for (const item of turn.items) {
        if (item.type === "userMessage") {
          const localImages = item.content.filter(
            (content): content is Extract<CodexUserInput, { type: "localImage" }> =>
              content.type === "localImage"
          );
          const rawText = item.content
            .filter((content): content is Extract<CodexUserInput, { type: "text" }> => content.type === "text")
            .map((content) => content.text.trim())
            .filter(Boolean)
            .join("\n");
          const text = userVisibleMessageText(rawText);
          const visibleText = text && !isInternalControlMessageText(text) ? text : undefined;
          if (localImages.length > 0) {
            localImages.forEach((localImage, index) => {
              const fileName = localImageFileName(localImage.path);
              const imageUrl = this.uploadStore.makeRelativeUrl(thread.id, fileName);
              messages.push(
                makeThreadImageMessage(
                  thread.id,
                  "user",
                  timestamp,
                  index === 0 ? item.id : `${item.id}:image-${index + 1}`,
                  {
                    fileName,
                    imageUrl
                  }
                )
              );
            });
            if (visibleText) {
              messages.push(
                makeThreadTextMessage(
                  thread.id,
                  "user",
                  visibleText,
                  timestamp,
                  `${item.id}:text`
                )
              );
            }
            continue;
          }

          if (visibleText) {
            messages.push(makeThreadTextMessage(thread.id, "user", text, timestamp, item.id));
          }
        }

        if (item.type === "agentMessage" && item.text.trim()) {
          const text = assistantVisibleMessageText(item.text);
          if (text && !isInternalControlMessageText(text)) {
            messages.push(makeThreadTextMessage(thread.id, "assistant", text, timestamp, item.id));
          }
        }
      }
    }

    return messages;
  }

  private buildThreadEvents(thread: CodexThread, turns: CodexTurn[]): ThreadEvent[] {
    const events: ThreadEvent[] = [];

    for (const turn of turns) {
      const timestamp = toIso(turn.completedAt ?? turn.startedAt ?? thread.updatedAt);
      for (const item of turn.items) {
        if (item.type === "plan" && item.text.trim()) {
          events.push(makeThreadEvent(thread.id, "plan", item.text.trim(), timestamp, item.id));
        }

        if (item.type === "commandExecution") {
          events.push(
            makeThreadEvent(
              thread.id,
              "command",
              `执行命令: ${item.command}`,
              timestamp,
              item.id
            )
          );
        }
      }

      if (turn.status === "completed") {
        events.push(
          makeThreadEvent(
            thread.id,
            "turn_completed",
            "本轮已完成",
            timestamp,
            `${turn.id}:completed`,
            "completed"
          )
        );
      } else if (turn.status === "failed") {
        events.push(
          makeThreadEvent(
            thread.id,
            "error",
            turn.error?.message ?? "线程执行失败",
            timestamp,
            `${turn.id}:failed`,
            "error"
          )
        );
      } else if (turn.status === "inProgress") {
        events.push(
          makeThreadEvent(
            thread.id,
            "turn_started",
            "开始处理新的输入",
            timestamp,
            `${turn.id}:started`,
            "running"
          )
        );
      }
    }

    return events;
  }

  private async readTerminalLogSignalEvents(threadId: string): Promise<ThreadEvent[]> {
    try {
      const signals = await this.logRepository.listRecentSignals([threadId]);
      return signals
        .filter((signal) => signal.threadId === threadId && shouldUseLogSignalForDetailStatus(signal))
        .map((signal) =>
          makeThreadEvent(
            signal.threadId,
            logSignalEventKind(signal.status),
            signal.text,
            signal.timestamp,
            `log:${signal.signalId}`,
            signal.status
          )
        );
    } catch {
      return [];
    }
  }

  async getThreadDetail(threadId: string): Promise<ThreadDetail> {
    let thread: CodexThread | null;
    let listItems: ThreadListItem[];
    let metadataRecords: ThreadMetadataRecord[];
    try {
      [thread, listItems, metadataRecords] = await Promise.all([
        this.readThreadForDetail(threadId),
        this.listThreads(),
        this.stateRepository.getThreadMetadata([threadId])
      ]);
    } catch (error) {
      if (isAppServerThreadReadTimeout(error)) {
        return this.getThreadDetailPreviewFallback(threadId);
      }
      throw error;
    }
    if (!thread) {
      return this.getThreadDetailPreviewFallback(threadId);
    }
    const metadata = metadataRecords[0];
    const listItem = listItems.find((item) => item.threadId === threadId);
    if (!listItem) {
      throw new Error(`thread_not_found:${threadId}`);
    }

    const recentTurns = thread.turns.slice(-5);
    const rolloutMessages = await readPreviewMessagesFromRollout(metadata?.rolloutPath, threadId);
    const rolloutEvents = await readPreviewEventsFromRollout(metadata?.rolloutPath, threadId);
    const rolloutTimestampMessages = await readMessagesFromRollout(metadata?.rolloutPath, threadId);
    const messages = alignMessagesWithKnownTimestamps(
      this.buildThreadMessages(thread, thread.turns),
      rolloutTimestampMessages.length > 0 ? rolloutTimestampMessages : rolloutMessages
    );
    const events = this.buildThreadEvents(thread, recentTurns);
    const terminalLogEvents = await this.readTerminalLogSignalEvents(threadId);

    const recentMessages = mergeRecentMessages(
      messages.slice(-MAX_MESSAGES),
      [...rolloutMessages, ...(this.threadMessages.get(threadId) ?? [])]
    );
    const recentEvents = mergeRecentEvents(
      events.slice(-MAX_EVENTS),
      [...rolloutEvents, ...(this.threadEvents.get(threadId) ?? []), ...terminalLogEvents]
    );
    this.threadMessages.set(threadId, recentMessages);
    this.threadEvents.set(threadId, recentEvents);
    const resolvedDetail = resolveDetailThread(listItem, thread, recentEvents);
    const detailThread = applyThreadAutomationSummary(
      resolvedDetail.thread,
      loadActiveAutomationSummaries(this.appServer.getCodexHome())
    );
    this.rememberStatusDecision(threadId, "detail", resolvedDetail.statusDecision);
    this.rememberResolvedDetailThread(detailThread);
    const fileChanges = extractThreadFileChanges(thread);
    const composerState =
      this.getCachedComposerState(threadId, metadata) ??
      this.rememberComposerState(
        threadId,
        await resolveThreadComposerState(this.appServer.getCodexHome(), threadId)
      );

    const sendDecision = makeSendDecision(resolvedDetail.statusDecision);

    return {
      thread: detailThread,
      recentMessages,
      recentEvents,
      fileChanges,
      composerState,
      queuedTextMessages: await this.getQueuedTextMessages(threadId),
      statusDecision: resolvedDetail.statusDecision,
      sendDecision,
      sendAvailable: sendDecision.available,
      sendDisabledReason: sendDecision.available ? undefined : sendDecision.message
    };
  }

  private async readThreadForDetail(threadId: string): Promise<CodexThread | null> {
    return this.readThreadWithGrace(threadId, APP_SERVER_DETAIL_READ_GRACE_MS);
  }

  private async readThreadForSend(threadId: string): Promise<CodexThread | null> {
    return this.readThreadWithGrace(threadId, APP_SERVER_SEND_READ_GRACE_MS);
  }

  private async readThreadWithGrace(
    threadId: string,
    graceMs: number
  ): Promise<CodexThread | null> {
    const readResult = this.appServer.readThread(threadId)
      .then((thread) => ({ thread }))
      .catch((error: unknown) => ({ error }));
    const result = await Promise.race([
      readResult,
      new Promise<{ timedOut: true }>((resolve) => {
        setTimeout(() => resolve({ timedOut: true }), graceMs);
      })
    ]);

    if ("thread" in result) {
      return result.thread;
    }
    void readResult.then((lateResult) => {
      if ("thread" in lateResult) {
        void this.refreshThreadDetailFromResolvedRead(threadId, lateResult.thread);
      }
    });
    if ("error" in result && !isAppServerThreadReadTimeout(result.error)) {
      throw result.error;
    }
    return null;
  }

  private async refreshThreadDetailFromResolvedRead(
    threadId: string,
    thread: CodexThread
  ): Promise<void> {
    try {
      const metadataRecords = await this.stateRepository.getThreadMetadata([threadId]);
      const metadata = metadataRecords[0];
      const codexHome = this.appServer.getCodexHome();
      const pinnedThreadIds = loadPinnedThreadIds(codexHome);
      const sessionThreadName = loadSessionIndexThreadNames(codexHome).get(threadId);
      const listItem = applyPinnedState(
        this.getCachedThreadList()?.find((item) => item.threadId === threadId) ??
          (metadata
            ? createListItemFromMetadata(metadata, this.getResolvedHint(threadId), sessionThreadName)
            : createListItem(thread, undefined, this.getResolvedHint(threadId), sessionThreadName)),
        pinnedThreadIds
      );
      const recentTurns = thread.turns.slice(-5);
      const rolloutMessages = await readPreviewMessagesFromRollout(metadata?.rolloutPath, threadId);
      const rolloutEvents = await readPreviewEventsFromRollout(metadata?.rolloutPath, threadId);
      const rolloutTimestampMessages = await readMessagesFromRollout(metadata?.rolloutPath, threadId);
      const messages = alignMessagesWithKnownTimestamps(
        this.buildThreadMessages(thread, thread.turns),
        rolloutTimestampMessages.length > 0 ? rolloutTimestampMessages : rolloutMessages
      );
      const events = this.buildThreadEvents(thread, recentTurns);
      const recentMessages = mergeRecentMessages(
        messages.slice(-MAX_MESSAGES),
        [...rolloutMessages, ...(this.threadMessages.get(threadId) ?? [])]
      );
      const recentEvents = mergeRecentEvents(
        events.slice(-MAX_EVENTS),
        [...rolloutEvents, ...(this.threadEvents.get(threadId) ?? [])]
      );
      this.threadMessages.set(threadId, recentMessages);
      this.threadEvents.set(threadId, recentEvents);
      const resolvedDetail = resolveDetailThread(listItem, thread, recentEvents);
      this.rememberStatusDecision(threadId, "late_detail", resolvedDetail.statusDecision);
      this.rememberResolvedDetailThread(resolvedDetail.thread);
    } catch {
      return;
    }
  }

  private async getThreadDetailPreviewFallback(threadId: string): Promise<ThreadDetail> {
    return this.getThreadPreview(threadId);
  }

  async getThreadMessages(
    threadId: string,
    before?: string | null,
    beforeTimestamp?: string | null,
    limit?: number | null,
    after?: string | null,
    afterTimestamp?: string | null
  ): Promise<ThreadMessagesResponse> {
    const metadataRecords = await this.stateRepository.getThreadMetadata([threadId]);
    const metadata = metadataRecords[0];
    if (metadata && !isDesktopVisibleMetadata(metadata)) {
      throw new Error(`thread_not_found:${threadId}`);
    }

    const hasCursor = Boolean(before || beforeTimestamp || after || afterTimestamp);
    if (!hasCursor) {
      const rolloutPage = await readRecentMessagesFromRollout(
        metadata?.rolloutPath,
        threadId,
        normalizeMessagePageLimit(limit)
      );
      if (rolloutPage.messages.length > 0) {
        return {
          messages: rolloutPage.messages,
          nextCursor: rolloutPage.hasEarlier ? rolloutPage.messages[0]?.messageId ?? null : null
        };
      }
    }

    const rolloutMessages = await readMessagesFromRollout(metadata?.rolloutPath, threadId);
    if (rolloutMessages.length > 0) {
      return pageThreadMessages(
        rolloutMessages,
        before,
        beforeTimestamp,
        limit,
        after,
        afterTimestamp
      );
    }

    const thread = await this.appServer.readThread(threadId);
    return pageThreadMessages(
      this.buildThreadMessages(thread, thread.turns),
      before,
      beforeTimestamp,
      limit,
      after,
      afterTimestamp
    );
  }

  async getThreadEvents(threadId: string, cursor?: string | null): Promise<ThreadEventsResponse> {
    if (!this.threadEvents.has(threadId)) {
      await this.getThreadDetail(threadId);
    }

    const events = this.threadEvents.get(threadId) ?? [];
    if (!cursor) {
      return {
        events,
        nextCursor: events.at(-1)?.eventId ?? null
      };
    }

    const index = events.findIndex((event) => event.eventId === cursor);
    const sliced = index >= 0 ? events.slice(index + 1) : events;
    return {
      events: sliced,
      nextCursor: sliced.at(-1)?.eventId ?? cursor
    };
  }

  async getQueuedTextMessages(threadId: string): Promise<QueuedTextMessage[]> {
    await this.ensureGatewayQueueLoaded();
    return this.visibleGatewayQueuedMessages(threadId);
  }

  async cancelQueuedTextMessage(
    threadId: string,
    queueId: string
  ): Promise<QueuedTextMessage | null> {
    await this.ensureGatewayQueueLoaded();
    const index = this.gatewayQueuedTextMessages.findIndex((message) =>
      message.threadId === threadId &&
      message.queueId === queueId &&
      message.status !== "DISPATCHING" &&
      message.status !== "SENT"
    );
    if (index < 0) {
      return null;
    }
    const [removed] = this.gatewayQueuedTextMessages.splice(index, 1);
    await this.persistGatewayQueue();
    return {
      ...removed,
      status: "CANCELLED"
    };
  }

  async retryQueuedTextMessage(
    threadId: string,
    queueId: string
  ): Promise<QueuedTextMessage | null> {
    await this.ensureGatewayQueueLoaded();
    const message = this.gatewayQueuedTextMessages.find((candidate) =>
      candidate.threadId === threadId &&
      candidate.queueId === queueId &&
      candidate.status === "FAILED"
    );
    if (!message) {
      return null;
    }
    message.status = "PENDING";
    delete message.errorMessage;
    delete message.dispatchStartedAt;
    delete message.dispatchStartedAtMillis;
    await this.persistGatewayQueue();
    this.scheduleGatewayQueueDispatch(0);
    return { ...message };
  }

  async getMarkdownFilePreview(threadId: string, filePath: string): Promise<MarkdownFilePreview> {
    const metadataRecords = await this.stateRepository.getThreadMetadata([threadId]);
    const metadata = metadataRecords[0];
    if (!metadata || !isDesktopVisibleMetadata(metadata)) {
      throw new Error(`thread_not_found:${threadId}`);
    }

    return readMarkdownFilePreview(metadata.cwd, filePath);
  }

  async sendMessage(threadId: string, payload: SendMessageRequest): Promise<SendMessageResponse> {
    const isGuideMessage = payload.guide === true;
    const isQueuedMessage = payload.queue === true && !isGuideMessage;
    const threadForSend = await this.resolveThreadForSend(threadId, {
      allowRunning: isGuideMessage || isQueuedMessage
    });

    if (this.sendMode === "official_persistence" && isQueuedMessage) {
      return this.enqueueGatewayQueuedTextMessage(threadId, payload);
    }

    const request = {
      threadId,
      title: threadForSend?.title ?? threadId,
      text: payload.text,
      clientMessageId: payload.clientMessageId,
      ...(isGuideMessage ? { guide: true } : {})
    };
    const now = new Date().toISOString();
    const message = makeThreadTextMessage(threadId, "user", payload.text, now, payload.clientMessageId);
    const eventText = isGuideMessage
      ? "已提交引导消息到桌面"
      : isQueuedMessage
        ? "已提交排队消息到桌面"
        : "已提交到桌面发送";
    const event = makeThreadEvent(
      threadId,
      isGuideMessage ? "message" : "turn_started",
      eventText,
      now,
      `${payload.clientMessageId}:start`,
      isGuideMessage ? undefined : "running"
    );
    this.threadMessages.set(
      threadId,
      appendCapped(this.threadMessages.get(threadId) ?? [], message, MAX_MESSAGES)
    );
    this.storeEvent(event);
    if (!isGuideMessage) {
      this.storeOptimisticRunningHint(threadId, eventText, now);
    }

    if (this.sendMode === "official_persistence" && isGuideMessage) {
      const activeTurnId = await this.readActiveTurnIdForSteer(threadId);
      return this.steerThroughOfficialPersistence(
        threadId,
        payload.clientMessageId,
        this.buildOfficialPersistenceInput({ text: payload.text }),
        activeTurnId,
        {
          doneText: "已通过官方持久化发送引导消息",
          failureTitle: "官方持久化发送引导失败"
        }
      );
    }

    if (this.sendMode === "official_persistence" && !isGuideMessage && !isQueuedMessage) {
      await this.observeMobileSubmittedThread(threadId, payload.clientMessageId, new Date().toISOString());
      try {
        await this.appServer.resumeThread(threadId);
        const turn = await this.appServer.startTurn(threadId, payload.text);
        const timestamp = new Date().toISOString();
        this.storeEvent(
          makeThreadEvent(
            threadId,
            "turn_started",
            "已通过官方持久化发送新消息",
            timestamp,
            `${payload.clientMessageId}:app-server`,
            "running"
          )
        );
        return {
          accepted: turn.accepted,
          threadId,
          clientMessageId: payload.clientMessageId,
          sendPath: "app_server",
          confirmation: "observed"
        };
      } catch (error) {
        this.stopThreadObservation(threadId, payload.clientMessageId, {
          reason: "send_failed",
          error: errorMessage(error)
        });
        this.recordSendFailure(threadId, payload.clientMessageId, error, {
          title: "官方持久化发送失败",
          prefix: "官方持久化发送失败"
        });
        throw error;
      }
    }

    return this.enqueueDesktopSend(
      {
        kind: isGuideMessage ? "guide_text" : isQueuedMessage ? "queued_text" : "text",
        threadId,
        clientMessageId: payload.clientMessageId
      },
      async () => {
      await this.observeMobileSubmittedThread(threadId, payload.clientMessageId, new Date().toISOString());
      try {
        const sendResponse = this.normalizeDesktopSendResponse(
          await this.desktopSendCoordinator.send(request),
          threadId,
          payload.clientMessageId
        );
        const timestamp = new Date().toISOString();
        const doneText =
          sendResponse.warning === "desktop_send_confirmation_timeout"
            ? "已发送到桌面，正在等待确认"
            : "已从手机端发送新消息";
        this.storeEvent(
          makeThreadEvent(
            threadId,
            "turn_started",
            doneText,
            timestamp,
            `${payload.clientMessageId}:desktop`
          )
        );
        return sendResponse;
      } catch (error) {
        this.stopThreadObservation(threadId, payload.clientMessageId, {
          reason: "send_failed",
          error: errorMessage(error)
        });
        this.recordBackgroundSendFailure(threadId, payload.clientMessageId, error);
        throw error;
      }
      }
    );
  }

  async sendImageMessage(
    threadId: string,
    payload: SendImageMessagePayload
  ): Promise<SendMessageResponse> {
    const threadForSend = await this.resolveThreadForSend(threadId);

    const stored = await this.uploadStore.persistUploadedFile({
      threadId,
      tempFilePath: payload.tempFilePath,
      originalName: payload.originalFileName,
      mimeType: payload.mimeType
    });

    const request = {
      threadId,
      title: threadForSend?.title ?? threadId,
      clientMessageId: payload.clientMessageId,
      localImagePath: stored.absolutePath,
      text: payload.text
    };
    const now = new Date().toISOString();
    const eventText = "已提交图片到桌面发送";
    const message = makeThreadImageMessage(threadId, "user", now, payload.clientMessageId, {
      text: payload.text,
      fileName: stored.fileName,
      imageUrl: this.uploadStore.makeRelativeUrl(threadId, stored.fileName)
    });
    this.threadMessages.set(
      threadId,
      appendCapped(this.threadMessages.get(threadId) ?? [], message, MAX_MESSAGES)
    );
    this.storeEvent(
      makeThreadEvent(
        threadId,
        "turn_started",
        eventText,
        now,
        `${payload.clientMessageId}:image-start`,
        "running"
      )
    );
    this.storeOptimisticRunningHint(threadId, eventText, now);

    if (this.sendMode === "official_persistence") {
      return this.sendThroughOfficialPersistence(
        threadId,
        payload.clientMessageId,
        this.buildOfficialPersistenceInput({
          localImagePaths: [stored.absolutePath],
          text: payload.text
        }),
        {
          doneText: "已通过官方持久化发送图片",
          failureTitle: "官方持久化发送图片失败"
        }
      );
    }

    return this.enqueueDesktopSend(
      {
        kind: "image",
        threadId,
        clientMessageId: payload.clientMessageId
      },
      async () => {
      await this.observeMobileSubmittedThread(threadId, payload.clientMessageId, new Date().toISOString());
      try {
        const sendResponse = this.normalizeDesktopSendResponse(
          await this.imageSendCoordinator.send(request),
          threadId,
          payload.clientMessageId
        );
        const timestamp = new Date().toISOString();
        const doneText =
          sendResponse.warning === "desktop_send_confirmation_timeout"
            ? "图片已发送，正在等待确认"
            : "已从手机端发送图片";
        this.storeEvent(
          makeThreadEvent(
            threadId,
            "turn_started",
            doneText,
            timestamp,
            `${payload.clientMessageId}:image-desktop`
          )
        );
        return sendResponse;
      } catch (error) {
        this.stopThreadObservation(threadId, payload.clientMessageId, {
          reason: "send_failed",
          error: errorMessage(error)
        });
        this.recordBackgroundSendFailure(threadId, payload.clientMessageId, error);
        throw error;
      }
      }
    );
  }

  async sendImageMessages(
    threadId: string,
    payload: SendImageMessagesPayload
  ): Promise<SendMessageResponse> {
    if (payload.images.length === 0) {
      throw new GatewayHttpError(400, {
        error: "image_upload_invalid",
        message: "缺少图片文件"
      });
    }

    const threadForSend = await this.resolveThreadForSend(threadId);
    const storedImages = await Promise.all(
      payload.images.map((image) =>
        this.uploadStore.persistUploadedFile({
          threadId,
          tempFilePath: image.tempFilePath,
          originalName: image.originalFileName,
          mimeType: image.mimeType
        })
      )
    );

    const request = {
      threadId,
      title: threadForSend?.title ?? threadId,
      clientMessageId: payload.clientMessageId,
      localImagePaths: storedImages.map((stored) => stored.absolutePath),
      text: payload.text
    };
    const now = new Date().toISOString();
    const eventText = "已提交图片到桌面发送";
    const messages = storedImages.map((stored, index) =>
      makeThreadImageMessage(
        threadId,
        "user",
        now,
        index === 0 ? payload.clientMessageId : `${payload.clientMessageId}:image-${index}`,
        {
          text: index === 0 ? payload.text : undefined,
          fileName: stored.fileName,
          imageUrl: this.uploadStore.makeRelativeUrl(threadId, stored.fileName)
        }
      )
    );
    this.threadMessages.set(
      threadId,
      messages.reduce(
        (items, message) => appendCapped(items, message, MAX_MESSAGES),
        this.threadMessages.get(threadId) ?? []
      )
    );
    this.storeEvent(
      makeThreadEvent(
        threadId,
        "turn_started",
        eventText,
        now,
        `${payload.clientMessageId}:image-start`,
        "running"
      )
    );
    this.storeOptimisticRunningHint(threadId, eventText, now);

    if (this.sendMode === "official_persistence") {
      return this.sendThroughOfficialPersistence(
        threadId,
        payload.clientMessageId,
        this.buildOfficialPersistenceInput({
          localImagePaths: storedImages.map((stored) => stored.absolutePath),
          text: payload.text
        }),
        {
          doneText: "已通过官方持久化发送图片",
          failureTitle: "官方持久化发送图片失败"
        }
      );
    }

    return this.enqueueDesktopSend(
      {
        kind: "images",
        threadId,
        clientMessageId: payload.clientMessageId
      },
      async () => {
      await this.observeMobileSubmittedThread(threadId, payload.clientMessageId, new Date().toISOString());
      try {
        const sendResponse = this.normalizeDesktopSendResponse(
          await this.imageSendCoordinator.sendMany(request),
          threadId,
          payload.clientMessageId
        );
        const timestamp = new Date().toISOString();
        const doneText =
          sendResponse.warning === "desktop_send_confirmation_timeout"
            ? "图片已发送，正在等待确认"
            : "已从手机端发送图片";
        this.storeEvent(
          makeThreadEvent(
            threadId,
            "turn_started",
            doneText,
            timestamp,
            `${payload.clientMessageId}:image-desktop`
          )
        );
        return sendResponse;
      } catch (error) {
        this.stopThreadObservation(threadId, payload.clientMessageId, {
          reason: "send_failed",
          error: errorMessage(error)
        });
        this.recordBackgroundSendFailure(threadId, payload.clientMessageId, error);
        throw error;
      }
      }
    );
  }

  async sendFileMessages(
    threadId: string,
    payload: SendFileMessagesPayload
  ): Promise<SendMessageResponse> {
    if (payload.files.length === 0) {
      throw new GatewayHttpError(400, {
        error: "file_upload_invalid",
        message: "缺少文件"
      });
    }

    const threadForSend = await this.resolveThreadForSend(threadId);
    const storedFiles = await Promise.all(
      payload.files.map((file) =>
        this.uploadStore.persistUploadedFile({
          threadId,
          tempFilePath: file.tempFilePath,
          originalName: file.originalFileName,
          mimeType: file.mimeType
        })
      )
    );

    const request = {
      threadId,
      title: threadForSend?.title ?? threadId,
      clientMessageId: payload.clientMessageId,
      localFilePaths: storedFiles.map((stored) => stored.absolutePath),
      text: payload.text
    };
    const now = new Date().toISOString();
    const eventText = "已提交文件到桌面发送";
    const messages = storedFiles.map((stored, index) =>
      makeThreadFileMessage(
        threadId,
        "user",
        now,
        index === 0 ? payload.clientMessageId : `${payload.clientMessageId}:file-${index}`,
        {
          fileName: stored.fileName,
          fileUrl: this.uploadStore.makeRelativeUrl(threadId, stored.fileName),
          mimeType: stored.mimeType
        }
      )
    );
    if (payload.text?.trim()) {
      messages.push(
        makeThreadTextMessage(
          threadId,
          "user",
          payload.text,
          now,
          `${payload.clientMessageId}:text`
        )
      );
    }
    this.threadMessages.set(
      threadId,
      messages.reduce(
        (items, message) => appendCapped(items, message, MAX_MESSAGES),
        this.threadMessages.get(threadId) ?? []
      )
    );
    this.storeEvent(
      makeThreadEvent(
        threadId,
        "turn_started",
        eventText,
        now,
        `${payload.clientMessageId}:file-start`,
        "running"
      )
    );
    this.storeOptimisticRunningHint(threadId, eventText, now);

    if (this.sendMode === "official_persistence") {
      return this.sendThroughOfficialPersistence(
        threadId,
        payload.clientMessageId,
        this.buildOfficialPersistenceInput({
          files: storedFiles,
          text: payload.text
        }),
        {
          doneText: "已通过官方持久化发送文件",
          failureTitle: "官方持久化发送文件失败"
        }
      );
    }

    return this.enqueueDesktopSend(
      {
        kind: "files",
        threadId,
        clientMessageId: payload.clientMessageId
      },
      async () => {
      await this.observeMobileSubmittedThread(threadId, payload.clientMessageId, new Date().toISOString());
      try {
        const sendResponse = this.normalizeDesktopSendResponse(
          await this.fileSendCoordinator.sendMany(request),
          threadId,
          payload.clientMessageId
        );
        const timestamp = new Date().toISOString();
        const doneText =
          sendResponse.warning === "desktop_send_confirmation_timeout"
            ? "文件已发送，正在等待确认"
            : "已从手机端发送文件";
        this.storeEvent(
          makeThreadEvent(
            threadId,
            "turn_started",
            doneText,
            timestamp,
            `${payload.clientMessageId}:file-desktop`
          )
        );
        return sendResponse;
      } catch (error) {
        this.stopThreadObservation(threadId, payload.clientMessageId, {
          reason: "send_failed",
          error: errorMessage(error)
        });
        this.recordBackgroundSendFailure(threadId, payload.clientMessageId, error);
        throw error;
      }
      }
    );
  }

  async sendAttachmentMessages(
    threadId: string,
    payload: SendAttachmentMessagesPayload
  ): Promise<SendMessageResponse> {
    if (payload.images.length === 0 && payload.files.length === 0) {
      throw new GatewayHttpError(400, {
        error: "attachment_upload_invalid",
        message: "缺少附件"
      });
    }

    const threadForSend = await this.resolveThreadForSend(threadId);
    const [storedImages, storedFiles] = await Promise.all([
      Promise.all(
        payload.images.map((image) =>
          this.uploadStore.persistUploadedFile({
            threadId,
            tempFilePath: image.tempFilePath,
            originalName: image.originalFileName,
            mimeType: image.mimeType
          })
        )
      ),
      Promise.all(
        payload.files.map((file) =>
          this.uploadStore.persistUploadedFile({
            threadId,
            tempFilePath: file.tempFilePath,
            originalName: file.originalFileName,
            mimeType: file.mimeType
          })
        )
      )
    ]);

    const request = {
      threadId,
      title: threadForSend?.title ?? threadId,
      clientMessageId: payload.clientMessageId,
      localFilePaths: [
        ...storedImages.map((stored) => stored.absolutePath),
        ...storedFiles.map((stored) => stored.absolutePath)
      ],
      text: payload.text
    };
    const now = new Date().toISOString();
    const eventText = "已提交附件到桌面发送";
    const messages = [
      ...storedImages.map((stored, index) =>
        makeThreadImageMessage(
          threadId,
          "user",
          now,
          `${payload.clientMessageId}:image-${index}`,
          {
            text: index === 0 ? payload.text : undefined,
            fileName: stored.fileName,
            imageUrl: this.uploadStore.makeRelativeUrl(threadId, stored.fileName)
          }
        )
      ),
      ...storedFiles.map((stored, index) =>
        makeThreadFileMessage(
          threadId,
          "user",
          now,
          `${payload.clientMessageId}:file-${index}`,
          {
            fileName: stored.fileName,
            fileUrl: this.uploadStore.makeRelativeUrl(threadId, stored.fileName),
            mimeType: stored.mimeType
          }
        )
      )
    ];
    if (storedImages.length === 0 && payload.text?.trim()) {
      messages.push(
        makeThreadTextMessage(
          threadId,
          "user",
          payload.text,
          now,
          `${payload.clientMessageId}:text`
        )
      );
    }
    this.threadMessages.set(
      threadId,
      messages.reduce(
        (items, message) => appendCapped(items, message, MAX_MESSAGES),
        this.threadMessages.get(threadId) ?? []
      )
    );
    this.storeEvent(
      makeThreadEvent(
        threadId,
        "turn_started",
        eventText,
        now,
        `${payload.clientMessageId}:attachment-start`,
        "running"
      )
    );
    this.storeOptimisticRunningHint(threadId, eventText, now);

    if (this.sendMode === "official_persistence") {
      return this.sendThroughOfficialPersistence(
        threadId,
        payload.clientMessageId,
        this.buildOfficialPersistenceInput({
          localImagePaths: storedImages.map((stored) => stored.absolutePath),
          files: storedFiles,
          text: payload.text
        }),
        {
          doneText: "已通过官方持久化发送附件",
          failureTitle: "官方持久化发送附件失败"
        }
      );
    }

    return this.enqueueDesktopSend(
      {
        kind: "attachments",
        threadId,
        clientMessageId: payload.clientMessageId
      },
      async () => {
      await this.observeMobileSubmittedThread(threadId, payload.clientMessageId, new Date().toISOString());
      try {
        const sendResponse = this.normalizeDesktopSendResponse(
          await this.fileSendCoordinator.sendMany(request),
          threadId,
          payload.clientMessageId
        );
        const timestamp = new Date().toISOString();
        const doneText =
          sendResponse.warning === "desktop_send_confirmation_timeout"
            ? "附件已发送，正在等待确认"
            : "已从手机端发送附件";
        this.storeEvent(
          makeThreadEvent(
            threadId,
            "turn_started",
            doneText,
            timestamp,
            `${payload.clientMessageId}:attachment-desktop`
          )
        );
        return sendResponse;
      } catch (error) {
        this.stopThreadObservation(threadId, payload.clientMessageId, {
          reason: "send_failed",
          error: errorMessage(error)
        });
        this.recordBackgroundSendFailure(threadId, payload.clientMessageId, error);
        throw error;
      }
      }
    );
  }

  private async resolveThreadForSend(
    threadId: string,
    options: { allowRunning?: boolean } = {}
  ): Promise<ThreadListItem | undefined> {
    const cachedThread = this.getCachedThreadList()?.find((item) => item.threadId === threadId);
    if (!cachedThread) {
      return undefined;
    }

    if (cachedThread.status === "offline") {
      throw makeSendUnavailableErrorFromStatus(cachedThread.status);
    }

    if (cachedThread.status !== "running") {
      return cachedThread;
    }

    const revalidatedThread = await this.readThreadForSend(threadId);
    if (revalidatedThread) {
      const recentEvents = mergeRecentEvents(
        this.buildThreadEvents(revalidatedThread, revalidatedThread.turns.slice(-5)).slice(-MAX_EVENTS),
        this.threadEvents.get(threadId) ?? []
      );
      this.threadEvents.set(threadId, recentEvents);
      const revalidatedItem = resolveDetailThread(cachedThread, revalidatedThread, recentEvents).thread;
      this.rememberResolvedDetailThread(revalidatedItem);
      if (!["running", "offline"].includes(revalidatedItem.status)) {
        return revalidatedItem;
      }
      if (revalidatedItem.status === "running" && options.allowRunning === true) {
        return revalidatedItem;
      }
      throw makeSendUnavailableErrorFromStatus(revalidatedItem.status);
    }

    return cachedThread;
  }

  async getUploadFile(
    threadId: string,
    fileName: string
  ): Promise<{ absolutePath: string; mimeType: string }> {
    try {
      const file = await this.uploadStore.resolveStoredFile(threadId, fileName);
      return {
        absolutePath: file.absolutePath,
        mimeType: file.mimeType
      };
    } catch (error) {
      if (!isUploadFileNotFoundError(error)) {
        throw error;
      }

      const metadataRecords = await this.stateRepository.getThreadMetadata([threadId]);
      const fallbackFile = await findRolloutLocalImageFile(metadataRecords[0]?.rolloutPath, fileName);
      if (fallbackFile) {
        return fallbackFile;
      }

      throw error;
    }
  }

  private enqueueDesktopSend<T>(
    metadata: {
      kind: DesktopSendJobKind;
      threadId: string;
      clientMessageId: string;
    },
    operation: () => Promise<T>
  ): Promise<T> {
    const queuedJob: DesktopSendJobDiagnostics = {
      id: ++this.desktopSendJobSequence,
      kind: metadata.kind,
      threadId: metadata.threadId,
      clientMessageId: metadata.clientMessageId,
      status: "queued",
      queuedAt: new Date().toISOString()
    };
    this.queuedDesktopSendJobs.set(queuedJob.id, queuedJob);
    this.rememberDesktopSendJob(queuedJob);

    const runJob = async () => {
      this.queuedDesktopSendJobs.delete(queuedJob.id);
      const activeJob: DesktopSendJobDiagnostics = {
        ...queuedJob,
        status: "running",
        startedAt: new Date().toISOString()
      };
      this.activeDesktopSendJob = activeJob;
      this.rememberDesktopSendJob(activeJob);
      try {
        const result = await operation();
        this.lastFinishedDesktopSendJob = {
          ...activeJob,
          status: "completed",
          finishedAt: new Date().toISOString()
        };
        this.rememberDesktopSendJob(this.lastFinishedDesktopSendJob);
        return result;
      } catch (error) {
        const failedJob: DesktopSendJobDiagnostics = {
          ...activeJob,
          status: "failed",
          finishedAt: new Date().toISOString(),
          error: errorMessage(error)
        };
        this.lastFinishedDesktopSendJob = failedJob;
        this.lastFailedDesktopSendJob = failedJob;
        this.rememberDesktopSendJob(failedJob);
        throw error;
      } finally {
        if (this.activeDesktopSendJob?.id === queuedJob.id) {
          this.activeDesktopSendJob = null;
        }
      }
    };

    const run = this.desktopSendQueue.then(runJob, runJob);
    this.desktopSendQueue = run.catch(() => undefined);
    return run;
  }

  private rememberDesktopSendJob(job: DesktopSendJobDiagnostics) {
    this.recentDesktopSendJobs = [
      ...this.recentDesktopSendJobs,
      copyDesktopSendJob(job)
    ].slice(-MAX_DESKTOP_SEND_DIAGNOSTICS);
  }

  private normalizeDesktopSendResponse(
    response: SendMessageResponse,
    threadId: string,
    clientMessageId: string
  ): SendMessageResponse {
    return {
      ...response,
      threadId,
      clientMessageId,
      sendPath: "desktop_bridge"
    };
  }

  private buildOfficialPersistenceInput(options: {
    text?: string;
    localImagePaths?: string[];
    files?: Array<Pick<StoredUploadFile, "absolutePath" | "fileName">>;
  }): CodexUserInput[] {
    const visibleText = buildOfficialPersistenceVisibleText(options.text, options.files ?? []);
    const input: CodexUserInput[] = [
      ...(options.localImagePaths ?? []).map((localImagePath): CodexUserInput => ({
        type: "localImage",
        path: localImagePath
      })),
      ...(options.files ?? []).map((file): CodexUserInput => ({
        type: "mention",
        name: file.fileName,
        path: file.absolutePath
      }))
    ];
    if (visibleText) {
      input.push({
        type: "text",
        text: visibleText,
        text_elements: []
      });
    }
    return input;
  }

  private async sendThroughOfficialPersistence(
    threadId: string,
    clientMessageId: string,
    input: CodexUserInput[],
    labels: { doneText: string; failureTitle: string }
  ): Promise<SendMessageResponse> {
    await this.observeMobileSubmittedThread(threadId, clientMessageId, new Date().toISOString());
    try {
      await this.appServer.resumeThread(threadId);
      const turn = await this.appServer.startTurnWithInput(threadId, input);
      const timestamp = new Date().toISOString();
      this.storeEvent(
        makeThreadEvent(
          threadId,
          "turn_started",
          labels.doneText,
          timestamp,
          `${clientMessageId}:app-server`,
          "running"
        )
      );
      return {
        accepted: turn.accepted,
        threadId,
        clientMessageId,
        sendPath: "app_server",
        confirmation: "observed"
      };
    } catch (error) {
      this.stopThreadObservation(threadId, clientMessageId, {
        reason: "send_failed",
        error: errorMessage(error)
      });
      this.recordSendFailure(threadId, clientMessageId, error, {
        title: labels.failureTitle,
        prefix: labels.failureTitle
      });
      throw error;
    }
  }

  private async steerThroughOfficialPersistence(
    threadId: string,
    clientMessageId: string,
    input: CodexUserInput[],
    expectedTurnId: string,
    labels: { doneText: string; failureTitle: string }
  ): Promise<SendMessageResponse> {
    try {
      await this.appServer.resumeThread(threadId);
      const turn = await this.appServer.steerTurnWithInput(threadId, input, expectedTurnId);
      const timestamp = new Date().toISOString();
      this.storeEvent(
        makeThreadEvent(
          threadId,
          "message",
          labels.doneText,
          timestamp,
          `${clientMessageId}:app-server-steer`
        )
      );
      return {
        accepted: turn.accepted,
        threadId,
        clientMessageId,
        sendPath: "app_server",
        confirmation: "observed"
      };
    } catch (error) {
      this.recordSendFailure(threadId, clientMessageId, error, {
        title: labels.failureTitle,
        prefix: labels.failureTitle
      });
      throw error;
    }
  }

  private async readActiveTurnIdForSteer(threadId: string): Promise<string> {
    const thread = await this.readThreadForSend(threadId);
    const activeTurn = [...(thread?.turns ?? [])].reverse().find((turn) => turn.status === "inProgress");
    if (!activeTurn) {
      throw new GatewayHttpError(409, {
        error: "send_unavailable",
        reason: "not_running",
        message: "线程当前没有可引导的运行中任务"
      });
    }
    return activeTurn.id;
  }

  private async enqueueGatewayQueuedTextMessage(
    threadId: string,
    payload: SendMessageRequest
  ): Promise<SendMessageResponse> {
    await this.ensureGatewayQueueLoaded();
    const existing = this.gatewayQueuedTextMessages.find((message) =>
      message.threadId === threadId &&
      message.clientMessageId === payload.clientMessageId &&
      message.status !== "CANCELLED" &&
      message.status !== "SENT"
    );
    if (existing) {
      return this.gatewayQueueAck(threadId, payload.clientMessageId, existing.queueId);
    }

    const now = new Date();
    const message: GatewayQueuedTextMessage = {
      queueId: payload.clientMessageId,
      threadId,
      text: payload.text,
      clientMessageId: payload.clientMessageId,
      queuedAt: now.toISOString(),
      queuedAtMillis: now.getTime(),
      status: "PENDING"
    };
    this.gatewayQueuedTextMessages.push(message);
    await this.persistGatewayQueue();
    this.storeEvent(
      makeThreadEvent(
        threadId,
        "message",
        "已加入 Gateway 排队",
        message.queuedAt,
        `${payload.clientMessageId}:gateway-queue`
      )
    );
    this.scheduleGatewayQueueDispatch(0);
    return this.gatewayQueueAck(threadId, payload.clientMessageId, message.queueId);
  }

  private gatewayQueueAck(
    threadId: string,
    clientMessageId: string,
    queueId: string
  ): SendMessageResponse {
    return {
      accepted: true,
      threadId,
      clientMessageId,
      sendPath: "app_server",
      confirmation: "observed",
      queueId,
      queued: true
    };
  }

  private async ensureGatewayQueueLoaded(): Promise<void> {
    if (this.gatewayQueueLoadPromise) {
      return this.gatewayQueueLoadPromise;
    }
    this.gatewayQueueLoadPromise = this.queueStore.load().then((messages) => {
      this.gatewayQueuedTextMessages = messages
        .filter((message) => message.status === "PENDING" || message.status === "FAILED")
        .map((message) => ({ ...message }));
    });
    return this.gatewayQueueLoadPromise;
  }

  private async persistGatewayQueue(): Promise<void> {
    await this.queueStore.save(this.gatewayQueuedTextMessages);
  }

  private visibleGatewayQueuedMessages(threadId: string): QueuedTextMessage[] {
    return this.gatewayQueuedTextMessages
      .filter((message) =>
        message.threadId === threadId &&
        (
          message.status === "PENDING" ||
          message.status === "DISPATCHING" ||
          message.status === "FAILED"
        )
      )
      .map((message) => ({ ...message }));
  }

  private scheduleGatewayQueueDispatch(delayMs = this.queueDispatchIntervalMs): void {
    if (this.gatewayQueueDispatchTimer) {
      return;
    }
    this.gatewayQueueDispatchTimer = setTimeout(() => {
      this.gatewayQueueDispatchTimer = null;
      void this.dispatchGatewayQueuedTextMessages();
    }, delayMs);
    this.gatewayQueueDispatchTimer.unref?.();
  }

  private async dispatchGatewayQueuedTextMessages(): Promise<void> {
    if (this.gatewayQueueDispatchInFlight) {
      this.scheduleGatewayQueueDispatch();
      return;
    }
    this.gatewayQueueDispatchInFlight = true;
    try {
      await this.ensureGatewayQueueLoaded();
      const message = this.gatewayQueuedTextMessages.find((candidate) => candidate.status === "PENDING");
      if (!message) {
        return;
      }
      const sendable = await this.isGatewayQueuedThreadSendable(message.threadId);
      if (!sendable) {
        this.scheduleGatewayQueueDispatch();
        return;
      }

      const dispatchStartedAt = new Date();
      message.status = "DISPATCHING";
      message.dispatchStartedAt = dispatchStartedAt.toISOString();
      message.dispatchStartedAtMillis = dispatchStartedAt.getTime();
      delete message.errorMessage;
      await this.persistGatewayQueue();

      try {
        await this.sendThroughOfficialPersistence(
          message.threadId,
          message.clientMessageId,
          this.buildOfficialPersistenceInput({ text: message.text }),
          {
            doneText: "已从 Gateway 队列发送新消息",
            failureTitle: "Gateway 排队发送失败"
          }
        );
        this.gatewayQueuedTextMessages = this.gatewayQueuedTextMessages.filter((candidate) =>
          candidate.queueId !== message.queueId
        );
        await this.persistGatewayQueue();
      } catch (error) {
        const current = this.gatewayQueuedTextMessages.find((candidate) => candidate.queueId === message.queueId);
        if (current) {
          current.status = "FAILED";
          current.errorMessage = errorMessage(error);
          delete current.dispatchStartedAt;
          delete current.dispatchStartedAtMillis;
          await this.persistGatewayQueue();
        }
      }
      if (this.gatewayQueuedTextMessages.some((candidate) => candidate.status === "PENDING")) {
        this.scheduleGatewayQueueDispatch(0);
      }
    } finally {
      this.gatewayQueueDispatchInFlight = false;
    }
  }

  private async isGatewayQueuedThreadSendable(threadId: string): Promise<boolean> {
    const thread = await this.readThreadForSend(threadId);
    if (!thread) {
      return false;
    }
    if (thread.status.type === "active" && !isActiveWaitingForInput(thread.status)) {
      return false;
    }
    return thread.turns.at(-1)?.status !== "inProgress";
  }

  private recordBackgroundSendFailure(
    threadId: string,
    clientMessageId: string,
    error: unknown
  ): void {
    this.recordSendFailure(threadId, clientMessageId, error, {
      title: "桌面发送失败",
      prefix: "桌面发送失败"
    });
  }

  private recordSendFailure(
    threadId: string,
    clientMessageId: string,
    error: unknown,
    labels: { title: string; prefix: string }
  ): void {
    const timestamp = new Date().toISOString();
    const message = errorMessage(error);
    const event = makeThreadEvent(
      threadId,
      "error",
      `${labels.prefix}: ${message}`,
      timestamp,
      `${clientMessageId}:desktop-error`,
      "error"
    );
    this.storeEvent(event);
    this.storeStableHint(threadId, {
      ...this.threadHints.get(threadId),
      status: "error",
      progressSummary: event.text,
      updatedAt: timestamp
    });
    this.storeAlert({
      alertId: event.eventId,
      threadId,
      trigger: "error",
      title: labels.title,
      body: event.text,
      timestamp
    });
  }

  private async observeMobileSubmittedThread(
    threadId: string,
    clientMessageId: string,
    submittedAt: string
  ): Promise<void> {
    this.stopThreadObservation(threadId, undefined, { reason: "replaced" });
    const observation: ThreadObservation = {
      clientMessageId,
      submittedAtMs: parseIsoTimestamp(submittedAt),
      deadlineMs: Date.now() + THREAD_OBSERVATION_MAX_DURATION_MS,
      timer: null,
      inFlight: false
    };
    await this.attachRolloutObservationBaseline(threadId, observation);
    this.threadObservations.set(threadId, observation);
    this.scheduleObservedThreadPoll(threadId);
  }

  private async attachRolloutObservationBaseline(
    threadId: string,
    observation: ThreadObservation
  ): Promise<void> {
    try {
      const metadata = (await this.stateRepository.getThreadMetadata([threadId]))[0];
      if (!metadata?.rolloutPath) {
        return;
      }

      const stat = await fs.stat(metadata.rolloutPath);
      if (!stat.isFile()) {
        return;
      }

      observation.rolloutPath = metadata.rolloutPath;
      observation.rolloutOffset = stat.size;
    } catch {
      return;
    }
  }

  private stopThreadObservation(
    threadId: string,
    clientMessageId?: string,
    stop?: StopThreadObservationOptions
  ): void {
    const observation = this.threadObservations.get(threadId);
    if (!observation) {
      return;
    }
    if (clientMessageId && observation.clientMessageId !== clientMessageId) {
      return;
    }

    if (observation.timer) {
      clearTimeout(observation.timer);
    }
    if (stop) {
      this.lastStoppedThreadObservation = copyStoppedThreadObservation(
        threadId,
        observation,
        Date.now(),
        stop
      );
    }
    this.threadObservations.delete(threadId);
  }

  private scheduleObservedThreadPoll(threadId: string): void {
    const observation = this.threadObservations.get(threadId);
    if (!observation) {
      return;
    }
    if (Date.now() >= observation.deadlineMs) {
      this.stopThreadObservation(threadId, observation.clientMessageId, { reason: "timeout" });
      return;
    }

    observation.timer = setTimeout(() => {
      const current = this.threadObservations.get(threadId);
      if (current) {
        current.timer = null;
      }
      void this.pollObservedThread(threadId);
    }, THREAD_OBSERVATION_POLL_INTERVAL_MS);
    observation.timer.unref?.();
  }

  private async pollObservedThread(threadId: string): Promise<void> {
    const observation = this.threadObservations.get(threadId);
    if (!observation || observation.inFlight) {
      return;
    }

    observation.inFlight = true;
    try {
      const rolloutResult = await this.makeObservedStatusEventFromRollout(threadId, observation);
      if (this.threadObservations.get(threadId) !== observation) {
        return;
      }
      if (rolloutResult) {
        const settled = this.reconcileObservedEvent(
          rolloutResult.event,
          rolloutResult.sourceId,
          rolloutResult.alertBody
        );
        if (settled) {
          this.stopThreadObservation(threadId, observation.clientMessageId, {
            reason: rolloutResult.stopReason ?? "settled",
            ...(rolloutResult.event.status ? { finalStatus: rolloutResult.event.status } : {})
          });
        } else {
          this.scheduleObservedThreadPoll(threadId);
        }
        return;
      }
      if (observation.rolloutPath) {
        this.scheduleObservedThreadPoll(threadId);
        return;
      }

      const thread = await this.appServer.readThread(threadId);
      if (this.threadObservations.get(threadId) !== observation) {
        return;
      }
      const settled = this.reconcileObservedThread(thread, observation);
      if (settled) {
        this.stopThreadObservation(threadId, observation.clientMessageId, { reason: "settled" });
      } else {
        this.scheduleObservedThreadPoll(threadId);
      }
    } catch {
      if (this.threadObservations.get(threadId) === observation) {
        this.scheduleObservedThreadPoll(threadId);
      }
    } finally {
      const current = this.threadObservations.get(threadId);
      if (current === observation) {
        current.inFlight = false;
      }
    }
  }

  private async makeObservedStatusEventFromRollout(
    threadId: string,
    observation: ThreadObservation
  ): Promise<ObservedStatusResult | null> {
    if (!observation.rolloutPath) {
      return null;
    }

    const read = await readRolloutObservationSignals(
      observation.rolloutPath,
      observation.rolloutOffset
    );
    if (!read) {
      observation.rolloutPath = undefined;
      observation.rolloutOffset = undefined;
      return null;
    }

    observation.rolloutOffset = read.nextOffset;
    const newMessages = read.messages
      .map((message) => ({ ...message, threadId }))
      .filter((message) => parseIsoTimestamp(message.timestamp) >= observation.submittedAtMs);
    if (newMessages.length > 0) {
      this.threadMessages.set(
        threadId,
        mergeRecentMessages(newMessages, this.threadMessages.get(threadId) ?? [])
      );
      this.storeMessagesAppended(
        threadId,
        newMessages,
        newMessages.at(-1)?.timestamp ?? new Date().toISOString()
      );
    }

    let latestSignal: RolloutObservationSignal | null = null;
    for (const signal of read.signals) {
      if (parseIsoTimestamp(signal.timestamp) < observation.submittedAtMs) {
        continue;
      }

      if (signal.status === "running" && signal.turnId) {
        if (!observation.rolloutObservedTurnId) {
          observation.rolloutObservedTurnId = signal.turnId;
          observation.rolloutObservedStartedAt = signal.timestamp;
        } else if (signal.turnId !== observation.rolloutObservedTurnId) {
          const sourceId = observation.rolloutObservedTurnId;
          return {
            sourceId,
            stopReason: "replaced",
            event: makeThreadEvent(
              threadId,
              "error",
              "上一轮未收到结束事件，已被新任务替换",
              signal.timestamp,
              `${sourceId}:replaced`,
              "error"
            )
          };
        }
        latestSignal = signal;
        continue;
      }

      if (
        signal.turnId &&
        observation.rolloutObservedTurnId &&
        signal.turnId !== observation.rolloutObservedTurnId
      ) {
        continue;
      }

      if (signal.turnId && !observation.rolloutObservedTurnId) {
        observation.rolloutObservedTurnId = signal.turnId;
      }
      latestSignal = signal;
    }

    if (!latestSignal) {
      return null;
    }

    const sourceId =
      latestSignal.turnId ?? observation.rolloutObservedTurnId ?? latestSignal.sourceId;
    const alertBody =
      latestSignal.status === "completed"
        ? completionAlertBody(
            processingDurationBetweenLabels(observation.rolloutObservedStartedAt, latestSignal.timestamp)
          )
        : undefined;
    return {
      sourceId,
      alertBody,
      event: makeThreadEvent(
        threadId,
        latestSignal.kind,
        latestSignal.text,
        latestSignal.timestamp,
        `${sourceId}:${latestSignal.status}`,
        latestSignal.status
      )
    };
  }

  private reconcileObservedThread(
    thread: CodexThread,
    observation: ThreadObservation
  ): boolean {
    const event = this.makeObservedStatusEvent(thread, observation);
    if (!event) {
      return false;
    }

    const latestTurn = thread.turns.at(-1);
    const alertBody =
      event.status === "completed" && latestTurn
        ? completionAlertBody(turnProcessingDurationLabel(latestTurn))
        : undefined;
    return this.reconcileObservedEvent(event, this.observedAlertSourceId(thread), alertBody);
  }

  private reconcileObservedEvent(event: ThreadEvent, sourceId: string, alertBody?: string): boolean {
    this.storeStableHint(event.threadId, {
      ...this.threadHints.get(event.threadId),
      status: event.status,
      progressSummary: event.text,
      updatedAt: event.timestamp
    });
    this.storeEvent(event);

    if (event.status === "completed" || event.status === "error" || event.status === "waiting_input") {
      this.storeAlert({
        alertId: this.stableAlertId(event.threadId, event.status, sourceId),
        threadId: event.threadId,
        trigger: event.status,
        title: event.text,
        body: alertBody ?? event.text,
        timestamp: event.timestamp
      });
      return true;
    }

    return false;
  }

  private makeObservedStatusEvent(
    thread: CodexThread,
    observation: ThreadObservation
  ): ThreadEvent | null {
    const latestTurn = thread.turns.at(-1);
    if (latestTurn && !this.isTurnRelevantToObservation(latestTurn, observation)) {
      observation.baselineTurnId = observation.baselineTurnId ?? latestTurn.id;
      return null;
    }

    if (thread.status.type === "active" && isActiveWaitingForInput(thread.status)) {
      const timestamp = toIso(latestTurn?.startedAt ?? thread.updatedAt);
      return makeThreadEvent(
        thread.id,
        "status_changed",
        statusText("waiting_input"),
        timestamp,
        `${thread.id}:waiting_input:${latestTurn?.id ?? "status"}`,
        "waiting_input"
      );
    }

    if (!latestTurn) {
      return null;
    }

    const timestamp = toIso(latestTurn.completedAt ?? latestTurn.startedAt ?? thread.updatedAt);
    if (latestTurn.status === "completed") {
      return makeThreadEvent(
        thread.id,
        "turn_completed",
        "本轮已完成",
        timestamp,
        `${latestTurn.id}:completed`,
        "completed"
      );
    }

    if (latestTurn.status === "failed") {
      return makeThreadEvent(
        thread.id,
        "error",
        latestTurn.error?.message ?? "线程执行失败",
        timestamp,
        `${latestTurn.id}:failed`,
        "error"
      );
    }

    if (latestTurn.status === "inProgress") {
      return makeThreadEvent(
        thread.id,
        "turn_started",
        "开始处理新的输入",
        timestamp,
        `${latestTurn.id}:started`,
        "running"
      );
    }

    return null;
  }

  private isTurnRelevantToObservation(
    turn: CodexTurn,
    observation: ThreadObservation
  ): boolean {
    if (observation.baselineTurnId && turn.id !== observation.baselineTurnId) {
      return true;
    }

    const turnTimestampMs =
      toMillis(turn.completedAt) ?? toMillis(turn.startedAt) ?? observation.submittedAtMs;
    return turnTimestampMs >= observation.submittedAtMs;
  }

  private observedAlertSourceId(thread: CodexThread): string {
    return thread.turns.at(-1)?.id ?? "status";
  }

  async getAlerts(cursor?: string | null): Promise<AlertsResponse> {
    if (!cursor) {
      return {
        alerts: this.alerts,
        nextCursor: this.alerts.at(-1)?.alertId ?? null
      };
    }

    const index = this.alerts.findIndex((alert) => alert.alertId === cursor);
    const sliced = index >= 0 ? this.alerts.slice(index + 1) : this.alerts;
    return {
      alerts: sliced,
      nextCursor: sliced.at(-1)?.alertId ?? cursor
    };
  }

  subscribe(listener: (event: GatewayEvent) => void): () => void {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }

  handleNotification(notification: CodexNotification): void {
    if (notification.method === "thread/status/changed") {
      const mappedStatus = mapStatusFromCodex(notification.params.status);
      const event = makeThreadEvent(
        notification.params.threadId,
        "status_changed",
        statusText(mappedStatus),
        new Date().toISOString(),
        `${notification.params.threadId}:status:${mappedStatus}`,
        mappedStatus
      );
      this.storeStableHint(notification.params.threadId, {
        ...this.threadHints.get(notification.params.threadId),
        status: mappedStatus,
        progressSummary: event.text,
        updatedAt: event.timestamp
      });
      this.storeEvent(event);
      if (mappedStatus === "waiting_input" || mappedStatus === "error") {
        this.storeAlert({
          alertId: event.eventId,
          threadId: notification.params.threadId,
          trigger: mappedStatus,
          title: event.text,
          body: event.text,
          timestamp: event.timestamp
        });
      }
      return;
    }

    if (notification.method === "turn/started") {
      const timestamp = toIso(notification.params.turn.startedAt);
      const event = makeThreadEvent(
        notification.params.threadId,
        "turn_started",
        "开始处理新的输入",
        timestamp,
        `${notification.params.turn.id}:started`,
        "running"
      );
      this.storeStableHint(notification.params.threadId, {
        ...this.threadHints.get(notification.params.threadId),
        status: "running",
        progressSummary: event.text,
        updatedAt: timestamp
      });
      this.storeEvent(event);
      return;
    }

    if (notification.method === "turn/completed") {
      const timestamp = toIso(notification.params.turn.completedAt);
      const status =
        notification.params.turn.status === "failed"
          ? "error"
          : notification.params.turn.status === "completed"
            ? "completed"
            : "idle";
      const event = makeThreadEvent(
        notification.params.threadId,
        status === "error" ? "error" : "turn_completed",
        status === "error"
          ? notification.params.turn.error?.message ?? "线程执行失败"
          : "本轮已完成",
        timestamp,
        `${notification.params.turn.id}:${status}`,
        status
      );
      this.storeStableHint(notification.params.threadId, {
        ...this.threadHints.get(notification.params.threadId),
        status,
        progressSummary: event.text,
        updatedAt: timestamp
      });
      this.storeEvent(event);
      if (status === "completed" || status === "error") {
        const body =
          status === "completed"
            ? completionAlertBody(turnProcessingDurationLabel(notification.params.turn))
            : event.text;
        this.storeAlert({
          alertId: this.stableAlertId(notification.params.threadId, status, notification.params.turn.id),
          threadId: notification.params.threadId,
          trigger: status,
          title: event.text,
          body,
          timestamp
        });
      }
      void this.catchUpThreadMessagesFromRollout(notification.params.threadId);
      return;
    }

    if (notification.method === "item/plan/delta") {
      const timestamp = new Date().toISOString();
      const event = makeThreadEvent(
        notification.params.threadId,
        "plan",
        notification.params.delta,
        timestamp,
        notification.params.itemId
      );
      this.storeStableHint(notification.params.threadId, {
        ...this.threadHints.get(notification.params.threadId),
        progressSummary: notification.params.delta,
        updatedAt: timestamp
      });
      this.storeEvent(event);
      return;
    }

    if (notification.method === "item/agentMessage/delta") {
      void this.catchUpThreadMessagesFromRollout(notification.params.threadId);
    }
  }

  private async catchUpThreadMessagesFromRollout(threadId: string): Promise<void> {
    try {
      const metadata = (await this.stateRepository.getThreadMetadata([threadId]))[0];
      if (!metadata?.rolloutPath || !isDesktopVisibleMetadata(metadata)) {
        return;
      }

      const rolloutMessages = await readPreviewMessagesFromRollout(metadata.rolloutPath, threadId);
      if (rolloutMessages.length === 0) {
        return;
      }

      const cachedMessages = this.threadMessages.get(threadId) ?? [];
      const cachedMessageIds = new Set(cachedMessages.map((message) => message.messageId));
      const newMessages = rolloutMessages.filter((message) => !cachedMessageIds.has(message.messageId));
      this.threadMessages.set(threadId, mergeRecentMessages(rolloutMessages, cachedMessages));
      this.storeMessagesAppended(
        threadId,
        newMessages,
        newMessages.at(-1)?.timestamp ?? new Date().toISOString()
      );
    } catch {
      return;
    }
  }

  private startSignalPolling(): void {
    void this.reconcileSignalsWithLogs();
    if (this.signalPollTimer) {
      return;
    }

    this.signalPollTimer = setInterval(() => {
      void this.pollSignalUpdates();
    }, MobileGatewayRuntimeService.SIGNAL_POLL_INTERVAL_MS);
    this.signalPollTimer.unref?.();
  }

  private stopSignalPolling(): void {
    if (!this.signalPollTimer) {
      return;
    }

    clearInterval(this.signalPollTimer);
    this.signalPollTimer = null;
  }

  private async reconcileSignalsWithLogs(): Promise<void> {
    try {
      const currentSignals = await this.logRepository.listRecentSignals([]);
      this.ingestSignals(currentSignals, { emitEvents: true, onlyIfChanged: true });
      const { nextCursor } = await this.logRepository.pollSignals(null);
      this.signalCursor = nextCursor;
    } catch {
      return;
    }
  }

  private async pollSignalUpdates(): Promise<void> {
    if (this.signalPollInFlight) {
      return;
    }

    this.signalPollInFlight = true;
    try {
      const { signals, nextCursor } = await this.logRepository.pollSignals(this.signalCursor);
      this.signalCursor = nextCursor ?? this.signalCursor;
      this.ingestSignals(signals, { emitEvents: true, onlyIfChanged: true });
    } catch {
      return;
    } finally {
      this.signalPollInFlight = false;
    }
  }

  private ingestSignals(signals: LogSignal[], options: IngestSignalsOptions = {}): void {
    for (const signal of signals) {
      if (!shouldUseLogSignalForStatus(signal)) {
        continue;
      }

      const previousHint = this.getResolvedHint(signal.threadId);
      const changed =
        previousHint?.status !== signal.status ||
        previousHint?.progressSummary !== signal.text ||
        previousHint?.updatedAt !== signal.timestamp;

      this.storeStableHint(signal.threadId, {
        ...previousHint,
        status: signal.status,
        progressSummary: signal.text,
        updatedAt: signal.timestamp
      });

      if (options.emitEvents && (!options.onlyIfChanged || changed)) {
        const kind =
          signal.status === "completed"
            ? "turn_completed"
            : signal.status === "error"
              ? "error"
              : "status_changed";
        this.storeEvent(
          makeThreadEvent(
            signal.threadId,
            kind,
            signal.text,
            signal.timestamp,
            `log:${signal.signalId}`,
            signal.status
          )
        );
      }

      let alertTrigger: AlertTrigger | null = null;
      if (signal.status === "error" || signal.status === "completed") {
        alertTrigger = signal.status;
      } else if (signal.status === "waiting_input" && signal.notificationEligible === true) {
        alertTrigger = signal.status;
      }
      if (alertTrigger) {
        const body =
          alertTrigger === "completed"
            ? completionAlertBody(this.messageProcessingDurationLabel(signal.threadId, signal.timestamp))
            : signal.text;
        this.storeAlert({
          alertId: signal.signalId,
          threadId: signal.threadId,
          trigger: alertTrigger,
          title: statusText(signal.status),
          body,
          timestamp: signal.timestamp
        });
      }
    }
  }

  private storeEvent(event: ThreadEvent): void {
    const existingEvents = this.threadEvents.get(event.threadId) ?? [];
    if (existingEvents.some((item) => item.eventId === event.eventId)) {
      return;
    }
    this.threadEvents.set(
      event.threadId,
      appendCapped(existingEvents, event, MAX_EVENTS)
    );
    this.emit(event);
  }

  private storeAlert(alert: Alert): void {
    const key = `${alert.threadId}:${alert.trigger}:${alert.timestamp}:${alert.body}`;
    if (this.alerts.some((item) => item.alertId === alert.alertId)) {
      return;
    }

    if (this.alertKeys.has(key)) {
      return;
    }

    this.alertKeys.add(key);
    this.lastAlertByThread.set(alert.threadId, alert);
    const next = appendCapped(this.alerts, alert, MAX_ALERTS);
    this.alerts.length = 0;
    this.alerts.push(...next);
    this.emit(alert);
  }

  private storeMessagesAppended(threadId: string, messages: ThreadMessage[], timestamp: string): void {
    const emittedMessageIds = this.emittedMessageIdsByThread.get(threadId) ?? new Set<string>();
    const newMessages = messages.filter((message) => {
      if (emittedMessageIds.has(message.messageId)) {
        return false;
      }
      emittedMessageIds.add(message.messageId);
      return true;
    });
    this.emittedMessageIdsByThread.set(threadId, emittedMessageIds);
    if (newMessages.length === 0) {
      return;
    }

    this.emit({
      type: "thread_messages_appended",
      eventId: `${threadId}:message:${newMessages.map((message) => message.messageId).join(",")}`,
      threadId,
      messages: newMessages,
      timestamp
    });

    const latestAssistantMessage = newMessages
      .filter((message) => message.role === "assistant")
      .sort(compareThreadMessages)
      .at(-1);
    if (!latestAssistantMessage) {
      return;
    }

    this.storeAlert({
      alertId: this.stableAlertId(threadId, "message", latestAssistantMessage.messageId),
      threadId,
      trigger: "message",
      title: "有新消息",
      body: messageAlertBody(
        latestAssistantMessage,
        this.messageProcessingDurationLabel(threadId, latestAssistantMessage.timestamp || timestamp)
      ),
      timestamp: latestAssistantMessage.timestamp || timestamp
    });
  }

  private messageProcessingDurationLabel(threadId: string, messageTimestamp: string): string | null {
    const messageTime = parseIsoTimestamp(messageTimestamp);
    if (!Number.isFinite(messageTime)) {
      return null;
    }

    const startEvent = (this.threadEvents.get(threadId) ?? [])
      .filter((event) => event.kind === "turn_started" && event.status === "running")
      .filter((event) => {
        const startTime = parseIsoTimestamp(event.timestamp);
        return Number.isFinite(startTime) && startTime <= messageTime;
      })
      .sort((left, right) => parseIsoTimestamp(left.timestamp) - parseIsoTimestamp(right.timestamp))
      .at(-1);
    if (!startEvent) {
      return null;
    }

    const durationMs = messageTime - parseIsoTimestamp(startEvent.timestamp);
    if (!Number.isFinite(durationMs) || durationMs < 0) {
      return null;
    }

    return formatProcessingDuration(durationMs);
  }

  private stableAlertId(threadId: string, trigger: Alert["trigger"], sourceId: string): string {
    return `${threadId}:${trigger}:${sourceId}`;
  }

  private emit(event: GatewayEvent): void {
    for (const listener of this.listeners) {
      listener(event);
    }
  }

  private getResolvedHint(threadId: string): RuntimeThreadHint | undefined {
    const hint = this.threadHints.get(threadId);
    if (!hint) {
      return undefined;
    }

    if (
      hint.status === "running" &&
      hint.optimisticUntilMs != null &&
      hint.optimisticUntilMs <= Date.now()
    ) {
      const fallback =
        hint.fallbackStatus || hint.fallbackProgressSummary || hint.fallbackUpdatedAt || hint.fallbackRunningStartedAt
          ? {
              status: hint.fallbackStatus,
              progressSummary: hint.fallbackProgressSummary,
              updatedAt: hint.fallbackUpdatedAt,
              runningStartedAt: hint.fallbackRunningStartedAt
            }
          : undefined;

      if (fallback) {
        this.threadHints.set(threadId, fallback);
        return fallback;
      }

      this.threadHints.delete(threadId);
      return undefined;
    }

    return {
      status: hint.status,
      progressSummary: hint.progressSummary,
      updatedAt: hint.updatedAt,
      runningStartedAt: hint.runningStartedAt
    };
  }

  private storeStableHint(threadId: string, hint: RuntimeThreadHint): void {
    const normalizedHint = normalizeRuntimeThreadHint(hint);
    const displayHint = this.withAutomationSummary(threadId, normalizedHint);
    if (shouldKeepExistingHint(this.threadHints.get(threadId), displayHint)) {
      return;
    }

    this.invalidateThreadListCache();
    this.threadHints.set(threadId, {
      status: displayHint.status,
      progressSummary: displayHint.progressSummary,
      updatedAt: displayHint.updatedAt,
      runningStartedAt: displayHint.runningStartedAt
    });
  }

  private withAutomationSummary(threadId: string, hint: RuntimeThreadHint): RuntimeThreadHint {
    if (
      hint.status === "running" ||
      hint.status === "waiting_input" ||
      hint.status === "error" ||
      !hint.status
    ) {
      return hint;
    }

    const automationSummary = loadActiveAutomationSummaries(this.appServer.getCodexHome()).get(threadId);
    return automationSummary
      ? {
          ...hint,
          progressSummary: automationSummary
        }
      : hint;
  }

  private storeOptimisticRunningHint(
    threadId: string,
    progressSummary: string,
    updatedAt: string
  ): void {
    this.invalidateThreadListCache();
    const fallback = this.getResolvedHint(threadId);
    this.threadHints.set(threadId, {
      status: "running",
      progressSummary,
      updatedAt,
      optimisticUntilMs: Date.now() + OPTIMISTIC_RUNNING_HINT_TTL_MS,
      fallbackStatus: fallback?.status,
      fallbackProgressSummary: fallback?.progressSummary,
      fallbackUpdatedAt: fallback?.updatedAt,
      fallbackRunningStartedAt: fallback?.runningStartedAt,
      runningStartedAt: updatedAt
    });
  }

  private rememberResolvedDetailThread(thread: ThreadListItem): void {
    const displayThread = applyThreadAutomationSummary(
      thread,
      loadActiveAutomationSummaries(this.appServer.getCodexHome())
    );
    if (displayThread.status === "running") {
      this.rememberThreadListItem(displayThread);
      return;
    }

    this.threadHints.set(displayThread.threadId, {
      status: displayThread.status,
      progressSummary: displayThread.progressSummary,
      updatedAt: displayThread.updatedAt
    });
    this.rememberThreadListItem(displayThread);
  }

  private rememberStatusDecision(
    threadId: string,
    context: StatusDecisionDiagnosticsContext,
    statusDecision: ThreadStatusDecision
  ): void {
    this.recentStatusDecisions = appendCapped(
      this.recentStatusDecisions,
      {
        ...statusDecision,
        candidates: statusDecision.candidates.map((candidate) => ({ ...candidate })),
        threadId,
        context,
        observedAt: new Date().toISOString()
      },
      MAX_STATUS_DECISION_DIAGNOSTICS
    );
  }

  private getCachedThreadList(): ThreadListItem[] | null {
    if (!this.threadListCache) {
      return null;
    }

    if (Date.now() - this.threadListCache.createdAtMs > THREAD_LIST_CACHE_TTL_MS) {
      this.threadListCache = null;
      return null;
    }

    return this.threadListCache.items;
  }

  private rememberThreadListItem(thread: ThreadListItem): void {
    const cachedItems = this.getCachedThreadList();
    if (!cachedItems) {
      return;
    }

    const hasExistingThread = cachedItems.some((item) => item.threadId === thread.threadId);
    const items = hasExistingThread
      ? cachedItems.map((item) => (item.threadId === thread.threadId ? thread : item))
      : [thread, ...cachedItems];
    this.threadListCache = { createdAtMs: Date.now(), items };
  }

  private invalidateThreadListCache(): void {
    this.threadListCache = null;
  }
}
