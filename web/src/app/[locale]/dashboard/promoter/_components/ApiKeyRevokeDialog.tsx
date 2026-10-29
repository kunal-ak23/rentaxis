"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";

interface ApiKeyRevokeDialogProps {
    keyName: string;
    busy: boolean;
    onConfirm: (reason: string) => void;
    onCancel: () => void;
}

/** Confirms a revoke and collects the optional reason. */
export function ApiKeyRevokeDialog({ keyName, busy, onConfirm, onCancel }: ApiKeyRevokeDialogProps) {
    const t = useTranslations("Promoter");
    const [reason, setReason] = useState("");

    return (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
            <div role="dialog" aria-modal="true" aria-labelledby="api-key-revoke-title"
                className="w-full max-w-md space-y-4 rounded-xl bg-white p-6 shadow-xl">
                <h2 id="api-key-revoke-title" className="text-lg font-semibold">
                    {t("devRevokeTitle", { name: keyName })}
                </h2>
                <p className="text-sm text-gray-600">{t("devRevokeBody")}</p>
                <label className="block text-sm">
                    <span className="mb-1 block">{t("devRevokeReasonLabel")}</span>
                    <input type="text" value={reason} maxLength={200}
                        onChange={e => setReason(e.target.value)}
                        className="w-full rounded-lg border px-3 py-2" />
                </label>
                <div className="flex justify-end gap-2">
                    <button type="button" onClick={onCancel} className="rounded-lg border px-4 py-2">
                        {t("devCancel")}
                    </button>
                    <button type="button" disabled={busy} onClick={() => onConfirm(reason.trim())}
                        className="rounded-lg bg-red-600 px-4 py-2 text-white disabled:opacity-40">
                        {t("devRevokeConfirm")}
                    </button>
                </div>
            </div>
        </div>
    );
}
