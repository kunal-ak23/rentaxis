"use client";

import type { ReactNode } from "react";
import { useLocale, useTranslations } from "next-intl";
import NextLink from "next/link";
import { AlertCircle, Building2, RefreshCw, SearchX, ShieldCheck } from "lucide-react";

/**
 * A locale-prefixed link without @/i18n/routing, so every page (and its unit
 * tests) can render these states without the navigation runtime.
 */
function Link({ href, className, children }: { href: string; className?: string; children: ReactNode }) {
    const locale = useLocale();
    return <NextLink href={`/${locale}${href}`} className={className}>{children}</NextLink>;
}

/**
 * Whole-page states shared by the dashboard: access denied, not found, load
 * failed and "select an organisation". One shape so a page never falls back to
 * a skeleton that never resolves, or to a false "no data" empty state, when
 * the real answer is "you may not see this" or "this does not exist".
 */
function StateCard({
    icon,
    title,
    body,
    testId,
    children,
}: {
    icon: ReactNode;
    title: string;
    body?: string;
    testId: string;
    children?: ReactNode;
}) {
    return (
        <div className="max-w-4xl mx-auto py-16" data-testid={testId}>
            <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                <div className="mx-auto text-muted mb-4 flex justify-center">{icon}</div>
                <h2 className="text-lg font-bold text-foreground mb-2">{title}</h2>
                {body && <p className="text-sm text-muted max-w-md mx-auto">{body}</p>}
                {children && <div className="mt-5 flex flex-wrap items-center justify-center gap-3">{children}</div>}
            </div>
        </div>
    );
}

export function AccessDeniedState({ message }: { message?: string }) {
    const t = useTranslations("PageState");
    return (
        <StateCard
            testId="page-access-denied"
            icon={<ShieldCheck size={48} />}
            title={t("accessDeniedTitle")}
            body={message ?? t("accessDeniedBody")}
        >
            <Link href="/dashboard" className="text-xs text-primary font-semibold">{t("backToDashboard")}</Link>
        </StateCard>
    );
}

export function NotFoundState({ message, backHref, backLabel }: { message: string; backHref: string; backLabel: string }) {
    const t = useTranslations("PageState");
    return (
        <StateCard testId="page-not-found" icon={<SearchX size={48} />} title={t("notFoundTitle")} body={message}>
            <Link href={backHref} className="text-xs text-primary font-semibold">{backLabel}</Link>
        </StateCard>
    );
}

export function LoadFailedState({ onRetry, message }: { onRetry: () => void; message?: string }) {
    const t = useTranslations("PageState");
    return (
        <StateCard
            testId="page-load-failed"
            icon={<AlertCircle size={48} />}
            title={t("loadFailedTitle")}
            body={message ?? t("loadFailedBody")}
        >
            <button
                type="button"
                onClick={onRetry}
                className="cursor-pointer inline-flex items-center gap-1.5 text-xs font-semibold bg-primary/10 hover:bg-primary/20 text-primary px-3 py-1.5 rounded-lg transition-colors"
            >
                <RefreshCw size={12} />
                {t("retry")}
            </button>
        </StateCard>
    );
}

export function SelectOrgState() {
    const t = useTranslations("PageState");
    return (
        <StateCard
            testId="page-select-org"
            icon={<Building2 size={48} />}
            title={t("selectOrgTitle")}
            body={t("selectOrgBody")}
        >
            <Link href="/superadmin/tenants" className="text-xs text-primary font-semibold">{t("goToOrganisations")}</Link>
        </StateCard>
    );
}
