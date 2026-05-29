import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { ThreadDetail, ThreadListItem } from "../../shared/src/api";
import { MobileApp } from "../src/MobileApp";

const sampleThread: ThreadListItem = {
  threadId: "thread-1",
  title: "开发 Codex 控制App",
  cwd: "D:\\projects\\codex-mobile-control",
  status: "running",
  updatedAt: "2026-04-22T11:00:00.000Z",
  progressSummary: "正在实现移动控制 v1",
  needsAttention: false
};

const sampleDetail: ThreadDetail = {
  thread: sampleThread,
  recentMessages: [
    {
      messageId: "m-1",
      threadId: "thread-1",
      role: "assistant",
      kind: "text",
      text: "我正在先搭后端网关。",
      timestamp: "2026-04-22T11:00:01.000Z"
    }
  ],
  recentEvents: [
    {
      eventId: "e-1",
      threadId: "thread-1",
      kind: "turn_started",
      status: "running",
      text: "开始处理新的实现请求",
      timestamp: "2026-04-22T11:00:02.000Z"
    }
  ],
  sendAvailable: true
};

function acceptedSendResponse() {
  return {
    accepted: true,
    threadId: "thread-1",
    clientMessageId: "client-1",
    sendPath: "desktop_bridge" as const,
    confirmation: "observed" as const
  };
}

describe("MobileApp", () => {
  it("keeps the gateway login view centered before connection", () => {
    const api = {
      login: vi.fn(async () => ({ authenticated: true })),
      getThreads: vi.fn(async () => [sampleThread]),
      getThreadDetail: vi.fn(async () => sampleDetail),
      sendMessage: vi.fn(async () => acceptedSendResponse()),
      createEventSource: vi.fn()
    };

    render(<MobileApp api={api} />);

    expect(screen.getByTestId("mobile-shell")).toHaveClass("is-auth-mode");
    expect(screen.getByTestId("auth-panel")).toBeInTheDocument();
    expect(screen.queryByTestId("content-grid")).not.toBeInTheDocument();
  });

  it("renders login form and loads threads after successful login", async () => {
    const api = {
      login: vi.fn(async () => ({ authenticated: true })),
      getThreads: vi.fn(async () => [sampleThread]),
      getThreadDetail: vi.fn(async () => sampleDetail),
      sendMessage: vi.fn(async () => acceptedSendResponse()),
      createEventSource: vi.fn()
    };

    render(<MobileApp api={api} />);

    fireEvent.change(screen.getByLabelText("Gateway URL"), {
      target: { value: "http://127.0.0.1:43124" }
    });
    fireEvent.change(screen.getByLabelText("Access Token"), {
      target: { value: "secret-token" }
    });
    expect(screen.getByLabelText("Access Token")).toHaveAttribute("type", "password");
    fireEvent.click(screen.getByRole("button", { name: "连接网关" }));

    await waitFor(() => {
      expect(api.login).toHaveBeenCalledWith("http://127.0.0.1:43124", "secret-token");
    });

    expect(
      await screen.findByRole("button", {
        name: /开发 Codex 控制App/
      })
    ).toBeInTheDocument();
    expect(screen.queryByTestId("auth-panel")).not.toBeInTheDocument();
    expect(screen.getByTestId("content-grid")).toBeInTheDocument();
    expect(screen.getByTestId("mobile-shell")).not.toHaveClass("is-auth-mode");
    expect((await screen.findAllByText("正在实现移动控制 v1")).length).toBeGreaterThan(0);
  });

  it("clears sensitive query connection params after auto login", async () => {
    window.history.replaceState(
      null,
      "",
      "/?gatewayUrl=http%3A%2F%2F127.0.0.1%3A43124&token=secret-token&threadId=thread-1"
    );
    const api = {
      login: vi.fn(async () => ({ authenticated: true })),
      getThreads: vi.fn(async () => [sampleThread]),
      getThreadDetail: vi.fn(async () => sampleDetail),
      sendMessage: vi.fn(async () => acceptedSendResponse()),
      createEventSource: vi.fn()
    };

    render(<MobileApp api={api} />);

    await waitFor(() => {
      expect(api.login).toHaveBeenCalledWith("http://127.0.0.1:43124", "secret-token");
    });
    expect(window.location.search).toBe("?threadId=thread-1");
  });

  it("lets users clear saved gateway connection details from the login view", async () => {
    window.localStorage.setItem(
      "codex-mobile-control:connection",
      JSON.stringify({ baseUrl: "http://127.0.0.1:43124", token: "saved-token" })
    );
    window.history.replaceState(
      null,
      "",
      "/?gatewayUrl=http%3A%2F%2F127.0.0.1%3A43124&token=saved-token"
    );
    const api = {
      login: vi.fn(async () => {
        throw new Error("gateway_offline");
      }),
      getThreads: vi.fn(async () => [sampleThread]),
      getThreadDetail: vi.fn(async () => sampleDetail),
      sendMessage: vi.fn(async () => acceptedSendResponse()),
      createEventSource: vi.fn()
    };

    render(<MobileApp api={api} />);

    expect(await screen.findByText("gateway_offline")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "清除本地连接信息" }));

    expect(window.localStorage.getItem("codex-mobile-control:connection")).toBeNull();
    expect(window.location.search).toBe("");
    expect(screen.getByLabelText("Access Token")).toHaveValue("");
    expect(screen.getByLabelText("Gateway URL")).toHaveValue(window.location.origin);
  });

  it("explains that plain HTTP gateway URLs should stay on trusted networks", () => {
    const api = {
      login: vi.fn(async () => ({ authenticated: true })),
      getThreads: vi.fn(async () => [sampleThread]),
      getThreadDetail: vi.fn(async () => sampleDetail),
      sendMessage: vi.fn(async () => acceptedSendResponse()),
      createEventSource: vi.fn()
    };

    render(<MobileApp api={api} />);

    fireEvent.change(screen.getByLabelText("Gateway URL"), {
      target: { value: "http://127.0.0.1:43124" }
    });
    expect(screen.getByText(/HTTP 连接仅适合可信局域网/)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("Gateway URL"), {
      target: { value: "https://codex.example.test" }
    });
    expect(screen.queryByText(/HTTP 连接仅适合可信局域网/)).not.toBeInTheDocument();
  });

  it("shows detail and sends a message with retry-ready input flow", async () => {
    const api = {
      login: vi.fn(async () => ({ authenticated: true })),
      getThreads: vi.fn(async () => [sampleThread]),
      getThreadDetail: vi.fn(async () => sampleDetail),
      sendMessage: vi.fn(async () => acceptedSendResponse()),
      createEventSource: vi.fn()
    };

    render(<MobileApp api={api} />);

    fireEvent.change(screen.getByLabelText("Gateway URL"), {
      target: { value: "http://127.0.0.1:43124" }
    });
    fireEvent.change(screen.getByLabelText("Access Token"), {
      target: { value: "secret-token" }
    });
    fireEvent.click(screen.getByRole("button", { name: "连接网关" }));

    const threadButton = await screen.findByRole("button", {
      name: /开发 Codex 控制App/
    });
    fireEvent.click(threadButton);

    expect(await screen.findByText("我正在先搭后端网关。")).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText("发送消息"), {
      target: { value: "请继续完成 Android 壳" }
    });
    fireEvent.click(screen.getByRole("button", { name: "发送" }));

    await waitFor(() => {
      expect(api.sendMessage).toHaveBeenCalledWith(
        "http://127.0.0.1:43124",
        "secret-token",
        "thread-1",
        "请继续完成 Android 壳"
      );
    });
  });

  it("shows a top bar entry and toggles the workspace drawer after connection", async () => {
    const api = {
      login: vi.fn(async () => ({ authenticated: true })),
      getThreads: vi.fn(async () => [sampleThread]),
      getThreadDetail: vi.fn(async () => sampleDetail),
      sendMessage: vi.fn(async () => acceptedSendResponse()),
      createEventSource: vi.fn()
    };

    render(<MobileApp api={api} />);

    fireEvent.change(screen.getByLabelText("Gateway URL"), {
      target: { value: "http://127.0.0.1:43124" }
    });
    fireEvent.change(screen.getByLabelText("Access Token"), {
      target: { value: "secret-token" }
    });
    fireEvent.click(screen.getByRole("button", { name: "连接网关" }));

    expect(await screen.findByTestId("app-topbar")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "打开工作区栏" })).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "打开工作区栏" }));
    expect(screen.getByTestId("workspace-drawer")).toHaveClass("is-open");

    fireEvent.click(screen.getByRole("button", { name: "关闭工作区栏" }));
    expect(screen.getByTestId("workspace-drawer")).not.toHaveClass("is-open");
  });
});
