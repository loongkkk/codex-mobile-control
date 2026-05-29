import { useEffect, useRef, useState } from "react";

import type { ThreadDetail, ThreadListItem } from "../../shared/src/api";
import { createMobileGatewayApi, type MobileGatewayApi } from "./api";
import "./styles.css";

type MobileAppProps = {
  api?: MobileGatewayApi;
};

type SavedConnection = {
  baseUrl: string;
  token: string;
};

function readInitialThreadId(): string | null {
  return new URLSearchParams(window.location.search).get("threadId");
}

const STORAGE_KEY = "codex-mobile-control:connection";

function readSavedConnection(): SavedConnection | null {
  const search = new URLSearchParams(window.location.search);
  const queryBaseUrl = search.get("gatewayUrl");
  const queryToken = search.get("token");
  if (queryBaseUrl && queryToken) {
    return { baseUrl: queryBaseUrl, token: queryToken };
  }

  const raw = window.localStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return null;
  }

  try {
    return JSON.parse(raw) as SavedConnection;
  } catch {
    return null;
  }
}

function persistConnection(connection: SavedConnection): void {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(connection));
}

function clearSavedConnection(): void {
  window.localStorage.removeItem(STORAGE_KEY);
}

function clearSensitiveConnectionParams(): void {
  const nextUrl = new URL(window.location.href);
  nextUrl.searchParams.delete("gatewayUrl");
  nextUrl.searchParams.delete("token");
  window.history.replaceState(null, "", nextUrl.toString());
}

function clearConnectionLocationParams(): void {
  const nextUrl = new URL(window.location.href);
  nextUrl.searchParams.delete("gatewayUrl");
  nextUrl.searchParams.delete("token");
  nextUrl.searchParams.delete("threadId");
  window.history.replaceState(null, "", nextUrl.toString());
}

function isPlainHttpGatewayUrl(value: string): boolean {
  try {
    return new URL(value).protocol === "http:";
  } catch {
    return /^http:\/\//i.test(value.trim());
  }
}

function statusLabel(status: ThreadListItem["status"]): string {
  switch (status) {
    case "running":
      return "运行中";
    case "waiting_input":
      return "待输入";
    case "error":
      return "错误";
    case "completed":
      return "已完成";
    case "offline":
      return "离线";
    case "idle":
    default:
      return "空闲";
  }
}

export function MobileApp({ api = createMobileGatewayApi() }: MobileAppProps) {
  const saved = readSavedConnection();
  const [baseUrl, setBaseUrl] = useState(saved?.baseUrl ?? window.location.origin);
  const [token, setToken] = useState(saved?.token ?? "");
  const [connected, setConnected] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [threads, setThreads] = useState<ThreadListItem[]>([]);
  const [selectedThreadId, setSelectedThreadId] = useState<string | null>(null);
  const [detail, setDetail] = useState<ThreadDetail | null>(null);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState("");
  const streamRef = useRef<{ close(): void } | null>(null);
  const selectedThreadIdRef = useRef<string | null>(readInitialThreadId());

  function updateThreadLocation(threadId: string | null): void {
    const nextUrl = new URL(window.location.href);
    nextUrl.searchParams.delete("gatewayUrl");
    nextUrl.searchParams.delete("token");
    if (threadId) {
      nextUrl.searchParams.set("threadId", threadId);
    } else {
      nextUrl.searchParams.delete("threadId");
    }
    window.history.replaceState(null, "", nextUrl.toString());
  }

  async function loadThreads(nextBaseUrl: string, nextToken: string): Promise<ThreadListItem[]> {
    const nextThreads = await api.getThreads(nextBaseUrl, nextToken);
    setThreads(nextThreads);
    return nextThreads;
  }

  async function loadDetail(nextBaseUrl: string, nextToken: string, threadId: string): Promise<void> {
    const nextDetail = await api.getThreadDetail(nextBaseUrl, nextToken, threadId);
    setSelectedThreadId(threadId);
    selectedThreadIdRef.current = threadId;
    updateThreadLocation(threadId);
    setDetail(nextDetail);
  }

  async function connect(nextBaseUrl: string, nextToken: string): Promise<void> {
    setError("");
    clearSensitiveConnectionParams();
    const response = await api.login(nextBaseUrl, nextToken);
    if (!response.authenticated) {
      throw new Error("token_invalid");
    }

    setConnected(true);
    setDrawerOpen(false);
    persistConnection({ baseUrl: nextBaseUrl, token: nextToken });
    const nextThreads = await loadThreads(nextBaseUrl, nextToken);
    const preferredThreadId =
      selectedThreadIdRef.current && nextThreads.some((thread) => thread.threadId === selectedThreadIdRef.current)
        ? selectedThreadIdRef.current
        : nextThreads[0]?.threadId;
    if (preferredThreadId) {
      await loadDetail(nextBaseUrl, nextToken, preferredThreadId);
    }

    streamRef.current?.close();
    streamRef.current = api.createEventSource(nextBaseUrl, nextToken, async () => {
      const updatedThreads = await loadThreads(nextBaseUrl, nextToken);
      const activeThreadId = selectedThreadIdRef.current ?? updatedThreads[0]?.threadId;
      if (activeThreadId) {
        await loadDetail(nextBaseUrl, nextToken, activeThreadId);
      }
    });
  }

  function handleClearSavedConnection(): void {
    streamRef.current?.close();
    streamRef.current = null;
    clearSavedConnection();
    clearConnectionLocationParams();
    setBaseUrl(window.location.origin);
    setToken("");
    setConnected(false);
    setDrawerOpen(false);
    setThreads([]);
    setSelectedThreadId(null);
    selectedThreadIdRef.current = null;
    setDetail(null);
    setDraft("");
    setError("");
  }

  useEffect(() => {
    if (saved?.baseUrl && saved?.token) {
      connect(saved.baseUrl, saved.token).catch((nextError: Error) => {
        setConnected(false);
        setError(nextError.message);
      });
    }

    return () => {
      streamRef.current?.close();
    };
  }, []);

  const selectedThread = detail?.thread ?? threads.find((thread) => thread.threadId === selectedThreadId) ?? null;
  const isAuthMode = !connected;
  const selectedThreadStatus = selectedThread ? statusLabel(selectedThread.status) : "未选线程";
  const selectedThreadUpdatedAt = selectedThread
    ? new Date(selectedThread.updatedAt).toLocaleString()
    : "连接后选择一个已有线程";

  return (
    <main
      className={`mobile-shell ${isAuthMode ? "is-auth-mode" : ""}`}
      data-testid="mobile-shell"
    >
      {isAuthMode ? (
        <section className="auth-layout">
          <section className="hero-card hero-card-auth">
            <p className="eyebrow">Codex Desktop Mobile Gateway</p>
            <h1>手机追踪桌面线程，随时补一句话</h1>
            <p className="hero-copy">
              第一阶段只聚焦已有线程：看最近进度、看少量最近消息、发一条纯文本。
            </p>
          </section>

          <section className="panel auth-panel" data-testid="auth-panel">
            <div className="panel-header">
              <h2>连接网关</h2>
              <span className={`connection-dot ${connected ? "is-online" : "is-offline"}`}>
                {connected ? "已连接" : "未连接"}
              </span>
            </div>

            <label className="field">
              <span>Gateway URL</span>
              <input
                aria-label="Gateway URL"
                value={baseUrl}
                onChange={(event) => setBaseUrl(event.target.value)}
                placeholder="http://<your-computer-ip>:43124"
              />
            </label>
            {isPlainHttpGatewayUrl(baseUrl) ? (
              <p className="auth-security-note">
                HTTP 连接仅适合可信局域网；公网或远程访问请使用 HTTPS。
              </p>
            ) : null}

            <label className="field">
              <span>Access Token</span>
              <input
                aria-label="Access Token"
                type="password"
                value={token}
                onChange={(event) => setToken(event.target.value)}
                placeholder="输入局域网 token"
              />
            </label>

            <div className="auth-actions">
              <button
                type="button"
                className="primary-button"
                onClick={() => {
                  connect(baseUrl, token).catch((nextError: Error) => {
                    setConnected(false);
                    setError(nextError.message);
                  });
                }}
              >
                连接网关
              </button>
              <button
                type="button"
                className="ghost-button"
                onClick={handleClearSavedConnection}
              >
                清除本地连接信息
              </button>
            </div>

            {error ? <p className="error-text">{error}</p> : null}
          </section>
        </section>
      ) : null}

      {connected ? (
        <section className="content-grid" data-testid="content-grid">
          <button
            type="button"
            className={`drawer-scrim ${drawerOpen ? "is-visible" : ""}`}
            aria-label="收起工作区遮罩"
            onClick={() => setDrawerOpen(false)}
          />

          <aside
            className={`panel workspace-drawer thread-list-panel ${drawerOpen ? "is-open" : ""}`}
            data-testid="workspace-drawer"
          >
            <div className="workspace-drawer-header">
              <div>
                <p className="eyebrow">Workspace Rail</p>
                <h2>工作区栏</h2>
              </div>
              <button
                type="button"
                className="ghost-button drawer-close-button"
                aria-label="关闭工作区栏"
                onClick={() => setDrawerOpen(false)}
              >
                收起
              </button>
            </div>

            <div className="workspace-drawer-meta">
              <span className={`connection-dot ${connected ? "is-online" : "is-offline"}`}>
                {connected ? "已连接" : "未连接"}
              </span>
              <span className="workspace-count">{threads.length} 个线程</span>
            </div>

            <div className="thread-list">
              {threads.map((thread) => (
                <button
                  key={thread.threadId}
                  className={`thread-card ${selectedThreadId === thread.threadId ? "is-selected" : ""}`}
                  onClick={() => {
                    loadDetail(baseUrl, token, thread.threadId)
                      .then(() => {
                        setDrawerOpen(false);
                      })
                      .catch((nextError: Error) => {
                        setError(nextError.message);
                      });
                  }}
                >
                  <div className="thread-card-top">
                    <strong>{thread.title}</strong>
                    <span className={`status-pill status-${thread.status}`}>{statusLabel(thread.status)}</span>
                  </div>
                  <p>{thread.progressSummary}</p>
                  <small>{thread.cwd}</small>
                </button>
              ))}
              {threads.length === 0 ? <p className="empty-text">连接后会显示桌面端已有线程。</p> : null}
            </div>
          </aside>

          <section className="chat-stage">
            <header className="panel app-topbar" data-testid="app-topbar">
              <div className="topbar-leading">
                <button
                  type="button"
                  className="ghost-button topbar-menu-button"
                  aria-label="打开工作区栏"
                  onClick={() => setDrawerOpen(true)}
                >
                  工作区
                </button>
                <div className="topbar-copy">
                  <p className="eyebrow">Codex Desktop</p>
                  <h2>{selectedThread?.title ?? "线程详情"}</h2>
                  <p className="topbar-subtitle">{selectedThread?.cwd ?? "连接后选择一个已有线程"}</p>
                </div>
              </div>

              <div className="topbar-trailing">
                <span className="topbar-meta">{threads.length} 个线程</span>
                {selectedThread ? (
                  <span className={`status-pill status-${selectedThread.status}`}>{selectedThreadStatus}</span>
                ) : null}
              </div>
            </header>

            <div className="chat-body">
              {error ? <p className="error-text page-error">{error}</p> : null}

              {selectedThread ? (
                <section className="panel thread-summary">
                  <div className="thread-summary-copy">
                    <p className="eyebrow">Current Workspace</p>
                    <h3>{selectedThread.title}</h3>
                    <p>{selectedThread.progressSummary}</p>
                  </div>
                  <div className="thread-summary-meta">
                    <span>状态：{selectedThreadStatus}</span>
                    <span>最近活跃：{selectedThreadUpdatedAt}</span>
                  </div>
                </section>
              ) : null}

              {detail ? (
                <>
                  <section className="detail-columns">
                    <section className="panel detail-panel">
                      <div className="panel-header">
                        <h3>最近事件</h3>
                      </div>
                      <ul className="timeline">
                        {detail.recentEvents.map((event) => (
                          <li key={event.eventId}>
                            <strong>{event.text}</strong>
                            <span>{new Date(event.timestamp).toLocaleString()}</span>
                          </li>
                        ))}
                      </ul>
                    </section>

                    <section className="panel detail-panel">
                      <div className="panel-header">
                        <h3>最近消息</h3>
                      </div>
                      <ul className="message-list">
                        {detail.recentMessages.map((message) => (
                          <li key={message.messageId} className={`message message-${message.role}`}>
                            <span className="message-role">{message.role === "user" ? "你" : "Codex"}</span>
                            <p>{message.text}</p>
                          </li>
                        ))}
                      </ul>
                    </section>
                  </section>

                  <section className="panel detail-panel composer-panel">
                    <div className="panel-header">
                      <h3>发送消息</h3>
                    </div>
                    <div className="detail-block composer">
                      <label className="field">
                        <span>发送消息</span>
                        <textarea
                          aria-label="发送消息"
                          value={draft}
                          onChange={(event) => setDraft(event.target.value)}
                          placeholder="给当前线程补一句文本消息"
                        />
                      </label>
                      <button
                        className="primary-button"
                        disabled={!detail.sendAvailable || !draft.trim()}
                        onClick={() => {
                          if (!selectedThreadId) {
                            return;
                          }

                          api
                            .sendMessage(baseUrl, token, selectedThreadId, draft.trim())
                            .then(async () => {
                              setDraft("");
                              await loadDetail(baseUrl, token, selectedThreadId);
                              await loadThreads(baseUrl, token);
                            })
                            .catch((nextError: Error) => {
                              setError(nextError.message);
                            });
                        }}
                      >
                        发送
                      </button>
                      {!detail.sendAvailable && detail.sendDisabledReason ? (
                        <p className="error-text">{detail.sendDisabledReason}</p>
                      ) : null}
                    </div>
                  </section>
                </>
              ) : (
                <section className="panel detail-panel empty-panel">
                  <p className="empty-text">先从工作区栏选择一个已有线程。</p>
                </section>
              )}
            </div>
          </section>
        </section>
      ) : null}
    </main>
  );
}
