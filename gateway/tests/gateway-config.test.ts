import { existsSync, readFileSync } from "node:fs";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";

import { afterEach, describe, expect, it, vi } from "vitest";

import { loadGatewayRuntimeConfig } from "../src/gateway-config";

describe("loadGatewayRuntimeConfig", () => {
  const originalEnv = { ...process.env };
  const tempDirs: string[] = [];

  afterEach(() => {
    process.env = { ...originalEnv };
    vi.restoreAllMocks();
    tempDirs.splice(0).forEach((dir) => rmSync(dir, { recursive: true, force: true }));
  });

  it("creates a random token in config.json on first start", () => {
    const configDir = mkdtempSync(path.join(os.tmpdir(), "codex-gateway-config-"));
    tempDirs.push(configDir);
    const configFile = path.join(configDir, "config.json");
    process.env.CODEX_MOBILE_CONFIG_FILE = configFile;

    const config = loadGatewayRuntimeConfig();
    const persisted = JSON.parse(readFileSync(configFile, "utf8"));

    expect(config.authToken).toMatch(/^[A-Za-z0-9_-]{32,}$/);
    expect(config.authToken).toBe(persisted.auth.token);
    expect(config.host).toBe("0.0.0.0");
    expect(config.port).toBe(43124);
    expect(config.storage.uploadsDir).toBe(path.join(configDir, "mobile-uploads"));
    expect(config.storage.queueFile).toBe(path.join(configDir, "queued-messages.json"));
    expect(config.storage.downloadsDir).toBe(path.join(configDir, "downloads"));
    expect(config.storage.mobileDistDir).toBe(path.resolve(configDir, "..", "mobile", "dist"));
    expect(config.codex.home).toBe(path.join(os.homedir(), ".codex"));
    expect(config.codex.sendMode).toBe("official_persistence");
    expect(config.codex.appServer.transport).toBe("stdio");
    expect(config.codex.ipcSend).toBe(true);
    expect(config.configFilePath).toBe(configFile);
    expect(config.created).toBe(true);
    expect(existsSync(configFile)).toBe(true);
  });

  it("loads existing config and allows environment overrides", () => {
    const configDir = mkdtempSync(path.join(os.tmpdir(), "codex-gateway-config-"));
    tempDirs.push(configDir);
    const configFile = path.join(configDir, "config.json");
    writeFileSync(
      configFile,
      JSON.stringify({ token: "saved-token", port: 43125 }, null, 2)
    );
    process.env.CODEX_MOBILE_CONFIG_FILE = configFile;
    process.env.CODEX_MOBILE_TOKEN = "env-token";
    process.env.CODEX_MOBILE_PORT = "43126";

    const config = loadGatewayRuntimeConfig();

    expect(config.authToken).toBe("env-token");
    expect(config.port).toBe(43126);
    expect(config.created).toBe(false);
  });

  it("loads nested public config fields and resolves relative paths from the config file", () => {
    const configDir = mkdtempSync(path.join(os.tmpdir(), "codex-gateway-config-"));
    tempDirs.push(configDir);
    const configFile = path.join(configDir, "config.json");
    writeFileSync(
      configFile,
      JSON.stringify(
        {
          auth: { token: "nested-token" },
          server: {
            host: "127.0.0.1",
            port: "43127",
            publicBaseUrl: " https://gateway.example.test/ "
          },
          storage: {
            uploadsDir: "data/uploads",
            queueFile: "data/queue.json",
            downloadsDir: "public/downloads",
            mobileDistDir: "../mobile/dist"
          },
          codex: {
            home: "codex-home",
            sendMode: "desktop_bridge",
            appServer: {
              transport: "ws",
              command: "codex-app-server",
              wsUrl: " ws://127.0.0.1:46124/ws "
            },
            ipcSend: false
          },
          desktopBridge: {
            pythonCommand: "python",
            pywinautoPath: "vendor/pywinauto"
          }
        },
        null,
        2
      )
    );
    process.env.CODEX_MOBILE_CONFIG_FILE = configFile;

    const config = loadGatewayRuntimeConfig();

    expect(config.authToken).toBe("nested-token");
    expect(config.host).toBe("127.0.0.1");
    expect(config.port).toBe(43127);
    expect(config.publicBaseUrl).toBe("https://gateway.example.test");
    expect(config.storage.uploadsDir).toBe(path.join(configDir, "data", "uploads"));
    expect(config.storage.queueFile).toBe(path.join(configDir, "data", "queue.json"));
    expect(config.storage.downloadsDir).toBe(path.join(configDir, "public", "downloads"));
    expect(config.storage.mobileDistDir).toBe(path.resolve(configDir, "..", "mobile", "dist"));
    expect(config.codex.home).toBe(path.join(configDir, "codex-home"));
    expect(config.codex.sendMode).toBe("desktop_bridge");
    expect(config.codex.appServer.transport).toBe("ws");
    expect(config.codex.appServer.command).toBe("codex-app-server");
    expect(config.codex.appServer.wsUrl).toBe("ws://127.0.0.1:46124/ws");
    expect(config.codex.ipcSend).toBe(false);
    expect(config.desktopBridge.pythonCommand).toBe("python");
    expect(config.desktopBridge.pywinautoPath).toBe(path.join(configDir, "vendor", "pywinauto"));
  });

  it("preserves existing nested config fields when adding missing defaults", () => {
    const configDir = mkdtempSync(path.join(os.tmpdir(), "codex-gateway-config-"));
    tempDirs.push(configDir);
    const configFile = path.join(configDir, "config.json");
    writeFileSync(
      configFile,
      JSON.stringify(
        {
          auth: { token: "saved-token" },
          storage: {
            uploadsDir: "custom-uploads",
            queueFile: "custom-queue.json"
          },
          codex: {
            sendMode: "desktop_bridge",
            appServer: {
              transport: "ws",
              wsUrl: "ws://127.0.0.1:43124/ws"
            },
            ipcSend: false
          }
        },
        null,
        2
      )
    );
    process.env.CODEX_MOBILE_CONFIG_FILE = configFile;

    loadGatewayRuntimeConfig();

    const persisted = JSON.parse(readFileSync(configFile, "utf8"));
    expect(persisted.auth.token).toBe("saved-token");
    expect(persisted.server).toEqual({ host: "0.0.0.0", port: 43124 });
    expect(persisted.storage.uploadsDir).toBe("custom-uploads");
    expect(persisted.storage.queueFile).toBe("custom-queue.json");
    expect(persisted.codex.sendMode).toBe("desktop_bridge");
    expect(persisted.codex.appServer.transport).toBe("ws");
    expect(persisted.codex.appServer.wsUrl).toBe("ws://127.0.0.1:43124/ws");
    expect(persisted.codex.ipcSend).toBe(false);
  });

  it("lets environment variables override public config fields", () => {
    const configDir = mkdtempSync(path.join(os.tmpdir(), "codex-gateway-config-"));
    tempDirs.push(configDir);
    const configFile = path.join(configDir, "config.json");
    writeFileSync(
      configFile,
      JSON.stringify({
        auth: { token: "nested-token" },
        server: { host: "127.0.0.1", port: 43127 },
        storage: { uploadsDir: "uploads" },
        codex: { sendMode: "desktop_bridge", appServer: { transport: "ws" }, ipcSend: false }
      })
    );
    process.env.CODEX_MOBILE_CONFIG_FILE = configFile;
    process.env.CODEX_MOBILE_TOKEN = "env-token";
    process.env.CODEX_MOBILE_HOST = "0.0.0.0";
    process.env.CODEX_MOBILE_PORT = "43128";
    process.env.CODEX_MOBILE_UPLOADS_DIR = path.join(configDir, "env-uploads");
    process.env.CODEX_MOBILE_QUEUE_FILE = path.join(configDir, "env-queue.json");
    process.env.CODEX_MOBILE_DOWNLOADS_DIR = path.join(configDir, "env-downloads");
    process.env.CODEX_MOBILE_MOBILE_DIST_DIR = path.join(configDir, "env-mobile-dist");
    process.env.CODEX_MOBILE_CODEX_HOME = path.join(configDir, "env-codex-home");
    process.env.CODEX_MOBILE_SEND_MODE = "official_persistence";
    process.env.CODEX_MOBILE_APP_SERVER_TRANSPORT = "stdio";
    process.env.CODEX_MOBILE_APP_SERVER_COMMAND = "env-codex-app-server";
    process.env.CODEX_MOBILE_APP_SERVER_WS_URL = "ws://env/ws";
    process.env.CODEX_MOBILE_IPC_SEND = "true";
    process.env.CODEX_MOBILE_PYTHON = "py-env";
    process.env.CODEX_MOBILE_PYWINAUTO_PATH = path.join(configDir, "env-pywinauto");

    const config = loadGatewayRuntimeConfig();

    expect(config.authToken).toBe("env-token");
    expect(config.host).toBe("0.0.0.0");
    expect(config.port).toBe(43128);
    expect(config.storage.uploadsDir).toBe(path.join(configDir, "env-uploads"));
    expect(config.storage.queueFile).toBe(path.join(configDir, "env-queue.json"));
    expect(config.storage.downloadsDir).toBe(path.join(configDir, "env-downloads"));
    expect(config.storage.mobileDistDir).toBe(path.join(configDir, "env-mobile-dist"));
    expect(config.codex.home).toBe(path.join(configDir, "env-codex-home"));
    expect(config.codex.sendMode).toBe("official_persistence");
    expect(config.codex.appServer.transport).toBe("stdio");
    expect(config.codex.appServer.command).toBe("env-codex-app-server");
    expect(config.codex.appServer.wsUrl).toBe("ws://env/ws");
    expect(config.codex.ipcSend).toBe(true);
    expect(config.desktopBridge.pythonCommand).toBe("py-env");
    expect(config.desktopBridge.pywinautoPath).toBe(path.join(configDir, "env-pywinauto"));
  });
});
