import type {
  Alert,
  AlertsResponse,
  MarkdownFilePreview,
  MobileAutomationItem,
  SendMessageRequest,
  SendMessageResponse,
  ThreadDetail,
  ThreadEvent,
  ThreadEventsResponse,
  ThreadListItem,
  MobileThreadStatus,
  QueuedTextMessage,
  ThreadMessage,
  ThreadMessagesResponse,
  ThreadStatusDecision
} from "../../shared/src/api";

export type HealthStatus = {
  ok: boolean;
  sidecarStatus: "connected" | "connecting" | "disconnected";
  codexHome: string;
  reason?: string;
};

export type GatewayMessagesAppendedEvent = {
  type: "thread_messages_appended";
  eventId: string;
  threadId: string;
  messages: ThreadMessage[];
  timestamp: string;
};

export type GatewayEvent = ThreadEvent | Alert | GatewayMessagesAppendedEvent;

export type DesktopSendJobKind =
  | "text"
  | "guide_text"
  | "queued_text"
  | "image"
  | "images"
  | "files"
  | "attachments";

export type DesktopSendJobDiagnostics = {
  id: number;
  kind: DesktopSendJobKind;
  threadId: string;
  clientMessageId: string;
  status: "queued" | "running" | "completed" | "failed";
  queuedAt: string;
  startedAt?: string;
  finishedAt?: string;
  error?: string;
};

export type ThreadObservationDiagnostics = {
  threadId: string;
  clientMessageId: string;
  submittedAt: string;
  deadlineAt: string;
  remainingMs: number;
  inFlight: boolean;
  rolloutTracking: boolean;
  baselineTurnId?: string;
  rolloutObservedTurnId?: string;
  rolloutObservedStartedAt?: string;
};

export type ThreadObservationStopReason =
  | "replaced"
  | "settled"
  | "timeout"
  | "send_failed";

export type StoppedThreadObservationDiagnostics = ThreadObservationDiagnostics & {
  stoppedAt: string;
  stopReason: ThreadObservationStopReason;
  finalStatus?: MobileThreadStatus;
  error?: string;
};

export type StatusDecisionDiagnosticsContext =
  | "list"
  | "preview"
  | "detail"
  | "late_detail";

export type StatusDecisionDiagnostics = ThreadStatusDecision & {
  threadId: string;
  context: StatusDecisionDiagnosticsContext;
  observedAt: string;
};

export type MobileGatewayRuntimeDiagnostics = {
  desktopSendQueue: {
    pending: number;
    queued: DesktopSendJobDiagnostics[];
    active: DesktopSendJobDiagnostics | null;
    lastFinished: DesktopSendJobDiagnostics | null;
    lastFailed: DesktopSendJobDiagnostics | null;
    recent: DesktopSendJobDiagnostics[];
  };
  threadObservations: {
    active: number;
    items: ThreadObservationDiagnostics[];
    lastStopped: StoppedThreadObservationDiagnostics | null;
  };
  statusDecisions?: {
    recent: StatusDecisionDiagnostics[];
    maxItems: number;
  };
};

export type SendImageMessagePayload = {
  text?: string;
  clientMessageId: string;
  tempFilePath: string;
  originalFileName: string;
  mimeType: string;
};

export type SendImageMessagesPayload = {
  text?: string;
  clientMessageId: string;
  images: Array<{
    tempFilePath: string;
    originalFileName: string;
    mimeType: string;
  }>;
};

export type SendFileMessagesPayload = {
  text?: string;
  clientMessageId: string;
  files: Array<{
    tempFilePath: string;
    originalFileName: string;
    mimeType: string;
  }>;
};

export type SendAttachmentMessagesPayload = {
  text?: string;
  clientMessageId: string;
  images: Array<{
    tempFilePath: string;
    originalFileName: string;
    mimeType: string;
  }>;
  files: Array<{
    tempFilePath: string;
    originalFileName: string;
    mimeType: string;
  }>;
};

export type MobileGatewayService = {
  authenticate(token: string): Promise<boolean>;
  getHealth(): Promise<HealthStatus>;
  listAutomations(): Promise<MobileAutomationItem[]>;
  listThreads(): Promise<ThreadListItem[]>;
  getThreadPreview(threadId: string): Promise<ThreadDetail>;
  getThreadDetail(threadId: string): Promise<ThreadDetail>;
  getThreadMessages(
    threadId: string,
    before?: string | null,
    beforeTimestamp?: string | null,
    limit?: number | null,
    after?: string | null,
    afterTimestamp?: string | null
  ): Promise<ThreadMessagesResponse>;
  getThreadEvents(threadId: string, cursor?: string | null): Promise<ThreadEventsResponse>;
  getMarkdownFilePreview(threadId: string, filePath: string): Promise<MarkdownFilePreview>;
  getQueuedTextMessages(threadId: string): Promise<QueuedTextMessage[]>;
  cancelQueuedTextMessage(threadId: string, queueId: string): Promise<QueuedTextMessage | null>;
  retryQueuedTextMessage(threadId: string, queueId: string): Promise<QueuedTextMessage | null>;
  sendMessage(threadId: string, payload: SendMessageRequest): Promise<SendMessageResponse>;
  sendImageMessage(threadId: string, payload: SendImageMessagePayload): Promise<SendMessageResponse>;
  sendImageMessages(threadId: string, payload: SendImageMessagesPayload): Promise<SendMessageResponse>;
  sendFileMessages(threadId: string, payload: SendFileMessagesPayload): Promise<SendMessageResponse>;
  sendAttachmentMessages(threadId: string, payload: SendAttachmentMessagesPayload): Promise<SendMessageResponse>;
  getUploadFile(
    threadId: string,
    fileName: string
  ): Promise<{ absolutePath: string; mimeType: string }>;
  getAlerts(cursor?: string | null): Promise<AlertsResponse>;
  getRuntimeDiagnostics?(): Promise<MobileGatewayRuntimeDiagnostics>;
  subscribe(listener: (event: GatewayEvent) => void): () => void;
};
