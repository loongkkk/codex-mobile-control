import { describe, expect, it } from "vitest";

import {
  compareDetailStatusCandidates,
  mapStatusFromCodex,
  shouldKeepExistingHint,
  statusText
} from "../src/thread-status-resolver";
import type { CodexThreadStatus, RuntimeThreadHint } from "../src/mobile-gateway-service";

describe("thread-status-resolver", () => {
  it("maps active waiting-on-user-input codex status to mobile waiting_input", () => {
    const status: CodexThreadStatus = {
      type: "active",
      activeFlags: ["waitingOnUserInput"]
    };

    expect(mapStatusFromCodex(status)).toBe("waiting_input");
    expect(statusText("waiting_input")).toBe("线程正在等待输入");
  });

  it("keeps existing error hint when a completed hint lands in the terminal conflict window", () => {
    const errorTimestamp = "2026-05-08T00:00:00.000Z";
    const completedTimestamp = "2026-05-08T00:00:00.500Z";
    const existing: RuntimeThreadHint = {
      status: "error",
      progressSummary: "429 Too Many Requests",
      updatedAt: errorTimestamp
    };
    const next: RuntimeThreadHint = {
      status: "completed",
      progressSummary: "本轮已完成",
      updatedAt: completedTimestamp
    };

    expect(shouldKeepExistingHint(existing, next)).toBe(true);
    expect(
      [
        {
          status: "completed" as const,
          source: "turn" as const,
          text: "本轮已完成",
          timestamp: completedTimestamp
        },
        {
          status: "error" as const,
          source: "event" as const,
          text: "429 Too Many Requests",
          timestamp: errorTimestamp
        }
      ].sort(compareDetailStatusCandidates)[0].status
    ).toBe("error");
  });
});
