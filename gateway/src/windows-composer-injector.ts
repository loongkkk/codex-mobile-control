import { execFile as execFileCallback } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { promisify } from "node:util";

import type { DesktopBridgeUnavailableReason } from "./desktop-bridge";

const execFileDefault = promisify(execFileCallback);
const TEXT_HELPER_TIMEOUT_MS = 45_000;
const ATTACHMENT_HELPER_TIMEOUT_MS = 60_000;

type ExecFileOptions = {
  timeout?: number;
  windowsHide?: boolean;
  env?: NodeJS.ProcessEnv;
};

type InjectorDependencies = {
  execFile?: (
    file: string,
    args: string[],
    options?: ExecFileOptions
  ) => Promise<{ stdout: string; stderr: string }>;
  pythonCommand?: string;
  pythonArgs?: string[];
  pythonPathEntries?: string[];
};

export type ComposerInjectionResult =
  | { ok: true }
  | { ok: false; reason: DesktopBridgeUnavailableReason; detail: string };

type HelperPayload =
  | {
      mode: "text";
      text: string;
      runningProcessId: number | null;
      guide?: boolean;
    }
  | {
      mode: "image";
      imagePath: string;
      text?: string;
      runningProcessId: number | null;
    }
  | {
      mode: "images";
      imagePaths: string[];
      text?: string;
      runningProcessId: number | null;
    }
  | {
      mode: "files";
      filePaths: string[];
      text?: string;
      runningProcessId: number | null;
    };

export class WindowsComposerInjector {
  private readonly execFile: NonNullable<InjectorDependencies["execFile"]>;
  private readonly pythonCommand: string;
  private readonly pythonArgs: string[];
  private readonly pythonPathEntries: string[];

  constructor(dependencies: InjectorDependencies = {}) {
    this.execFile = dependencies.execFile ?? execFileDefault;
    this.pythonCommand = dependencies.pythonCommand ?? "py";
    this.pythonArgs =
      dependencies.pythonArgs ?? defaultPythonArgs(this.pythonCommand);
    this.pythonPathEntries =
      dependencies.pythonPathEntries ?? defaultPythonPathEntries(process.cwd());
  }

  async sendText(options: {
    text: string;
    runningProcessId: number | null;
    guide?: boolean;
  }): Promise<ComposerInjectionResult> {
    return this.runHelper({
      mode: "text",
      text: options.text,
      runningProcessId: options.runningProcessId,
      guide: options.guide === true
    });
  }

  async sendImage(options: {
    imagePath: string;
    text?: string;
    runningProcessId: number | null;
  }): Promise<ComposerInjectionResult> {
    return this.runHelper({
      mode: "image",
      imagePath: options.imagePath,
      text: options.text,
      runningProcessId: options.runningProcessId
    });
  }

  async sendImages(options: {
    imagePaths: string[];
    text?: string;
    runningProcessId: number | null;
  }): Promise<ComposerInjectionResult> {
    return this.runHelper({
      mode: "images",
      imagePaths: options.imagePaths,
      text: options.text,
      runningProcessId: options.runningProcessId
    });
  }

  async sendFiles(options: {
    filePaths: string[];
    text?: string;
    runningProcessId: number | null;
  }): Promise<ComposerInjectionResult> {
    return this.runHelper({
      mode: "files",
      filePaths: options.filePaths,
      text: options.text,
      runningProcessId: options.runningProcessId
    });
  }

  private async runHelper(payload: HelperPayload): Promise<ComposerInjectionResult> {
    const tempDir = await mkdtemp(path.join(os.tmpdir(), "codex-mobile-pywinauto-"));
    const scriptPath = path.join(tempDir, "composer_helper.py");
    const payloadPath = path.join(tempDir, "payload.json");
    const stagePath = path.join(tempDir, "composer-stage.json");

    try {
      await writeFile(scriptPath, PYTHON_COMPOSER_HELPER, "utf8");
      await writeFile(payloadPath, JSON.stringify(payload), "utf8");
      const { stdout } = await this.execFile(
        this.pythonCommand,
        [...this.pythonArgs, scriptPath, payloadPath],
        {
          timeout: helperTimeoutMs(payload),
          windowsHide: true,
          env: {
            ...this.buildPythonEnv(),
            CODEX_MOBILE_PYWINAUTO_STAGE_PATH: stagePath
          }
        }
      );
      return parseHelperResult(stdout);
    } catch (error) {
      const lastStage = await readHelperStage(stagePath);
      return {
        ok: false,
        reason: "composer_input_failed",
        detail: formatHelperFailureDetail(error, lastStage)
      };
    } finally {
      await rm(tempDir, { force: true, recursive: true }).catch(() => undefined);
    }
  }

  private buildPythonEnv(): NodeJS.ProcessEnv {
    const existingPythonPath = process.env.PYTHONPATH;
    const pythonPathEntries = this.pythonPathEntries.filter((entry) => existsSync(entry));
    return {
      ...process.env,
      PYTHONIOENCODING: "utf-8",
      ...(pythonPathEntries.length > 0 || existingPythonPath
        ? {
            PYTHONPATH: [
              ...pythonPathEntries,
              ...(existingPythonPath ? [existingPythonPath] : [])
            ].join(path.delimiter)
          }
        : {})
    };
  }
}

function helperTimeoutMs(payload: HelperPayload): number {
  return payload.mode === "text" ? TEXT_HELPER_TIMEOUT_MS : ATTACHMENT_HELPER_TIMEOUT_MS;
}

function formatHelperFailureDetail(error: unknown, stage: string | null): string {
  if (isKilledHelperTimeout(error)) {
    return formatKilledHelperTimeout(error, stage);
  }

  return `桌面输入失败: ${formatHelperExecError(error)}`;
}

function formatKilledHelperTimeout(error: unknown, stage: string | null): string {
  const diagnostics = helperExecDiagnostics(error);
  const detailParts = [
    ...(stage ? [`阶段: ${stage}`] : []),
    ...diagnostics
  ];
  return detailParts.length > 0
    ? `桌面输入超时，可点重试再次发送（${detailParts.join("; ")}）`
    : "桌面输入超时，可点重试再次发送";
}

function isKilledHelperTimeout(error: unknown): boolean {
  if (!error || typeof error !== "object") {
    return false;
  }

  const record = error as Record<string, unknown>;
  return record.killed === true && record.signal === "SIGTERM";
}

function formatHelperExecError(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error);
  const diagnostics = helperExecDiagnostics(error);
  return diagnostics.length > 0 ? `${message}（${diagnostics.join("; ")}）` : message;
}

function helperExecDiagnostics(error: unknown): string[] {
  if (!error || typeof error !== "object") {
    return [];
  }

  const record = error as Record<string, unknown>;
  const diagnostics: string[] = [];
  if (typeof record.killed === "boolean") {
    diagnostics.push(`killed=${record.killed}`);
  }
  if (typeof record.signal === "string" && record.signal.trim()) {
    diagnostics.push(`signal=${record.signal}`);
  }
  if (record.code !== undefined && record.code !== null) {
    diagnostics.push(`code=${String(record.code)}`);
  }
  const stdout = compactExecOutput(record.stdout);
  if (stdout) {
    diagnostics.push(`stdout=${stdout}`);
  }
  const stderr = compactExecOutput(record.stderr);
  if (stderr) {
    diagnostics.push(`stderr=${stderr}`);
  }
  return diagnostics;
}

function compactExecOutput(value: unknown): string | null {
  if (value === undefined || value === null) {
    return null;
  }
  const text = String(value).trim().replace(/\s+/g, " ");
  if (!text) {
    return null;
  }
  return text.length > 1_000 ? `${text.slice(0, 1_000)}...` : text;
}

async function readHelperStage(stagePath: string): Promise<string | null> {
  try {
    const raw = await readFile(stagePath, "utf8");
    const parsed = JSON.parse(raw) as { stage?: unknown };
    const stage = typeof parsed.stage === "string" ? parsed.stage.trim() : "";
    return stage || null;
  } catch {
    return null;
  }
}

function defaultPythonArgs(command: string): string[] {
  const baseName = path.basename(command).toLowerCase();
  return baseName === "py" || baseName === "py.exe" ? ["-3.8"] : [];
}

function defaultPythonPathEntries(cwd: string): string[] {
  return [
    path.resolve(cwd, ".pywinauto"),
    path.resolve(cwd, "pywinauto-pkgs"),
    path.resolve(cwd, "../gateway/.pywinauto"),
    path.resolve(cwd, "../gateway/pywinauto-pkgs")
  ];
}

function parseHelperResult(stdout: string): ComposerInjectionResult {
  const line =
    stdout
      .trim()
      .split(/\r?\n/)
      .reverse()
      .find((item) => item.trim().startsWith("{")) ?? "";
  if (!line) {
    return {
      ok: false,
      reason: "composer_input_failed",
      detail: "pywinauto 输入脚本没有返回结果"
    };
  }

  return JSON.parse(line) as ComposerInjectionResult;
}

const PYTHON_COMPOSER_HELPER = String.raw`
import json
import os
import struct
import sys
import time


CURRENT_STAGE = "初始化"
STAGE_PATH = os.environ.get("CODEX_MOBILE_PYWINAUTO_STAGE_PATH")


def write_stage_file(stage):
    if not STAGE_PATH:
        return
    try:
        with open(STAGE_PATH, "w", encoding="utf-8") as handle:
            json.dump({"stage": stage, "updatedAt": time.time()}, handle, ensure_ascii=False)
    except Exception:
        pass


def set_stage(stage):
    global CURRENT_STAGE
    CURRENT_STAGE = stage
    write_stage_file(stage)


def emit(obj):
    print(json.dumps(obj, ensure_ascii=False))
    raise SystemExit(0)


try:
    from pywinauto import Application, Desktop, keyboard, timings
    import win32clipboard
    import win32con
    import win32gui
except Exception as exc:
    emit({
        "ok": False,
        "reason": "composer_input_failed",
        "detail": "pywinauto 运行环境不可用: " + str(exc),
    })


def load_payload():
    with open(sys.argv[1], "r", encoding="utf-8-sig") as handle:
        return json.load(handle)


def find_codex_window(running_process_id):
    set_stage("查找 Codex 窗口")
    if running_process_id is not None:
        app = Application(backend="uia").connect(process=int(running_process_id), timeout=8)
        return app.top_window()

    desktop = Desktop(backend="uia")
    window = desktop.window(
        title="Codex",
        control_type="Window",
        class_name="Chrome_WidgetWin_1",
    )
    window.wait("visible", timeout=8)
    return window


def get_window_handle(window):
    handle = getattr(window, "handle", None)
    if handle is not None:
        return int(handle)
    return int(window.wrapper_object().handle)


def get_root_window_handle(handle):
    root = win32gui.GetAncestor(handle, win32con.GA_ROOT)
    return int(root or handle)


def describe_foreground_window():
    handle = int(win32gui.GetForegroundWindow())
    if handle == 0:
        return "foreground=<none>"
    root = get_root_window_handle(handle)
    title = win32gui.GetWindowText(root)
    class_name = win32gui.GetClassName(root)
    return "foreground hwnd=%s root=%s class=%s title=%r" % (
        handle,
        root,
        class_name,
        title,
    )


def ensure_codex_foreground(window, stage):
    set_stage(stage)
    expected = get_root_window_handle(get_window_handle(window))
    for attempt in range(3):
        foreground = int(win32gui.GetForegroundWindow())
        foreground_root = get_root_window_handle(foreground) if foreground else 0
        if foreground_root == expected:
            return
        if attempt < 2:
            try:
                window.restore()
            except Exception:
                pass
            window.set_focus()
            time.sleep(0.25)
    raise RuntimeError(
        "Codex 窗口未获得前台焦点，停止桌面发送（阶段: %s; expected root=%s; %s）"
        % (stage, expected, describe_foreground_window())
    )


def focus_composer(window):
    set_stage("聚焦 Codex 窗口")
    try:
        window.restore()
    except Exception:
        pass
    window.set_focus()
    time.sleep(0.6)
    ensure_codex_foreground(window, "点击输入框前")
    set_stage("点击输入框")
    rect = window.rectangle()
    x = int(rect.left + rect.width() * 0.32)
    y = int(rect.top + rect.height() * 0.90)
    window.click_input(coords=(x - rect.left, y - rect.top))
    time.sleep(0.3)


def clear_composer(window):
    ensure_codex_foreground(window, "清空输入框前")
    for _ in range(3):
        set_stage("清空输入框")
        window.type_keys("^a", pause=0.04, set_foreground=True)
        time.sleep(0.08)
        keyboard.send_keys("{BACKSPACE}", pause=0.04)
        time.sleep(0.16)


def escape_send_keys_text(text):
    replacements = {
        "{": "{{}",
        "}": "{}}",
        "+": "{+}",
        "^": "{^}",
        "%": "{%}",
        "~": "{~}",
        "(": "{(}",
        ")": "{)}",
    }
    return "".join(replacements.get(ch, ch) for ch in text)


def type_keys_compat(window, text, pause=0.01, with_spaces=True, set_foreground=True):
    try:
        window.type_keys(
            text,
            pause=pause,
            with_spaces=with_spaces,
            set_foreground=set_foreground,
            vk_packet=True,
        )
    except TypeError as exc:
        if "vk_packet" not in str(exc):
            raise
        window.type_keys(
            text,
            pause=pause,
            with_spaces=with_spaces,
            set_foreground=set_foreground,
        )


def type_text_into_composer(window, text):
    ensure_codex_foreground(window, "输入文字前")
    normalized = str(text).replace("\r\n", "\n").replace("\r", "\n")
    lines = normalized.split("\n")
    for index, line in enumerate(lines):
        if line:
            set_stage("输入文字")
            type_keys_compat(
                window,
                escape_send_keys_text(line),
                pause=0.01,
                with_spaces=True,
                set_foreground=True,
            )
        if index < len(lines) - 1:
            set_stage("输入换行")
            keyboard.send_keys("+{ENTER}", pause=0.04)
            time.sleep(0.05)


def submit_composer(guide=False):
    time.sleep(0.2)
    if guide:
        keyboard.send_keys("^{ENTER}", pause=0.04)
    else:
        keyboard.send_keys("{ENTER}", pause=0.04)
    time.sleep(0.8)


def build_hdrop_data(paths):
    # DROPFILES: pFiles, POINT(x,y), fNC, fWide, followed by UTF-16LE file list.
    header = struct.pack("<IiiII", 20, 0, 0, 0, 1)
    file_list = ("\0".join(str(path) for path in paths) + "\0\0").encode("utf-16le")
    return header + file_list


def set_clipboard_files(paths):
    set_stage("写入文件剪贴板")
    win32clipboard.OpenClipboard()
    try:
        win32clipboard.EmptyClipboard()
        win32clipboard.SetClipboardData(win32con.CF_HDROP, build_hdrop_data(paths))
    finally:
        win32clipboard.CloseClipboard()


def paste_clipboard():
    keyboard.send_keys("^v", pause=0.04)
    time.sleep(1.0)


def settle_pasted_files():
    time.sleep(3.0)


def refocus_composer_after_paste(window):
    set_stage("粘贴后重新聚焦 Codex 窗口")
    try:
        window.restore()
    except Exception:
        pass
    window.set_focus()
    time.sleep(0.2)
    ensure_codex_foreground(window, "粘贴后点击输入框前")
    set_stage("粘贴后点击输入框")
    rect = window.rectangle()
    x = int(rect.left + rect.width() * 0.50)
    y = int(rect.top + rect.height() * 0.885)
    window.click_input(coords=(x - rect.left, y - rect.top))
    time.sleep(0.25)


def send_text(window, payload):
    focus_composer(window)
    clear_composer(window)
    type_text_into_composer(window, payload.get("text", ""))
    ensure_codex_foreground(window, "发送前")
    submit_composer(bool(payload.get("guide")))


def send_image(window, payload):
    focus_composer(window)
    clear_composer(window)
    caption = payload.get("text")
    if caption:
        type_text_into_composer(window, caption)
    set_clipboard_files([payload["imagePath"]])
    ensure_codex_foreground(window, "粘贴前")
    paste_clipboard()
    settle_pasted_files()
    refocus_composer_after_paste(window)
    ensure_codex_foreground(window, "发送前")
    submit_composer()


def send_images(window, payload):
    focus_composer(window)
    clear_composer(window)
    image_paths = payload.get("imagePaths") or []
    if not image_paths:
        emit({
            "ok": False,
            "reason": "composer_input_failed",
            "detail": "缺少图片路径",
        })
    caption = payload.get("text")
    if caption:
        type_text_into_composer(window, caption)
    set_clipboard_files(image_paths)
    ensure_codex_foreground(window, "粘贴前")
    paste_clipboard()
    settle_pasted_files()
    refocus_composer_after_paste(window)
    ensure_codex_foreground(window, "发送前")
    submit_composer()


def send_files(window, payload):
    focus_composer(window)
    clear_composer(window)
    file_paths = payload.get("filePaths") or []
    if not file_paths:
        emit({
            "ok": False,
            "reason": "composer_input_failed",
            "detail": "缺少文件路径",
        })
    caption = payload.get("text")
    if caption:
        type_text_into_composer(window, caption)
    set_clipboard_files(file_paths)
    ensure_codex_foreground(window, "粘贴前")
    paste_clipboard()
    settle_pasted_files()
    refocus_composer_after_paste(window)
    ensure_codex_foreground(window, "发送前")
    submit_composer()


def main():
    payload = load_payload()
    window = find_codex_window(payload.get("runningProcessId"))
    mode = payload.get("mode")
    if mode == "text":
        send_text(window, payload)
    elif mode == "image":
        send_image(window, payload)
    elif mode == "images":
        send_images(window, payload)
    elif mode == "files":
        send_files(window, payload)
    else:
        emit({
            "ok": False,
            "reason": "composer_input_failed",
            "detail": "未知桌面输入模式: " + str(mode),
        })
    emit({"ok": True})


try:
    main()
except Exception as exc:
    emit({
        "ok": False,
        "reason": "composer_input_failed",
        "detail": "pywinauto 输入失败[" + CURRENT_STAGE + "]: " + str(exc),
    })
`;
