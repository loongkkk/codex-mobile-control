import os from "node:os";
import path from "node:path";

import { JsonRpcCodexAppServerClient } from "./app-server-client";
import { DefaultDesktopBridge, UnsupportedDesktopBridge } from "./desktop-bridge";
import { DesktopSendCoordinator } from "./desktop-send-coordinator";
import { FileSendCoordinator } from "./file-send-coordinator";
import { FileGatewayQueueStore } from "./gateway-queue-store";
import { ImageSendCoordinator } from "./image-send-coordinator";
import { IpcFirstCodexAppServerClient } from "./ipc-first-app-server-client";
import { SqliteLogRepository } from "./log-repository";
import { MobileUploadStore } from "./mobile-upload-store";
import { MobileGatewayRuntimeService, type MobileGatewaySendMode } from "./mobile-gateway-service";
import { CodexSidecarManager } from "./sidecar-manager";
import { SqliteStateRepository } from "./state-repository";
import type { MobileGatewayService } from "./service";
import { StdioCodexAppServerClient } from "./stdio-app-server-client";
import type { GatewayRuntimeConfig } from "./gateway-config";
import { WindowsComposerInjector } from "./windows-composer-injector";

type CreateMobileGatewayServiceOptions = {
  authToken: string;
  storage?: GatewayRuntimeConfig["storage"];
  codex?: GatewayRuntimeConfig["codex"];
  desktopBridge?: GatewayRuntimeConfig["desktopBridge"];
};

type AppServerTransport = "stdio" | "ws";

export function resolveConfiguredMobileSendMode(
  value: string | undefined
): MobileGatewaySendMode {
  return value?.trim() === "desktop_bridge" ? "desktop_bridge" : "official_persistence";
}

export function resolveConfiguredAppServerTransport(value: string | undefined): AppServerTransport {
  return value?.trim() === "ws" ? "ws" : "stdio";
}

export function resolveConfiguredAppServerWsUrl(value: string | undefined): string | null {
  const trimmed = value?.trim();
  return trimmed ? trimmed : null;
}

export function resolveConfiguredIpcSendEnabled(value: string | undefined): boolean {
  const normalized = value?.trim().toLowerCase();
  return normalized !== "0" && normalized !== "false" && normalized !== "off";
}

export async function createMobileGatewayService(
  options: CreateMobileGatewayServiceOptions
): Promise<MobileGatewayService> {
  const codexHome = options.codex?.home ?? path.join(os.homedir(), ".codex");
  const sendMode = options.codex?.sendMode ?? resolveConfiguredMobileSendMode(undefined);
  const appServerTransport =
    options.codex?.appServer.transport ?? resolveConfiguredAppServerTransport(undefined);
  let sidecar: CodexSidecarManager | null = null;
  const baseAppServer = await (async () => {
    if (appServerTransport === "ws") {
      const configuredWsUrl = resolveConfiguredAppServerWsUrl(options.codex?.appServer.wsUrl);
      if (configuredWsUrl) {
        return new JsonRpcCodexAppServerClient({ wsUrl: configuredWsUrl });
      }

      sidecar = new CodexSidecarManager();
      const { wsUrl } = await sidecar.ensureStarted();
      return new JsonRpcCodexAppServerClient({ wsUrl });
    }

    return new StdioCodexAppServerClient({
      command: options.codex?.appServer.command
    });
  })();
  await baseAppServer.connect();
  const appServer =
    sendMode === "official_persistence" &&
    (options.codex?.ipcSend ?? resolveConfiguredIpcSendEnabled(undefined))
      ? new IpcFirstCodexAppServerClient(baseAppServer)
      : baseAppServer;
  const desktopBridge =
    process.platform === "win32"
      ? new DefaultDesktopBridge({
          injector: new WindowsComposerInjector({
            pythonCommand: options.desktopBridge?.pythonCommand,
            pythonPathEntries: options.desktopBridge?.pywinautoPath
              ? [options.desktopBridge.pywinautoPath]
              : undefined
          })
        })
      : new UnsupportedDesktopBridge();
  const desktopSendCoordinator = new DesktopSendCoordinator({
    bridge: desktopBridge,
    readThread: (threadId) => appServer.readThread(threadId)
  });
  const uploadStore = new MobileUploadStore({
    rootDir: options.storage?.uploadsDir ?? path.resolve(process.cwd(), "mobile-uploads")
  });
  const imageSendCoordinator = new ImageSendCoordinator({
    bridge: desktopBridge,
    readThread: (threadId) => appServer.readThread(threadId)
  });
  const fileSendCoordinator = new FileSendCoordinator({
    bridge: desktopBridge,
    readThread: (threadId) => appServer.readThread(threadId)
  });

  const service = new MobileGatewayRuntimeService({
    authToken: options.authToken,
    appServer,
    stateRepository: new SqliteStateRepository(path.join(codexHome, "state_5.sqlite")),
    logRepository: new SqliteLogRepository(path.join(codexHome, "logs_2.sqlite")),
    sendMode,
    desktopSendCoordinator,
    imageSendCoordinator,
    fileSendCoordinator,
    queueStore: new FileGatewayQueueStore(
      options.storage?.queueFile ?? path.resolve(process.cwd(), "queued-messages.json")
    ),
    uploadStore
  });

  const stopAppServer = () => {
    sidecar?.stop();
    if (appServer instanceof IpcFirstCodexAppServerClient) {
      appServer.stop();
    } else if (baseAppServer instanceof StdioCodexAppServerClient) {
      baseAppServer.stop();
    }
  };
  process.once("exit", stopAppServer);
  process.once("SIGINT", stopAppServer);
  process.once("SIGTERM", stopAppServer);

  return service;
}
