"use client";

import { useCallback, useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { AlertTriangle, CheckCircle2 } from "lucide-react";
import { ApiError } from "@/lib/api/facilities";
import { ledgerApi } from "@/lib/api/ledger";
import { serverText } from "@/components/finance/bankrec/serverText";

/**
 * F15-11: journals posted before every journal had to balance per property (a
 * cross-property transfer's carry, a voucher across buildings) leave a property's
 * trial balance out. An admin posts one clearing journal per such journal; a
 * second run posts nothing. Shown only while there is something to repair.
 */
export default function InterPropertyRepairBanner() {
    const t = useTranslations("Ledger");
    const tCommon = useTranslations("Common");
    const [count, setCount] = useState<number | null>(null);
    const [busy, setBusy] = useState(false);
    const [done, setDone] = useState<number | null>(null);
    const [error, setError] = useState<string | null>(null);

    const load = useCallback(async () => {
        try {
            setCount((await ledgerApi.journals.interPropertyUnbalanced()).length);
        } catch {
            setCount(null);
        }
    }, []);

    useEffect(() => { load(); }, [load]);

    const repair = async () => {
        if (!window.confirm(t("interPropertyRepairConfirm", { count: count ?? 0 }))) return;
        setBusy(true);
        setError(null);
        try {
            const r = await ledgerApi.journals.interPropertyRepair();
            setDone(r.repaired.length);
            await load();
        } catch (e) {
            setError(e instanceof ApiError ? serverText(tCommon, e) || e.message : tCommon("loadFailed"));
        } finally {
            setBusy(false);
        }
    };

    if (done !== null && !count) {
        return (
            <div data-testid="ip-repair-done" className="mb-4 flex items-center gap-2 bg-success/10 border border-success/30 text-success rounded-xl px-5 py-3 text-xs">
                <CheckCircle2 size={16} />{t("interPropertyRepairDone", { count: done })}
            </div>
        );
    }
    if (!count) return null;
    return (
        <div data-testid="ip-repair" className="mb-4 flex flex-wrap items-center justify-between gap-3 bg-warning/10 border border-warning/30 rounded-xl px-5 py-3">
            <span className="flex items-start gap-2 text-xs text-foreground">
                <AlertTriangle size={16} className="shrink-0 mt-0.5 text-warning" />
                {t("interPropertyUnbalanced", { count })}
            </span>
            <button type="button" disabled={busy} onClick={repair} data-testid="ip-repair-run"
                    className="px-4 py-2 rounded-lg bg-primary text-primary-foreground text-xs font-bold disabled:opacity-50">
                {t("interPropertyRepair")}
            </button>
            {error && <p className="w-full text-xs text-error">{error}</p>}
        </div>
    );
}
