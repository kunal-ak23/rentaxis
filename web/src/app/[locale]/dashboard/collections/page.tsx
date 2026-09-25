"use client";

import { Suspense, type FormEvent } from "react";
import { useSearchParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useLocale, useTranslations } from "next-intl";
import { Loader2, Search } from "lucide-react";
import { useRouter } from "@/i18n/routing";
import { type UserRole } from "@/lib/rbac";
import { buildCollectionsTabs, type CollectionsTabId } from "@/lib/nav/collectionsModel";
import { useNameLookup } from "@/components/finance/useNameLookup";
import CollectionPills from "@/components/collections/CollectionPills";
import { usePillCounts } from "@/components/collections/usePillCounts";
import ToDepositPanel from "@/components/collections/ToDepositPanel";
import DueChequesPanel from "@/components/collections/DueChequesPanel";
import ReturnReplacePanel from "@/components/collections/ReturnReplacePanel";
import PostDatedPanel from "@/components/collections/PostDatedPanel";
import PenaltiesPanel from "@/components/collections/PenaltiesPanel";
import ChequeRegisterPanel from "@/components/collections/ChequeRegisterPanel";

const field = "bg-surface border border-border rounded-lg px-3 py-2 text-xs focus:ring-2 focus:ring-primary/20 focus:border-primary focus:outline-none";

/**
 * `useSearchParams` opts the tree into client rendering, which `next build`
 * rejects unless it sits under a Suspense boundary.
 */
export default function CollectionsPage() {
    return (
        <Suspense fallback={<div className="flex items-center justify-center h-64"><Loader2 size={24} className="animate-spin text-muted" /></div>}>
            <CollectionsHub />
        </Suspense>
    );
}

/**
 * Cheque / Cash Collection (spec §2): status pills with counts, a property
 * filter and a search box over the existing cheque and penalty screens, each
 * rendered unchanged as a panel. The old cheque/penalty URLs 308 here with
 * their query kept, so `?tab=all&status=BOUNCED&leaseId=…` still lands on the
 * filtered register.
 */
function CollectionsHub() {
    const t = useTranslations("Collections");
    const locale = useLocale();
    const router = useRouter();
    const { data: session } = useSession();
    const role = session?.user?.role as UserRole | undefined;
    const params = useSearchParams();
    const tabs = buildCollectionsTabs(role);
    const requested = params?.get("tab");
    const active: CollectionsTabId | undefined = tabs.find(x => x.id === requested)?.id ?? tabs[0]?.id;
    const propertyId = params?.get("propertyId") ?? "";
    const search = params?.get("search") ?? "";
    const counts = usePillCounts(role, propertyId);
    const properties = useNameLookup("properties", tabs.length > 0);

    /** The hub URL with `changes` applied on top of the current query (a register bookmark's filters survive). */
    const hubHref = (changes: Record<string, string>) => {
        const q = new URLSearchParams(params?.toString() ?? "");
        for (const [k, v] of Object.entries(changes)) {
            if (v) q.set(k, v);
            else q.delete(k);
        }
        return `/dashboard/collections?${q.toString()}`;
    };

    if (!session) return null;
    if (!active) {
        return (
            <div className="max-w-3xl bg-surface rounded-xl border border-border p-10 text-center" data-testid="collections-no-access">
                <p className="text-sm text-muted">{t("noAccess")}</p>
            </div>
        );
    }

    const onSearch = (ev: FormEvent<HTMLFormElement>) => {
        ev.preventDefault();
        const value = (new FormData(ev.currentTarget).get("search") ?? "").toString().trim();
        // Search is a cheque-register filter (registerFilters reads `search`).
        router.push(hubHref({ tab: "all", search: value }));
    };

    return (
        <div className="space-y-5">
            <h1 className="text-xl font-bold text-foreground tracking-tight">{t("title")}</h1>
            <CollectionPills tabs={tabs} active={active} counts={counts} propertyId={propertyId} label={t("tabsLabel")} />
            <form action={`/${locale}/dashboard/collections`} method="get" onSubmit={onSearch} role="search"
                className="flex flex-col sm:flex-row gap-2" data-testid="collections-filters">
                <input type="hidden" name="tab" value="all" />
                {propertyId && <input type="hidden" name="propertyId" value={propertyId} />}
                <label className="sr-only" htmlFor="collections-property">{t("propertyLabel")}</label>
                <select id="collections-property" data-testid="collections-property" value={propertyId}
                    onChange={ev => router.replace(hubHref({ tab: active, propertyId: ev.target.value }))}
                    className={`${field} sm:min-w-[14rem]`}>
                    <option value="">{t("allProperties")}</option>
                    {properties.options.map(p => <option key={p.id} value={p.id}>{p.label}</option>)}
                </select>
                <div className="relative flex-1">
                    <Search size={14} className="absolute start-3 top-1/2 -translate-y-1/2 text-muted pointer-events-none" />
                    <label className="sr-only" htmlFor="collections-search">{t("searchLabel")}</label>
                    <input id="collections-search" type="search" name="search" key={search} defaultValue={search}
                        placeholder={t("searchPlaceholder")} data-testid="collections-search"
                        className={`${field} w-full ps-8`} />
                </div>
            </form>
            <div key={`${active}-${propertyId}-${search}`}>
                {active === "deposit" && <ToDepositPanel embedded propertyId={propertyId} />}
                {active === "due" && <DueChequesPanel overdueOnly={false} propertyId={propertyId} />}
                {active === "overdue" && <DueChequesPanel overdueOnly propertyId={propertyId} />}
                {active === "returned" && <ReturnReplacePanel embedded propertyId={propertyId} />}
                {active === "post-dated" && <PostDatedPanel embedded propertyId={propertyId} />}
                {active === "penalties" && <PenaltiesPanel embedded propertyId={propertyId} />}
                {active === "all" && <ChequeRegisterPanel embedded />}
            </div>
        </div>
    );
}
