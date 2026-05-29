import { DesktopDeepLinkNavigator } from "./desktop-deep-link-navigator";
import { DesktopInstallResolver } from "./desktop-install-resolver";
import { WindowsComposerInjector } from "./windows-composer-injector";

const DEFAULT_NAVIGATION_DELAY_MS = 2_500;

export type DesktopBridgeUnavailableReason =
  | "desktop_not_installed"
  | "desktop_not_running"
  | "desktop_window_not_found"
  | "desktop_navigation_failed"
  | "desktop_focus_failed"
  | "clipboard_unavailable"
  | "composer_input_failed"
  | "thread_not_sendable"
  | "unsupported_platform";

export type DesktopBridgeSendRequest = {
  threadId: string;
  title: string;
  text: string;
  clientMessageId: string;
  guide?: boolean;
};

export type DesktopBridgeImageSendRequest = {
  threadId: string;
  title: string;
  localImagePath: string;
  text?: string;
  clientMessageId: string;
};

export type DesktopBridgeImagesSendRequest = {
  threadId: string;
  title: string;
  localImagePaths: string[];
  text?: string;
  clientMessageId: string;
};

export type DesktopBridgeFilesSendRequest = {
  threadId: string;
  title: string;
  localFilePaths: string[];
  text?: string;
  clientMessageId: string;
};

export type DesktopBridgeSendResult =
  | { ok: true; confirmation: "observed" | "keystrokes_sent" }
  | { ok: false; reason: DesktopBridgeUnavailableReason; detail: string };

export type DesktopBridge = {
  sendTextToDesktopThread(request: DesktopBridgeSendRequest): Promise<DesktopBridgeSendResult>;
  sendImageToDesktopThread(
    request: DesktopBridgeImageSendRequest
  ): Promise<DesktopBridgeSendResult>;
  sendImagesToDesktopThread(
    request: DesktopBridgeImagesSendRequest
  ): Promise<DesktopBridgeSendResult>;
  sendFilesToDesktopThread(
    request: DesktopBridgeFilesSendRequest
  ): Promise<DesktopBridgeSendResult>;
};

export class UnsupportedDesktopBridge implements DesktopBridge {
  async sendTextToDesktopThread(): Promise<DesktopBridgeSendResult> {
    return this.unsupported();
  }

  async sendImageToDesktopThread(): Promise<DesktopBridgeSendResult> {
    return this.unsupported();
  }

  async sendImagesToDesktopThread(): Promise<DesktopBridgeSendResult> {
    return this.unsupported();
  }

  async sendFilesToDesktopThread(): Promise<DesktopBridgeSendResult> {
    return this.unsupported();
  }

  private unsupported(): DesktopBridgeSendResult {
    return {
      ok: false,
      reason: "unsupported_platform",
      detail: "当前平台暂不支持桌面窗口桥接发送"
    };
  }
}

type DefaultDesktopBridgeOptions = {
  resolver?: Pick<DesktopInstallResolver, "resolve">;
  navigator?: Pick<DesktopDeepLinkNavigator, "openThread">;
  injector?: Pick<WindowsComposerInjector, "sendText" | "sendImage" | "sendImages" | "sendFiles">;
  sleep?: (ms: number) => Promise<void>;
  navigationDelayMs?: number;
};

export class DefaultDesktopBridge implements DesktopBridge {
  private readonly resolver: Pick<DesktopInstallResolver, "resolve">;
  private readonly navigator: Pick<DesktopDeepLinkNavigator, "openThread">;
  private readonly injector: Pick<WindowsComposerInjector, "sendText" | "sendImage" | "sendImages" | "sendFiles">;
  private readonly sleep: (ms: number) => Promise<void>;
  private readonly navigationDelayMs: number;

  constructor(options: DefaultDesktopBridgeOptions = {}) {
    this.resolver = options.resolver ?? new DesktopInstallResolver();
    this.navigator = options.navigator ?? new DesktopDeepLinkNavigator();
    this.injector = options.injector ?? new WindowsComposerInjector();
    this.sleep = options.sleep ?? ((ms) => new Promise((resolve) => setTimeout(resolve, ms)));
    this.navigationDelayMs = options.navigationDelayMs ?? DEFAULT_NAVIGATION_DELAY_MS;
  }

  async sendTextToDesktopThread(request: DesktopBridgeSendRequest): Promise<DesktopBridgeSendResult> {
    const resolved = await this.resolver.resolve();
    if (!resolved.ok) {
      return resolved;
    }

    const navigation = await this.navigator.openThread({
      executablePath: resolved.executablePath,
      threadId: request.threadId
    });
    if (!navigation.ok) {
      return navigation;
    }

    await this.sleep(this.navigationDelayMs);
    const injected = await this.injector.sendText({
      text: request.text,
      runningProcessId: resolved.runningProcessId,
      guide: request.guide === true
    });
    if (!injected.ok) {
      return injected;
    }

    return {
      ok: true,
      confirmation: "keystrokes_sent"
    };
  }

  async sendImageToDesktopThread(
    request: DesktopBridgeImageSendRequest
  ): Promise<DesktopBridgeSendResult> {
    const resolved = await this.resolver.resolve();
    if (!resolved.ok) {
      return resolved;
    }

    const navigation = await this.navigator.openThread({
      executablePath: resolved.executablePath,
      threadId: request.threadId
    });
    if (!navigation.ok) {
      return navigation;
    }

    await this.sleep(this.navigationDelayMs);
    const injected = await this.injector.sendImage({
      imagePath: request.localImagePath,
      text: request.text,
      runningProcessId: resolved.runningProcessId
    });
    if (!injected.ok) {
      return injected;
    }

    return {
      ok: true,
      confirmation: "keystrokes_sent"
    };
  }

  async sendImagesToDesktopThread(
    request: DesktopBridgeImagesSendRequest
  ): Promise<DesktopBridgeSendResult> {
    const resolved = await this.resolver.resolve();
    if (!resolved.ok) {
      return resolved;
    }

    const navigation = await this.navigator.openThread({
      executablePath: resolved.executablePath,
      threadId: request.threadId
    });
    if (!navigation.ok) {
      return navigation;
    }

    await this.sleep(this.navigationDelayMs);
    const injected = await this.injector.sendImages({
      imagePaths: request.localImagePaths,
      text: request.text,
      runningProcessId: resolved.runningProcessId
    });
    if (!injected.ok) {
      return injected;
    }

    return {
      ok: true,
      confirmation: "keystrokes_sent"
    };
  }

  async sendFilesToDesktopThread(
    request: DesktopBridgeFilesSendRequest
  ): Promise<DesktopBridgeSendResult> {
    const resolved = await this.resolver.resolve();
    if (!resolved.ok) {
      return resolved;
    }

    const navigation = await this.navigator.openThread({
      executablePath: resolved.executablePath,
      threadId: request.threadId
    });
    if (!navigation.ok) {
      return navigation;
    }

    await this.sleep(this.navigationDelayMs);
    const injected = await this.injector.sendFiles({
      filePaths: request.localFilePaths,
      text: request.text,
      runningProcessId: resolved.runningProcessId
    });
    if (!injected.ok) {
      return injected;
    }

    return {
      ok: true,
      confirmation: "keystrokes_sent"
    };
  }
}
