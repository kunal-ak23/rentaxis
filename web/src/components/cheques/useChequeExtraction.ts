import { useCallback, useState } from "react";
import type { ChequeExtractionResponse } from "@/types/cheque";

export type ChequeExtractionState = {
  isPending: boolean;
  error: Error | null;
  data: ChequeExtractionResponse | null;
  mutateAsync: (file: File) => Promise<ChequeExtractionResponse>;
  reset: () => void;
};

export function useChequeExtraction(): ChequeExtractionState {
  const [isPending, setIsPending] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const [data, setData] = useState<ChequeExtractionResponse | null>(null);

  const mutateAsync = useCallback(async (file: File): Promise<ChequeExtractionResponse> => {
    setIsPending(true);
    setError(null);
    setData(null);
    try {
      const form = new FormData();
      form.append("file", file);

      const res = await fetch("/api/proxy/v1/cheques/extract", {
        method: "POST",
        body: form,
      });

      if (!res.ok) {
        const err = await res.json().catch(() => ({}));
        throw new Error(err.error ?? `Upload failed (${res.status})`);
      }

      const json = (await res.json()) as ChequeExtractionResponse;
      setData(json);
      return json;
    } catch (e) {
      const err = e instanceof Error ? e : new Error("Upload failed");
      setError(err);
      throw err;
    } finally {
      setIsPending(false);
    }
  }, []);

  const reset = useCallback(() => {
    setIsPending(false);
    setError(null);
    setData(null);
  }, []);

  return { isPending, error, data, mutateAsync, reset };
}
