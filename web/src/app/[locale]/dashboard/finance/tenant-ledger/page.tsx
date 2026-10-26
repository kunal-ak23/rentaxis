"use client";

import { useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useTranslations } from "next-intl";
import AllAccountsView from "./_components/AllAccountsView";
import ReceivableView from "./_components/ReceivableView";
import { viewFrom, type TenantLedgerView } from "./_components/query";

const VIEWS: { view: TenantLedgerView; key: "tlViewReceivable" | "tlViewAll" }[] = [
    { view: "receivable", key: "tlViewReceivable" },
    { view: "all", key: "tlViewAll" },
];

/**
 * Sets `view` in the address bar, leaving every other key (from, to, renterId, leaseId, …) as it is.
 *
 * The state is `null`, never `history.state`: the App Router's own entries carry its `__NA` marker,
 * and a replaceState that passes it back is taken for one of the router's calls, so `useSearchParams`
 * stays on the URL of arrival and the view mounted by the next switch reads stale filters. With
 * `null` Next copies its marker on and moves `useSearchParams` to the new URL.
 */
function writeView(view: TenantLedgerView) {
    if (typeof window === "undefined") return;
    const url = new URL(window.location.href);
    url.searchParams.set("view", view);
    window.history.replaceState(null, "", url.pathname + url.search + url.hash);
}

/**
 * Accounting › Registers › Tenant Ledger (PACT report spec §2, "Screen").
 *
 * Opens on PACT's receivable view; "All accounts" keeps the one-tenant
 * statement across every account, which the lease "Ledger" link
 * (`?renterId=&leaseId=`) still lands on. The view is resolved once, on
 * arrival, and pinned in the URL straight away, so an "All accounts" apply
 * that drops the contract cannot flip the page to the other view. Switching
 * keeps the period, tenant and contract in the URL for the other view to read.
 */
export default function TenantLedgerPage() {
    const t = useTranslations("Reports");
    const tLedger = useTranslations("Ledger");
    const searchParams = useSearchParams();
    const [view, setView] = useState<TenantLedgerView>(() => viewFrom(new URLSearchParams(searchParams?.toString() ?? "")));

    useEffect(() => {
        writeView(view);
    }, [view]);

    const viewSwitch = (
        <div role="group" aria-label={tLedger("tenantLedger")} className="inline-flex max-w-full rounded-lg border border-border bg-surface p-0.5 mb-4" data-testid="tl-view-switch">
            {VIEWS.map(v => (
                <button
                    key={v.view}
                    type="button"
                    aria-pressed={view === v.view}
                    onClick={() => setView(v.view)}
                    className={`px-3 py-1.5 rounded-md text-xs font-bold cursor-pointer transition-colors focus:ring-2 focus:ring-primary/20 focus:outline-none ${
                        view === v.view ? "bg-primary text-primary-foreground" : "text-muted hover:text-foreground hover:bg-input"
                    }`}
                >
                    {t(v.key)}
                </button>
            ))}
        </div>
    );

    return view === "all" ? <AllAccountsView viewSwitch={viewSwitch} /> : <ReceivableView viewSwitch={viewSwitch} />;
}
