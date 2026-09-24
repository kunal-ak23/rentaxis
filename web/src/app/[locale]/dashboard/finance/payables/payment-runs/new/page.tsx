"use client";

import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { Send, ShieldCheck } from "lucide-react";
import { PaymentRunWizard } from "@/components/finance/PaymentRunWizard";
import { hasPermission, type UserRole } from "@/lib/rbac";

/** Finance → Payables → Payment runs → New run: the wizard (select, preview, post). */
export default function NewPaymentRunPage() {
    const t = useTranslations("PaymentRuns");
    const { data: session } = useSession();
    const userRole = session?.user?.role as UserRole | undefined;
    if (userRole && !hasPermission(userRole, "canManagePayables")) {
        return (
            <div className="bg-surface rounded-xl p-12 shadow-sm border border-border text-center max-w-4xl">
                <ShieldCheck size={48} className="mx-auto text-muted mb-4" />
                <p className="text-sm text-muted">{t("accessDenied")}</p>
            </div>
        );
    }
    return (
        <div>
            <h1 className="text-xl font-bold text-foreground tracking-tight mb-1 flex items-center gap-2">
                <Send size={20} className="text-primary rtl:-scale-x-100" />{t("newRun")}
            </h1>
            <p className="text-xs text-muted font-medium mb-6">{t("newRunDesc")}</p>
            <PaymentRunWizard />
        </div>
    );
}
