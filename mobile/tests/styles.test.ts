import { readFileSync } from "node:fs";
import path from "node:path";

import { describe, expect, it } from "vitest";

const stylesPath = path.resolve(import.meta.dirname, "../src/styles.css");

describe("mobile responsive layout", () => {
  it("keeps drawer and fixed topbar styles active below 1200px", () => {
    const styles = readFileSync(stylesPath, "utf-8");

    expect(styles).toContain("@media (min-width: 1200px)");
    expect(styles).toContain("@media (max-width: 1199px)");
    expect(styles).toContain("position: fixed;");
    expect(styles).toContain("transform: translateX(-104%)");
  });
});
