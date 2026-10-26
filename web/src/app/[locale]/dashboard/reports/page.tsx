"use client";

import { useState } from "react";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { ArrowRight, FileSpreadsheet, Search, SearchX } from "lucide-react";
import { Link } from "@/i18n/routing";
import { AccessDeniedState } from "@/components/ui/PageStates";
import { useLabel } from "@/lib/nav/useLabel";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { visibleReports, type ReportFileFormat, type ReportGroup } from "@/lib/reports/catalog";

const FORMAT_KEY: Record<ReportFileFormat, "formatXlsx" | "formatPdf" | "formatCsv"> = {
    xlsx: "formatXlsx", pdf: "formatPdf", csv: "formatCsv",
};
const SECTIONS: { group: ReportGroup; key: "groupRegisters" | "groupFinal" }[] = [
    { group: "registers", key: "groupRegisters" },
    { group: "final", key: "groupFinal" },
];

/**
 * Accounting › Final Reports › All reports: every report the signed-in role can
 * open (lib/reports/catalog.ts), searchable by its title and description, each
 * card naming its export formats and linking to the report's own page.
 */
export default function ReportsHubPage() {
    const t = useTranslations("Reports");
    const label = useLabel();
    const { data: session, status } = useSession();
    const role = session?.user?.role as UserRole | undefined;
    const [search, setSearch] = useState("");

    if (status === "loading") return null;
    if (!hasPermission(role, "canViewReportsHub")) return <AccessDeniedState />;

    // Searched in the reader's language: the title and description as they read them.
    const q = search.trim().toLocaleLowerCase();
    const shown = visibleReports(role)
        .map(e => ({ ...e, titleText: label(e.title), descText: t(e.descKey) }))
        .filter(r => !q || r.titleText.toLocaleLowerCase().includes(q) || r.descText.toLocaleLowerCase().includes(q));

    const scoped = role === "PROPERTY_MANAGER";

    return (
        <div className="min-w-0">
            <div className="flex flex-wrap items-end justify-between gap-3 mb-6">
                <div className="min-w-0">
                    <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                        <FileSpreadsheet size={20} className="text-primary shrink-0" aria-hidden />
                        {t("hubTitle")}
                    </h1>
                    <p className="text-xs text-muted font-medium">{t("hubDesc")}</p>
                </div>
                <div className="relative w-full sm:w-72">
                    <Search size={14} className="absolute start-3 top-1/2 -translate-y-1/2 text-muted pointer-events-none" aria-hidden />
                    <input
                        type="search"
                        value={search}
                        onChange={e => setSearch(e.target.value)}
                        placeholder={t("hubSearch")}
                        aria-label={t("hubSearch")}
                        className="w-full ps-9 pe-3 py-2 rounded-lg border border-border bg-background text-xs text-foreground focus:ring-2 focus:ring-primary/20 focus:outline-none"
                    />
                </div>
            </div>

            {shown.length === 0 && (
                <div role="status" className="text-center py-20 bg-background border border-dashed border-border rounded-xl flex flex-col items-center gap-3 px-4">
                    <SearchX size={28} className="text-muted" aria-hidden />
                    <p className="text-sm font-bold text-foreground break-words max-w-md">{t("hubNoMatches", { q: search.trim() })}</p>
                </div>
            )}

            {SECTIONS.map(({ group, key }) => {
                const items = shown.filter(r => r.group === group);
                if (items.length === 0) return null;
                return (
                    <section key={group} aria-labelledby={`reports-${group}`} className="mb-8">
                        <h2 id={`reports-${group}`} className="text-xs font-bold uppercase tracking-wide text-muted mb-3">{t(key)}</h2>
                        <ul className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
                            {items.map(r => (
                                <li key={r.key} data-testid={`report-card-${r.key}`}
                                    className="min-w-0 flex flex-col gap-3 bg-surface border border-border rounded-xl p-4 shadow-sm">
                                    <div className="min-w-0 flex-1">
                                        <h3 className="text-sm font-bold text-foreground break-words">{r.titleText}</h3>
                                        <p className="text-xs text-muted mt-1 break-words">{r.descText}</p>
                                        {scoped && r.pmScoped && (
                                            <p className="text-xs text-info font-medium mt-2">{t("hubPmScoped")}</p>
                                        )}
                                    </div>
                                    <div className="flex flex-wrap items-center justify-between gap-2">
                                        <ul className="flex flex-wrap gap-1.5">
                                            {r.formats.map(f => (
                                                <li key={f} className="px-2 py-0.5 rounded-md bg-input text-[11px] font-semibold text-muted">
                                                    {t(FORMAT_KEY[f])}
                                                </li>
                                            ))}
                                        </ul>
                                        <Link href={r.href}
                                            className="inline-flex items-center gap-1 text-xs font-bold text-primary hover:underline focus:outline-none focus:ring-2 focus:ring-primary/20 rounded">
                                            {t("hubOpen")}
                                            <span className="sr-only"> {r.titleText}</span>
                                            <ArrowRight size={12} className="rtl:rotate-180" aria-hidden />
                                        </Link>
                                    </div>
                                </li>
                            ))}
                        </ul>
                    </section>
                );
            })}
        </div>
    );
}
