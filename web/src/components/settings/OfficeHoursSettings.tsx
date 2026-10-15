"use client";
import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";

type OfficeHours = { start: string; end: string };

const ENDPOINT = "/api/proxy/v1/settings/org/office-hours";

/** "09:00:00" or "09:00" → "09:00", as an <input type="time"> takes it. */
const hhmm = (v: string | null | undefined) => (v ?? "").slice(0, 5);

/**
 * Settings › Organisation › Office hours (tutorial 23): office-visit meetings are
 * offered on the half hour between these times, UAE time. An organisation that
 * never set them gets 09:00–18:00. Property visits keep 09:00–21:00.
 */
export default function OfficeHoursSettings() {
    const t = useTranslations("SettingsPage");
    const [start, setStart] = useState("");
    const [end, setEnd] = useState("");
    const [loaded, setLoaded] = useState(false);
    const [loadFailed, setLoadFailed] = useState(false);
    const [saving, setSaving] = useState(false);
    const [status, setStatus] = useState<"saved" | "failed" | "invalid" | null>(null);

    useEffect(() => {
        let live = true;
        fetch(ENDPOINT)
            .then(r => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
            .then((h: OfficeHours) => {
                if (!live) return;
                setStart(hhmm(h.start));
                setEnd(hhmm(h.end));
                setLoaded(true);
            })
            .catch(() => { if (live) setLoadFailed(true); });
        return () => { live = false; };
    }, []);

    const onHalfHour = (v: string) => /^\d{2}:(00|30)$/.test(v);
    const valid = onHalfHour(start) && onHalfHour(end) && start < end;

    const save = async () => {
        if (!valid) { setStatus("invalid"); return; }
        setSaving(true);
        setStatus(null);
        try {
            const res = await fetch(ENDPOINT, {
                method: "PUT",
                headers: { "Content-Type": "application/json" },
                body: JSON.stringify({ start, end }),
            });
            if (!res.ok) throw new Error(String(res.status));
            const h: OfficeHours = await res.json();
            setStart(hhmm(h.start));
            setEnd(hhmm(h.end));
            setStatus("saved");
        } catch {
            setStatus("failed");
        } finally {
            setSaving(false);
        }
    };

    if (loadFailed) return <p className="text-xs text-error" role="alert">{t("officeHoursLoadFailed")}</p>;

    const input = "w-full bg-input border border-border px-3 py-2 rounded-lg text-xs focus:ring-2 focus:ring-primary/30 focus:outline-none tabular-nums";
    return (
        <section className="bg-surface rounded-xl border border-border p-5 space-y-4" data-testid="office-hours-settings" aria-busy={!loaded}>
            <div>
                <h2 className="text-sm font-bold text-foreground">{t("officeHoursHeading")}</h2>
                <p className="text-xs text-muted mt-1">{t("officeHoursHint")}</p>
            </div>
            <div className="grid grid-cols-2 gap-3 max-w-sm">
                <div>
                    <label htmlFor="office-hours-start" className="block text-[11px] text-muted mb-1.5">{t("officeHoursOpens")}</label>
                    <input id="office-hours-start" type="time" step={1800} dir="ltr" disabled={!loaded} value={start}
                        onChange={e => { setStart(e.target.value); setStatus(null); }} className={input} />
                </div>
                <div>
                    <label htmlFor="office-hours-end" className="block text-[11px] text-muted mb-1.5">{t("officeHoursCloses")}</label>
                    <input id="office-hours-end" type="time" step={1800} dir="ltr" disabled={!loaded} value={end}
                        onChange={e => { setEnd(e.target.value); setStatus(null); }} className={input} />
                </div>
            </div>
            <div className="flex flex-wrap items-center justify-end gap-3">
                {status === "saved" && <span className="text-xs text-success" role="status">{t("officeHoursSaved")}</span>}
                {status === "failed" && <span className="text-xs text-error" role="alert">{t("officeHoursSaveFailed")}</span>}
                {status === "invalid" && <span className="text-xs text-error" role="alert">{t("officeHoursInvalid")}</span>}
                <button
                    type="button"
                    onClick={() => void save()}
                    disabled={!loaded || saving}
                    data-testid="office-hours-save"
                    className="bg-primary text-primary-foreground px-4 py-2 rounded-lg text-xs font-semibold hover:bg-primary/90 disabled:opacity-50 cursor-pointer"
                >
                    {t("officeHoursSave")}
                </button>
            </div>
        </section>
    );
}
