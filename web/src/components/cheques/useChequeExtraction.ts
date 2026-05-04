import { useMutation } from "@tanstack/react-query";
import type { ChequeExtractionResponse } from "@/types/cheque";

export function useChequeExtraction() {
  return useMutation<ChequeExtractionResponse, Error, File>({
    mutationFn: async (file) => {
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

      return res.json();
    },
  });
}
