import { createServer, type Server } from "node:http";

import WebSocket from "ws";
import { afterEach, describe, expect, it, vi } from "vitest";

import { createGatewayApp } from "../src/app";
import { __test__, attachMobileSocketHub } from "../src/mobile-socket-hub";
import { MobileRealtimeEventBuffer } from "../src/mobile-realtime";
import type { GatewayEvent, MobileGatewayService } from "../src/service";

function createServiceStub(): MobileGatewayService & { emitGatewayEvent: (event: GatewayEvent) => void } {
  const listeners = new Set<(event: GatewayEvent) => void>();
  return {
    authenticate: vi.fn(async (token: string) => token === "secret-token"),
    getHealth: vi.fn(async () => ({ ok: true, sidecarStatus: "connected" as const, codexHome: "" })),
    listAutomations: vi.fn(async () => []),
    listThreads: vi.fn(async () => []),
    getThreadPreview: vi.fn(async () => {
      throw new Error("unused");
    }),
    getThreadDetail: vi.fn(async () => {
      throw new Error("unused");
    }),
    getThreadMessages: vi.fn(async () => ({ messages: [], nextCursor: null })),
    getThreadEvents: vi.fn(async () => ({ events: [], nextCursor: null })),
    getMarkdownFilePreview: vi.fn(async () => {
      throw new Error("unused");
    }),
    getQueuedTextMessages: vi.fn(async () => []),
    cancelQueuedTextMessage: vi.fn(async () => null),
    retryQueuedTextMessage: vi.fn(async () => null),
    sendMessage: vi.fn(async () => {
      throw new Error("unused");
    }),
    sendImageMessage: vi.fn(async () => {
      throw new Error("unused");
    }),
    sendImageMessages: vi.fn(async () => {
      throw new Error("unused");
    }),
    sendFileMessages: vi.fn(async () => {
      throw new Error("unused");
    }),
    sendAttachmentMessages: vi.fn(async () => {
      throw new Error("unused");
    }),
    getUploadFile: vi.fn(async () => {
      throw new Error("unused");
    }),
    getAlerts: vi.fn(async () => ({ alerts: [], nextCursor: null })),
    subscribe: vi.fn((listener: (event: GatewayEvent) => void) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    }),
    emitGatewayEvent: (event: GatewayEvent) => {
      for (const listener of listeners) {
        listener(event);
      }
    }
  };
}

function waitForMessage(socket: WebSocket): Promise<any> {
  return new Promise((resolve) => {
    socket.once("message", (raw) => resolve(JSON.parse(String(raw))));
  });
}

describe("mobile socket hub", () => {
  let server: Server | null = null;
  let hub: { close: () => void } | null = null;

  afterEach(async () => {
    hub?.close();
    hub = null;
    await new Promise<void>((resolve) => {
      if (!server) {
        resolve();
        return;
      }
      server.close(() => resolve());
      server = null;
    });
  });

  it("rejects missing tokens", async () => {
    const service = createServiceStub();
    const app = createGatewayApp({ service, authToken: "secret-token" });
    server = createServer(app);
    hub = attachMobileSocketHub({ server, service });
    await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (!address || typeof address === "string") {
      throw new Error("missing port");
    }

    const socket = new WebSocket(`ws://127.0.0.1:${address.port}/ws/mobile`);
    const closeCode = await new Promise<number>((resolve) => socket.once("close", resolve));
    expect(closeCode).toBe(1008);
  });

  it("accepts bearer tokens without requiring token in the socket url", async () => {
    const service = createServiceStub();
    const app = createGatewayApp({ service, authToken: "secret-token" });
    server = createServer(app);
    hub = attachMobileSocketHub({ server, service });
    await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (!address || typeof address === "string") {
      throw new Error("missing port");
    }

    const socket = new WebSocket(`ws://127.0.0.1:${address.port}/ws/mobile`, {
      headers: {
        Authorization: "Bearer secret-token"
      }
    });
    expect(await waitForMessage(socket)).toMatchObject({ type: "hello", protocolVersion: 1 });
    expect(service.authenticate).toHaveBeenCalledWith("secret-token");
    socket.close();
  });

  it("broadcasts status events to authenticated clients", async () => {
    const service = createServiceStub();
    const app = createGatewayApp({ service, authToken: "secret-token" });
    server = createServer(app);
    hub = attachMobileSocketHub({ server, service });
    await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (!address || typeof address === "string") {
      throw new Error("missing port");
    }

    const socket = new WebSocket(`ws://127.0.0.1:${address.port}/ws/mobile?token=secret-token`);
    expect(await waitForMessage(socket)).toMatchObject({ type: "hello", protocolVersion: 1 });

    service.emitGatewayEvent({
      eventId: "event-1",
      threadId: "thread-1",
      kind: "turn_completed",
      status: "completed",
      text: "本轮已完成",
      timestamp: "2026-04-30T01:00:00.000Z"
    });

    expect(await waitForMessage(socket)).toEqual({
      type: "thread_status_changed",
      eventId: "event-1",
      threadId: "thread-1",
      status: "completed",
      progressSummary: "本轮已完成",
      needsAttention: false,
      timestamp: "2026-04-30T01:00:00.000Z"
    });
    socket.close();
  });

  it("filters notification events by client preferences", async () => {
    const service = createServiceStub();
    const app = createGatewayApp({ service, authToken: "secret-token" });
    server = createServer(app);
    hub = attachMobileSocketHub({ server, service });
    await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (!address || typeof address === "string") {
      throw new Error("missing port");
    }

    const socket = new WebSocket(`ws://127.0.0.1:${address.port}/ws/mobile?token=secret-token`);
    expect(await waitForMessage(socket)).toMatchObject({ type: "hello" });
    socket.send(
      JSON.stringify({
        type: "notification_preferences",
        enabledThreadIds: ["thread-1"],
        knownNotificationIds: []
      })
    );
    await new Promise((resolve) => setTimeout(resolve, 25));

    service.emitGatewayEvent({
      alertId: "thread-1:completed:turn-9",
      threadId: "thread-1",
      trigger: "completed",
      title: "本轮已完成",
      body: "本轮已完成",
      timestamp: "2026-04-30T01:00:00.000Z"
    });

    expect(await waitForMessage(socket)).toMatchObject({
      type: "notification",
      notificationId: "thread-1:completed:turn-9",
      threadId: "thread-1",
      trigger: "completed"
    });
    socket.close();
  });

  it("responds to latency probes over the existing mobile socket", async () => {
    const service = createServiceStub();
    const app = createGatewayApp({ service, authToken: "secret-token" });
    server = createServer(app);
    hub = attachMobileSocketHub({ server, service });
    await new Promise<void>((resolve) => server!.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (!address || typeof address === "string") {
      throw new Error("missing port");
    }

    const socket = new WebSocket(`ws://127.0.0.1:${address.port}/ws/mobile?token=secret-token`);
    expect(await waitForMessage(socket)).toMatchObject({ type: "hello" });

    socket.send(JSON.stringify({ type: "latency_probe", probeId: "probe-1", sentAt: 101 }));
    await expect(waitForMessage(socket)).resolves.toMatchObject({
      type: "latency_probe_result",
      probeId: "probe-1",
      sentAt: 101
    });

    socket.send(JSON.stringify({ type: "latency_probe", probeId: "probe-2", sentAt: 102 }));
    await expect(waitForMessage(socket)).resolves.toMatchObject({
      type: "latency_probe_result",
      probeId: "probe-2",
      sentAt: 102
    });

    socket.send(JSON.stringify({ type: "latency_probe", probeId: "probe-3", sentAt: 103 }));
    await expect(waitForMessage(socket)).resolves.toMatchObject({
      type: "latency_probe_result",
      probeId: "probe-3",
      sentAt: 103
    });
    socket.close();
  });

  it("replays only buffered notifications after preferences update", () => {
    const sentPayloads: any[] = [];
    const client = {
      socket: {
        readyState: WebSocket.OPEN,
        send: (payload: string) => {
          sentPayloads.push(JSON.parse(payload));
        }
      } as unknown as WebSocket,
      lastEventId: "event-before",
      enabledThreadIds: new Set(["thread-1"]),
      knownNotificationIds: new Set<string>(),
      deliveredEventIds: new Set<string>()
    };
    const eventBuffer = new MobileRealtimeEventBuffer(10);
    eventBuffer.push({
      type: "thread_messages_appended",
      eventId: "thread-1:message:assistant-1",
      threadId: "thread-1",
      messages: [
        {
          messageId: "assistant-1",
          threadId: "thread-1",
          role: "assistant",
          kind: "text",
          text: "hello",
          timestamp: "2026-05-03T13:47:38.258Z"
        }
      ],
      timestamp: "2026-05-03T13:47:38.258Z"
    });
    eventBuffer.push({
      type: "notification",
      eventId: "notification:thread-1:message:assistant-1",
      notificationId: "thread-1:message:assistant-1",
      threadId: "thread-1",
      trigger: "message",
      title: "有新消息",
      body: "hello",
      timestamp: "2026-05-03T13:47:38.258Z"
    });

    __test__.sendToClient(client, eventBuffer.replayAfter(client.lastEventId)[0]!);
    __test__.replayNotificationsAfter(client, eventBuffer);

    expect(sentPayloads).toHaveLength(2);
    expect(sentPayloads[0]).toMatchObject({
      type: "thread_messages_appended",
      eventId: "thread-1:message:assistant-1"
    });
    expect(sentPayloads[1]).toMatchObject({
      type: "notification",
      eventId: "notification:thread-1:message:assistant-1"
    });
  });
});
