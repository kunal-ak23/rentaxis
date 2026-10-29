"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";

interface ApiKeyRevealDialogProps {
    /** The one-time secret. Held by the parent's state only; cleared when `onClose` runs. */
    secret: string;
    /** True after a rotation: the old key lives on for 24 hours. */
    rotated: boolean;
    onClose: () => void;
}

/**
 * Shows a freshly issued key once. It can only be dismissed with "I've saved it",
 * so the secret is never lost by a stray click. The secret is never persisted.
 */
export function ApiKeyRevealDialog({ secret, rotated, onClose }: ApiKeyRevealDialogProps) {
    const t = useTranslations("Promoter");
    const [copyState, setCopyState] = useState<"idle" | "copied" | "failed">("idle");

    async function copy() {
        try {
            await navigator.clipboard.writeText(secret);
            setCopyState("copied");
        } catch {
            setCopyState("failed");
        }
    }

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
            <div role="dialog" aria-modal="true" aria-labelledby="api-key-reveal-title"
                className="w-full max-w-lg space-y-4 rounded-xl bg-white p-6 shadow-xl">
                <h2 id="api-key-reveal-title" className="text-lg font-semibold">{t("devRevealTitle")}</h2>
                <p role="alert" className="rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900">
                    {t("devRevealWarning")}
                </p>
                {rotated && <p className="text-sm text-gray-600">{t("devRevealRotateNote")}</p>}
                <div className="flex items-center gap-2">
                    <code dir="ltr" data-testid="api-key-secret"
                        className="min-w-0 flex-1 break-all rounded-lg bg-gray-100 px-3 py-2 font-mono text-sm">
                        {secret}
                    </code>
                    <button type="button" onClick={() => void copy()}
                        className="shrink-0 rounded-lg border px-3 py-2 text-sm">
                        {copyState === "copied" ? t("devCopied") : t("devCopy")}
                    </button>
                </div>
                {copyState === "failed" && (
                    <p role="status" className="text-sm text-red-700">{t("devCopyFailed")}</p>
                )}
                <div className="flex justify-end">
                    <button type="button" onClick={onClose}
                        className="rounded-lg bg-gray-900 px-4 py-2 text-white">
                        {t("devSavedIt")}
                    </button>
                </div>
            </div>
        </div>
    );
}
