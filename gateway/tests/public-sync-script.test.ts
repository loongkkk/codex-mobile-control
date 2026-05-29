import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { pathToFileURL } from "node:url";

import { describe, expect, it } from "vitest";

const repoRoot = path.resolve(__dirname, "..", "..");
const syncScript = path.join(repoRoot, "scripts", "sync-public-repo.mjs");

describe("public repo sync script", () => {
  it("defaults to a stable sibling public repository directory", async () => {
    const script = await import(pathToFileURL(syncScript).href);

    expect(script.defaultPublicRepoDir(path.join("D:", "code", "codex_app"))).toBe(
      path.join("D:", "code", "codex-mobile-control")
    );
  });

  it("replaces the public worktree while preserving its git metadata", async () => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "codex-public-sync-"));
    const exportDir = path.join(tempDir, "export");
    const publicDir = path.join(tempDir, "public");

    fs.mkdirSync(path.join(exportDir, "docs"), { recursive: true });
    fs.writeFileSync(path.join(exportDir, "README.md"), "new readme\n", "utf8");
    fs.writeFileSync(path.join(exportDir, "docs", "guide.md"), "new guide\n", "utf8");

    fs.mkdirSync(path.join(publicDir, ".git"), { recursive: true });
    fs.mkdirSync(path.join(publicDir, "node_modules", "cached-package"), { recursive: true });
    fs.writeFileSync(path.join(publicDir, ".git", "config"), "[remote]\n", "utf8");
    fs.writeFileSync(
      path.join(publicDir, "node_modules", "cached-package", "index.js"),
      "module.exports = true;\n",
      "utf8"
    );
    fs.writeFileSync(path.join(publicDir, "stale.txt"), "stale\n", "utf8");

    try {
      const script = await import(pathToFileURL(syncScript).href);
      script.replacePublicWorktree(exportDir, publicDir);

      expect(fs.readFileSync(path.join(publicDir, "README.md"), "utf8")).toBe("new readme\n");
      expect(fs.readFileSync(path.join(publicDir, "docs", "guide.md"), "utf8")).toBe(
        "new guide\n"
      );
      expect(fs.existsSync(path.join(publicDir, "stale.txt"))).toBe(false);
      expect(fs.readFileSync(path.join(publicDir, ".git", "config"), "utf8")).toBe("[remote]\n");
      expect(
        fs.readFileSync(path.join(publicDir, "node_modules", "cached-package", "index.js"), "utf8")
      ).toBe("module.exports = true;\n");
    } finally {
      fs.rmSync(tempDir, { recursive: true, force: true });
    }
  });

  it("forces tar archives to be treated as local Windows paths", async () => {
    const script = await import(pathToFileURL(syncScript).href);

    expect(script.tarExtractArgs("C:\\Temp\\tree.tar", "C:\\Temp\\tree")).toEqual([
      "--force-local",
      "-xf",
      "C:/Temp/tree.tar",
      "-C",
      "C:/Temp/tree"
    ]);
  });
});
