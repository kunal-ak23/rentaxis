"use client";

import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import type { PipelineStage, StageId } from "@/lib/dashboard/pipeline";

const KEY: Record<StageId, string> = {
    draft: "stageDraft", upcoming: "stageUpcoming", active: "stageActive",
    expiring: "stageExpiring", notice: "stageNotice", settlement: "stageSettlement",
};

/**
 * Home's contract pipeline (spec §1a): Draft → Upcoming → Active → Expiring ≤ 60 d
 * → Notice → Settlement due, each with its count (linking to the filtered
 * contract list) and its oldest contract.
 */
export default function ContractPipeline({ stages }: { stages: PipelineStage[] }) {
    const t = useTranslations("Today");
    return (
        <section aria-labelledby="pipeline-title" data-testid="contract-pipeline" className="bg-surface border border-border rounded-[var(--radius-lg)] p-4">
            <h2 id="pipeline-title" className="text-[13px] text-[var(--ink-500)] mb-3">{t("pipelineTitle")}</h2>
            <ol className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-2">
                {stages.map(s => (
                    <li key={s.id} data-testid={`pipeline-${s.id}`} className="rounded-[var(--radius)] border border-border p-3 min-w-0 hover:border-primary transition-colors">
                        <Link href={s.href} className="block" data-testid={`pipeline-${s.id}-link`}>
                            <div className="text-[11px] font-semibold uppercase tracking-wider text-[var(--ink-500)] truncate">{t(KEY[s.id])}</div>
                            <div className="font-serif text-[22px] font-semibold tabular-nums">{s.capped ? t("capped", { count: s.count }) : s.count}</div>
                        </Link>
                        {s.oldest ? (
                            <Link href={`/dashboard/leases/${s.oldest.leaseId}`} className="block truncate text-[11px] text-primary hover:underline" title={s.oldest.label}>
                                {t("oldest", { label: s.oldest.label })}
                            </Link>
                        ) : <span className="block text-[11px] text-transparent select-none" aria-hidden>·</span>}
                    </li>
                ))}
            </ol>
        </section>
    );
}
