import { readFile, writeFile } from "node:fs/promises";

import { describe, expect, it, vi } from "vitest";

import { WindowsComposerInjector } from "../src/windows-composer-injector";

describe("WindowsComposerInjector", () => {
  it("runs a Python pywinauto helper for text input instead of PowerShell SendKeys", async () => {
    let command = "";
    let args: string[] = [];
    let payloadText = "";
    let scriptText = "";
    const injector = new WindowsComposerInjector({
      pythonCommand: "py",
      pythonArgs: ["-3.8"],
      execFile: vi.fn(async (file, execArgs) => {
        command = file;
        args = execArgs;
        scriptText = await readFile(execArgs.at(-2) ?? "", "utf8");
        payloadText = await readFile(execArgs.at(-1) ?? "", "utf8");
        return {
          stdout: '{"ok":true}\n',
          stderr: ""
        };
      })
    });

    await expect(
      injector.sendText({
        text: "你好，桌面 Codex",
        runningProcessId: 22056
      })
    ).resolves.toEqual({ ok: true });

    expect(command).toBe("py");
    expect(args.slice(0, 1)).toEqual(["-3.8"]);
    expect(JSON.parse(payloadText)).toMatchObject({
      mode: "text",
      text: "你好，桌面 Codex",
      runningProcessId: 22056
    });
    expect(scriptText).toContain("pywinauto");
    expect(scriptText).toContain("type_text_into_composer");
    expect(scriptText).not.toContain("WScript.Shell");
    expect(scriptText).not.toContain("powershell");
  });

  it("falls back when pywinauto type_keys does not support vk_packet", async () => {
    let scriptText = "";
    const injector = new WindowsComposerInjector({
      pythonCommand: "python",
      pythonArgs: [],
      execFile: vi.fn(async (_file, execArgs) => {
        scriptText = await readFile(execArgs.at(-2) ?? "", "utf8");
        return {
          stdout: '{"ok":true}\n',
          stderr: ""
        };
      })
    });

    await injector.sendText({
      text: "compat check",
      runningProcessId: 22056
    });

    expect(scriptText).toContain("def type_keys_compat(");
    expect(scriptText).toContain("vk_packet=True");
    expect(scriptText).toContain('if "vk_packet" not in str(exc):');
    expect(scriptText).toContain("type_keys_compat(");
  });

  it("uses Ctrl+Enter for guide text sends", async () => {
    let payloadText = "";
    let scriptText = "";
    const injector = new WindowsComposerInjector({
      pythonCommand: "python",
      pythonArgs: [],
      execFile: vi.fn(async (_file, execArgs) => {
        scriptText = await readFile(execArgs.at(-2) ?? "", "utf8");
        payloadText = await readFile(execArgs.at(-1) ?? "", "utf8");
        return {
          stdout: '{"ok":true}\n',
          stderr: ""
        };
      })
    });

    await (injector as any).sendText({
      text: "运行中补充一句",
      runningProcessId: 22056,
      guide: true
    });

    expect(JSON.parse(payloadText)).toMatchObject({
      mode: "text",
      text: "运行中补充一句",
      runningProcessId: 22056,
      guide: true
    });
    expect(scriptText).toContain("def submit_composer(guide=False):");
    expect(scriptText).toContain('keyboard.send_keys("^{ENTER}", pause=0.04)');
    expect(scriptText).toContain("submit_composer(bool(payload.get(\"guide\")))");
  });

  it("passes image send requests through the Python helper without a PowerShell fallback", async () => {
    let payloadText = "";
    let scriptText = "";
    const injector = new WindowsComposerInjector({
      pythonCommand: "python",
      pythonArgs: [],
      execFile: vi.fn(async (_file, execArgs) => {
        scriptText = await readFile(execArgs.at(-2) ?? "", "utf8");
        payloadText = await readFile(execArgs.at(-1) ?? "", "utf8");
        return {
          stdout: '{"ok":true}\n',
          stderr: ""
        };
      })
    });

    await expect(
      injector.sendImage({
        imagePath: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png",
        text: "请分析这张图",
        runningProcessId: 22056
      })
    ).resolves.toEqual({ ok: true });

    expect(JSON.parse(payloadText)).toMatchObject({
      mode: "image",
      imagePath: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png",
      text: "请分析这张图",
      runningProcessId: 22056
    });
    expect(scriptText).toContain("set_clipboard_files");
    expect(scriptText).not.toContain("SetFileDropList");
    expect(scriptText).not.toContain("WScript.Shell");
  });

  it("uses longer Python helper timeouts for text image and file sends", async () => {
    const timeouts: Array<number | undefined> = [];
    const injector = new WindowsComposerInjector({
      pythonCommand: "python",
      pythonArgs: [],
      execFile: vi.fn(async (_file, _execArgs, options) => {
        timeouts.push(options?.timeout);
        return {
          stdout: '{"ok":true}\n',
          stderr: ""
        };
      })
    });

    await injector.sendText({
      text: "普通文字",
      runningProcessId: 22056
    });
    await injector.sendImages({
      imagePaths: ["D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\one.png"],
      runningProcessId: 22056
    });
    await injector.sendFiles({
      filePaths: ["D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\report.zip"],
      runningProcessId: 22056
    });

    expect(timeouts).toEqual([45_000, 60_000, 60_000]);
  });

  it("writes a helper stage heartbeat while the Python script is running", async () => {
    let scriptText = "";
    const injector = new WindowsComposerInjector({
      pythonCommand: "python",
      pythonArgs: [],
      execFile: vi.fn(async (_file, execArgs) => {
        scriptText = await readFile(execArgs.at(-2) ?? "", "utf8");
        return {
          stdout: '{"ok":true}\n',
          stderr: ""
        };
      })
    });

    await injector.sendText({
      text: "阶段心跳",
      runningProcessId: 22056
    });

    expect(scriptText).toContain("CODEX_MOBILE_PYWINAUTO_STAGE_PATH");
    expect(scriptText).toContain("def write_stage_file(stage):");
    expect(scriptText).toContain("write_stage_file(stage)");
  });

  it("writes image file clipboard data as CF_HDROP bytes instead of a tuple", async () => {
    let scriptText = "";
    const injector = new WindowsComposerInjector({
      pythonCommand: "python",
      pythonArgs: [],
      execFile: vi.fn(async (_file, execArgs) => {
        scriptText = await readFile(execArgs.at(-2) ?? "", "utf8");
        return {
          stdout: '{"ok":true}\n',
          stderr: ""
        };
      })
    });

    await injector.sendImage({
      imagePath: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png",
      runningProcessId: 22056
    });

    expect(scriptText).toContain("import struct");
    expect(scriptText).toContain("def build_hdrop_data(paths):");
    expect(scriptText).toContain("struct.pack(\"<IiiII\"");
    expect(scriptText).toContain(".encode(\"utf-16le\")");
    expect(scriptText).toContain("win32clipboard.SetClipboardData(win32con.CF_HDROP, build_hdrop_data(paths))");
    expect(scriptText).not.toContain("win32clipboard.SetClipboardData(win32con.CF_HDROP, tuple(paths))");
  });

  it("types the caption before pasting an image and presses Enter to send", async () => {
    let scriptText = "";
    const injector = new WindowsComposerInjector({
      pythonCommand: "python",
      pythonArgs: [],
      execFile: vi.fn(async (_file, execArgs) => {
        scriptText = await readFile(execArgs.at(-2) ?? "", "utf8");
        return {
          stdout: '{"ok":true}\n',
          stderr: ""
        };
      })
    });

    await injector.sendImage({
      imagePath: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png",
      text: "请分析这张图",
      runningProcessId: 22056
    });

    expect(scriptText).toContain("def clear_composer(window):");
    expect(scriptText).toContain("def refocus_composer_after_paste(window):");
    expect(scriptText).toContain("clear_composer(window)");
    expect(scriptText).toContain("settle_pasted_files()");
    expect(scriptText).toContain("refocus_composer_after_paste(window)");
    expect(scriptText).toContain("submit_composer()");

    const sendImageIndex = scriptText.indexOf("def send_image");
    const setFilesIndex = scriptText.indexOf("set_clipboard_files([payload[\"imagePath\"]])", sendImageIndex);
    const pasteIndex = scriptText.indexOf("paste_clipboard()", sendImageIndex);
    const settleIndex = scriptText.indexOf("settle_pasted_files()", sendImageIndex);
    const refocusIndex = scriptText.indexOf("refocus_composer_after_paste(window)", sendImageIndex);
    const captionIndex = scriptText.indexOf("caption = payload.get(\"text\")", sendImageIndex);
    const keyboardSubmitIndex = scriptText.indexOf("submit_composer()", sendImageIndex);
    const clickIndex = scriptText.indexOf("click_submit_button(window)", sendImageIndex);
    expect(captionIndex).toBeLessThan(setFilesIndex);
    expect(captionIndex).toBeLessThan(pasteIndex);
    expect(pasteIndex).toBeLessThan(settleIndex);
    expect(settleIndex).toBeLessThan(refocusIndex);
    expect(refocusIndex).toBeLessThan(keyboardSubmitIndex);
    expect(clickIndex).toBe(-1);
  });

  it("refocuses inside the composer input after pasting images", async () => {
    let scriptText = "";
    const injector = new WindowsComposerInjector({
      pythonCommand: "python",
      pythonArgs: [],
      execFile: vi.fn(async (_file, execArgs) => {
        scriptText = await readFile(execArgs.at(-2) ?? "", "utf8");
        return {
          stdout: '{"ok":true}\n',
          stderr: ""
        };
      })
    });

    await injector.sendImage({
      imagePath: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png",
      runningProcessId: 22056
    });

    const refocusIndex = scriptText.indexOf("def refocus_composer_after_paste");
    const refocusBlock = scriptText.slice(
      refocusIndex,
      scriptText.indexOf("def send_text", refocusIndex)
    );
    expect(refocusBlock).toContain("rect.width() * 0.50");
    expect(refocusBlock).not.toContain("rect.width() * 0.38");
    expect(refocusBlock).toContain("rect.height() * 0.885");
    expect(refocusBlock).not.toContain("rect.height() * 0.915");
  });

  it("checks that the Codex window is foreground before paste and submit", async () => {
    let scriptText = "";
    const injector = new WindowsComposerInjector({
      pythonCommand: "python",
      pythonArgs: [],
      execFile: vi.fn(async (_file, execArgs) => {
        scriptText = await readFile(execArgs.at(-2) ?? "", "utf8");
        return {
          stdout: '{"ok":true}\n',
          stderr: ""
        };
      })
    });

    await injector.sendImage({
      imagePath: "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\demo.png",
      text: "请分析这张图",
      runningProcessId: 22056
    });

    expect(scriptText).toContain("def ensure_codex_foreground(window, stage):");
    expect(scriptText).toContain("win32gui.GetForegroundWindow()");
    expect(scriptText).toContain("win32gui.GetAncestor");
    expect(scriptText).toContain("Codex 窗口未获得前台焦点，停止桌面发送");

    const sendImageIndex = scriptText.indexOf("def send_image");
    const pasteIndex = scriptText.indexOf("paste_clipboard()", sendImageIndex);
    const submitIndex = scriptText.indexOf("submit_composer()", sendImageIndex);
    const pasteGuardIndex = scriptText.indexOf(
      'ensure_codex_foreground(window, "粘贴前")',
      sendImageIndex
    );
    const submitGuardIndex = scriptText.indexOf(
      'ensure_codex_foreground(window, "发送前")',
      sendImageIndex
    );
    expect(pasteGuardIndex).toBeGreaterThan(sendImageIndex);
    expect(pasteGuardIndex).toBeLessThan(pasteIndex);
    expect(submitGuardIndex).toBeGreaterThan(pasteIndex);
    expect(submitGuardIndex).toBeLessThan(submitIndex);
  });

  it("includes the current Python helper stage when pywinauto raises", async () => {
    let scriptText = "";
    const injector = new WindowsComposerInjector({
      pythonCommand: "python",
      pythonArgs: [],
      execFile: vi.fn(async (_file, execArgs) => {
        scriptText = await readFile(execArgs.at(-2) ?? "", "utf8");
        return {
          stdout: '{"ok":true}\n',
          stderr: ""
        };
      })
    });

    await injector.sendText({
      text: "测试异常定位",
      runningProcessId: 22056
    });

    expect(scriptText).toContain("CURRENT_STAGE");
    expect(scriptText).toContain("def set_stage(stage):");
    expect(scriptText).toContain('"detail": "pywinauto 输入失败[" + CURRENT_STAGE + "]: " + str(exc)');
  });

  it("types the caption before pasting multiple images and presses Enter to send", async () => {
    let scriptText = "";
    const injector = new WindowsComposerInjector({
      pythonCommand: "python",
      pythonArgs: [],
      execFile: vi.fn(async (_file, execArgs) => {
        scriptText = await readFile(execArgs.at(-2) ?? "", "utf8");
        return {
          stdout: '{"ok":true}\n',
          stderr: ""
        };
      })
    });

    await injector.sendImages({
      imagePaths: [
        "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\one.png",
        "D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\two.png"
      ],
      text: "请分析这两张图",
      runningProcessId: 22056
    });

    expect(scriptText).toContain("submit_composer()");
    expect(scriptText).toContain("refocus_composer_after_paste(window)");
    const sendImagesIndex = scriptText.indexOf("def send_images");
    const setFilesIndex = scriptText.indexOf("set_clipboard_files(image_paths)", sendImagesIndex);
    const pasteIndex = scriptText.indexOf("paste_clipboard()", sendImagesIndex);
    const settleIndex = scriptText.indexOf("settle_pasted_files()", sendImagesIndex);
    const refocusIndex = scriptText.indexOf("refocus_composer_after_paste(window)", sendImagesIndex);
    const captionIndex = scriptText.indexOf("caption = payload.get(\"text\")", sendImagesIndex);
    const keyboardSubmitIndex = scriptText.indexOf("submit_composer()", sendImagesIndex);
    const clickIndex = scriptText.indexOf("click_submit_button(window)", sendImagesIndex);
    expect(captionIndex).toBeLessThan(setFilesIndex);
    expect(captionIndex).toBeLessThan(pasteIndex);
    expect(pasteIndex).toBeLessThan(settleIndex);
    expect(settleIndex).toBeLessThan(refocusIndex);
    expect(refocusIndex).toBeLessThan(keyboardSubmitIndex);
    expect(clickIndex).toBe(-1);
  });

  it("surfaces Python helper failures as composer input failures", async () => {
    const injector = new WindowsComposerInjector({
      pythonCommand: "py",
      pythonArgs: ["-3.8"],
      execFile: vi.fn(async () => ({
        stdout:
          'debug log\n{"ok":false,"reason":"composer_input_failed","detail":"pywinauto is not installed"}\n',
        stderr: ""
      }))
    });

    await expect(
      injector.sendText({
        text: "测试失败返回",
        runningProcessId: 22056
      })
    ).resolves.toEqual({
      ok: false,
      reason: "composer_input_failed",
      detail: "pywinauto is not installed"
    });
  });

  it("returns a structured error when the Python process crashes", async () => {
    const injector = new WindowsComposerInjector({
      pythonCommand: "py",
      pythonArgs: ["-3.8"],
      execFile: vi.fn(async () => {
        throw new Error("spawn failed");
      })
    });

    await expect(
      injector.sendText({
        text: "测试异常",
        runningProcessId: 22056
      })
    ).resolves.toMatchObject({
      ok: false,
      reason: "composer_input_failed"
    });
  });

  it("includes process diagnostics when the Python helper is killed", async () => {
    const injector = new WindowsComposerInjector({
      pythonCommand: "py",
      pythonArgs: ["-3.8"],
      execFile: vi.fn(async () => {
        const error = new Error("Command failed: py -3.8 composer_helper.py payload.json\n");
        Object.assign(error, {
          killed: true,
          signal: "SIGTERM",
          code: null,
          stdout: "helper stdout",
          stderr: "helper stderr"
        });
        throw error;
      })
    });

    await expect(
      injector.sendImages({
        imagePaths: ["D:\\projects\\codex-mobile-control\\mobile-uploads\\thread-1\\one.png"],
        runningProcessId: 22056
      })
    ).resolves.toEqual({
      ok: false,
      reason: "composer_input_failed",
      detail:
        "桌面输入超时，可点重试再次发送" +
        "（killed=true; signal=SIGTERM; stdout=helper stdout; stderr=helper stderr）"
    });
  });

  it("includes the last helper stage from the heartbeat when a timed out helper is killed", async () => {
    const injector = new WindowsComposerInjector({
      pythonCommand: "py",
      pythonArgs: ["-3.8"],
      execFile: vi.fn(async (_file, _execArgs, options) => {
        const stagePath = options?.env?.CODEX_MOBILE_PYWINAUTO_STAGE_PATH;
        expect(stagePath).toMatch(/composer-stage\.json$/);
        await writeFile(
          String(stagePath),
          JSON.stringify({ stage: "输入文字", updatedAt: 1778458437 }),
          "utf8"
        );
        const error = new Error("Command failed: py -3.8 composer_helper.py payload.json\n");
        Object.assign(error, {
          killed: true,
          signal: "SIGTERM",
          code: null
        });
        throw error;
      })
    });

    await expect(
      injector.sendText({
        text: "会超时的消息",
        runningProcessId: 22056
      })
    ).resolves.toEqual({
      ok: false,
      reason: "composer_input_failed",
      detail: "桌面输入超时，可点重试再次发送（阶段: 输入文字; killed=true; signal=SIGTERM）"
    });
  });
});
