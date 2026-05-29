import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";
import { randomBytes } from "node:crypto";

import type { MobileGatewaySendMode } from "./mobile-gateway-service";

export type GatewayRuntimeConfig = {
  authToken: string;
  host: string;
  port: number;
  publicBaseUrl?: string;
  storage: {
    uploadsDir: string;
    queueFile: string;
    downloadsDir: string;
    mobileDistDir: string;
  };
  codex: {
    home: string;
    sendMode: MobileGatewaySendMode;
    appServer: {
      transport: "stdio" | "ws";
      command?: string;
      wsUrl?: string;
    };
    ipcSend: boolean;
  };
  desktopBridge: {
    pythonCommand?: string;
    pywinautoPath?: string;
  };
  configFilePath: string;
  created: boolean;
};

type GatewayConfigFile = {
  token?: unknown;
  port?: unknown;
  auth?: unknown;
  server?: unknown;
  storage?: unknown;
  codex?: unknown;
  desktopBridge?: unknown;
};

type WritableGatewayConfig = {
  auth: { token: string } & Record<string, unknown>;
  server: { host: string; port: number } & Record<string, unknown>;
  storage: {
    uploadsDir: string;
    queueFile: string;
    downloadsDir: string;
    mobileDistDir: string;
  } & Record<string, unknown>;
  codex: {
    home: string;
    sendMode: MobileGatewaySendMode;
    appServer: { transport: "stdio" | "ws" } & Record<string, unknown>;
    ipcSend: boolean;
  } & Record<string, unknown>;
  desktopBridge: Record<string, unknown>;
} & Record<string, unknown>;

const DEFAULT_PORT = 43124;
const DEFAULT_HOST = "0.0.0.0";

export function loadGatewayRuntimeConfig(): GatewayRuntimeConfig {
  const configFilePath = path.resolve(
    process.env.CODEX_MOBILE_CONFIG_FILE ?? path.resolve(process.cwd(), "config.json")
  );
  const configDir = path.dirname(configFilePath);
  const fileConfig = readGatewayConfigFile(configFilePath);
  const auth = objectField(fileConfig, "auth");
  const server = objectField(fileConfig, "server");
  const storage = objectField(fileConfig, "storage");
  const codex = objectField(fileConfig, "codex");
  const appServer = objectField(codex, "appServer");
  const desktopBridge = objectField(fileConfig, "desktopBridge");
  const tokenFromFile = firstString(
    stringField(auth, "token"),
    typeof fileConfig?.token === "string" ? fileConfig.token : undefined
  );
  const created = fileConfig == null || !tokenFromFile;
  const generatedToken = created ? generateAuthToken() : null;
  const portFromFile = parsePort(stringOrNumberField(server, "port") ?? fileConfig?.port);
  const hostFromFile = firstString(stringField(server, "host"));
  const nextFileConfig = makeWritableConfig(fileConfig, {
    token: tokenFromFile || generatedToken || generateAuthToken(),
    host: hostFromFile ?? DEFAULT_HOST,
    port: portFromFile ?? DEFAULT_PORT
  });

  if (created || portFromFile == null || hostFromFile == null) {
    writeGatewayConfigFile(configFilePath, nextFileConfig);
  }

  const appServerTransport = parseAppServerTransport(
    process.env.CODEX_MOBILE_APP_SERVER_TRANSPORT ??
      stringField(appServer, "transport")
  );

  return {
    authToken: process.env.CODEX_MOBILE_TOKEN?.trim() || nextFileConfig.auth.token,
    host: firstString(process.env.CODEX_MOBILE_HOST, stringField(server, "host")) ?? DEFAULT_HOST,
    port: parsePort(process.env.CODEX_MOBILE_PORT) ?? portFromFile ?? DEFAULT_PORT,
    publicBaseUrl: trimUrl(process.env.CODEX_MOBILE_PUBLIC_BASE_URL ?? stringField(server, "publicBaseUrl")),
    storage: {
      uploadsDir: resolveConfigPath(
        process.env.CODEX_MOBILE_UPLOADS_DIR,
        stringField(storage, "uploadsDir"),
        "mobile-uploads",
        configDir
      ),
      queueFile: resolveConfigPath(
        process.env.CODEX_MOBILE_QUEUE_FILE,
        stringField(storage, "queueFile"),
        "queued-messages.json",
        configDir
      ),
      downloadsDir: resolveConfigPath(
        process.env.CODEX_MOBILE_DOWNLOADS_DIR,
        stringField(storage, "downloadsDir"),
        "downloads",
        configDir
      ),
      mobileDistDir: resolveConfigPath(
        process.env.CODEX_MOBILE_MOBILE_DIST_DIR,
        stringField(storage, "mobileDistDir"),
        path.join("..", "mobile", "dist"),
        configDir
      )
    },
    codex: {
      home: resolveConfigPath(
        process.env.CODEX_MOBILE_CODEX_HOME,
        stringField(codex, "home"),
        path.join(os.homedir(), ".codex"),
        configDir
      ),
      sendMode: parseSendMode(process.env.CODEX_MOBILE_SEND_MODE ?? stringField(codex, "sendMode")),
      appServer: {
        transport: appServerTransport,
        ...(firstString(process.env.CODEX_MOBILE_APP_SERVER_COMMAND, stringField(appServer, "command"))
          ? { command: firstString(process.env.CODEX_MOBILE_APP_SERVER_COMMAND, stringField(appServer, "command")) }
          : {}),
        ...(firstString(process.env.CODEX_MOBILE_APP_SERVER_WS_URL, stringField(appServer, "wsUrl"))
          ? { wsUrl: firstString(process.env.CODEX_MOBILE_APP_SERVER_WS_URL, stringField(appServer, "wsUrl")) }
          : {})
      },
      ipcSend: parseBoolean(
        process.env.CODEX_MOBILE_IPC_SEND ?? stringOrBooleanField(codex, "ipcSend"),
        true
      )
    },
    desktopBridge: {
      ...(firstString(process.env.CODEX_MOBILE_PYTHON, stringField(desktopBridge, "pythonCommand"))
        ? { pythonCommand: firstString(process.env.CODEX_MOBILE_PYTHON, stringField(desktopBridge, "pythonCommand")) }
        : {}),
      ...(firstString(process.env.CODEX_MOBILE_PYWINAUTO_PATH, stringField(desktopBridge, "pywinautoPath"))
        ? {
            pywinautoPath: resolveConfigPath(
              process.env.CODEX_MOBILE_PYWINAUTO_PATH,
              stringField(desktopBridge, "pywinautoPath"),
              "",
              configDir
            )
          }
        : {})
    },
    configFilePath,
    created
  };
}

function readGatewayConfigFile(filePath: string): GatewayConfigFile | null {
  const stats = statSync(filePath, { throwIfNoEntry: false });
  if (!stats?.isFile()) {
    return null;
  }

  return JSON.parse(readFileSync(filePath, "utf8")) as GatewayConfigFile;
}

function writeGatewayConfigFile(
  filePath: string,
  config: WritableGatewayConfig
): void {
  const directory = path.dirname(filePath);
  if (!existsSync(directory)) {
    mkdirSync(directory, { recursive: true });
  }
  writeFileSync(filePath, JSON.stringify(config, null, 2) + "\n", "utf8");
}

function generateAuthToken(): string {
  return randomBytes(32)
    .toString("base64")
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/g, "");
}

function parsePort(value: unknown): number | null {
  if (typeof value !== "string" && typeof value !== "number") {
    return null;
  }
  const parsed = Number.parseInt(String(value), 10);
  return Number.isFinite(parsed) && parsed > 0 && parsed <= 65_535 ? parsed : null;
}

function makeWritableConfig(
  existing: GatewayConfigFile | null,
  config: { token: string; host: string; port: number }
): WritableGatewayConfig {
  const base = objectRecord(existing);
  const auth = objectField(existing, "auth") ?? {};
  const server = objectField(existing, "server") ?? {};
  const storage = objectField(existing, "storage") ?? {};
  const codex = objectField(existing, "codex") ?? {};
  const appServer = objectField(codex, "appServer") ?? {};
  const desktopBridge = objectField(existing, "desktopBridge") ?? {};

  return {
    ...base,
    auth: {
      ...auth,
      token: config.token
    },
    server: {
      ...server,
      host: config.host,
      port: config.port
    },
    storage: {
      ...storage,
      uploadsDir: firstString(stringField(storage, "uploadsDir")) ?? "mobile-uploads",
      queueFile: firstString(stringField(storage, "queueFile")) ?? "queued-messages.json",
      downloadsDir: firstString(stringField(storage, "downloadsDir")) ?? "downloads",
      mobileDistDir: firstString(stringField(storage, "mobileDistDir")) ?? "../mobile/dist"
    },
    codex: {
      ...codex,
      home: firstString(stringField(codex, "home")) ?? "",
      sendMode: parseSendMode(stringField(codex, "sendMode")),
      appServer: {
        ...appServer,
        transport: parseAppServerTransport(stringField(appServer, "transport"))
      },
      ipcSend: parseBoolean(stringOrBooleanField(codex, "ipcSend"), true)
    },
    desktopBridge: {
      ...desktopBridge
    }
  };
}

function objectRecord(value: unknown): Record<string, unknown> {
  return value && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

function objectField(value: unknown, key: string): Record<string, unknown> | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return undefined;
  }
  const field = (value as Record<string, unknown>)[key];
  return field && typeof field === "object" && !Array.isArray(field)
    ? (field as Record<string, unknown>)
    : undefined;
}

function stringField(value: Record<string, unknown> | undefined, key: string): string | undefined {
  const field = value?.[key];
  return typeof field === "string" ? field : undefined;
}

function stringOrNumberField(
  value: Record<string, unknown> | undefined,
  key: string
): string | number | undefined {
  const field = value?.[key];
  return typeof field === "string" || typeof field === "number" ? field : undefined;
}

function stringOrBooleanField(
  value: Record<string, unknown> | undefined,
  key: string
): string | boolean | undefined {
  const field = value?.[key];
  return typeof field === "string" || typeof field === "boolean" ? field : undefined;
}

function firstString(...values: Array<string | undefined>): string | undefined {
  for (const value of values) {
    const trimmed = value?.trim();
    if (trimmed) {
      return trimmed;
    }
  }
  return undefined;
}

function resolveConfigPath(
  envValue: string | undefined,
  fileValue: string | undefined,
  fallback: string,
  configDir: string
): string {
  const envPath = firstString(envValue);
  if (envPath) {
    return path.resolve(envPath);
  }

  const configuredPath = firstString(fileValue) ?? fallback;
  return path.isAbsolute(configuredPath)
    ? path.resolve(configuredPath)
    : path.resolve(configDir, configuredPath);
}

function trimUrl(value: string | undefined): string | undefined {
  const trimmed = value?.trim().replace(/\/+$/, "");
  return trimmed || undefined;
}

function parseSendMode(value: string | undefined): MobileGatewaySendMode {
  return value?.trim() === "desktop_bridge" ? "desktop_bridge" : "official_persistence";
}

function parseAppServerTransport(value: string | undefined): "stdio" | "ws" {
  return value?.trim() === "ws" ? "ws" : "stdio";
}

function parseBoolean(value: string | boolean | undefined, defaultValue: boolean): boolean {
  if (typeof value === "boolean") {
    return value;
  }
  const normalized = value?.trim().toLowerCase();
  if (!normalized) {
    return defaultValue;
  }
  if (normalized === "0" || normalized === "false" || normalized === "off" || normalized === "no") {
    return false;
  }
  if (normalized === "1" || normalized === "true" || normalized === "on" || normalized === "yes") {
    return true;
  }
  return defaultValue;
}
