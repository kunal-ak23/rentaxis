"use client";

import { useEffect, useState } from "react";
import { useTranslations } from "next-intl";
import { CheckCircle2, Circle } from "lucide-react";
import { Link } from "@/i18n/routing";
import { lookupApi } from "@/lib/api/lookup";

/**
 * QA-12 (26 Nov): a new organisation's Today page said only "Nothing needs you
 * right now". This card lists the four setup steps, ticks the ones already done
 * and links to where each is done. It is shown until the first contract exists
 * (a contract needs a project, a unit and a tenant, so every step is then done).
 */
export default function GettingStarted({ projects, units, hasContracts }: {
    projects: number;
    units: number;
    /** Null while the contract counts load: the card waits rather than flashing. */
    hasContracts: boolean | null;
}) {
    const t = useTranslations("Today");
    const [hasTenant, setHasTenant] = useState<boolean | null>(null);
    const show = hasContracts === false;

    useEffect(() => {
        if (!show) return;
        let alive = true;
        lookupApi.searchRenters({ limit: 1 })
            .then(rows => { if (alive) setHasTenant(rows.length > 0); })
            .catch(() => { if (alive) setHasTenant(false); });
        return () => { alive = false; };
    }, [show]);

    if (!show) return null;

    const steps = [
        { key: "gsProject", done: projects > 0, href: "/dashboard/properties" },
        { key: "gsUnits", done: units > 0, href: "/dashboard/properties" },
        { key: "gsTenant", done: hasTenant === true, href: "/dashboard/renters?new=1" },
        { key: "gsContract", done: false, href: "/dashboard/leases?new=1" },
    ] as const;

    return (
        <div className="bg-surface border border-border rounded-[var(--radius-lg)] p-5" data-testid="getting-started">
            <div className="font-serif text-[20px] font-semibold text-foreground">{t("gsTitle")}</div>
            <p className="text-[13px] text-[var(--ink-500)] mt-1 mb-3">{t("gsBody")}</p>
            <ol className="flex flex-col gap-2">
                {steps.map((s, i) => (
                    <li key={s.key} data-testid={`getting-started-${s.key}`} data-done={s.done ? "true" : "false"}
                        className="flex items-center gap-2 text-[13px]">
                        {s.done
                            ? <CheckCircle2 size={15} className="text-[var(--green-600)]" aria-label={t("gsDone")} />
                            : <Circle size={15} className="text-[var(--ink-400)]" aria-hidden />}
                        <span className="text-[var(--ink-500)] tabular-nums">{i + 1}.</span>
                        {s.done
                            ? <span className="text-[var(--ink-500)] line-through">{t(s.key)}</span>
                            : <Link href={s.href} className="text-primary font-medium hover:underline">{t(s.key)}</Link>}
                    </li>
                ))}
            </ol>
        </div>
    );
}
