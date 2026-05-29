#!/usr/bin/env node
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptPath = fileURLToPath(import.meta.url);
const repoRoot = path.resolve(path.dirname(scriptPath), "..");
const preservedPublicEntries = new Set([".git", "node_modules"]);

export function defaultPublicRepoDir(sourceRepoRoot = repoRoot) {
  return path.resolve(path.dirname(sourceRepoRoot), "codex-mobile-control");
}

export function replacePublicWorktree(exportDir, publicDir) {
  assertDirectory(exportDir, "Export directory");
  assertDirectory(publicDir, "Public repository directory");

  const publicGitDir = path.join(publicDir, ".git");
  if (!fs.existsSync(publicGitDir)) {
    throw new Error(`Public repository is missing .git metadata: ${publicDir}`);
  }

  for (const entry of fs.readdirSync(publicDir, { withFileTypes: true })) {
    if (preservedPublicEntries.has(entry.name)) {
      continue;
    }
    fs.rmSync(path.join(publicDir, entry.name), { recursive: true, force: true });
  }

  for (const entry of fs.readdirSync(exportDir, { withFileTypes: true })) {
    fs.cpSync(path.join(exportDir, entry.name), path.join(publicDir, entry.name), {
      recursive: true,
      verbatimSymlinks: true
    });
  }
}

export function syncPublicRepo(options = {}) {
  const sourceRoot = path.resolve(options.sourceRoot ?? repoRoot);
  const publicDir = path.resolve(
    options.publicDir ?? process.env.CODEX_PUBLIC_REPO_DIR ?? defaultPublicRepoDir(sourceRoot)
  );

  assertDirectory(sourceRoot, "Source repository directory");
  assertDirectory(path.join(sourceRoot, ".git"), "Source repository .git directory");
  assertDirectory(publicDir, "Public repository directory");
  assertDirectory(path.join(publicDir, ".git"), "Public repository .git directory");
  assertDifferentDirectories(sourceRoot, publicDir);

  const publicStatus = gitOutput(["status", "--short"], publicDir);
  if (publicStatus.trim() && !options.allowDirtyPublic) {
    throw new Error(
      [
        `Public repository has uncommitted changes: ${publicDir}`,
        "Commit, stash, or rerun with --allow-dirty-public if you intentionally want to replace them."
      ].join("\n")
    );
  }

  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), "codex-public-export-"));
  const exportDir = path.join(tempRoot, "tree");
  const archiveFile = path.join(tempRoot, "tree.tar");
  fs.mkdirSync(exportDir, { recursive: true });

  try {
    execFileSync(
      "git",
      ["-c", "core.autocrlf=false", "archive", "--format=tar", `--output=${archiveFile}`, "HEAD"],
      { cwd: sourceRoot, stdio: ["ignore", "pipe", "pipe"] }
    );
    execFileSync("tar", tarExtractArgs(archiveFile, exportDir), {
      cwd: sourceRoot,
      stdio: ["ignore", "pipe", "pipe"]
    });

    if (options.dryRun) {
      return {
        publicDir,
        sourceRoot,
        changedFiles: gitChangedFileCount(publicDir)
      };
    }

    replacePublicWorktree(exportDir, publicDir);
    return {
      publicDir,
      sourceRoot,
      changedFiles: gitChangedFileCount(publicDir)
    };
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

export function tarExtractArgs(archiveFile, exportDir) {
  return ["--force-local", "-xf", toTarPath(archiveFile), "-C", toTarPath(exportDir)];
}

function toTarPath(value) {
  return value.replace(/\\/g, "/");
}

function assertDirectory(dir, label) {
  if (!fs.existsSync(dir) || !fs.statSync(dir).isDirectory()) {
    throw new Error(`${label} does not exist: ${dir}`);
  }
}

function assertDifferentDirectories(sourceRoot, publicDir) {
  const normalizedSource = normalizePath(fs.realpathSync(sourceRoot));
  const normalizedPublic = normalizePath(fs.realpathSync(publicDir));
  if (normalizedSource === normalizedPublic) {
    throw new Error("Source repository and public repository must be different directories.");
  }
}

function normalizePath(value) {
  return path.resolve(value).replace(/\\/g, "/").toLowerCase();
}

function gitOutput(args, cwd) {
  return execFileSync("git", args, {
    cwd,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"]
  });
}

function gitChangedFileCount(cwd) {
  return gitOutput(["status", "--short"], cwd)
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter(Boolean).length;
}

function parseArgs(argv) {
  const options = {};
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--public-dir") {
      options.publicDir = requireValue(argv, ++index, arg);
    } else if (arg === "--source-dir") {
      options.sourceRoot = requireValue(argv, ++index, arg);
    } else if (arg === "--dry-run") {
      options.dryRun = true;
    } else if (arg === "--allow-dirty-public") {
      options.allowDirtyPublic = true;
    } else if (arg === "--help" || arg === "-h") {
      options.help = true;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }
  return options;
}

function requireValue(argv, index, flag) {
  const value = argv[index];
  if (!value || value.startsWith("--")) {
    throw new Error(`Missing value for ${flag}`);
  }
  return value;
}

function printHelp() {
  console.log(`Usage: node scripts/sync-public-repo.mjs [options]

Options:
  --public-dir <dir>       Public repository directory. Defaults to ../codex-mobile-control.
  --source-dir <dir>       Private source repository directory. Defaults to this repository.
  --dry-run                Verify paths and export HEAD without changing the public repository.
  --allow-dirty-public     Replace the public worktree even if it has uncommitted changes.
  -h, --help               Show this help.
`);
}

function main() {
  try {
    const options = parseArgs(process.argv.slice(2));
    if (options.help) {
      printHelp();
      return;
    }
    const result = syncPublicRepo(options);
    console.log(`Synced ${result.sourceRoot} -> ${result.publicDir}`);
    console.log(`Public repository changed files: ${result.changedFiles}`);
    console.log("Next: review, commit, and push from the public repository.");
  } catch (error) {
    console.error(error instanceof Error ? error.message : String(error));
    process.exitCode = 1;
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === scriptPath) {
  main();
}
