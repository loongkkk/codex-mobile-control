import type { IncomingMessage, Server } from "node:http";

import { WebSocket, WebSocketServer } from "ws";

import type {
  MobileRealtimeClientMessage,
  MobileRealtimeEvent,
  MobileRealtimeStoredEvent
} from "../../shared/src/api";
import {
  MobileRealtimeEventBuffer,
  makeMobileMessagesAppendedEvent,
  makeMobileNotificationEvent,
  makeMobileThreadStatusEvent
} from "./mobile-realtime";
import type { GatewayEvent, MobileGatewayService } from "./service";

type AttachMobileSocketHubOptions = {
  server: Server;
  service: MobileGatewayService;
  path?: string;
  replayCapacity?: number;
};

type ClientState = {
  socket: WebSocket;
  lastEventId: string | null;
  enabledThreadIds: Set<string>;
  knownNotificationIds: Set<string>;
  deliveredEventIds: Set<string>;
};

export function attachMobileSocketHub(options: AttachMobileSocketHubOptions): { close: () => void } {
  const path = options.path ?? "/ws/mobile";
  const eventBuffer = new MobileRealtimeEventBuffer(options.replayCapacity ?? 500);
  const clients = new Set<ClientState>();
  const webSocketServer = new WebSocketServer({ noServer: true });
  let unsubscribe: (() => void) | null = null;

  const upgradeHandler = (request: IncomingMessage, socket: any, head: Buffer) => {
    const url = new URL(request.url ?? "", "http://127.0.0.1");
    if (url.pathname !== path) {
      return;
    }

    webSocketServer.handleUpgrade(request, socket, head, (webSocket) => {
      void acceptClient(options.service, eventBuffer, clients, webSocket, url, request).then((accepted) => {
        if (!accepted) {
          return;
        }
        if (!unsubscribe) {
          unsubscribe = options.service.subscribe((event) => {
            const mapped = mapGatewayEvent(event);
            if (!mapped) {
              return;
            }
            eventBuffer.push(mapped);
            broadcast(clients, mapped);
          });
        }
      });
    });
  };

  options.server.on("upgrade", upgradeHandler);

  const pingTimer = setInterval(() => {
    for (const client of clients) {
      if (client.socket.readyState === WebSocket.OPEN) {
        client.socket.ping();
      }
    }
  }, 25_000);
  pingTimer.unref?.();

  return {
    close: () => {
      clearInterval(pingTimer);
      options.server.off("upgrade", upgradeHandler);
      unsubscribe?.();
      unsubscribe = null;
      for (const client of clients) {
        client.socket.close();
      }
      clients.clear();
      webSocketServer.close();
    }
  };
}

async function acceptClient(
  service: MobileGatewayService,
  eventBuffer: MobileRealtimeEventBuffer,
  clients: Set<ClientState>,
  socket: WebSocket,
  url: URL,
  request: IncomingMessage
): Promise<boolean> {
  const token = readMobileSocketToken(url, request);
  const authenticated = await service.authenticate(token).catch(() => false);
  if (!authenticated) {
    socket.close(1008, "invalid_token");
    return false;
  }

  const client: ClientState = {
    socket,
    lastEventId: url.searchParams.get("lastEventId"),
    enabledThreadIds: new Set(),
    knownNotificationIds: new Set(),
    deliveredEventIds: new Set()
  };
  clients.add(client);

  socket.send(
    JSON.stringify({
      type: "hello",
      protocolVersion: 1,
      timestamp: new Date().toISOString()
    })
  );

  for (const event of eventBuffer.replayAfter(client.lastEventId)) {
    sendToClient(client, event);
  }

  socket.on("message", (raw) => {
    applyClientMessage(client, String(raw), eventBuffer);
  });
  socket.on("close", () => {
    clients.delete(client);
  });

  return true;
}

function readMobileSocketToken(url: URL, request: IncomingMessage): string {
  return readBearerToken(request.headers.authorization) ?? url.searchParams.get("token") ?? "";
}

function readBearerToken(headerValue: string | string[] | undefined): string | null {
  const value = Array.isArray(headerValue) ? headerValue[0] : headerValue;
  if (!value) {
    return null;
  }
  const match = /^Bearer\s+(.+)$/i.exec(value);
  return match?.[1]?.trim() || null;
}

function broadcast(clients: Set<ClientState>, event: MobileRealtimeEvent): void {
  for (const client of clients) {
    sendToClient(client, event);
  }
}

function sendToClient(client: ClientState, event: MobileRealtimeEvent): void {
  const eventId =
    event.type === "thread_status_changed" ||
        event.type === "thread_messages_appended" ||
        event.type === "notification"
      ? event.eventId
      : null;
  if (eventId && client.deliveredEventIds.has(eventId)) {
    return;
  }

  if (event.type === "notification") {
    if (!client.enabledThreadIds.has(event.threadId)) {
      return;
    }
    if (client.knownNotificationIds.has(event.notificationId)) {
      return;
    }
    client.knownNotificationIds.add(event.notificationId);
  }

  if (client.socket.readyState === WebSocket.OPEN) {
    if (eventId) {
      client.deliveredEventIds.add(eventId);
    }
    client.socket.send(JSON.stringify(event));
  }
}

function applyClientMessage(
  client: ClientState,
  raw: string,
  eventBuffer: MobileRealtimeEventBuffer
): void {
  const parsed = parseClientMessage(raw);
  if (!parsed) {
    return;
  }

  if (parsed.type === "latency_probe") {
    if (client.socket.readyState === WebSocket.OPEN) {
      client.socket.send(
        JSON.stringify({
          type: "latency_probe_result",
          probeId: parsed.probeId,
          sentAt: parsed.sentAt,
          timestamp: new Date().toISOString()
        })
      );
    }
    return;
  }

  if (parsed.type !== "notification_preferences") {
    return;
  }

  client.enabledThreadIds = new Set(parsed.enabledThreadIds.filter((id) => id.trim().length > 0));
  client.knownNotificationIds = new Set(
    parsed.knownNotificationIds.filter((id) => id.trim().length > 0)
  );

  replayNotificationsAfter(client, eventBuffer);
}

function replayNotificationsAfter(
  client: ClientState,
  eventBuffer: MobileRealtimeEventBuffer
): void {
  for (const event of eventBuffer.replayAfter(client.lastEventId)) {
    if (event.type !== "notification") {
      continue;
    }
    sendToClient(client, event);
  }
}

export const __test__ = {
  replayNotificationsAfter,
  sendToClient
};

function parseClientMessage(raw: string): MobileRealtimeClientMessage | null {
  return runCatching(() => JSON.parse(raw) as MobileRealtimeClientMessage);
}

function runCatching<T>(operation: () => T): T | null {
  try {
    return operation();
  } catch {
    return null;
  }
}

function mapGatewayEvent(event: GatewayEvent): MobileRealtimeStoredEvent | null {
  if ("trigger" in event) {
    return makeMobileNotificationEvent({
      notificationId: event.alertId,
      threadId: event.threadId,
      trigger: event.trigger,
      title: event.title,
      body: event.body,
      timestamp: event.timestamp
    });
  }

  if ("type" in event && event.type === "thread_messages_appended") {
    return makeMobileMessagesAppendedEvent({
      eventId: event.eventId,
      threadId: event.threadId,
      messages: event.messages,
      timestamp: event.timestamp
    });
  }

  if ("kind" in event && event.status) {
    return makeMobileThreadStatusEvent({
      eventId: event.eventId,
      threadId: event.threadId,
      status: event.status,
      progressSummary: event.text,
      needsAttention: event.status === "waiting_input" || event.status === "error",
      runningStartedAt: event.status === "running" ? event.timestamp : undefined,
      timestamp: event.timestamp
    });
  }

  return null;
}
