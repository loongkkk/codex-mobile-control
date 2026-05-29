import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { afterEach, describe, expect, it } from "vitest";

import { MobileUploadStore } from "../src/mobile-upload-store";

describe("MobileUploadStore", () => {
  const tempDirs: string[] = [];

  afterEach(async () => {
    for (const dir of tempDirs) {
      await rm(dir, { recursive: true, force: true });
    }
  });

  it("persists uploaded images under the thread directory with sanitized names", async () => {
    const root = await mkdtemp(path.join(os.tmpdir(), "mobile-upload-store-"));
    tempDirs.push(root);
    const incomingPath = path.join(root, "incoming.bin");
    await writeFile(incomingPath, Buffer.from("image-bytes"));

    const store = new MobileUploadStore({ rootDir: root });
    const saved = await store.persistUploadedFile({
      threadId: "thread-1",
      tempFilePath: incomingPath,
      originalName: "..\\weird name.png",
      mimeType: "image/png"
    });

    expect(saved.absolutePath).toContain(path.join("thread-1", ""));
    expect(saved.fileName).toMatch(/weird-name\.png$/);
    await expect(readFile(saved.absolutePath, "utf8")).resolves.toBe("image-bytes");
  });

  it("rejects upload file reads outside the configured root", async () => {
    const root = await mkdtemp(path.join(os.tmpdir(), "mobile-upload-store-"));
    tempDirs.push(root);
    const store = new MobileUploadStore({ rootDir: root });

    await expect(
      store.resolveStoredFile("thread-1", "..\\..\\Windows\\win.ini")
    ).rejects.toThrow("upload_file_not_found");
  });
});
