import type { SendMessageResponse } from "../../shared/src/api";
import type {
  DesktopBridge,
  DesktopBridgeFilesSendRequest
} from "./desktop-bridge";
import { GatewayHttpError } from "./gateway-error";

type FileSendCoordinatorOptions = {
  bridge: Pick<DesktopBridge, "sendFilesToDesktopThread">;
  readThread: (threadId: string) => Promise<{ updatedAt: number; turns: Array<{ id: string }> }>;
};

export class FileSendCoordinator {
  constructor(private readonly options: FileSendCoordinatorOptions) {}

  async sendMany(request: {
    threadId: string;
    title: string;
    clientMessageId: string;
    localFilePaths: string[];
    text?: string;
  }): Promise<SendMessageResponse> {
    const bridgeRequest: DesktopBridgeFilesSendRequest = {
      threadId: request.threadId,
      title: request.title,
      localFilePaths: request.localFilePaths,
      text: request.text,
      clientMessageId: request.clientMessageId
    };

    const bridgeResult = await this.options.bridge.sendFilesToDesktopThread(bridgeRequest);
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
