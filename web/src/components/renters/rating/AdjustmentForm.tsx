"use client";

import { useState } from "react";
import { useTranslations } from "next-intl";
import { renterRatingsApi } from "@/lib/api/renterRatings";
import { ratingErrorText } from "./ratingFormat";

export default function AdjustmentForm({ renterId, onSaved }: { renterId: string; onSaved: () => void }) {
    const t = useTranslations("RenterRating");
    const [points, setPoints] = useState("");
    const [reason, setReason] = useState("");
    const [error, setError] = useState<string | null>(null);
    const [saving, setSaving] = useState(false);

    async function submit(e: React.FormEvent) {
        e.preventDefault();
        const n = Number(points);
        if (points.trim() === "" || !Number.isInteger(n) || n === 0 || n < -30 || n > 30) { setError(t("errPoints")); return; }
        const r = reason.trim();
        if (r.length < 1 || r.length > 500) { setError(t("errReason")); return; }
        setError(null);
        setSaving(true);
        try {
            await renterRatingsApi.addAdjustment(renterId, { points: n, reason: r });
            setPoints(""); setReason("");
            onSaved();
        } catch (err) {
            setError(ratingErrorText(err, { generic: t("saveError"), noContract: t("errNoContract") }));
        } finally {
            setSaving(false);
        }
    }

    const field = "mt-1 w-full rounded-md border border-border bg-surface px-2 py-1.5 text-sm";
    return (
        <form onSubmit={submit} noValidate data-testid="rating-adjust-form" className="space-y-3 rounded-lg border border-border p-3">
            <h3 className="text-xs font-bold text-foreground">{t("adjustTitle")}</h3>
            <label className="block text-xs text-muted">
                {t("points")}
                <input type="number" inputMode="numeric" step={1} value={points} onChange={e => setPoints(e.target.value)} className={field} />
            </label>
            <label className="block text-xs text-muted">
                {t("reason")}
                <textarea value={reason} maxLength={500} rows={2} onChange={e => setReason(e.target.value)} className={field} />
            </label>
            {error && <p role="alert" className="text-xs text-red-600">{error}</p>}
            <button type="submit" disabled={saving}
                className="rounded-md bg-primary px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-50 cursor-pointer">
                {saving ? t("saving") : t("add")}
            </button>
        </form>
    );
}
