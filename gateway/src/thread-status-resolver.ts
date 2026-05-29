import type {
  MobileThreadStatus,
  ThreadEvent,
  ThreadStatusDecisionSource
} from "../../shared/src/api";
import type {
  CodexThreadStatus,
  LogSignal,
  RuntimeThreadHint
} from "./mobile-gateway-service";

export type DetailStatusCandidate = {
  status: MobileThreadStatus;
  source: ThreadStatusDecisionSource;
  text: string;
  timestamp: string;
  sourceId?: string;
  runningStartedAt?: string;
};

const TERMINAL_STATUS_CONFLICT_WINDOW_MS = 1_000;

export function statusText(status: MobileThreadStatus): string {
  switch (status) {
    case "running":
      return "线程正在运行";
    case "waiting_input":
      return "线程正在等待输入";
    case "error":
      return "线程发生错误";
    case "completed":
      return "线程刚刚完成";
    case "offline":
      return "网关当前离线";
    case "idle":
    default:
      return "线程空闲";
  }
}

export function mapStatusFromCodex(
  threadStatus: CodexThreadStatus,
  hintedStatus?: MobileThreadStatus
): MobileThreadStatus {
  if (threadStatus.type === "systemError") {
    return "error";
  }

  if (threadStatus.type === "active") {
    if (isActiveWaitingForInput(threadStatus)) {
      return "waiting_input";
    }

    if (hintedStatus === "completed" || hintedStatus === "error") {
      return hintedStatus;
    }

    return "running";
  }

  if (hintedStatus && hintedStatus !== "offline") {
    return hintedStatus;
  }

  if (threadStatus.type === "idle") {
    return "idle";
  }

  return hintedStatus ?? "idle";
}

export function isActiveWaitingForInput(threadStatus: CodexThreadStatus): boolean {
  return (
    threadStatus.type === "active" &&
    threadStatus.activeFlags.includes("waitingOnUserInput")
  );
}

export function shouldUseLogSignalForStatus(signal: LogSignal): boolean {
  return signal.status !== "waiting_input" || signal.notificationEligible === true;
}

export function shouldUseLogSignalForDetailStatus(signal: LogSignal): boolean {
  return signal.status === "error" || signal.status === "completed";
}

export function logSignalEventKind(status: MobileThreadStatus): ThreadEvent["kind"] {
  if (status === "error") {
    return "error";
  }

  if (status === "completed") {
    return "turn_completed";
  }

  return "status_changed";
}

export function shouldUseRuntimeHintForThread(
  threadStatus: CodexThreadStatus,
  hint: RuntimeThreadHint | undefined
): boolean {
  if (!hint?.status) {
    return false;
  }

  if (threadStatus.type === "active" && !isActiveWaitingForInput(threadStatus)) {
    if (hint.status === "running") {
      return true;
    }

    return hint.status !== "waiting_input";
  }

  return true;
}

export function detailStatusPriority(status: MobileThreadStatus): number {
  switch (status) {
    case "error":
      return 5;
    case "waiting_input":
      return 4;
    case "completed":
      return 3;
    case "idle":
      return 2;
    case "offline":
      return 1;
    case "running":
    default:
      return 0;
  }
}

export function isNearTerminalStatusConflict(
  leftStatus: MobileThreadStatus,
  leftTimestamp: string | null | undefined,
  rightStatus: MobileThreadStatus,
  rightTimestamp: string | null | undefined
): boolean {
  return (
    isErrorCompletedStatusPair(leftStatus, rightStatus) &&
    Math.abs(parseIsoTimestamp(leftTimestamp) - parseIsoTimestamp(rightTimestamp)) <=
      TERMINAL_STATUS_CONFLICT_WINDOW_MS
  );
}

export function compareDetailStatusCandidates(
  left: DetailStatusCandidate,
  right: DetailStatusCandidate
): number {
  if (
    isNearTerminalStatusConflict(
      left.status,
      left.timestamp,
      right.status,
      right.timestamp
    )
  ) {
    const priorityDifference = detailStatusPriority(right.status) - detailStatusPriority(left.status);
    if (priorityDifference !== 0) {
      return priorityDifference;
    }
  }

  const timestampDifference = parseIsoTimestamp(right.timestamp) - parseIsoTimestamp(left.timestamp);
  if (timestampDifference !== 0) {
    return timestampDifference;
  }

  return detailStatusPriority(right.status) - detailStatusPriority(left.status);
}

export function shouldKeepExistingHint(
  existing: RuntimeThreadHint | undefined,
  next: RuntimeThreadHint
): boolean {
  if (!existing) {
    return false;
  }

  const existingTimestamp = parseIsoTimestamp(existing.updatedAt);
  const nextTimestamp = parseIsoTimestamp(next.updatedAt);
  if (nextTimestamp < existingTimestamp) {
    return true;
  }
  if (
    existing.status &&
    next.status &&
    isNearTerminalStatusConflict(existing.status, existing.updatedAt, next.status, next.updatedAt)
  ) {
    return detailStatusPriority(existing.status) > detailStatusPriority(next.status);
  }
  if (nextTimestamp > existingTimestamp) {
    return false;
  }
  if (existing.status && next.status) {
    return detailStatusPriority(next.status) < detailStatusPriority(existing.status);
  }
  return false;
}

function isErrorCompletedStatusPair(
  leftStatus: MobileThreadStatus,
  rightStatus: MobileThreadStatus
): boolean {
  return (
    (leftStatus === "error" && rightStatus === "completed") ||
    (leftStatus === "completed" && rightStatus === "error")
  );
}

function parseIsoTimestamp(value: string | null | undefined): number {
  if (!value) {
    return Number.NEGATIVE_INFINITY;
  }

  const timestamp = Date.parse(value);
  return Number.isNaN(timestamp) ? Number.NEGATIVE_INFINITY : timestamp;
}
