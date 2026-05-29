import { mkdir, rename, stat } from "node:fs/promises";
import path from "node:path";

type PersistUploadedFileOptions = {
  threadId: string;
  tempFilePath: string;
  originalName: string;
  mimeType: string;
};

export type StoredUploadFile = {
  threadId: string;
  fileName: string;
  absolutePath: string;
  mimeType: string;
};

export class MobileUploadStore {
  constructor(private readonly options: { rootDir: string }) {}

  async persistUploadedFile(options: PersistUploadedFileOptions): Promise<StoredUploadFile> {
    const threadDir = path.join(this.options.rootDir, options.threadId);
    await mkdir(threadDir, { recursive: true });

    const sanitizedBaseName = sanitizeFileName(options.originalName);
    const fileName = `${Date.now()}-${sanitizedBaseName}`;
    const absolutePath = path.join(threadDir, fileName);
    await rename(options.tempFilePath, absolutePath);

    return {
      threadId: options.threadId,
      fileName,
      absolutePath,
      mimeType: options.mimeType
    };
  }

  async resolveStoredFile(threadId: string, fileName: string): Promise<StoredUploadFile> {
    const expectedRoot = path.resolve(this.options.rootDir, threadId);
    const candidate = path.resolve(expectedRoot, fileName);
    if (candidate === expectedRoot || !candidate.startsWith(expectedRoot + path.sep)) {
      throw new Error("upload_file_not_found");
    }

    const fileStat = await stat(candidate).catch(() => null);
    if (!fileStat?.isFile()) {
      throw new Error("upload_file_not_found");
    }

    return {
      threadId,
      fileName: path.basename(candidate),
      absolutePath: candidate,
      mimeType: mimeTypeFromExtension(candidate)
    };
  }

  makeRelativeUrl(threadId: string, fileName: string): string {
    return `/api/uploads/${encodeURIComponent(threadId)}/${encodeURIComponent(fileName)}`;
  }
}

function sanitizeFileName(value: string): string {
  const sanitized = path.basename(value).replace(/[^a-zA-Z0-9._-]+/g, "-").replace(/^-+/, "");
  return sanitized || "upload.bin";
}

function mimeTypeFromExtension(fileName: string): string {
  const extension = path.extname(fileName).toLowerCase();
  switch (extension) {
    case ".png":
      return "image/png";
    case ".jpg":
    case ".jpeg":
      return "image/jpeg";
    case ".webp":
      return "image/webp";
    case ".gif":
      return "image/gif";
    default:
      return "application/octet-stream";
  }
}
