import type { FullConfig } from '@playwright/test';
import { createHash, randomUUID } from 'crypto';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';

interface LockOwner {
  pid: number;
  token: string;
  startedAt: string;
  baseURL: string;
}

function processIsAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    return (error as NodeJS.ErrnoException).code === 'EPERM';
  }
}

function lockPathFor(baseURL: string): string {
  const target = createHash('sha256').update(baseURL).digest('hex').slice(0, 16);
  return path.join(os.tmpdir(), `rentaxis-prod-e2e-${target}.lock`);
}

function readOwner(lockPath: string): LockOwner | null {
  try {
    return JSON.parse(fs.readFileSync(lockPath, 'utf8')) as LockOwner;
  } catch {
    return null;
  }
}

export default function acquireProductionSuiteLock(config: FullConfig): () => void {
  const baseURL = config.projects[0]?.use.baseURL?.toString() ?? 'production';
  const lockPath = lockPathFor(baseURL);
  const owner: LockOwner = {
    pid: process.pid,
    token: randomUUID(),
    startedAt: new Date().toISOString(),
    baseURL,
  };

  for (;;) {
    try {
      fs.writeFileSync(lockPath, JSON.stringify(owner), { flag: 'wx', mode: 0o600 });
      break;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw error;

      const existing = readOwner(lockPath);
      if (existing?.pid && processIsAlive(existing.pid)) {
        throw new Error(
          `Another production E2E suite is already running (pid ${existing.pid}, started ${existing.startedAt}).`,
        );
      }

      // The owner no longer exists (or the lock is unreadable), so recover the
      // stale lock and retry the atomic create.
      try {
        fs.unlinkSync(lockPath);
      } catch (unlinkError) {
        if ((unlinkError as NodeJS.ErrnoException).code !== 'ENOENT') throw unlinkError;
      }
    }
  }

  return () => {
    const existing = readOwner(lockPath);
    if (existing?.token === owner.token) {
      fs.unlinkSync(lockPath);
    }
  };
}
