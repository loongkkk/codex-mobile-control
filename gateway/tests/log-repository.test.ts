import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { DatabaseSync } from "node:sqlite";
import { afterEach, describe, expect, it } from "vitest";

import { SqliteLogRepository } from "../src/log-repository";

type FixtureRow = {
  id: number;
  ts: number;
  level: string;
  target: string;
  threadId: string;
  body: string;
};

const tempDirs: string[] = [];
const RESPONSE_COMPLETED_MARKER = ["response", "completed"].join(".");

function createRepository(rows: FixtureRow[]): SqliteLogRepository {
  const dir = mkdtempSync(path.join(tmpdir(), "codex-mobile-logs-"));
  tempDirs.push(dir);
  const dbPath = path.join(dir, "logs.sqlite");
  const db = new DatabaseSync(dbPath);
  db.exec(`
    create table logs (
      id integer primary key,
      ts integer not null,
      level text not null,
      target text not null,
      thread_id text,
      feedback_log_body text
    );
  `);

  const insert = db.prepare(`
    insert into logs (id, ts, level, target, thread_id, feedback_log_body)
    values (?, ?, ?, ?, ?, ?)
  `);

  for (const row of rows) {
    insert.run(row.id, row.ts, row.level, row.target, row.threadId, row.body);
  }

  db.close();
  return new SqliteLogRepository(dbPath);
}

afterEach(() => {
  for (const dir of tempDirs.splice(0)) {
    rmSync(dir, { recursive: true, force: true });
  }
});

describe("SqliteLogRepository", () => {
  it("keeps follow-up turn logs running instead of showing user input wait", async () => {
    const repository = createRepository([
      {
        id: 1,
        ts: 100,
        level: "DEBUG",
        target: "codex_core::stream_events_utils",
        threadId: "thread-1",
        body:
          'session_loop turn.id=turn-1 response.in_progress output item'
      },
      {
        id: 2,
        ts: 101,
        level: "TRACE",
        target: "codex_core::session::turn",
        threadId: "thread-1",
        body:
          'session_loop turn_id=turn-1 model_needs_follow_up=true has_pending_input=false needs_follow_up=true'
      },
      {
        id: 3,
        ts: 102,
        level: "INFO",
        target: "codex_core::stream_events_utils",
        threadId: "thread-1",
        body:
          `session_loop turn.id=turn-1 ${RESPONSE_COMPLETED_MARKER}`
      }
    ]);

    const signals = await repository.listRecentSignals(["thread-1"]);

    expect(signals).toEqual([
      {
        signalId: "1",
        threadId: "thread-1",
        status: "running",
        text: "正在处理新的请求",
        timestamp: "1970-01-01T00:01:40.000Z",
        cursor: "1"
      }
    ]);
  });

  it("only marks direct user-input wait logs as notification eligible", async () => {
    const repository = createRepository([
      {
        id: 1,
        ts: 100,
        level: "INFO",
        target: "codex_core::session::turn",
        threadId: "thread-1",
        body: "status changed active_flags=[waitingOnUserInput]"
      },
      {
        id: 2,
        ts: 101,
        level: "INFO",
        target: "codex_core::session::turn",
        threadId: "thread-2",
        body: "tool call request_user_input prompt=choose"
      }
    ]);

    const { signals } = await repository.pollSignals(null);

    expect(signals).toEqual([
      {
        signalId: "1",
        threadId: "thread-1",
        status: "waiting_input",
        text: "线程正在等待新的输入",
        timestamp: "1970-01-01T00:01:40.000Z",
        cursor: "1",
        notificationEligible: true
      },
      {
        signalId: "2",
        threadId: "thread-2",
        status: "waiting_input",
        text: "线程正在等待新的输入",
        timestamp: "1970-01-01T00:01:41.000Z",
        cursor: "2",
        notificationEligible: true
      }
    ]);
  });

  it("does not expose approval or weak follow-up hints as waiting input", async () => {
    const repository = createRepository([
      {
        id: 1,
        ts: 100,
        level: "INFO",
        target: "codex_core::session::turn",
        threadId: "thread-1",
        body: "status changed active_flags=[waitingOnApproval]"
      },
      {
        id: 2,
        ts: 101,
        level: "TRACE",
        target: "codex_core::session::turn",
        threadId: "thread-2",
        body: "session_loop turn_id=turn-2 pending_input=true"
      },
      {
        id: 3,
        ts: 102,
        level: "TRACE",
        target: "codex_core::session::turn",
        threadId: "thread-3",
        body: "session_loop turn_id=turn-3 model_needs_follow_up=true has_pending_input=false needs_follow_up=true"
      }
    ]);

    const { signals } = await repository.pollSignals(null);

    expect(signals).toEqual([
      {
        signalId: "1",
        threadId: "thread-1",
        status: "running",
        text: "正在处理新的请求",
        timestamp: "1970-01-01T00:01:40.000Z",
        cursor: "1"
      },
      {
        signalId: "2",
        threadId: "thread-2",
        status: "running",
        text: "正在处理新的请求",
        timestamp: "1970-01-01T00:01:41.000Z",
        cursor: "2"
      },
      {
        signalId: "3",
        threadId: "thread-3",
        status: "running",
        text: "正在处理新的请求",
        timestamp: "1970-01-01T00:01:42.000Z",
        cursor: "3"
      }
    ]);
  });

  it("does not treat quoted prompt text as a waiting input signal", async () => {
    const repository = createRepository([
      {
        id: 1,
        ts: 100,
        level: "TRACE",
        target: "codex_client::transport",
        threadId: "thread-1",
        body:
          'responses.stream_with: POST to http://127.0.0.1/v1/responses: {"instructions":"Only waitingOnUserInput and request_user_input should notify."}'
      },
      {
        id: 2,
        ts: 101,
        level: "DEBUG",
        target: "codex_core::stream_events_utils",
        threadId: "thread-1",
        body:
          'handle_output_item_done: Output item item=Message { role: "assistant", content: [OutputText { text: "waitingOnUserInput 是强信号名称" }], phase: Some(Commentary) }'
      }
    ]);

    const { signals } = await repository.pollSignals(null);

    expect(signals).toEqual([
      {
        signalId: "2",
        threadId: "thread-1",
        status: "running",
        text: "正在处理新的请求",
        timestamp: "1970-01-01T00:01:41.000Z",
        cursor: "2"
      }
    ]);
  });

  it("does not treat command output logs as waiting input signals", async () => {
    const repository = createRepository([
      {
        id: 1,
        ts: 100,
        level: "INFO",
        target: "codex_otel.log_only",
        threadId: "thread-1",
        body:
          'shell_command output: src/log-repository.ts:177: lowerBody.includes("active_flags") || body.includes("waitingOnUserInput") || lowerBody.includes("request_user_input")'
      }
    ]);

    const { signals } = await repository.pollSignals(null);

    expect(signals).toEqual([]);
  });

  it("keeps the thread running before a follow-up turn actually completes", async () => {
    const repository = createRepository([
      {
        id: 1,
        ts: 100,
        level: "DEBUG",
        target: "codex_core::stream_events_utils",
        threadId: "thread-1",
        body:
          'session_loop turn.id=turn-1 response.in_progress output item'
      },
      {
        id: 2,
        ts: 101,
        level: "TRACE",
        target: "codex_core::session::turn",
        threadId: "thread-1",
        body:
          'session_loop turn_id=turn-1 model_needs_follow_up=true has_pending_input=false needs_follow_up=true'
      }
    ]);

    const signals = await repository.listRecentSignals(["thread-1"]);

    expect(signals).toEqual([
      {
        signalId: "1",
        threadId: "thread-1",
        status: "running",
        text: "正在处理新的请求",
        timestamp: "1970-01-01T00:01:40.000Z",
        cursor: "1"
      }
    ]);
  });

  it("does not expose response completion markers as completed signals", async () => {
    const repository = createRepository([
      {
        id: 1,
        ts: 100,
        level: "INFO",
        target: "codex_core::stream_events_utils",
        threadId: "thread-1",
        body:
          `session_loop turn.id=turn-1 ${RESPONSE_COMPLETED_MARKER}`
      }
    ]);

    const { signals } = await repository.pollSignals(null);

    expect(signals).toEqual([
      {
        signalId: "1",
        threadId: "thread-1",
        status: "running",
        text: "正在处理新的请求",
        timestamp: "1970-01-01T00:01:40.000Z",
        cursor: "1"
      }
    ]);
  });

  it("ignores response completion text captured from command logs", async () => {
    const repository = createRepository([
      {
        id: 1,
        ts: 100,
        level: "INFO",
        target: "codex_core::spawn",
        threadId: "thread-1",
        body:
          `node -e "lowerBody.includes('${RESPONSE_COMPLETED_MARKER}')"`
      }
    ]);

    const { signals } = await repository.pollSignals(null);

    expect(signals).toEqual([]);
  });

  it("exposes terminal no-follow-up turn logs as completed signals", async () => {
    const repository = createRepository([
      {
        id: 1,
        ts: 100,
        level: "DEBUG",
        target: "codex_core::stream_events_utils",
        threadId: "thread-1",
        body:
          'session_loop{thread_id=thread-1}:turn{otel.name="session_task.turn" thread.id=thread-1 turn.id=turn-1 model=gpt-5.4}: Output item item=Message { role: "assistant", content: [OutputText { text: "OK" }], phase: Some(FinalAnswer) }'
      },
      {
        id: 2,
        ts: 101,
        level: "TRACE",
        target: "codex_core::session::turn",
        threadId: "thread-1",
        body:
          'session_loop{thread_id=thread-1}:turn{otel.name="session_task.turn" thread.id=thread-1 turn.id=turn-1 model=gpt-5.4}:run_turn: post sampling token usage turn_id=turn-1 total_usage_tokens=10 model_needs_follow_up=false has_pending_input=false needs_follow_up=false'
      }
    ]);

    const signals = await repository.listRecentSignals(["thread-1"]);

    expect(signals).toEqual([
      {
        signalId: "2",
        threadId: "thread-1",
        status: "completed",
        text: "本轮已完成",
        timestamp: "1970-01-01T00:01:41.000Z",
        cursor: "2"
      }
    ]);
  });

  it("keeps a failed response over a completed marker in the same turn", async () => {
    const repository = createRepository([
      {
        id: 1,
        ts: 1778178368,
        level: "ERROR",
        target: "codex_core::session::turn",
        threadId: "thread-1",
        body:
          "session_loop turn.id=turn-rate-limited response.failed exceeded retry limit, last status: 429 Too Many Requests"
      },
      {
        id: 2,
        ts: 1778178369,
        level: "TRACE",
        target: "codex_core::session::turn",
        threadId: "thread-1",
        body:
          "session_loop turn.id=turn-rate-limited model_needs_follow_up=false has_pending_input=false needs_follow_up=false"
      }
    ]);

    const signals = await repository.listRecentSignals(["thread-1"]);

    expect(signals).toEqual([
      {
        signalId: "1",
        threadId: "thread-1",
        status: "error",
        text: "session_loop turn.id=turn-rate-limited response.failed exceeded retry limit, last status: 429 Too Many Requests",
        timestamp: "2026-05-07T18:26:08.000Z",
        cursor: "1"
      }
    ]);
  });

  it("keeps completion signals for separate turns inside the dedupe window", async () => {
    const repository = createRepository([
      {
        id: 1,
        ts: 100,
        level: "TRACE",
        target: "codex_core::session::turn",
        threadId: "thread-1",
        body:
          'session_loop{thread_id=thread-1}:turn{otel.name="session_task.turn" thread.id=thread-1 turn.id=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa}:run_turn: post sampling token usage turn_id=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa model_needs_follow_up=false has_pending_input=false needs_follow_up=false'
      },
      {
        id: 2,
        ts: 120,
        level: "TRACE",
        target: "codex_core::session::turn",
        threadId: "thread-1",
        body:
          'session_loop{thread_id=thread-1}:turn{otel.name="session_task.turn" thread.id=thread-1 turn.id=bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb}:run_turn: post sampling token usage turn_id=bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb model_needs_follow_up=false has_pending_input=false needs_follow_up=false'
      }
    ]);

    const { signals } = await repository.pollSignals(null);

    expect(signals.map((signal) => signal.signalId)).toEqual(["1", "2"]);
  });
});
