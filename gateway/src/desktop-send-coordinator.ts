import type { SendMessageResponse } from "../../shared/src/api";
import type { CodexThread } from "./mobile-gateway-service";
import type { DesktopBridge, DesktopBridgeSendRequest } from "./desktop-bridge";
import { GatewayHttpError } from "./gateway-error";

type DesktopSendCoordinatorOptions = {
  bridge: DesktopBridge;
  readThread: (threadId: string) => Promise<Pick<CodexThread, "updatedAt" | "turns">>;
  now?: () => number;
  sleep?: (ms: number) => Promise<void>;
  confirmationTimeoutMs?: number;
  confirmationPollMs?: number;
};

export class DesktopSendCoordinator {
  constructor(private readonly options: DesktopSendCoordinatorOptions) {}

  async send(request: DesktopBridgeSendRequest): Promise<SendMessageResponse> {
    const bridgeResult = await this.options.bridge.sendTextToDesktopThread(request);

    if (!bridgeResult.ok) {
      throw new GatewayHttpError(409, {
        error: "desktop_bridge_unavailable",
        reason: bridgeResult.reason,
        message: bridgeResult.detail
      });
    }

    return {
      accepted: true,
      threadId: request.threadId,
      clientMessageId: request.clientMessageId,
      sendPath: "desktop_bridge",
      confirmation: bridgeResult.confirmation
    };
  }
}
