import fs from "node:fs/promises";
import path from "node:path";

import type { GatewayQueuedTextMessage } from "./mobile-gateway-service";

export type GatewayQueueStore = {
  load(): Promise<GatewayQueuedTextMessage[]>;
  save(messages: GatewayQueuedTextMessage[]): Promise<void>;
};

export class FileGatewayQueueStore implements GatewayQueueStore {
  constructor(private readonly filePath: string) {}

  async load(): Promise<GatewayQueuedTextMessage[]> {
    const source = await fs.readFile(this.filePath, "utf8").catch((error: NodeJS.ErrnoException) => {
      if (error.code === "ENOENT") {
        return null;
      }
      throw error;
    });
    if (!source) {
      return [];
    }
    const parsed = JSON.parse(source) as { messages?: GatewayQueuedTextMessage[] };
    return Array.isArray(parsed.messages) ? parsed.messages : [];
  }

  async save(messages: GatewayQueuedTextMessage[]): Promise<void> {
    await fs.mkdir(path.dirname(this.filePath), { recursive: true });
    await fs.writeFile(
      this.filePath,
      JSON.stringify({ version: 1, messages }, null, 2) + "\n",
      "utf8"
    );
  }
}

export class MemoryGatewayQueueStore implements GatewayQueueStore {
  private messages: GatewayQueuedTextMessage[] = [];

  async load(): Promise<GatewayQueuedTextMessage[]> {
    return this.messages.map((message) => ({ ...message }));
  }

  async save(messages: GatewayQueuedTextMessage[]): Promise<void> {
    this.messages = messages.map((message) => ({ ...message }));
  }
}
