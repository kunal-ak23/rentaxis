"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { ArrowLeft, Loader2, ShieldCheck } from "lucide-react";
import { Link, useRouter } from "@/i18n/routing";
import VoucherForm from "@/components/finance/VoucherForm";
import { hasPermission, type UserRole } from "@/lib/rbac";
import type { EditableVoucherType } from "@/lib/api/vouchers";

/**
 * The page shell both voucher documents wear: a back link, a title, the role
 * gate and the form. One component rather than two 60-line pages that differ in
 * a string, so the session handling below has exactly one implementation.
 *
 * **Why it waits for the session.** `useSession` answers `null` before NextAuth
 * has resolved, and `hasPermission(undefined, …)` is false — so rendering
 * eagerly would flash an access-denied panel at an accountant and then MOUNT THE
 * FORM A SECOND TIME, with fresh state, throwing away anything typed into the
 * first. That is the plan 3 walkthrough's 240-second timeout (commit a2bbd01f)
 * in a new place. Nothing renders until the role is known.
 *
 * `useSearchParams` needs a Suspense boundary for `next build` to prerender the
 * route, which is why the default export is a wrapper.
 */
export default function VoucherDocumentPage({
    type,
    titleKey,
    descKey,
}: {
    type: EditableVoucherType;
    titleKey: "purchaseInvoice" | "paymentVoucher";
    descKey: "purchaseInvoiceDesc" | "paymentVoucherDesc";
}) {
    return (
        <Suspense
            fallback={
                <div className="flex items-center justify-center h-64">
                    <Loader2 size={24} className="animate-spin text-muted" />
                </div>
            }
        >
            <VoucherDocument type={type} titleKey={titleKey} descKey={descKey} />
        </Suspense>
    );
}

function VoucherDocument({
    type,
    titleKey,
    descKey,
}: {
    type: EditableVoucherType;
    titleKey: "purchaseInvoice" | "paymentVoucher";
    descKey: "purchaseInvoiceDesc" | "paymentVoucherDesc";
}) {
    const t = useTranslations("Vouchers");
    const tLedger = useTranslations("Ledger");
    const router = useRouter();
    const params = useSearchParams();
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;

    if (!userRole) {
        return (
            <div className="flex items-center justify-center h-64" data-testid="voucher-page-loading">
                <Loader2 size={24} className="animate-spin text-muted" />
            </div>
        );
    }

    // VoucherController's class-level @PreAuthorize refuses PROPERTY_MANAGER,
    // TENANT_USER and RENTER outright, so they get this panel rather than a
    // screen whose first fetch 403s.
    if (!hasPermission(userRole, "canManageVouchers")) {
        return (
            <div className="max-w-4xl" data-testid="voucher-access-denied">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{tLedger("accessDeniedTitle")}</h2>
                    <p className="text-sm text-muted">{t("notAllowed")}</p>
                </div>
            </div>
        );
    }

    return (
        <div className="max-w-6xl">
            <Link
                href="/dashboard/finance/vouchers"
                className="inline-flex items-center gap-1.5 text-xs font-semibold text-muted hover:text-foreground mb-4 cursor-pointer"
            >
                <ArrowLeft size={13} className="rtl:rotate-180" />
                {t("vouchers")}
            </Link>

            <div className="mb-8">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1">{t(titleKey)}</h1>
                <p className="text-sm text-muted">{t(descKey)}</p>
            </div>

            <VoucherForm
                type={type}
                voucherId={params.get("id") ?? undefined}
                onPosted={() => router.push("/dashboard/finance/vouchers")}
            />
        </div>
    );
}
