import { DatabaseSync } from "node:sqlite";

import type { StateRepository, ThreadMetadataRecord } from "./mobile-gateway-service";

export class SqliteStateRepository implements StateRepository {
  constructor(private readonly dbPath: string) {}

  async getThreadMetadata(threadIds: string[]): Promise<ThreadMetadataRecord[]> {
    const db = new DatabaseSync(this.dbPath, {
      open: true,
      readOnly: true
    });

    try {
      const select =
        threadIds.length > 0
          ? `select
               id as threadId,
               title,
               cwd,
               updated_at as updatedAt,
               archived,
               first_user_message as firstUserMessage,
               rollout_path as rolloutPath,
               model,
               reasoning_effort as reasoningEffort,
               sandbox_policy as sandboxPolicy,
               approval_mode as approvalMode,
               source,
               agent_role as agentRole,
               agent_nickname as agentNickname,
               agent_path as agentPath
             from threads
             where id in (${threadIds.map(() => "?").join(",")})`
          : `select
               id as threadId,
               title,
               cwd,
               updated_at as updatedAt,
               archived,
               first_user_message as firstUserMessage,
               rollout_path as rolloutPath,
               model,
               reasoning_effort as reasoningEffort,
               sandbox_policy as sandboxPolicy,
               approval_mode as approvalMode,
               source,
               agent_role as agentRole,
               agent_nickname as agentNickname,
               agent_path as agentPath
             from threads`;

      return mapThreadMetadataRows(db.prepare(select).all(...threadIds));
    } finally {
      db.close();
    }
  }

  async listDesktopVisibleThreadMetadata(limit: number): Promise<ThreadMetadataRecord[]> {
    const db = new DatabaseSync(this.dbPath, {
      open: true,
      readOnly: true
    });

    try {
      const select = `select
           id as threadId,
           title,
           cwd,
           updated_at as updatedAt,
           archived,
           first_user_message as firstUserMessage,
           rollout_path as rolloutPath,
           model,
           reasoning_effort as reasoningEffort,
           sandbox_policy as sandboxPolicy,
           approval_mode as approvalMode,
           source,
           agent_role as agentRole,
           agent_nickname as agentNickname,
           agent_path as agentPath
         from threads
         where archived = 0
           and coalesce(agent_role, '') = ''
           and coalesce(agent_nickname, '') = ''
           and coalesce(agent_path, '') = ''
           and (source is null or trim(source) = '' or trim(source) = 'vscode')
         order by updated_at desc
         limit ?`;

      return mapThreadMetadataRows(db.prepare(select).all(limit));
    } finally {
      db.close();
    }
  }
}

function mapThreadMetadataRows(rows: unknown[]): ThreadMetadataRecord[] {
  return rows.map((row) => {
    const record = row as Record<string, unknown>;
    return {
      threadId: String(record.threadId),
      title: record.title ? String(record.title) : null,
      cwd: record.cwd ? String(record.cwd) : null,
      updatedAt: Number(record.updatedAt ?? 0),
      archived: Boolean(record.archived),
      firstUserMessage: record.firstUserMessage ? String(record.firstUserMessage) : null,
      rolloutPath: record.rolloutPath ? String(record.rolloutPath) : null,
      model: record.model ? String(record.model) : null,
      reasoningEffort: record.reasoningEffort ? String(record.reasoningEffort) : null,
      sandboxPolicy: record.sandboxPolicy ? String(record.sandboxPolicy) : null,
      approvalMode: record.approvalMode ? String(record.approvalMode) : null,
      source: record.source ? String(record.source) : null,
      agentRole: record.agentRole ? String(record.agentRole) : null,
      agentNickname: record.agentNickname ? String(record.agentNickname) : null,
      agentPath: record.agentPath ? String(record.agentPath) : null
    };
  });
}
