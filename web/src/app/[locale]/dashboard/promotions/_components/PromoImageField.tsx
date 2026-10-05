"use client";

import { useId, useRef, useState } from "react";
import { useTranslations } from "next-intl";
import { ImageIcon, Loader2, Trash2, Upload } from "lucide-react";
import { PROMO_IMAGE_MAX_BYTES, uploadPromoImage } from "@/lib/api/promotions";
import { promoImageSrc } from "@/lib/assetUrl";

interface PromoImageFieldProps {
    label: string;
    value: string;
    onChange: (url: string) => void;
    /** Reports an upload in progress, so the editor can hold Save until it lands. */
    onBusyChange?: (busy: boolean) => void;
}

const ACCEPT = "image/png,image/jpeg,image/gif,image/webp";

/**
 * An ad's artwork or a business logo: upload an image (ux6 item 2 — these were
 * bare URL boxes, so an admin needed the image hosted somewhere already).
 * A value saved earlier as an https link to artwork hosted elsewhere is shown
 * and kept until it is replaced or removed.
 */
export function PromoImageField({ label, value, onChange, onBusyChange }: PromoImageFieldProps) {
    const t = useTranslations("Promotions");
    const inputRef = useRef<HTMLInputElement>(null);
    const hintId = useId();
    const errorId = useId();
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [broken, setBroken] = useState(false);

    function setUploading(next: boolean) {
        setBusy(next);
        onBusyChange?.(next);
    }

    async function pick(file: File | undefined) {
        if (!file) return;
        setError(null);
        if (file.size > PROMO_IMAGE_MAX_BYTES) {
            setError(t("imageTooLarge"));
            return;
        }
        setUploading(true);
        try {
            const url = await uploadPromoImage(file);
            setBroken(false);
            onChange(url);
        } catch {
            setError(t("imageUploadFailed"));
        } finally {
            setUploading(false);
            if (inputRef.current) inputRef.current.value = "";
        }
    }

    return (
        <div>
            <span className="mb-1 block text-sm font-medium">{label}</span>
            <div className="flex items-center gap-3">
                <div className="flex h-16 w-16 shrink-0 items-center justify-center overflow-hidden rounded-lg border bg-gray-50">
                    {value && !broken ? (
                        // eslint-disable-next-line @next/next/no-img-element
                        <img src={promoImageSrc(value)} alt={label} className="h-full w-full object-cover"
                            onError={() => setBroken(true)} />
                    ) : (
                        <ImageIcon size={20} className="text-gray-400" aria-hidden="true" />
                    )}
                </div>
                <div className="flex flex-wrap items-center gap-2">
                    <input
                        ref={inputRef}
                        type="file"
                        accept={ACCEPT}
                        className="sr-only"
                        aria-label={`${label}: ${t("uploadImage")}`}
                        aria-describedby={error ? `${hintId} ${errorId}` : hintId}
                        onChange={e => void pick(e.target.files?.[0])}
                        disabled={busy}
                    />
                    <button type="button" disabled={busy}
                        onClick={() => inputRef.current?.click()}
                        className="inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-sm disabled:opacity-50">
                        {busy ? <Loader2 size={14} className="animate-spin" aria-hidden="true" />
                            : <Upload size={14} aria-hidden="true" />}
                        {busy ? t("uploading") : value ? t("replaceImage") : t("uploadImage")}
                    </button>
                    {value && !busy && (
                        <button type="button" onClick={() => { setError(null); setBroken(false); onChange(""); }}
                            className="inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-sm text-red-600">
                            <Trash2 size={14} aria-hidden="true" />
                            {t("removeImage")}
                        </button>
                    )}
                </div>
            </div>
            <p id={hintId} className="mt-1 text-xs text-gray-500">{t("imageHint")}</p>
            {error && <p role="alert" id={errorId} className="mt-1 text-sm text-red-600">{error}</p>}
        </div>
    );
}
