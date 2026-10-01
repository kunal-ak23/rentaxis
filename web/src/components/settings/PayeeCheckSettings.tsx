"use client";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";

type PayeeCheck = { enabled: boolean; validNames: string[] };

const ENDPOINT = "/api/proxy/v1/settings/org/payee-check";

/**
 * Settings › Organisation: "Check the payee name on scanned cheques" and the
 * valid payee names (owner ruling 2026-09-29). A scanned cheque passes when its
 * payee matches ANY listed name; the server ignores case, spaces and
 * punctuation and compares Arabic and English as written. The organisation's
 * own name is not added automatically: the Company Admin types every name.
 */
export default function PayeeCheckSettings() {
    const t = useTranslations("SettingsPage");
    const [enabled, setEnabled] = useState(false);
    const [namesText, setNamesText] = useState("");
    const [savedNames, setSavedNames] = useState<string[]>([]);
    const [loaded, setLoaded] = useState(false);
    const [loadFailed, setLoadFailed] = useState(false);
    const [saving, setSaving] = useState(false);
    const [status, setStatus] = useState<"saved" | "failed" | null>(null);

    useEffect(() => {
        let live = true;
        fetch(ENDPOINT)
            .then(r => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
            .then((s: PayeeCheck) => {
                if (!live) return;
                setEnabled(!!s.enabled);
                setSavedNames(s.validNames ?? []);
                setNamesText((s.validNames ?? []).join("\n"));
                setLoaded(true);
            })
            .catch(() => { if (live) setLoadFailed(true); });
        return () => { live = false; };
    }, []);

    const save = async () => {
        setSaving(true);
        setStatus(null);
        const validNames = namesText.split("\n").map(n => n.trim()).filter(n => n.length > 0);
        try {
            const res = await fetch(ENDPOINT, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ enabled, validNames }),
            });
            if (!res.ok) throw new Error(String(res.status));
            const s: PayeeCheck = await res.json();
            setEnabled(!!s.enabled);
            setSavedNames(s.validNames ?? []);
            setNamesText((s.validNames ?? []).join("\n"));
            setStatus("saved");
        } catch {
            setStatus("failed");
        } finally {
            setSaving(false);
        }
    };

    if (loadFailed) return <p className="text-xs text-error" role="alert">{t("payeeCheckLoadFailed")}</p>;

    return (
        <section className="bg-surface rounded-xl border border-border p-5 space-y-4" data-testid="payee-check-settings" aria-busy={!loaded}>
            <div>
                <h2 className="text-sm font-bold text-foreground">{t("payeeCheckHeading")}</h2>
                <p className="text-xs text-muted mt-1">{t("payeeCheckHint")}</p>
            </div>
            <div className="flex items-center gap-3">
                <button
                    type="button"
                    role="switch"
                    aria-checked={enabled}
                    aria-labelledby="payee-check-toggle-label"
                    disabled={!loaded}
                    onClick={() => { setEnabled(v => !v); setStatus(null); }}
                    className={"relative inline-flex h-5 w-9 shrink-0 cursor-pointer rounded-full transition-colors disabled:opacity-50 "
                        + (enabled ? "bg-primary" : "bg-border")}
                >
                    <span
                        aria-hidden="true"
                        className={"absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-all "
                            + (enabled ? "start-[18px]" : "start-0.5")}
                    />
                </button>
                <span id="payee-check-toggle-label" className="text-xs font-semibold text-foreground">{t("payeeCheckToggle")}</span>
            </div>
            <div>
                <label htmlFor="payee-check-names" className="block text-[11px] text-muted mb-1.5">{t("payeeCheckNamesLabel")}</label>
                <textarea
                    id="payee-check-names"
                    dir="auto"
                    rows={4}
                    disabled={!loaded}
                    value={namesText}
                    onChange={e => { setNamesText(e.target.value); setStatus(null); }}
                    className="w-full bg-input border border-border p-3 rounded-xl text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none"
                />
                {loaded && enabled && savedNames.length === 0 && (
                    <p className="text-[11px] text-warning mt-1">{t("payeeCheckEmpty")}</p>
                )}
            </div>
            <div className="flex items-center justify-end gap-3">
                {status === "saved" && <span className="text-xs text-success" role="status">{t("payeeCheckSaved")}</span>}
                {status === "failed" && <span className="text-xs text-error" role="alert">{t("payeeCheckSaveFailed")}</span>}
                <button
                    type="button"
                    onClick={() => void save()}
                    disabled={!loaded || saving}
                    className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 disabled:opacity-50 cursor-pointer"
                >
                    {t("payeeCheckSave")}
                </button>
            </div>
        </section>
    );
}
