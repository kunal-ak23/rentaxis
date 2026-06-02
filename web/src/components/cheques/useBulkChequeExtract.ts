"use client";

import { useCallback, useState } from "react";
import type { ChequeExtractionResponse } from "@/types/cheque";

export type BulkExtractItemStatus = "pending" | "extracting" | "extracted" | "failed";

export type BulkExtractItem = {
  id: string;
  file: File;
  previewUrl: string;
  status: BulkExtractItemStatus;
  response: ChequeExtractionResponse | null;
  error: string | null;
};

// Gentle parallelism (2): the backend now uses the OkHttp Azure client (no more
// Netty "channel not registered" race), so concurrent calls are safe. Kept at 2
// (not higher) to avoid Azure OpenAI 429 rate limiting under bulk load; the
// backend additionally retries 429 with backoff.
const MAX_CONCURRENT = 2;
const ALLOWED_TYPES = new Set([
  "image/jpeg",
  "image/jpg",
  "image/png",
  "image/heic",
  "image/heif",
]);

async function extractOne(file: File): Promise<ChequeExtractionResponse> {
  const form = new FormData();
  form.append("file", file);
  const res = await fetch("/api/proxy/v1/cheques/extract", { method: "POST", body: form });
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new Error(err.error ?? `Upload failed (${res.status})`);
  }
  return (await res.json()) as ChequeExtractionResponse;
}

export function buildItemsFromFiles(files: File[]): { items: BulkExtractItem[]; rejectedCount: number } {
  let rejectedCount = 0;
  const items: BulkExtractItem[] = [];
  for (const file of files) {
    if (!ALLOWED_TYPES.has(file.type.toLowerCase())) {
      rejectedCount++;
      continue;
    }
    items.push({
      id: crypto.randomUUID(),
      file,
      previewUrl: URL.createObjectURL(file),
      status: "pending",
      response: null,
      error: null,
    });
  }
  return { items, rejectedCount };
}

export function useBulkChequeExtract() {
  const [items, setItems] = useState<BulkExtractItem[]>([]);
  const [running, setRunning] = useState(false);

  const setItem = (id: string, patch: Partial<BulkExtractItem>) => {
    setItems(prev => prev.map(it => (it.id === id ? { ...it, ...patch } : it)));
  };

  const start = useCallback(async (toExtract: BulkExtractItem[]): Promise<BulkExtractItem[]> => {
    setRunning(true);
    // Local mutable copies so we can return the resolved items synchronously,
    // independent of React state propagation.
    const results: BulkExtractItem[] = toExtract.map(it => ({ ...it }));
    const queue = [...results];
    const workers: Promise<void>[] = [];

    const next = async (): Promise<void> => {
      const job = queue.shift();
      if (!job) return;
      setItem(job.id, { status: "extracting" });
      try {
        const response = await extractOne(job.file);
        job.status = "extracted";
        job.response = response;
        setItem(job.id, { status: "extracted", response });
      } catch (e) {
        const message = e instanceof Error ? e.message : "Failed";
        job.status = "failed";
        job.error = message;
        setItem(job.id, { status: "failed", error: message });
      }
      return next();
    };

    for (let i = 0; i < MAX_CONCURRENT; i++) workers.push(next());
    await Promise.all(workers);
    setRunning(false);
    return results;
  }, []);

  const retry = useCallback(async (id: string) => {
    const target = items.find(it => it.id === id);
    if (!target) return;
    setItem(id, { status: "extracting", error: null });
    try {
      const response = await extractOne(target.file);
      setItem(id, { status: "extracted", response });
    } catch (e) {
      setItem(id, { status: "failed", error: e instanceof Error ? e.message : "Failed" });
    }
  }, [items]);

  const removeItem = (id: string) => {
    setItems(prev => {
      const target = prev.find(it => it.id === id);
      if (target) URL.revokeObjectURL(target.previewUrl);
      return prev.filter(it => it.id !== id);
    });
  };

  const reset = () => {
    setItems(prev => {
      prev.forEach(it => URL.revokeObjectURL(it.previewUrl));
      return [];
    });
  };

  return { items, setItems, running, start, retry, removeItem, reset };
}
