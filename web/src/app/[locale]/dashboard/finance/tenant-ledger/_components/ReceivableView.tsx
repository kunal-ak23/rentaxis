"use client";

import { useEffect, useState, type ReactNode } from "react";
import { useSearchParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { ExportMenu, type ExportOption } from "@/components/reports/ExportMenu";
import { GroupedReportTable } from "@/components/reports/GroupedReportTable";
import { ReportShell, type ReportState } from "@/components/reports/ReportShell";
import { useReportQuery } from "@/components/reports/useReportQuery";
import { ApiError } from "@/lib/api/facilities";
import { reportsApi, type ReportGrid, type TenantLedgerQuery } from "@/lib/api/reports";
import { hasPermission, type UserRole } from "@/lib/rbac";
import { fmtReportDate } from "@/lib/reports/format";
import TenantLedgerFilters from "./TenantLedgerFilters";
import { hasScope, readQuery, toUrl } from "./query";

/** Group numbers print with Latin digits in both languages, like the amounts. */
const COUNT = new Intl.NumberFormat("en-US");

/** The URL keys of one applied filter: the filter itself, the view, and the first page (page 0 stays out of the URL). */
const writeApplied = (q: TenantLedgerQuery): Record<string, string | undefined> => ({ ...toUrl(q), view: "receivable", page: undefined });

/** Writes the page number into the address bar, leaving every other key alone (`null` state: see `useReportQuery`). */
function writePage(page: number) {
    if (typeof window === "undefined") return;
    const url = new URL(window.location.href);
    if (page > 0) url.searchParams.set("page", String(page));
    else url.searchParams.delete("page");
    window.history.replaceState(null, "", url.pathname + url.search + url.hash);
}

/** A grid with no tenant band and no row: the filters matched nothing (the REPORT TOTAL alone is still there). */
const isEmpty = (grid: ReportGrid) => !grid.items.some(i => i.type === "row" || i.type === "band");

type Result = { key: string; grid: ReportGrid | null; error: string | null };

/**
 * The Tenant Ledger's "Receivable (PACT)" view (PACT report spec §2): every
 * receivable account by tenant, with PACT's cheque columns, as the shared
 * report grid. Paged by tenant groups on screen; Excel, PDF (English or
 * Arabic) and CSV carry the whole report for the applied filter.
 *
 * Nothing loads until a tenant, property, contract or account is chosen. Every
 * request has its own AbortController, so a superseded page or filter cannot
 * land on screen after the one the user asked for.
 */
export default function ReceivableView({ viewSwitch }: { viewSwitch?: ReactNode }) {
    const t = useTranslations("Reports");
    const tLedger = useTranslations("Ledger");
    const tCommon = useTranslations("Common");
    const locale = useLocale();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canAccessFinance");

    const { draft, setDraft, applied, apply } = useReportQuery(sp => readQuery(sp).q, writeApplied);
    const searchParams = useSearchParams();
    const [page, setPage] = useState(() => readQuery(new URLSearchParams(searchParams?.toString() ?? "")).page);
    // Bumped by Apply and Retry, so re-running the same filter fetches again.
    const [run, setRun] = useState(0);
    const [result, setResult] = useState<Result | null>(null);

    // One string per distinct request; null when nothing should load.
    const key = allowed && hasScope(applied) ? JSON.stringify([applied, page, locale, run]) : null;

    useEffect(() => {
        if (!key) return;
        const ctrl = new AbortController();
        reportsApi.tenantLedger.get(applied, page, locale, ctrl.signal).then(
            grid => { if (!ctrl.signal.aborted) setResult({ key, grid, error: null }); },
            (err: unknown) => {
                if (ctrl.signal.aborted) return;
                setResult({ key, grid: null, error: err instanceof ApiError ? err.message : tCommon("loadFailed") });
            },
        );
        return () => ctrl.abort();
        // `applied`, `page` and `locale` only ever change together with `key`, so this runs once per request.
    }, [key, applied, page, locale, tCommon]);

    // Only the answer to the current request counts; anything older is still loading.
    const current = key && result?.key === key ? result : null;
    const grid = current?.grid ?? null;
    const state: ReportState = !hasScope(applied)
        ? "idle"
        : !current
            ? "loading"
            : current.error !== null
                ? "error"
                : grid && isEmpty(grid)
                    ? "empty"
                    : "ready";

    const onApply = () => {
        apply();
        setPage(0);
        setRun(r => r + 1);
    };
    const onPage = (next: number) => {
        setPage(next);
        writePage(next);
    };

    const fileUrl = reportsApi.tenantLedger.fileUrl;
    const exportOptions: ExportOption[] = [
        { id: "xlsx", url: fileUrl("xlsx", applied, locale), fallbackName: "tenant-ledger.xlsx" },
        { id: "pdfEn", url: fileUrl("pdf", applied, "en"), fallbackName: "tenant-ledger.pdf" },
        { id: "pdfAr", url: fileUrl("pdf", applied, "ar"), fallbackName: "tenant-ledger.pdf" },
        { id: "csv", url: fileUrl("csv", applied, locale), fallbackName: "tenant-ledger.csv" },
    ];

    const groupsLabel = grid
        ? t("tlGroups", {
            from: COUNT.format(grid.page.firstGroup + 1),
            to: COUNT.format(grid.page.lastGroup + 1),
            total: COUNT.format(grid.page.totalGroups),
        })
        : undefined;

    return (
        <ReportShell
            title={tLedger("tenantLedger")}
            description={t("tlDesc")}
            period={hasScope(applied) ? tLedger("periodLabel", { from: fmtReportDate(applied.from), to: fmtReportDate(applied.to) }) : undefined}
            // While the session loads the bar shows; access is refused only once the role is known.
            allowed={!userRole || allowed}
            filterBar={
                <>
                    {viewSwitch}
                    <TenantLedgerFilters value={draft} onChange={setDraft} onApply={onApply} />
                </>
            }
            exportMenu={<ExportMenu options={exportOptions} disabled={!grid} />}
            state={state}
            idleMessage={t("tlPickScope")}
            error={current?.error}
            onRetry={() => setRun(r => r + 1)}
            truncated={grid?.page.truncated ? { shown: grid.page.pageRows, total: grid.page.totalRows } : null}
        >
            {grid && <GroupedReportTable grid={grid} onPage={onPage} groupsLabel={groupsLabel} />}
        </ReportShell>
    );
}
