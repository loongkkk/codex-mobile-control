import type {
  LoginResponse,
  SendMessageResponse,
  ThreadDetail,
  ThreadListItem
} from "../../shared/src/api";

type StreamEvent =
  | { type: "thread_event"; payload: unknown }
  | { type: "alert"; payload: unknown };

export type MobileGatewayApi = {
  login(baseUrl: string, token: string): Promise<LoginResponse>;
  getThreads(baseUrl: string, token: string): Promise<ThreadListItem[]>;
  getThreadDetail(baseUrl: string, token: string, threadId: string): Promise<ThreadDetail>;
  sendMessage(
    baseUrl: string,
    token: string,
    threadId: string,
    text: string
  ): Promise<SendMessageResponse>;
  createEventSource(
    baseUrl: string,
    token: string,
    onEvent: (event: StreamEvent) => void
  ): { close(): void };
};

async function requestJson<T>(input: string, init?: RequestInit): Promise<T> {
  const response = await fetch(input, init);
  if (!response.ok) {
    throw new Error(`${response.status}:${response.statusText}`);
  }

  return (await response.json()) as T;
}

export function createMobileGatewayApi(): MobileGatewayApi {
  return {
    login(baseUrl, token) {
      return requestJson<LoginResponse>(`${baseUrl}/api/auth/login`, {
        method: "POST",
        headers: {
          "Content-Type": "application/json"
        },
        body: JSON.stringify({ token })
      });
    },
    async getThreads(baseUrl, token) {
      const response = await requestJson<{ threads: ThreadListItem[] }>(`${baseUrl}/api/threads`, {
        headers: {
          Authorization: `Bearer ${token}`
        }
      });
      return response.threads;
    },
    getThreadDetail(baseUrl, token, threadId) {
      return requestJson<ThreadDetail>(`${baseUrl}/api/threads/${threadId}`, {
        headers: {
          Authorization: `Bearer ${token}`
        }
      });
    },
    sendMessage(baseUrl, token, threadId, text) {
      return requestJson<SendMessageResponse>(`${baseUrl}/api/threads/${threadId}/messages`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${token}`,
          "Content-Type": "application/json"
        },
        body: JSON.stringify({
          text,
          clientMessageId: `client-${Date.now()}`
        })
      });
    },
    createEventSource(baseUrl, token, onEvent) {
      const source = new EventSource(
        `${baseUrl}/api/stream?token=${encodeURIComponent(token)}`
      );

      source.addEventListener("thread_event", (event) => {
        onEvent({
          type: "thread_event",
          payload: JSON.parse((event as MessageEvent).data)
        });
      });

      source.addEventListener("alert", (event) => {
        onEvent({
          type: "alert",
          payload: JSON.parse((event as MessageEvent).data)
        });
      });

      return {
        close() {
          source.close();
        }
      };
    }
  };
}
