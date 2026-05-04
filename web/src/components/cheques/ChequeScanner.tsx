"use client";

import { useRef, useState } from "react";
import { Camera, Loader2, AlertTriangle } from "lucide-react";
import { useChequeExtraction } from "./useChequeExtraction";
import type { ExtractedCheque } from "@/types/cheque";

export type ChequeScannerResult = Partial<ExtractedCheque> & {
  imageUrl: string;
  imageBlobPath: string;
};

type Props = {
  onExtracted: (data: ChequeScannerResult) => void;
  disabled?: boolean;
};

export default function ChequeScanner({ onExtracted, disabled = false }: Props) {
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [open, setOpen] = useState(false);
  const [selectedFileName, setSelectedFileName] = useState<string | null>(null);
  const [pendingFallback, setPendingFallback] = useState<ChequeScannerResult | null>(null);
  const extraction = useChequeExtraction();

  const picking = extraction.isPending;
  const payload = extraction.data;
  const extracted = payload?.extracted;

  const onPick = async (file: File | null) => {
    if (!file) return;
    setSelectedFileName(file.name);
    setPendingFallback(null);
    try {
      const response = await extraction.mutateAsync(file);
      if (response.extracted) {
        onExtracted({
          chequeNumber: response.extracted.chequeNumber,
          bankName: response.extracted.bankName,
          payerName: response.extracted.payerName,
          chequeDate: response.extracted.chequeDate,
          confidence: response.extracted.confidence,
          imageUrl: response.image.url,
          imageBlobPath: response.image.blobPath,
        });
      } else {
        setPendingFallback({
          imageUrl: response.image.url,
          imageBlobPath: response.image.blobPath,
        });
      }
    } catch {
      // rendered below by extraction.error
    }
  };

  return (
    <>
      <button
        type="button"
        disabled={disabled}
        onClick={() => setOpen(true)}
        className="inline-flex items-center gap-1 rounded border border-border px-2 py-1 text-xs hover:bg-input/40 disabled:opacity-50"
      >
        <Camera size={12} />
        Scan Cheque
      </button>

      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-4">
          <div className="w-full max-w-lg rounded-xl border border-border bg-background p-4">
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-sm font-semibold">Scan Cheque</h3>
              <button type="button" className="text-xs text-muted" onClick={() => setOpen(false)}>
                Close
              </button>
            </div>

            <input
              ref={inputRef}
              type="file"
              accept="image/*"
              capture="environment"
              className="hidden"
              onChange={(e) => void onPick(e.target.files?.[0] ?? null)}
            />

            <div className="rounded-lg border border-dashed border-border p-4 text-center">
              <p className="mb-3 text-xs text-muted">Upload a cheque photo to auto-fill values.</p>
              <button
                type="button"
                className="rounded bg-primary px-3 py-2 text-xs font-semibold text-primary-foreground"
                onClick={() => inputRef.current?.click()}
                disabled={picking}
              >
                {picking ? "Reading cheque..." : "Choose photo"}
              </button>
              {selectedFileName && <p className="mt-2 text-[11px] text-muted">{selectedFileName}</p>}
            </div>

            {picking && (
              <div className="mt-3 inline-flex items-center gap-2 text-xs text-muted">
                <Loader2 size={12} className="animate-spin" /> Uploading and extracting...
              </div>
            )}

            {extraction.error && (
              <div className="mt-3 rounded border border-red-300 bg-red-50 p-2 text-xs text-red-700">
                Couldn't read this cheque. Please try again.
              </div>
            )}

            {extracted && extracted.confidence === "LOW" && (
              <div className="mt-3 rounded border border-yellow-300 bg-yellow-50 p-2 text-xs text-yellow-800">
                <span className="inline-flex items-center gap-1"><AlertTriangle size={12} /> AI confidence is low. Please verify values.</span>
              </div>
            )}

            {pendingFallback && (
              <div className="mt-3 rounded border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900">
                <p className="mb-2">Couldn't read this cheque automatically. Attach photo and continue?</p>
                <button
                  type="button"
                  className="rounded bg-amber-700 px-3 py-1 text-xs font-semibold text-white"
                  onClick={() => {
                    onExtracted(pendingFallback);
                    setOpen(false);
                  }}
                >
                  Attach photo and continue
                </button>
              </div>
            )}

            {payload?.extracted && (
              <div className="mt-3 flex justify-end">
                <button
                  type="button"
                  className="rounded bg-primary px-3 py-1 text-xs font-semibold text-primary-foreground"
                  onClick={() => setOpen(false)}
                >
                  Use these values
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </>
  );
}
