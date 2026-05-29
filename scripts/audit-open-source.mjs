#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, "..");

const blockedFiles = new Set([
  ".codex-analysis.md",
  ".codex-queue.md",
  ".open-source-audit.private.json",
  "NEXT_WINDOW_HANDOFF.md",
  "gateway/config.json",
  "release.config.json"
]);

const blockedExtensions = [
  ".apk",
  ".jks",
  ".keystore",
  ".p12",
  ".pem",
  ".key",
  ".crt"
];

const blockedPathFragments = [
  "gateway/mobile-uploads/",
  "gateway/run-logs/",
  "gateway/downloads/",
  "artifacts/",
  "tmp/"
];

const blockedTextPatterns = [
  ...loadPrivateTextPatterns()
];

function gitLsFiles() {
  return execFileSync("git", ["ls-files", "--cached", "--others", "--exclude-standard"], {
    cwd: repoRoot,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"]
  })
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((file) => file.replace(/\\/g, "/"));
}

function isProbablyText(filePath) {
  const buffer = fs.readFileSync(filePath);
  return !buffer.includes(0);
}

function loadPrivateTextPatterns() {
  const privateDenylistFile = path.join(repoRoot, ".open-source-audit.private.json");
  if (!fs.existsSync(privateDenylistFile)) {
    return [];
  }

  const config = JSON.parse(fs.readFileSync(privateDenylistFile, "utf8"));
  const entries = Array.isArray(config?.patterns) ? config.patterns : [];
  return entries
    .map((entry, index) => compilePrivatePattern(entry, index))
    .filter(Boolean);
}

function compilePrivatePattern(entry, index) {
  const fallbackName = `private denylist pattern ${index + 1}`;
  if (typeof entry === "string" && entry.trim()) {
    return {
      name: fallbackName,
      pattern: new RegExp(entry, "iu")
    };
  }
  if (!entry || typeof entry !== "object") {
    return null;
  }

  const source = typeof entry.pattern === "string" ? entry.pattern.trim() : "";
  if (!source) {
    return null;
  }
  const flags = typeof entry.flags === "string" && entry.flags.trim() ? entry.flags.trim() : "iu";
  return {
    name: typeof entry.name === "string" && entry.name.trim() ? entry.name.trim() : fallbackName,
    pattern: new RegExp(source, flags)
  };
}

const failures = [];
for (const file of gitLsFiles()) {
  if (blockedFiles.has(file)) {
    failures.push(`${file}: private/local file must not be tracked`);
  }
  if (blockedExtensions.includes(path.extname(file).toLowerCase())) {
    failures.push(`${file}: generated or secret-like binary extension must not be tracked`);
  }
  if (blockedPathFragments.some((fragment) => file.startsWith(fragment))) {
    failures.push(`${file}: generated runtime path must not be tracked`);
  }

  const absolutePath = path.join(repoRoot, file);
  if (!fs.existsSync(absolutePath) || !isProbablyText(absolutePath)) {
    continue;
  }
  const text = fs.readFileSync(absolutePath, "utf8");
  for (const blocked of blockedTextPatterns) {
    if (blocked.pattern.test(text)) {
      failures.push(`${file}: contains ${blocked.name}`);
    }
  }
}

if (failures.length > 0) {
  console.error("Open-source audit failed:");
  for (const failure of failures) {
    console.error(`- ${failure}`);
  }
  process.exitCode = 1;
} else {
  console.log("Open-source audit passed");
}
