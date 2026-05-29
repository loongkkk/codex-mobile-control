export type MobileThreadStatus =
  | "running"
  | "waiting_input"
  | "error"
  | "completed"
  | "idle"
  | "offline";

export type ThreadListItem = {
  threadId: string;
  title: string;
  cwd: string;
  status: MobileThreadStatus;
  updatedAt: string;
  progressSummary: string;
  needsAttention: boolean;
  isPinned?: boolean;
  automationActive?: boolean;
  automationSummary?: string;
  runningStartedAt?: string;
};

export type MobileAutomationStatus = "ACTIVE" | "PAUSED" | "UNKNOWN";

export type MobileAutomationKind = "heartbeat" | "cron" | "unknown";

export type MobileAutomationItem = {
  id: string;
  name: string;
  kind: MobileAutomationKind;
  status: MobileAutomationStatus;
  scheduleSummary: string;
  targetThreadId?: string;
  targetThreadTitle?: string;
  cwd?: string;
};

export type ThreadEventKind =
  | "status_changed"
  | "turn_started"
  | "turn_completed"
  | "message"
  | "plan"
  | "command"
  | "log_signal"
  | "error";

export type ThreadEvent = {
  eventId: string;
  threadId: string;
  kind: ThreadEventKind;
  status?: MobileThreadStatus;
  text: string;
  timestamp: string;
};

export type ThreadMessageRole = "user" | "assistant" | "system";

export type ThreadMessageKind = "text" | "image" | "file";

export type ThreadMessage = {
  messageId: string;
  threadId: string;
  role: ThreadMessageRole;
  kind: ThreadMessageKind;
  text?: string;
  imageUrl?: string;
  thumbnailUrl?: string;
  fileUrl?: string;
  fileName?: string;
  mimeType?: string;
  timestamp: string;
};

export type QueuedTextMessageStatus =
  | "PENDING"
  | "DISPATCHING"
  | "SENT"
  | "FAILED"
  | "CANCELLED";

export type QueuedTextMessage = {
  queueId: string;
  threadId: string;
  text: string;
  clientMessageId: string;
  queuedAt: string;
  queuedAtMillis: number;
  status: QueuedTextMessageStatus;
  dispatchStartedAt?: string;
  dispatchStartedAtMillis?: number;
  errorMessage?: string;
};

export type ThreadFileChangeItem = {
  path: string;
  added: number;
  removed: number;
};

export type ThreadFileChanges = {
  summary: string;
  changedFiles: number;
  added: number;
  removed: number;
  items: ThreadFileChangeItem[];
};

export type ThreadComposerState = {
  permissionLabel: string;
  modelLabel: string;
  effortLabel?: string;
};

export type ThreadStatusDecisionSource =
  | "list"
  | "thread_status"
  | "turn"
  | "event";

export type ThreadStatusDecisionCandidate = {
  status: MobileThreadStatus;
  source: ThreadStatusDecisionSource;
  text: string;
  timestamp: string;
  selected: boolean;
  sourceId?: string;
};

export type ThreadStatusDecision = {
  status: MobileThreadStatus;
  source: ThreadStatusDecisionSource;
  text: string;
  timestamp: string;
  reason: string;
  candidates: ThreadStatusDecisionCandidate[];
  sourceId?: string;
};

export type ThreadSendDecisionReason =
  | "ready"
  | "thread_running"
  | "gateway_offline";

export type ThreadSendDecision = {
  available: boolean;
  reason: ThreadSendDecisionReason;
  source: "statusDecision";
  message: string;
  recommendedAction: "send" | "queue" | "reconnect";
};

export type ThreadDetail = {
  thread: ThreadListItem;
  recentMessages: ThreadMessage[];
  recentEvents: ThreadEvent[];
  fileChanges?: ThreadFileChanges;
  composerState?: ThreadComposerState;
  queuedTextMessages?: QueuedTextMessage[];
  statusDecision?: ThreadStatusDecision;
  sendDecision?: ThreadSendDecision;
  sendAvailable: boolean;
  sendDisabledReason?: string;
};

export type ThreadMessagesResponse = {
  messages: ThreadMessage[];
  nextCursor: string | null;
};

export type MarkdownFilePreview = {
  fileName: string;
  path: string;
  content: string;
  sizeBytes: number;
};

export type AlertTrigger = "waiting_input" | "error" | "completed" | "message";

export type Alert = {
  alertId: string;
  threadId: string;
  trigger: AlertTrigger;
  title: string;
  body: string;
  timestamp: string;
};

export type MobileNotificationTrigger = AlertTrigger | "message";

export type MobileRealtimeHelloEvent = {
  type: "hello";
  protocolVersion: 1;
  timestamp: string;
};

export type MobileRealtimeThreadStatusEvent = {
  type: "thread_status_changed";
  eventId: string;
  threadId: string;
  status: MobileThreadStatus;
  progressSummary: string;
  needsAttention: boolean;
  runningStartedAt?: string;
  timestamp: string;
};

export type MobileRealtimeMessagesAppendedEvent = {
  type: "thread_messages_appended";
  eventId: string;
  threadId: string;
  messages: ThreadMessage[];
  timestamp: string;
};

export type MobileRealtimeNotificationEvent = {
  type: "notification";
  eventId: string;
  notificationId: string;
  threadId: string;
  trigger: MobileNotificationTrigger;
  title: string;
  body: string;
  timestamp: string;
};

export type MobileRealtimeLatencyProbeResultEvent = {
  type: "latency_probe_result";
  probeId: string;
  sentAt: number;
  timestamp: string;
};

export type MobileRealtimeEvent =
  | MobileRealtimeHelloEvent
  | MobileRealtimeThreadStatusEvent
  | MobileRealtimeMessagesAppendedEvent
  | MobileRealtimeNotificationEvent
  | MobileRealtimeLatencyProbeResultEvent;

export type MobileRealtimeStoredEvent =
  | MobileRealtimeThreadStatusEvent
  | MobileRealtimeMessagesAppendedEvent
  | MobileRealtimeNotificationEvent;

export type MobileRealtimeNotificationPreferencesMessage = {
  type: "notification_preferences";
  enabledThreadIds: string[];
  knownNotificationIds: string[];
};

export type MobileRealtimeLatencyProbeMessage = {
  type: "latency_probe";
  probeId: string;
  sentAt: number;
};

export type MobileRealtimeClientMessage =
  | MobileRealtimeNotificationPreferencesMessage
  | MobileRealtimeLatencyProbeMessage;

export type SendMessageRequest = {
  text: string;
  clientMessageId: string;
  guide?: boolean;
  queue?: boolean;
};

export type SendPath = "app_server" | "desktop_bridge" | "sidecar_debug";

export type DesktopSendConfirmation = "observed" | "keystrokes_sent";

export type SendMessageResponse = {
  accepted: boolean;
  threadId: string;
  clientMessageId: string;
  sendPath: SendPath;
  confirmation: DesktopSendConfirmation;
  queueId?: string;
  queued?: boolean;
  warning?: "desktop_send_confirmation_timeout";
};

export type GatewayErrorResponse = {
  error: string;
  reason?: string;
  message: string;
};

export type LoginRequest = {
  token: string;
};

export type LoginResponse = {
  authenticated: boolean;
};

export type LatestApkResponse = {
  available: boolean;
  fileName?: string;
  versionCode?: number;
  versionName?: string;
  downloadUrl?: string;
};

export type ThreadsResponse = {
  threads: ThreadListItem[];
};

export type ThreadDetailResponse = ThreadDetail;

export type ThreadEventsResponse = {
  events: ThreadEvent[];
  nextCursor: string | null;
};

export type AlertsResponse = {
  alerts: Alert[];
  nextCursor: string | null;
};
