import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import os from "node:os";
import path from "node:path";

import { describe, expect, it } from "vitest";

import { readMarkdownFilePreview } from "../src/markdown-preview-resolver";

function normalizedAbsolutePath(filePath: string): string {
  return path.resolve(filePath).replace(/^\\\\\?\\/, "").replace(/\\/g, "/");
}

describe("readMarkdownFilePreview", () => {
  it("reads txt and log previews and returns the resolved absolute path", async () => {
    const cwd = mkdtempSync(path.join(os.tmpdir(), "codex-preview-"));
    const txtPath = path.join(cwd, "logs", "dde_fetch_23af2e4_1800.txt");
    const logPath = path.join(cwd, "logs", "frida_gopay_sso_consumer_180640.log");
    mkdirSync(path.dirname(txtPath), { recursive: true });
    writeFileSync(txtPath, "txt line\n");
    writeFileSync(logPath, "log line\n");

    try {
      const txtPreview = await readMarkdownFilePreview(cwd, "logs/dde_fetch_23af2e4_1800.txt:141");
      const logPreview = await readMarkdownFilePreview(
        cwd,
        "frida_gopay_sso_consumer_180640.log:321"
      );

      expect(txtPreview).toEqual({
        fileName: "dde_fetch_23af2e4_1800.txt",
        path: normalizedAbsolutePath(txtPath),
        content: "txt line\n",
        sizeBytes: Buffer.byteLength("txt line\n", "utf8")
      });
      expect(logPreview).toEqual({
        fileName: "frida_gopay_sso_consumer_180640.log",
        path: normalizedAbsolutePath(logPath),
        content: "log line\n",
        sizeBytes: Buffer.byteLength("log line\n", "utf8")
      });
    } finally {
      rmSync(cwd, { recursive: true, force: true });
    }
  });
});
