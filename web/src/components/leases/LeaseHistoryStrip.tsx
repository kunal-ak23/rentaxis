"use client";

import { Fragment, useEffect, useState } from "react";
import { useLocale, useTranslations } from "next-intl";
import { ArrowRight } from "lucide-react";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { leaseApi, type LeaseChainLink } from "@/lib/api/leasing";
import { fmtIsoDate } from "@/components/leases/leaseMath";

/**
 * #100-e: the lease's whole history — every renewal and unit transfer, oldest
 * first (spec §2, "One audit trail"). The server walks the chain and leaves out
 * contracts the caller may not read, so this only lays it out.
 *
 * Shown only when there is a history (two or more contracts). It scrolls sideways
 * rather than wrapping, so a long chain stays one line at 390 px, and it reads
 * right to left in Arabic: logical spacing, and the arrow flips with the direction.
 * A failed read hides the strip — the one-hop links above still work.
 */
export default function LeaseHistoryStrip({ leaseId }: { leaseId: string }) {
    const t = useTranslations("Leasing");
    const locale = useLocale();
    const [chain, setChain] = useState<LeaseChainLink[]>([]);

    useEffect(() => {
        let cancelled = false;
        Promise.resolve()
            .then(() => leaseApi.chain(leaseId))
            .then(rows => {
                if (!cancelled) setChain(Array.isArray(rows) ? rows : []);
            })
            .catch(() => {
                if (!cancelled) setChain([]);
            });
        return () => {
            cancelled = true;
        };
    }, [leaseId]);

    if (chain.length < 2) return null;

    return (
        <nav aria-label={t("history.title")} data-testid="lease-history" className="mt-2 max-w-full">
            <p className="text-[10px] font-semibold text-muted uppercase tracking-wider mb-1">{t("history.title")}</p>
            <ol className="flex items-stretch gap-1.5 overflow-x-auto pb-1 [scrollbar-width:thin]" data-testid="lease-history-list">
                {chain.map(link => {
                    const current = link.id === leaseId;
                    const body = (
                        <>
                            <span className="block text-[11px] font-semibold text-foreground truncate">
                                {link.unitIdentifier ? t("history.unit", { unit: link.unitIdentifier }) : "—"}
                            </span>
                            {link.displayContractNumber && (
                                <span className="block text-[10px] text-muted truncate">
                                    <bdi dir="ltr">{link.displayContractNumber}</bdi>
                                </span>
                            )}
                            <span className="block text-[10px] text-muted whitespace-nowrap">
                                <bdi dir="ltr">{fmtIsoDate(link.startDate, locale)} – {fmtIsoDate(link.endDate, locale)}</bdi>
                            </span>
                            <span className="block text-[10px] text-muted">
                                {current ? t("history.current") : t(`leaseStatus.${link.status}`)}
                            </span>
                        </>
                    );
                    return (
                        <Fragment key={link.id}>
                            {link.link && (
                                <li aria-hidden="true" className="flex shrink-0 flex-col items-center justify-center px-0.5 text-[9px] font-semibold uppercase text-info">
                                    <span>{t(`history.${link.link}`)}</span>
                                    <ArrowRight size={12} className="rtl:rotate-180" />
                                </li>
                            )}
                            <li
                                className={cn(
                                    "shrink-0 min-w-[132px] max-w-[180px] rounded-lg border px-2.5 py-1.5",
                                    current ? "border-primary/40 bg-primary/5" : "border-border bg-surface",
                                )}
                                data-testid={`lease-history-${link.id}`}
                                data-link={link.link ?? "FIRST"}
                                aria-current={current ? "page" : undefined}
                            >
                                <span className="sr-only">{link.link ? t(`history.${link.link}`) : t("history.first")}</span>
                                {current ? (
                                    body
                                ) : (
                                    <Link href={`/dashboard/leases/${link.id}`} className="block hover:underline">
                                        {body}
                                    </Link>
                                )}
                            </li>
                        </Fragment>
                    );
                })}
            </ol>
        </nav>
    );
}
