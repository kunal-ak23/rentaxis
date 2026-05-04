"use client";

import { useRef, useState } from "react";
import { Camera, Loader2, AlertTriangle } from "lucide-react";
import { useTranslations } from "next-intl";
import { useChequeExtraction } from "./useChequeExtraction";
import type { ExtractedCheque } from "@/types/cheque";

export type ChequeScannerResult = Partial<ExtractedCheque> & {
  imageUrl: string;
  imageBlobPath: string;
  imageUploadedAt: string;
};

type Props = {
  onExtracted: (data: ChequeScannerResult) => void;
  disabled?: boolean;
};

export default function ChequeScanner({ onExtracted, disabled = false }: Props) {
  const t = useTranslations("cheque.scanner");
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [open, setOpen] = useState(false);
  const [selectedFileName, setSelectedFileName] = useState<string | null>(null);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [pendingResult, setPendingResult] = useState<ChequeScannerResult | null>(null);
  const [pendingFallback, setPendingFallback] = useState<ChequeScannerResult | null>(null);
  const extraction = useChequeExtraction();

  const picking = extraction.isPending;
  const payload = extraction.data;
  const extracted = payload?.extracted;
  const allowedTypes = new Set(["image/jpeg", "image/jpg", "image/png", "image/heic", "image/heif"]);

  const onPick = async (file: File | null) => {
    if (!file) return;
    if (!allowedTypes.has(file.type.toLowerCase())) {
      setValidationError(t("invalidType"));
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      setValidationError(t("fileTooLarge"));
      return;
    }
    setSelectedFileName(file.name);
    setValidationError(null);
    setPendingResult(null);
    setPendingFallback(null);
    try {
      const response = await extraction.mutateAsync(file);
      if (response.extracted) {
        setPendingResult({
          chequeNumber: response.extracted.chequeNumber,
          bankName: response.extracted.bankName,
          payerName: response.extracted.payerName,
          chequeDate: response.extracted.chequeDate,
          confidence: response.extracted.confidence,
          imageUrl: response.image.url,
          imageBlobPath: response.image.blobPath,
          imageUploadedAt: response.image.uploadedAt,
        });
      } else {
        setPendingFallback({
          imageUrl: response.image.url,
          imageBlobPath: response.image.blobPath,
          imageUploadedAt: response.image.uploadedAt,
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
        {t("scanButton")}
      </button>

      {open && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/45 p-4">
          <div className="w-full max-w-lg rounded-xl border border-border bg-background p-4">
            <div className="mb-3 flex items-center justify-between">
              <h3 className="text-sm font-semibold">{t("scanButton")}</h3>
              <button type="button" className="text-xs text-muted" onClick={() => setOpen(false)}>
                {t("close")}
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
              <p className="mb-3 text-xs text-muted">{t("uploadPrompt")}</p>
              <button
                type="button"
                className="rounded bg-primary px-3 py-2 text-xs font-semibold text-primary-foreground"
                onClick={() => inputRef.current?.click()}
                disabled={picking}
              >
                {picking ? t("extracting") : t("choosePhoto")}
              </button>
              {selectedFileName && <p className="mt-2 text-[11px] text-muted">{selectedFileName}</p>}
            </div>

            {picking && (
              <div className="mt-3 inline-flex items-center gap-2 text-xs text-muted">
                <Loader2 size={12} className="animate-spin" /> {t("uploadingAndExtracting")}
              </div>
            )}

            {(validationError || extraction.error) && (
              <div className="mt-3 rounded border border-red-300 bg-red-50 p-2 text-xs text-red-700">
                {validationError ?? t("genericError")}
              </div>
            )}

            {extracted && extracted.confidence === "LOW" && (
              <div className="mt-3 rounded border border-yellow-300 bg-yellow-50 p-2 text-xs text-yellow-800">
                <span className="inline-flex items-center gap-1"><AlertTriangle size={12} /> {t("lowConfidenceBanner")}</span>
              </div>
            )}

            {pendingFallback && (
              <div className="mt-3 rounded border border-amber-300 bg-amber-50 p-2 text-xs text-amber-900">
                <p className="mb-2">{t("extractionFailed")}</p>
                <button
                  type="button"
                  className="rounded bg-amber-700 px-3 py-1 text-xs font-semibold text-white"
                  onClick={() => {
                    onExtracted(pendingFallback);
                    setOpen(false);
                  }}
                >
                  {t("attachPhoto")}
                </button>
              </div>
            )}

            {pendingResult && (
              <div className="mt-3 flex justify-end gap-2">
                <button
                  type="button"
                  className="rounded border border-border px-3 py-1 text-xs font-semibold"
                  onClick={() => inputRef.current?.click()}
                >
                  {t("rescan")}
                </button>
                <button
                  type="button"
                  className="rounded bg-primary px-3 py-1 text-xs font-semibold text-primary-foreground"
                  onClick={() => {
                    onExtracted(pendingResult);
                    setOpen(false);
                  }}
                >
                  {t("useValues")}
                </button>
              </div>
            )}
          </div>
        </div>
      )}
    </>
  );
}
