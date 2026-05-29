import type { SendMessageResponse } from "../../shared/src/api";
import type {
  DesktopBridge,
  DesktopBridgeImageSendRequest,
  DesktopBridgeImagesSendRequest
} from "./desktop-bridge";
import { GatewayHttpError } from "./gateway-error";

type ImageSendCoordinatorOptions = {
  bridge: Pick<DesktopBridge, "sendImageToDesktopThread" | "sendImagesToDesktopThread">;
  readThread: (threadId: string) => Promise<{ updatedAt: number; turns: Array<{ id: string }> }>;
  now?: () => number;
  sleep?: (ms: number) => Promise<void>;
  confirmationTimeoutMs?: number;
  confirmationPollMs?: number;
};

export class ImageSendCoordinator {
  constructor(private readonly options: ImageSendCoordinatorOptions) {}

  async send(request: {
    threadId: string;
    title: string;
    clientMessageId: string;
    localImagePath: string;
    text?: string;
  }): Promise<SendMessageResponse> {
    const bridgeRequest: DesktopBridgeImageSendRequest = {
      threadId: request.threadId,
      title: request.title,
      localImagePath: request.localImagePath,
      text: request.text,
      clientMessageId: request.clientMessageId
    };

    const bridgeResult = await this.options.bridge.sendImageToDesktopThread(bridgeRequest);
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

  async sendMany(request: {
    threadId: string;
    title: string;
    clientMessageId: string;
    localImagePaths: string[];
    text?: string;
  }): Promise<SendMessageResponse> {
    const bridgeRequest: DesktopBridgeImagesSendRequest = {
      threadId: request.threadId,
      title: request.title,
      localImagePaths: request.localImagePaths,
      text: request.text,
      clientMessageId: request.clientMessageId
    };

    const bridgeResult = await this.options.bridge.sendImagesToDesktopThread(bridgeRequest);
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
