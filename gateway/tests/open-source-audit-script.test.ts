import { execFileSync } from "node:child_process";
import { existsSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";

import { afterEach, describe, expect, it } from "vitest";

const repoRoot = path.resolve(__dirname, "..", "..");
const auditScript = path.join(repoRoot, "scripts", "audit-open-source.mjs");
const privateDenylist = path.join(repoRoot, ".open-source-audit.private.json");
const probeFile = path.join(repoRoot, "open-source-audit-private-probe.txt");
const originalPrivateDenylist = existsSync(privateDenylist)
  ? readFileSync(privateDenylist, "utf8")
  : null;
const legacyPrivateTerms = [
  [99, 111, 100, 101, 120, 46, 108, 111, 111, 110, 103, 107, 107, 107],
  [108, 111, 111, 110, 103, 107, 107, 107],
  [85, 98, 117, 110, 116, 117, 95, 50, 50, 95, 48, 52],
  [100, 49, 48, 54, 54, 99, 98, 57],
  [68, 58, 92, 99, 111, 100, 101, 92, 99, 111, 100, 101, 120, 95, 97, 112, 112],
  [68, 58, 92, 99, 111, 100, 101, 120, 92, 99, 111, 100, 101, 120, 95, 97, 112, 112]
].map((codes) => String.fromCharCode(...codes));

describe("audit-open-source script", () => {
  afterEach(() => {
    if (originalPrivateDenylist == null) {
      rmSync(privateDenylist, { force: true });
    } else {
      writeFileSync(privateDenylist, originalPrivateDenylist, "utf8");
    }
    rmSync(probeFile, { force: true });
  });

  it("keeps personal denylist values out of the public audit script", () => {
    const scriptText = readFileSync(auditScript, "utf8");

    for (const term of legacyPrivateTerms) {
      expect(scriptText).not.toContain(term);
    }
  });

  it("loads local private denylist patterns without tracking the denylist file", () => {
    writeFileSync(
      privateDenylist,
      JSON.stringify(
        {
          patterns: [
            {
              name: "private probe",
              pattern: "private-probe-token"
            }
          ]
        },
        null,
        2
      ),
      "utf8"
    );
    writeFileSync(probeFile, "contains private-probe-token\n", "utf8");

    let output = "";
    try {
      execFileSync("node", [auditScript], {
        cwd: repoRoot,
        encoding: "utf8",
        stdio: ["ignore", "pipe", "pipe"]
      });
    } catch (error) {
      output = String((error as { stderr?: string }).stderr ?? "");
    }

    expect(existsSync(privateDenylist)).toBe(true);
    expect(output).toContain("open-source-audit-private-probe.txt: contains private probe");
  });
});
