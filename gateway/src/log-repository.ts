import { DatabaseSync } from "node:sqlite";

import type { MobileThreadStatus } from "../../shared/src/api";
import type { LogRepository, LogSignal } from "./mobile-gateway-service";

type LogRow = {
  id: number;
  ts: number;
  level: string;
  target: string;
  thread_id: string | null;
  feedback_log_body: string | null;
};

type ParsedLogSignal = LogSignal & {
  turnId: string | null;
  sourceKind:
    | "running"
    | "waiting_strong"
    | "waiting_weak"
    | "waiting_follow_up"
    | "completed"
    | "completed_marker"
    | "error";
};

const RESPONSE_COMPLETED_MARKER = ["response", "completed"].join(".");

function shorten(text: string, maxLength = 120): string {
  const normalized = text.replace(/\s+/g, " ").trim();
  return normalized.length <= maxLength ? normalized : `${normalized.slice(0, maxLength - 1)}…`;
}

function extractTurnId(text: string): string | null {
  const patterns = [/turn(?:\.id|_id)=([0-9a-f-]+)/i, /turn id[:=]"?([0-9a-f-]+)/i];
  for (const pattern of patterns) {
    const match = pattern.exec(text);
    if (match?.[1]) {
      return match[1];
    }
  }

  return null;
}

function toPublicSignal(signal: ParsedLogSignal): LogSignal {
  return {
    signalId: signal.signalId,
    threadId: signal.threadId,
    status: signal.status,
    text: signal.text,
    timestamp: signal.timestamp,
    cursor: signal.cursor,
    ...(signal.status === "waiting_input"
      ? { notificationEligible: signal.notificationEligible === true }
      : {})
  };
}

function signalPriority(signal: ParsedLogSignal, groupHasCompleted: boolean): number {
  switch (signal.sourceKind) {
    case "waiting_strong":
      return 500;
    case "waiting_weak":
      return 150;
    case "waiting_follow_up":
      return groupHasCompleted ? 450 : 150;
    case "completed":
      return 400;
    case "error":
      return 600;
    case "completed_marker":
      return 100;
    case "running":
    default:
      return 200;
  }
}

function selectCurrentSignals(signals: ParsedLogSignal[]): LogSignal[] {
  const byThread = new Map<string, ParsedLogSignal[]>();
  for (const signal of signals) {
    const threadSignals = byThread.get(signal.threadId) ?? [];
    threadSignals.push(signal);
    byThread.set(signal.threadId, threadSignals);
  }

  const currentSignals: ParsedLogSignal[] = [];

  for (const threadSignals of byThread.values()) {
    const byTurn = new Map<string, ParsedLogSignal[]>();
    for (const signal of threadSignals) {
      const turnKey = signal.turnId ?? "__no_turn__";
      const turnSignals = byTurn.get(turnKey) ?? [];
      turnSignals.push(signal);
      byTurn.set(turnKey, turnSignals);
    }

    let currentTurnSignals: ParsedLogSignal[] | null = null;
    let currentTurnTimestamp = -1;
    for (const turnSignals of byTurn.values()) {
      const latestTimestamp = Math.max(...turnSignals.map((signal) => Date.parse(signal.timestamp)));
      if (latestTimestamp > currentTurnTimestamp) {
        currentTurnTimestamp = latestTimestamp;
        currentTurnSignals = turnSignals;
      }
    }

    if (!currentTurnSignals) {
      continue;
    }

    const groupHasCompleted = currentTurnSignals.some(
      (signal) => signal.sourceKind === "completed" || signal.sourceKind === "completed_marker"
    );
    const bestSignal = [...currentTurnSignals].sort((left, right) => {
      const priorityDiff = signalPriority(right, groupHasCompleted) - signalPriority(left, groupHasCompleted);
      if (priorityDiff !== 0) {
        return priorityDiff;
      }

      return Date.parse(right.timestamp) - Date.parse(left.timestamp);
    })[0];

    currentSignals.push(bestSignal);
  }

  return currentSignals
    .sort((left, right) => Date.parse(left.timestamp) - Date.parse(right.timestamp))
    .map(toPublicSignal);
}

function isSessionLifecycleLog(body: string): boolean {
  const normalized = body.trimStart().toLowerCase();
  if (!normalized.startsWith("session_loop")) {
    return false;
  }

  return (
    normalized.includes("turn.id=") ||
    normalized.includes("turn_id=") ||
    normalized.includes(":turn{") ||
    normalized.includes("session_task.turn")
  );
}

function isTerminalNoFollowUpLog(lowerBody: string): boolean {
  return (
    lowerBody.includes("model_needs_follow_up=false") &&
    lowerBody.includes("has_pending_input=false") &&
    lowerBody.includes("needs_follow_up=false")
  );
}

function isEmbeddedPayloadLog(lowerTarget: string, lowerBody: string): boolean {
  return (
    lowerTarget.includes("codex_client::transport") ||
    lowerTarget.includes("codex_api::sse") ||
    lowerTarget.includes("codex_otel.log_only") ||
    lowerTarget === "rmcp::service" ||
    lowerTarget === "log" ||
    lowerBody.includes("stream_request:model_client") ||
    lowerBody.includes("post to http") ||
    lowerBody.includes("instructions")
  );
}

function isAssistantOutputTextLog(lowerBody: string): boolean {
  return lowerBody.includes("outputtext") || lowerBody.includes("output_text");
}

function hasWaitingOnUserInputSignal(body: string, lowerBody: string): boolean {
  if (!body.includes("waitingOnUserInput")) {
    return false;
  }

  return (
    lowerBody.includes("active_flags") ||
    lowerBody.includes("activeflags") ||
    lowerBody.includes("thread/status/changed") ||
    lowerBody.includes("status changed")
  );
}

function hasRequestUserInputSignal(lowerBody: string): boolean {
  if (!lowerBody.includes("request_user_input")) {
    return false;
  }

  return (
    lowerBody.includes("tool_name=\"request_user_input\"") ||
    lowerBody.includes("tool_name=request_user_input") ||
    lowerBody.includes("tool call request_user_input") ||
    lowerBody.includes("request_user_input prompt=")
  );
}

function parseLogSignal(row: LogRow): ParsedLogSignal | null {
  if (!row.thread_id) {
    return null;
  }

  const body = row.feedback_log_body ?? "";
  const lowerBody = body.toLowerCase();
  const lowerTarget = row.target.toLowerCase();
  const turnId = extractTurnId(body);
  let status: MobileThreadStatus | null = null;
  let text = "";
  let sourceKind: ParsedLogSignal["sourceKind"] | null = null;
  let notificationEligible = false;
  const allowDirectWaitingSignal =
    !isEmbeddedPayloadLog(lowerTarget, lowerBody) &&
    !isAssistantOutputTextLog(lowerBody);

  if (
    allowDirectWaitingSignal &&
    (hasWaitingOnUserInputSignal(body, lowerBody) || hasRequestUserInputSignal(lowerBody))
  ) {
    status = "waiting_input";
    text = "线程正在等待新的输入";
    sourceKind = "waiting_strong";
    notificationEligible = true;
  } else if (
    lowerBody.includes("response.failed") ||
    lowerTarget.includes("error")
  ) {
    status = "error";
    text = shorten(body || `${row.target} reported an error`);
    sourceKind = "error";
  } else if (
    isTerminalNoFollowUpLog(lowerBody) &&
    isSessionLifecycleLog(body)
  ) {
    status = "completed";
    text = "本轮已完成";
    sourceKind = "completed";
  } else if (
    lowerBody.includes(RESPONSE_COMPLETED_MARKER) &&
    isSessionLifecycleLog(body)
  ) {
    status = "running";
    text = "正在处理新的请求";
    sourceKind = "completed_marker";
  } else if (
    lowerBody.includes("response.in_progress") ||
    lowerBody.includes("response.created") ||
    lowerTarget.includes("session::turn") ||
    lowerTarget.includes("stream_events_utils")
  ) {
    status = "running";
    text = "正在处理新的请求";
    sourceKind = "running";
  }

  if (!status || !sourceKind) {
    return null;
  }

  return {
    signalId: String(row.id),
    threadId: row.thread_id,
    status,
    text,
    timestamp: new Date(row.ts * 1000).toISOString(),
    cursor: String(row.id),
    turnId,
    sourceKind,
    notificationEligible
  };
}

export class SqliteLogRepository implements LogRepository {
  constructor(private readonly dbPath: string) {}

  async listRecentSignals(threadIds: string[]): Promise<LogSignal[]> {
    const signals = await this.querySignals(threadIds, null);
    return selectCurrentSignals(signals);
  }

  async pollSignals(cursor?: string | null): Promise<{ signals: LogSignal[]; nextCursor: string | null }> {
    const signals = (await this.querySignals([], cursor)).map(toPublicSignal);
    return {
      signals,
      nextCursor: signals.at(-1)?.cursor ?? cursor ?? null
    };
  }

  private async querySignals(threadIds: string[], cursor?: string | null): Promise<ParsedLogSignal[]> {
    const db = new DatabaseSync(this.dbPath, {
      open: true,
      readOnly: true
    });

    try {
      const values: Array<string | number> = [];
      const filters = ["thread_id is not null"];

      if (cursor) {
        filters.push("id > ?");
        values.push(Number(cursor));
      }

      if (threadIds.length > 0) {
        filters.push(`thread_id in (${threadIds.map(() => "?").join(",")})`);
        values.push(...threadIds);
      }

      const sql = `
        select id, ts, level, target, thread_id, feedback_log_body
        from logs
        where ${filters.join(" and ")}
        order by id desc
        limit 300
      `;

      const rows = db.prepare(sql).all(...values) as LogRow[];
      const parsedSignals = rows
        .reverse()
        .map(parseLogSignal)
        .filter((signal): signal is ParsedLogSignal => signal !== null);

      const latestByKey = new Map<string, number>();
      const dedupedSignals: ParsedLogSignal[] = [];
      for (const signal of parsedSignals) {
        const key = `${signal.threadId}:${signal.turnId ?? "__no_turn__"}:${signal.status}:${signal.text}:${signal.sourceKind}`;
        const timestamp = Date.parse(signal.timestamp);
        const previousTimestamp = latestByKey.get(key);
        if (previousTimestamp && timestamp - previousTimestamp < 180_000) {
          continue;
        }

        latestByKey.set(key, timestamp);
        dedupedSignals.push(signal);
      }

      return dedupedSignals;
    } finally {
      db.close();
    }
  }
}
