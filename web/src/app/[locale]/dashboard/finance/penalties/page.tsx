"use client";

import { useTranslations } from "next-intl";
import { useSession } from "next-auth/react";
import { AlertTriangle, ShieldCheck } from "lucide-react";
import { hasPermission, type UserRole } from "@/lib/rbac";
import PenaltyQueue from "@/components/penalties/PenaltyQueue";

/**
 * The finance-wide penalty worklist — every contract's proposals, not just
 * one lease's own tab. Gated on `canProposePenalties` because that mirrors
 * `PenaltyAssessmentController#list`'s own role set (SA/TA/ACCOUNTANT/PM):
 * a property manager may not decide a penalty, but may still see the queue
 * they help fill. Deciding stays narrower, inside `PenaltyQueue` itself.
 */
export default function PenaltiesQueuePage() {
    const t = useTranslations("Cheques");
    const tLedger = useTranslations("Ledger");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    const allowed = hasPermission(userRole, "canProposePenalties");

    if (userRole && !allowed) {
        return (
            <div className="max-w-4xl">
                <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center">
                    <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                    <h2 className="text-lg font-bold text-foreground mb-2">{tLedger("accessDeniedTitle")}</h2>
                    <p className="text-sm text-muted">{t("penaltiesAccessDenied")}</p>
                </div>
            </div>
        );
    }

    return (
        <div>
            <div className="mb-6">
                <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                    <AlertTriangle size={20} className="text-primary" />
                    {t("penalties")}
                </h1>
            </div>

            <PenaltyQueue userRole={userRole} />
        </div>
    );
}
