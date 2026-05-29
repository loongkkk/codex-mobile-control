import { describe, expect, it } from "vitest";

import {
  MobileRealtimeEventBuffer,
  makeMobileNotificationEvent,
  makeMobileThreadStatusEvent
} from "../src/mobile-realtime";

describe("mobile realtime protocol", () => {
  it("creates stable thread status events from resolved gateway state", () => {
    expect(
      makeMobileThreadStatusEvent({
        eventId: "thread-1:status:completed:turn-9",
        threadId: "thread-1",
        status: "completed",
        progressSummary: "本轮已完成",
        needsAttention: false,
        runningStartedAt: "2026-04-30T00:59:12.000Z",
        timestamp: "2026-04-30T01:00:00.000Z"
      })
    ).toEqual({
      type: "thread_status_changed",
      eventId: "thread-1:status:completed:turn-9",
      threadId: "thread-1",
      status: "completed",
      progressSummary: "本轮已完成",
      needsAttention: false,
      runningStartedAt: "2026-04-30T00:59:12.000Z",
      timestamp: "2026-04-30T01:00:00.000Z"
    });
  });

  it("uses notificationId as the stable mobile dedupe key", () => {
    expect(
      makeMobileNotificationEvent({
        notificationId: "thread-1:completed:turn-9",
        threadId: "thread-1",
        trigger: "completed",
        title: "当前线程已结束",
        body: "本轮已完成",
        timestamp: "2026-04-30T01:00:01.000Z"
      })
    ).toEqual({
      type: "notification",
      eventId: "notification:thread-1:completed:turn-9",
      notificationId: "thread-1:completed:turn-9",
      threadId: "thread-1",
      trigger: "completed",
      title: "当前线程已结束",
      body: "本轮已完成",
      timestamp: "2026-04-30T01:00:01.000Z"
    });
  });

  it("replays events after the last event id", () => {
    const buffer = new MobileRealtimeEventBuffer(3);
    buffer.push(
      makeMobileThreadStatusEvent({
        eventId: "event-1",
        threadId: "thread-1",
        status: "running",
        progressSummary: "正在处理新的请求",
        needsAttention: false,
        timestamp: "2026-04-30T01:00:00.000Z"
      })
    );
    buffer.push(
      makeMobileThreadStatusEvent({
        eventId: "event-2",
        threadId: "thread-1",
        status: "completed",
        progressSummary: "本轮已完成",
        needsAttention: false,
        timestamp: "2026-04-30T01:00:05.000Z"
      })
    );

    expect(buffer.replayAfter("event-1").map((event) => event.eventId)).toEqual(["event-2"]);
    expect(buffer.replayAfter("missing-event").map((event) => event.eventId)).toEqual([
      "event-1",
      "event-2"
    ]);
  });
});
