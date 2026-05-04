"use client";

import { useRef, useState } from "react";
import Image from "next/image";
import { Camera, Loader2, AlertTriangle, Check, X } from "lucide-react";
import { useTranslations } from "next-intl";
import { useChequeExtraction } from "./useChequeExtraction";
import type { ChequeConfidence, ExtractedCheque } from "@/types/cheque";

export type ChequeScannerResult = Partial<ExtractedCheque> & {
  imageUrl: string;
  imageBlobPath: string;
  imageUploadedAt: string;
};

type Props = {
  onExtracted: (data: ChequeScannerResult) => void;
  disabled?: boolean;
};

type Step = 1 | 2;

const ALLOWED_TYPES = new Set([
  "image/jpeg",
  "image/jpg",
  "image/png",
  "image/heic",
  "image/heif",
]);

export default function ChequeScanner({ onExtracted, disabled = false }: Props) {
  const t = useTranslations("cheque.scanner");
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [open, setOpen] = useState(false);
  const [step, setStep] = useState<Step>(1);
  const [validationError, setValidationError] = useState<string | null>(null);
  const [imagePreviewUrl, setImagePreviewUrl] = useState<string | null>(null);
  const extraction = useChequeExtraction();

  const close = () => {
    setOpen(false);
    setStep(1);
    setValidationError(null);
    setImagePreviewUrl(null);
    extraction.reset();
  };

  const onPick = async (file: File | null) => {
    if (!file) return;
    setValidationError(null);
    extraction.reset();

    if (!ALLOWED_TYPES.has(file.type.toLowerCase())) {
      setValidationError(t("invalidType"));
      return;
    }
    if (file.size > 10 * 1024 * 1024) {
      setValidationError(t("fileTooLarge"));
      return;
    }

    setImagePreviewUrl(URL.createObjectURL(file));
    try {
      await extraction.mutateAsync(file);
      setStep(2);
    } catch {
      // error rendered via extraction.error
    }
  };

  const apply = () => {
    if (!extraction.data) return;
    const { image, extracted } = extraction.data;
    if (extracted) {
      onExtracted({
        chequeNumber: extracted.chequeNumber,
        bankName: extracted.bankName,
        payerName: extracted.payerName,
        chequeDate: extracted.chequeDate,
        confidence: extracted.confidence,
        imageUrl: image.url,
        imageBlobPath: image.blobPath,
        imageUploadedAt: image.uploadedAt,
      });
    } else {
      onExtracted({
        imageUrl: image.url,
        imageBlobPath: image.blobPath,
        imageUploadedAt: image.uploadedAt,
      });
    }
    close();
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
          <div className="w-full max-w-3xl rounded-xl border border-border bg-background shadow-lg">
            <ModalHeader t={t} step={step} onClose={close} />
            <input
              ref={inputRef}
              type="file"
              accept="image/jpeg,image/png,image/heic,image/heif"
              capture="environment"
              className="hidden"
              onChange={(e) => void onPick(e.target.files?.[0] ?? null)}
            />

            <div className="px-5 py-4">
              {step === 1 && (
                <Step1Upload
                  t={t}
                  isPicking={extraction.isPending}
                  onClickPick={() => inputRef.current?.click()}
                  validationError={validationError}
                  networkError={extraction.error?.message ?? null}
                />
              )}
              {step === 2 && extraction.data && (
                <Step2Review
                  t={t}
                  imagePreviewUrl={imagePreviewUrl}
                  data={extraction.data}
                />
              )}
            </div>

            <ModalFooter
              t={t}
              step={step}
              canApply={!!extraction.data}
              hasFallback={!!extraction.data && !extraction.data.extracted}
              onBack={() => setStep(1)}
              onRescan={() => {
                extraction.reset();
                setStep(1);
                inputRef.current?.click();
              }}
              onApply={apply}
              onClose={close}
            />
          </div>
        </div>
      )}
    </>
  );
}

function ModalHeader({
  t,
  step,
  onClose,
}: {
  t: ReturnType<typeof useTranslations>;
  step: Step;
  onClose: () => void;
}) {
  const steps: { n: Step; label: string }[] = [
    { n: 1, label: t("step1") },
    { n: 2, label: t("step2") },
  ];
  return (
    <div className="flex items-start justify-between border-b border-border px-5 py-4">
      <div>
        <p className="text-[11px] uppercase tracking-wider text-muted">{t("breadcrumb")}</p>
        <h3 className="text-base font-semibold">{t("title")}</h3>
      </div>
      <div className="flex items-center gap-3">
        <ol className="hidden items-center gap-2 sm:flex">
          {steps.map((s) => {
            const active = s.n === step;
            const done = s.n < step;
            return (
              <li
                key={s.n}
                className={
                  "inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-xs " +
                  (active
                    ? "border-primary bg-primary/10 text-primary"
                    : done
                    ? "border-emerald-300 bg-emerald-50 text-emerald-700"
                    : "border-border text-muted")
                }
              >
                {done ? <Check size={10} /> : <span>{s.n}</span>}
                {s.label}
              </li>
            );
          })}
        </ol>
        <button
          type="button"
          onClick={onClose}
          aria-label={t("close")}
          className="rounded p-1 text-muted hover:bg-input/40"
        >
          <X size={16} />
        </button>
      </div>
    </div>
  );
}

function Step1Upload({
  t,
  isPicking,
  onClickPick,
  validationError,
  networkError,
}: {
  t: ReturnType<typeof useTranslations>;
  isPicking: boolean;
  onClickPick: () => void;
  validationError: string | null;
  networkError: string | null;
}) {
  const fields = [
    t("fieldChequeNumber"),
    t("fieldBankName"),
    t("fieldPayerName"),
    t("fieldChequeDate"),
  ];
  return (
    <div className="grid gap-4 sm:grid-cols-[1fr_220px]">
      <button
        type="button"
        onClick={onClickPick}
        disabled={isPicking}
        className="flex min-h-[200px] flex-col items-center justify-center rounded-lg border border-dashed border-border bg-input/20 px-6 py-8 text-center hover:border-primary/60 disabled:cursor-not-allowed disabled:opacity-60"
      >
        {isPicking ? (
          <span className="inline-flex items-center gap-2 text-sm text-muted">
            <Loader2 size={14} className="animate-spin" /> {t("uploadingAndExtracting")}
          </span>
        ) : (
          <>
            <Camera size={20} className="mb-2 text-muted" />
            <p className="text-sm font-medium">{t("choosePhoto")}</p>
            <p className="mt-1 text-xs text-muted">{t("dropPrompt")}</p>
          </>
        )}
      </button>

      <aside className="rounded-lg border border-border bg-background p-3 text-xs">
        <p className="mb-2 font-semibold">{t("whatWeExtract")}</p>
        <ul className="space-y-1.5 text-muted">
          {fields.map((f) => (
            <li key={f} className="flex items-center gap-1.5">
              <Check size={12} className="text-emerald-600" /> {f}
            </li>
          ))}
        </ul>
      </aside>

      {(validationError || networkError) && (
        <div className="sm:col-span-2 rounded border border-red-300 bg-red-50 p-2 text-xs text-red-700">
          {validationError ?? networkError ?? t("genericError")}
        </div>
      )}
    </div>
  );
}

function Step2Review({
  t,
  imagePreviewUrl,
  data,
}: {
  t: ReturnType<typeof useTranslations>;
  imagePreviewUrl: string | null;
  data: { extracted: ExtractedCheque | null; warnings: string[] };
}) {
  const extracted = data.extracted;
  return (
    <div className="grid gap-4 sm:grid-cols-2">
      <div className="rounded-lg border border-border bg-input/20 p-2">
        {imagePreviewUrl ? (
          <Image
            src={imagePreviewUrl}
            alt={t("imagePreview")}
            width={500}
            height={250}
            unoptimized
            className="h-auto w-full rounded"
          />
        ) : (
          <div className="flex h-40 items-center justify-center text-xs text-muted">
            {t("imagePreview")}
          </div>
        )}
      </div>

      <div className="space-y-3">
        {extracted ? (
          <>
            <ConfidenceBadge t={t} confidence={extracted.confidence} />
            <FieldRow label={t("fieldChequeNumber")} value={extracted.chequeNumber} t={t} />
            <FieldRow label={t("fieldBankName")} value={extracted.bankName} t={t} />
            <FieldRow label={t("fieldPayerName")} value={extracted.payerName} t={t} />
            <FieldRow label={t("fieldChequeDate")} value={extracted.chequeDate} t={t} />
            {extracted.confidence === "LOW" && (
              <div className="rounded border border-yellow-300 bg-yellow-50 p-2 text-xs text-yellow-800">
                <span className="inline-flex items-center gap-1">
                  <AlertTriangle size={12} /> {t("lowConfidenceBanner")}
                </span>
              </div>
            )}
          </>
        ) : (
          <div className="rounded border border-amber-300 bg-amber-50 p-3 text-xs text-amber-900">
            {t("extractionFailed")}
          </div>
        )}
        {data.warnings.length > 0 && (
          <ul className="space-y-1 text-[11px] text-muted">
            {data.warnings.map((w, i) => (
              <li key={i} className="flex items-start gap-1">
                <AlertTriangle size={10} className="mt-0.5 shrink-0" /> {w}
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function FieldRow({
  label,
  value,
  t,
}: {
  label: string;
  value: string | null;
  t: ReturnType<typeof useTranslations>;
}) {
  return (
    <div className="flex items-baseline justify-between gap-3 border-b border-border pb-1.5">
      <span className="text-xs text-muted">{label}</span>
      <span className={value ? "text-sm font-medium" : "text-xs italic text-muted"}>
        {value ?? t("notExtracted")}
      </span>
    </div>
  );
}

function ConfidenceBadge({
  t,
  confidence,
}: {
  t: ReturnType<typeof useTranslations>;
  confidence: ChequeConfidence;
}) {
  const tone =
    confidence === "HIGH"
      ? "border-emerald-300 bg-emerald-50 text-emerald-700"
      : confidence === "MEDIUM"
      ? "border-blue-300 bg-blue-50 text-blue-700"
      : "border-yellow-300 bg-yellow-50 text-yellow-800";
  const label =
    confidence === "HIGH"
      ? t("confidenceHigh")
      : confidence === "MEDIUM"
      ? t("confidenceMedium")
      : t("confidenceLow");
  return (
    <span className={"inline-flex items-center gap-1 rounded-full border px-2 py-0.5 text-[11px] " + tone}>
      {t("confidence")}: {label}
    </span>
  );
}

function ModalFooter({
  t,
  step,
  canApply,
  hasFallback,
  onBack,
  onRescan,
  onApply,
  onClose,
}: {
  t: ReturnType<typeof useTranslations>;
  step: Step;
  canApply: boolean;
  hasFallback: boolean;
  onBack: () => void;
  onRescan: () => void;
  onApply: () => void;
  onClose: () => void;
}) {
  return (
    <div className="flex items-center justify-between border-t border-border px-5 py-3">
      <button
        type="button"
        onClick={step === 1 ? onClose : onBack}
        className="rounded border border-border px-3 py-1 text-xs"
      >
        {step === 1 ? t("close") : t("back")}
      </button>
      {step === 2 && (
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={onRescan}
            className="rounded border border-border px-3 py-1 text-xs"
          >
            {t("rescan")}
          </button>
          <button
            type="button"
            onClick={onApply}
            disabled={!canApply}
            className="rounded bg-primary px-3 py-1 text-xs font-semibold text-primary-foreground disabled:opacity-50"
          >
            {hasFallback ? t("attachPhoto") : t("applyButton")}
          </button>
        </div>
      )}
    </div>
  );
}
