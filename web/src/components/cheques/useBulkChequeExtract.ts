"use client";

import { useCallback, useRef, useState } from "react";
import type { ChequeExtractionResponse, ChequeMultiExtractionResponse } from "@/types/cheque";

export type BulkExtractItemStatus = "pending" | "extracting" | "extracted" | "failed";

export type BulkExtractItem = {
  id: string;
  file: File;
  previewUrl: string;
  status: BulkExtractItemStatus;
  /** Every cheque found in this file (a photo of several, or a PDF, yields several). */
  response: ChequeMultiExtractionResponse | null;
  error: string | null;
  /** The server's refusal code (e.g. `cheque_upload_too_many_pages`), when it gave one. */
  errorCode?: string | null;
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
  // "application/pdf" — PDF input is off on the server (rentaxis.cheques.pdf-upload.enabled)
  // until its renderer is hardened; add it back here when that flag is turned on.
]);

/** A refused or failed upload; `code` is the server's refusal code when it sent one. */
export class ChequeExtractError extends Error {
  constructor(message: string, readonly code: string | null) {
    super(message);
  }
}

/**
 * The multi-cheque shape, whatever the server answered: `/extract-many` already
 * is; a single-cheque `/extract` body (older server, test fixture) becomes a
 * list of one, exactly as that cheque would have been shown before.
 */
export function normalizeExtraction(
  body: ChequeMultiExtractionResponse | ChequeExtractionResponse,
): ChequeMultiExtractionResponse {
  if (body && Array.isArray((body as ChequeMultiExtractionResponse).items)) {
    return body as ChequeMultiExtractionResponse;
  }
  const single = body as ChequeExtractionResponse;
  return {
    original: single.image,
    pages: [],
    items: [{
      imageId: null,
      image: single.image,
      page: 1,
      box: null,
      thumbnailUrl: null,
      extracted: single.extracted,
      warnings: single.warnings ?? [],
      flags: [],
      payeeCheck: single.payeeCheck ?? null,
    }],
    warnings: [],
  };
}

/**
 * How long one file may take before the row says so (tutorial 15: with blob storage
 * down the extraction hung with no feedback). A slow model read of a busy photo takes
 * well under a minute; two is generous.
 */
export const EXTRACT_TIMEOUT_MS = 120_000;

/**
 * `key` identifies the file (PR #400 review P3-3): the server stores a file once per key,
 * so a Retry after the page stopped waiting gets the first attempt's answer, never a
 * second stored scan.
 */
async function extractOne(file: File, key?: string): Promise<ChequeMultiExtractionResponse> {
  const form = new FormData();
  form.append("file", file);
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), EXTRACT_TIMEOUT_MS);
  let res: Response;
  try {
    res = await fetch("/api/proxy/v1/cheques/extract-many", {
      method: "POST", body: form, signal: controller.signal,
      headers: key ? { "Idempotency-Key": key } : undefined,
    });
  } catch (e) {
    if (controller.signal.aborted) throw new ChequeExtractError("Reading this file took too long", "cheque_upload_timeout");
    throw e;
  } finally {
    clearTimeout(timer);
  }
  if (!res.ok) {
    const err = await res.json().catch(() => ({}));
    throw new ChequeExtractError(err.error ?? `Upload failed (${res.status})`, err.code ?? null);
  }
  return normalizeExtraction(await res.json());
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
      errorCode: null,
    });
  }
  return { items, rejectedCount };
}

export function useBulkChequeExtract() {
  const [items, setItems] = useState<BulkExtractItem[]>([]);
  const inFlight = useRef<Set<string>>(new Set());
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
        const response = await extractOne(job.file, job.id);
        job.status = "extracted";
        job.response = response;
        setItem(job.id, { status: "extracted", response });
      } catch (e) {
        const message = e instanceof Error ? e.message : "Failed";
        const errorCode = e instanceof ChequeExtractError ? e.code : null;
        job.status = "failed";
        job.error = message;
        job.errorCode = errorCode;
        setItem(job.id, { status: "failed", error: message, errorCode });
      }
      return next();
    };

    for (let i = 0; i < MAX_CONCURRENT; i++) workers.push(next());
    await Promise.all(workers);
    setRunning(false);
    return results;
  }, []);

  /**
   * Re-run extraction for one item. Resolves with the new response (or null on
   * failure) so a caller holding its own derived rows can merge the result,
   * the same way `start` hands back its results.
   */
  const retry = useCallback(async (id: string): Promise<ChequeMultiExtractionResponse | null> => {
    const target = items.find(it => it.id === id);
    if (!target) return null;
    // The Retry button disables only once `setItem` re-renders, so a fast
    // double-click would upload and OCR the same scan twice and orphan one
    // blob. A ref is read synchronously, before any re-render.
    if (inFlight.current.has(id)) return null;
    inFlight.current.add(id);
    setItem(id, { status: "extracting", error: null, errorCode: null });
    try {
      const response = await extractOne(target.file, target.id);
      setItem(id, { status: "extracted", response });
      return response;
    } catch (e) {
      setItem(id, {
        status: "failed",
        error: e instanceof Error ? e.message : "Failed",
        errorCode: e instanceof ChequeExtractError ? e.code : null,
      });
      return null;
    } finally {
      inFlight.current.delete(id);
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
