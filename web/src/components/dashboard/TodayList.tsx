"use client";

import { useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { ChevronDown, ChevronRight } from "lucide-react";
import { Link } from "@/i18n/routing";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { chequeApi, recognitionApi, type Cheque, type RecognitionStatusSummary } from "@/lib/api/leasing";
import { formatCurrencyCompact } from "@/lib/format";
import { fmtAmount } from "@/lib/api/ledger";
import { fmtIsoDate } from "@/components/leases/leaseMath";
import { buildCollectionsTabs, collectionsHref } from "@/lib/nav/collectionsModel";
import { usePillCounts } from "@/components/collections/usePillCounts";
import type { PipelineStage } from "@/lib/dashboard/pipeline";
import FollowUpsWidget from "./FollowUpsWidget";

export type TodayRowId = "deposit" | "overdue" | "ending" | "drafts" | "tickets" | "penalties" | "recognition" | "recognitionFailed" | "followUps";
export interface TodayRowDef { id: TodayRowId; href: string | null; testId: string }

/** Ticket statuses that still need someone (TicketStatus minus RESOLVED and CLOSED). */
export const OPEN_TICKET_STATUSES = ["OPEN", "ASSIGNED", "IN_PROGRESS", "REOPENED"] as const;

/**
 * "Needs you now" (spec §6): one row per kind of work, each gated by the page
 * it opens, so a role only sees rows it can act on. Cheque rows follow the
 * Collection hub's own tab gates.
 */
export function todayRowDefs(role: UserRole | undefined): TodayRowDef[] {
    const rows: TodayRowDef[] = [];
    const add = (id: TodayRowId, href: string | null) =>
        rows.push({ id, href, testId: `today-${id.replace(/[A-Z]/g, c => `-${c.toLowerCase()}`)}` });
    const tabs = new Set(buildCollectionsTabs(role).map(t => t.id));
    if (tabs.has("deposit")) add("deposit", collectionsHref("deposit"));
    if (tabs.has("overdue")) add("overdue", collectionsHref("overdue"));
    if (hasPermission(role, "canViewLeases")) {
        add("ending", "/dashboard/leases?view=expiring");
        add("drafts", "/dashboard/leases?view=draft");
    }
    if (hasPermission(role, "canResolveIssues")) add("tickets", "/dashboard/tickets");
    if (hasPermission(role, "canApprovePenalties") && tabs.has("penalties")) add("penalties", collectionsHref("penalties"));
    if (hasPermission(role, "canRunRecognition")) {
        add("recognition", "/dashboard/finance/recognition");
        add("recognitionFailed", "/dashboard/finance/recognition");
    }
    if (hasPermission(role, "canViewLeases")) add("followUps", null);
    return rows;
}

async function countOpenTickets(): Promise<number> {
    const totals = await Promise.all(OPEN_TICKET_STATUSES.map(async status => {
        const res = await fetch(`/api/proxy/v1/tickets/paged?status=${status}&page=0&size=1`);
        if (!res.ok) throw new Error(String(res.status));
        return ((await res.json()) as { totalElements?: number }).totalElements ?? 0;
    }));
    return totals.reduce((a, b) => a + b, 0);
}

export default function TodayList({ role, pipeline }: { role: UserRole | undefined; pipeline: PipelineStage[] | null }) {
    const t = useTranslations("Today");
    const locale = useLocale();
    const [counts, setCounts] = useState<Partial<Record<TodayRowId, number>>>({});
    // The top five rows the old Home widgets listed (R1 P3-6), and the recognition detail.
    const [previews, setPreviews] = useState<{ deposit?: Cheque[]; overdue?: Cheque[] }>({});
    const [recognition, setRecognition] = useState<RecognitionStatusSummary | null>(null);
    const pills = usePillCounts(role, "");
    const defs = todayRowDefs(role);

    useEffect(() => {
        let alive = true;
        const set = (id: TodayRowId, n: number) => { if (alive) setCounts(c => ({ ...c, [id]: n })); };
        const ids = new Set(todayRowDefs(role).map(d => d.id));
        // Counts only (size=1 or a status summary): nothing here grows with the portfolio.
        if (ids.has("tickets")) countOpenTickets().then(n => set("tickets", n)).catch(() => {});
        if (ids.has("recognition")) {
            recognitionApi.status().then(s => {
                if (alive) setRecognition(s);
                set("recognition", s.behind ?? 0);
                set("recognitionFailed", s.lastRunFailed ?? 0);
            }).catch(() => {});
        }
        if (ids.has("deposit")) {
            chequeApi.toDeposit({ page: 0, size: 5 }).then(p => { if (alive) setPreviews(x => ({ ...x, deposit: p.content ?? [] })); }).catch(() => {});
        }
        if (ids.has("overdue")) {
            // GET /cheques/due has no overdue-only filter: a bounded page, nearest maturity first, as the old widget read it.
            chequeApi.due({ page: 0, size: 50 }).then(p => {
                if (alive) setPreviews(x => ({ ...x, overdue: (p.content ?? []).filter(c => c.overdue).slice(0, 5) }));
            }).catch(() => {});
        }
        if (ids.has("followUps")) {
            fetch("/api/proxy/v1/renewals/follow-ups")
                .then(r => (r.ok ? r.json() : []))
                .then((l: unknown) => set("followUps", Array.isArray(l) ? l.length : 0))
                .catch(() => {});
        }
        return () => { alive = false; };
    }, [role]);

    if (defs.length === 0) return null;

    const stage = (id: string) => pipeline?.find(s => s.id === id);
    const value = (id: TodayRowId): { n: number | undefined; capped?: boolean } => {
        switch (id) {
            case "ending": return { n: stage("expiring")?.count, capped: stage("expiring")?.capped };
            case "drafts": return { n: stage("draft")?.count };
            case "deposit": return { n: pills.deposit };
            case "overdue": return { n: pills.overdue };
            case "penalties": return { n: pills.penalties };
            default: return { n: counts[id] };
        }
    };
    const shown = defs.filter(d => (value(d.id).n ?? 0) > 0);
    const show = (id: TodayRowId) => {
        const v = value(id);
        return v.capped ? t("capped", { count: v.n ?? 0 }) : String(v.n ?? 0);
    };

    return (
        <section aria-labelledby="today-title" data-testid="today-list" className="bg-surface border border-border rounded-[var(--radius-lg)] p-5">
            <h2 id="today-title" className="font-serif text-[20px] font-semibold text-foreground mb-2">{t("title")}</h2>
            {shown.length === 0 ? (
                <p className="text-[13px] text-[var(--ink-500)]" data-testid="today-all-clear">{t("allClear")}</p>
            ) : (
                <ul className="divide-y divide-border">
                    {shown.map(d => d.href ? (
                        <li key={d.id}>
                            <Link href={d.href} data-testid={d.testId} className="flex items-center gap-3 py-2.5 text-[13.5px] hover:text-primary">
                                <span className="flex-1 min-w-0">{t(d.id)}</span>
                                <span className="font-semibold tabular-nums">{show(d.id)}</span>
                                <ChevronRight size={14} className="text-muted rtl:rotate-180 shrink-0" aria-hidden />
                            </Link>
                            {(d.id === "deposit" || d.id === "overdue") && (previews[d.id]?.length ?? 0) > 0 && (
                                <ul className="pb-2.5 ps-3 space-y-1" data-testid={`${d.testId}-rows`}>
                                    {previews[d.id]!.map(c => (
                                        <li key={c.id} className="text-xs">
                                            <Link href={`/dashboard/leases/${c.leaseId}`} className="flex items-center justify-between gap-2 hover:underline">
                                                <span className="truncate text-[var(--ink-600)]">
                                                    {c.unitIdentifier ?? "—"} · {c.renterName ?? "—"}{c.chequeNumber ? ` · #${c.chequeNumber}` : ""}
                                                </span>
                                                <bdi dir="ltr" className={`font-semibold tabular-nums whitespace-nowrap ${d.id === "overdue" ? "text-error" : ""}`}>
                                                    {formatCurrencyCompact(c.amount)}
                                                </bdi>
                                            </Link>
                                        </li>
                                    ))}
                                </ul>
                            )}
                            {d.id === "recognition" && recognition && recognition.behind > 0 && (
                                <p className="pb-2.5 ps-3 text-xs text-[var(--ink-600)]" data-testid="today-recognition-detail">
                                    {t("recognitionBehindDetail", {
                                        amount: fmtAmount(recognition.behindAmount),
                                        date: recognition.oldestPeriodEnd ? fmtIsoDate(recognition.oldestPeriodEnd, locale) : "—",
                                    })}
                                </p>
                            )}
                        </li>
                    ) : (
                        <li key={d.id}>
                            <details data-testid={d.testId} className="group">
                                <summary className="flex items-center gap-3 py-2.5 text-[13.5px] cursor-pointer list-none [&::-webkit-details-marker]:hidden">
                                    <span className="flex-1 min-w-0">{t(d.id)}</span>
                                    <span className="font-semibold tabular-nums">{show(d.id)}</span>
                                    <ChevronDown size={14} className="text-muted group-open:rotate-180 transition-transform shrink-0" aria-hidden />
                                </summary>
                                <div className="pb-3"><FollowUpsWidget /></div>
                            </details>
                        </li>
                    ))}
                </ul>
            )}
        </section>
    );
}
