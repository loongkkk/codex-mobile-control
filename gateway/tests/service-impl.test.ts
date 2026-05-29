import { describe, expect, it } from "vitest";

import {
  resolveConfiguredIpcSendEnabled,
  resolveConfiguredAppServerWsUrl,
  resolveConfiguredAppServerTransport,
  resolveConfiguredMobileSendMode
} from "../src/service-impl";

describe("resolveConfiguredMobileSendMode", () => {
  it("uses official persistence as the default send mode", () => {
    expect(resolveConfiguredMobileSendMode(undefined)).toBe("official_persistence");
    expect(resolveConfiguredMobileSendMode("")).toBe("official_persistence");
    expect(resolveConfiguredMobileSendMode("unknown")).toBe("official_persistence");
  });

  it("keeps a desktop bridge fallback when explicitly configured", () => {
    expect(resolveConfiguredMobileSendMode("desktop_bridge")).toBe("desktop_bridge");
  });

  it("allows official persistence to be enabled explicitly", () => {
    expect(resolveConfiguredMobileSendMode("official_persistence")).toBe("official_persistence");
  });
});

describe("resolveConfiguredAppServerTransport", () => {
  it("uses the official-style stdio app-server transport by default", () => {
    expect(resolveConfiguredAppServerTransport(undefined)).toBe("stdio");
    expect(resolveConfiguredAppServerTransport("")).toBe("stdio");
    expect(resolveConfiguredAppServerTransport("unknown")).toBe("stdio");
  });

  it("keeps a websocket fallback for diagnostics", () => {
    expect(resolveConfiguredAppServerTransport("ws")).toBe("ws");
  });
});

describe("resolveConfiguredAppServerWsUrl", () => {
  it("uses an explicitly configured shared websocket app-server URL", () => {
    expect(resolveConfiguredAppServerWsUrl(" ws://127.0.0.1:46124/ws ")).toBe(
      "ws://127.0.0.1:46124/ws"
    );
  });

  it("falls back to spawning a sidecar when no shared websocket URL is configured", () => {
    expect(resolveConfiguredAppServerWsUrl(undefined)).toBeNull();
    expect(resolveConfiguredAppServerWsUrl("")).toBeNull();
  });
});

describe("resolveConfiguredIpcSendEnabled", () => {
  it("enables official IPC sending by default", () => {
    expect(resolveConfiguredIpcSendEnabled(undefined)).toBe(true);
    expect(resolveConfiguredIpcSendEnabled("")).toBe(true);
    expect(resolveConfiguredIpcSendEnabled("unknown")).toBe(true);
  });

  it("allows disabling IPC sending for diagnostics", () => {
    expect(resolveConfiguredIpcSendEnabled("0")).toBe(false);
    expect(resolveConfiguredIpcSendEnabled("false")).toBe(false);
    expect(resolveConfiguredIpcSendEnabled("off")).toBe(false);
  });
});
