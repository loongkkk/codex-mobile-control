import fs from "node:fs/promises";
import path from "node:path";

import type { MarkdownFilePreview } from "../../shared/src/api";
import { GatewayHttpError } from "./gateway-error";

const MARKDOWN_PREVIEW_MAX_BYTES = 512 * 1024;
const JSON_PREVIEW_MAX_BYTES = 2 * 1024 * 1024;
const TEXT_PREVIEW_MAX_BYTES = 2 * 1024 * 1024;
const MARKDOWN_PREVIEW_SEARCH_MAX_ENTRIES = 8_000;
const MARKDOWN_PREVIEW_SEARCH_SKIPPED_DIRS = new Set([
  ".git",
  ".gradle",
  "build",
  "dist",
  "node_modules"
]);

export async function readMarkdownFilePreview(
  cwd: string | null | undefined,
  rawFilePath: string
): Promise<MarkdownFilePreview> {
  let resolved = resolveMarkdownPreviewPath(cwd, rawFilePath);
  let fileStat = await fs.stat(resolved.absolutePath).catch(() => null);
  if (!fileStat?.isFile()) {
    const basenameFallback = await findUniqueMarkdownPreviewByBasename(cwd, rawFilePath);
    if (basenameFallback) {
      resolved = basenameFallback;
      fileStat = await fs.stat(resolved.absolutePath).catch(() => null);
    }
  }
  if (!fileStat?.isFile()) {
    throw markdownPreviewError(404, "markdown_preview_not_found", "文件不存在或不可访问");
  }
  if (fileStat.size > getMarkdownPreviewMaxBytes(resolved.absolutePath)) {
    throw markdownPreviewError(413, "markdown_preview_too_large", "文件过大，暂不支持预览");
  }

  return {
    fileName: path.basename(resolved.absolutePath),
    path: resolved.displayPath,
    content: await fs.readFile(resolved.absolutePath, "utf8"),
    sizeBytes: fileStat.size
  };
}

function normalizeCwd(cwd: string | null | undefined): string {
  return (cwd ?? "").replace(/^\\\\\?\\/, "");
}

function normalizeDisplayPath(value: string): string {
  return value.replace(/^\\\\\?\\/, "").replace(/\\/g, "/");
}

function stripMarkdownPreviewPathDecorations(filePath: string): string {
  return filePath
    .trim()
    .replace(/^file:\/+/i, "")
    .replace(/[?#].*$/, "")
    .replace(/(\.(?:md|markdown|json|txt|log))(?::\d+){1,2}$/i, "$1");
}

function isSupportedMarkdownPreviewPath(filePath: string): boolean {
  return /\.(md|markdown|json|txt|log)$/i.test(stripMarkdownPreviewPathDecorations(filePath));
}

function getMarkdownPreviewMaxBytes(filePath: string): number {
  const normalizedFilePath = stripMarkdownPreviewPathDecorations(filePath);
  if (/\.json$/i.test(normalizedFilePath)) {
    return JSON_PREVIEW_MAX_BYTES;
  }
  if (/\.(txt|log)$/i.test(normalizedFilePath)) {
    return TEXT_PREVIEW_MAX_BYTES;
  }
  return MARKDOWN_PREVIEW_MAX_BYTES;
}

function isPathInsideDirectory(parentDir: string, childPath: string): boolean {
  const relative = path.relative(parentDir, childPath);
  return relative === "" || (!!relative && !relative.startsWith("..") && !path.isAbsolute(relative));
}

function markdownPreviewError(
  statusCode: number,
  error: string,
  message: string
): GatewayHttpError {
  return new GatewayHttpError(statusCode, {
    error,
    message
  });
}

function resolveMarkdownPreviewPath(cwd: string | null | undefined, rawFilePath: string): {
  absolutePath: string;
  displayPath: string;
} {
  const normalizedCwd = normalizeCwd(cwd).trim();
  const normalizedFilePath = stripMarkdownPreviewPathDecorations(rawFilePath);
  if (!normalizedCwd) {
    throw markdownPreviewError(404, "thread_cwd_missing", "当前线程没有可读取的工作目录");
  }
  if (!normalizedFilePath) {
    throw markdownPreviewError(400, "markdown_preview_invalid", "缺少文件路径");
  }
  if (/^[a-z][a-z\d+.-]*:/i.test(normalizedFilePath) && !/^[A-Za-z]:[\\/]/.test(normalizedFilePath)) {
    throw markdownPreviewError(400, "markdown_preview_invalid", "不支持的文件路径");
  }
  if (!isSupportedMarkdownPreviewPath(normalizedFilePath)) {
    throw markdownPreviewError(
      400,
      "markdown_preview_invalid",
      "仅支持预览 Markdown、JSON、TXT 或 LOG 文件"
    );
  }

  const cwdAbsolute = path.resolve(normalizedCwd);
  const candidateAbsolute = path.isAbsolute(normalizedFilePath)
    ? path.resolve(normalizedFilePath)
    : path.resolve(cwdAbsolute, normalizedFilePath);
  if (!isPathInsideDirectory(cwdAbsolute, candidateAbsolute)) {
    throw markdownPreviewError(404, "markdown_preview_not_found", "文件不存在或不可访问");
  }

  return {
    absolutePath: candidateAbsolute,
    displayPath: normalizeDisplayPath(candidateAbsolute)
  };
}

function isMarkdownPreviewBasenameOnly(filePath: string): boolean {
  const normalizedFilePath = stripMarkdownPreviewPathDecorations(filePath).replace(/\\/g, "/");
  return normalizedFilePath.length > 0 && !normalizedFilePath.includes("/");
}

async function findUniqueMarkdownPreviewByBasename(
  cwd: string | null | undefined,
  rawFilePath: string
): Promise<{ absolutePath: string; displayPath: string } | null> {
  const normalizedCwd = normalizeCwd(cwd).trim();
  if (!normalizedCwd || !isMarkdownPreviewBasenameOnly(rawFilePath)) {
    return null;
  }
  const fileName = stripMarkdownPreviewPathDecorations(rawFilePath).replace(/\\/g, "/");
  if (!isSupportedMarkdownPreviewPath(fileName)) {
    return null;
  }

  const cwdAbsolute = path.resolve(normalizedCwd);
  const expectedName = fileName.toLowerCase();
  const matches: string[] = [];
  let visitedEntries = 0;

  async function visit(dirPath: string): Promise<void> {
    if (matches.length > 1 || visitedEntries >= MARKDOWN_PREVIEW_SEARCH_MAX_ENTRIES) {
      return;
    }

    const entries = await fs.readdir(dirPath, { withFileTypes: true }).catch(() => []);
    for (const entry of entries) {
      if (matches.length > 1 || visitedEntries >= MARKDOWN_PREVIEW_SEARCH_MAX_ENTRIES) {
        return;
      }
      visitedEntries += 1;
      const absolutePath = path.join(dirPath, entry.name);
      if (entry.isDirectory()) {
        if (!MARKDOWN_PREVIEW_SEARCH_SKIPPED_DIRS.has(entry.name)) {
          await visit(absolutePath);
        }
        continue;
      }
      if (
        entry.isFile() &&
        entry.name.toLowerCase() === expectedName &&
        isPathInsideDirectory(cwdAbsolute, absolutePath)
      ) {
        matches.push(absolutePath);
      }
    }
  }

  await visit(cwdAbsolute);
  if (matches.length !== 1) {
    return null;
  }

  return {
    absolutePath: matches[0],
    displayPath: normalizeDisplayPath(matches[0])
  };
}
