import type {
  MobileNotificationTrigger,
  MobileRealtimeMessagesAppendedEvent,
  MobileRealtimeNotificationEvent,
  MobileRealtimeStoredEvent,
  MobileRealtimeThreadStatusEvent,
  MobileThreadStatus,
  ThreadMessage
} from "../../shared/src/api";

type ThreadStatusInput = {
  eventId: string;
  threadId: string;
  status: MobileThreadStatus;
  progressSummary: string;
  needsAttention: boolean;
  runningStartedAt?: string;
  timestamp: string;
};

type NotificationInput = {
  notificationId: string;
  threadId: string;
  trigger: MobileNotificationTrigger;
  title: string;
  body: string;
  timestamp: string;
};

type MessagesInput = {
  eventId: string;
  threadId: string;
  messages: ThreadMessage[];
  timestamp: string;
};

export function makeMobileThreadStatusEvent(input: ThreadStatusInput): MobileRealtimeThreadStatusEvent {
  return {
    type: "thread_status_changed",
    eventId: input.eventId,
    threadId: input.threadId,
    status: input.status,
    progressSummary: input.progressSummary,
    needsAttention: input.needsAttention,
    ...(input.runningStartedAt ? { runningStartedAt: input.runningStartedAt } : {}),
    timestamp: input.timestamp
  };
}

export function makeMobileNotificationEvent(input: NotificationInput): MobileRealtimeNotificationEvent {
  return {
    type: "notification",
    eventId: `notification:${input.notificationId}`,
    notificationId: input.notificationId,
    threadId: input.threadId,
    trigger: input.trigger,
    title: input.title,
    body: input.body,
    timestamp: input.timestamp
  };
}

export function makeMobileMessagesAppendedEvent(input: MessagesInput): MobileRealtimeMessagesAppendedEvent {
  return {
    type: "thread_messages_appended",
    eventId: input.eventId,
    threadId: input.threadId,
    messages: input.messages,
    timestamp: input.timestamp
  };
}

export class MobileRealtimeEventBuffer {
  private readonly events: MobileRealtimeStoredEvent[] = [];

  constructor(private readonly capacity: number) {}

  push(event: MobileRealtimeStoredEvent): MobileRealtimeStoredEvent {
    if (this.events.some((existing) => existing.eventId === event.eventId)) {
      return event;
    }

    this.events.push(event);
    while (this.events.length > this.capacity) {
      this.events.shift();
    }
    return event;
  }

  replayAfter(lastEventId?: string | null): MobileRealtimeStoredEvent[] {
    if (!lastEventId) {
      return [];
    }

    const index = this.events.findIndex((event) => event.eventId === lastEventId);
    return index >= 0 ? this.events.slice(index + 1) : [...this.events];
  }
}
