// src/app/[locale]/dashboard/settings/page.tsx
"use client";
import { useSearchParams } from "next/navigation";
import { useSession } from "next-auth/react";
import { useTranslations } from "next-intl";
import { Link } from "@/i18n/routing";
import { cn } from "@/lib/utils";
import { canConfigureFines, canConfigureRentSettings, hasPermission, type UserRole } from "@/lib/rbac";
import { buildSettingsSections } from "@/lib/nav/settingsModel";
import { useLabel } from "@/lib/nav/useLabel";
import FinesSettings from "@/components/settings/FinesSettings";
import RentSettings from "@/components/settings/RentSettings";
import GatewaySettings from "@/components/settings/GatewaySettings";
import OnlinePaymentSwitch from "@/components/settings/OnlinePaymentSwitch";
import OrganisationSection from "@/components/settings/OrganisationSection";
import StaffManager from "@/components/staff/StaffManager";
import UsersManager from "@/components/users/UsersManager";

export default function SettingsPage() {
    const t = useTranslations("SettingsPage");
    const label = useLabel();
    const { data: session } = useSession();
    const role = session?.user?.role as UserRole | undefined;
    const sections = buildSettingsSections(role);
    const requested = useSearchParams()?.get("section");
    const active = sections.find(s => s.id === requested) ?? sections[0];

    if (!active) {
        return (
            <div className="max-w-3xl bg-surface rounded-xl border border-border p-10 text-center" data-testid="settings-no-access">
                <h1 className="text-lg font-bold mb-2">{t("noAccessTitle")}</h1>
                <p className="text-sm text-muted">{t("noAccessBody")}</p>
            </div>
        );
    }

    return (
        <div className="max-w-5xl">
            <h1 className="text-xl font-bold text-foreground tracking-tight mb-4">{t("title")}</h1>
            <nav aria-label={t("sectionsLabel")} data-testid="settings-sections" className="flex gap-1 border-b border-border overflow-x-auto mb-6">
                {sections.map(s => (
                    <Link key={s.id} href={s.href} data-testid={s.testId} aria-current={s.id === active.id ? "page" : undefined}
                        className={cn("px-3.5 py-2.5 text-[13.5px] -mb-px whitespace-nowrap",
                            s.id === active.id ? "font-semibold text-foreground border-b-2 border-[var(--gold-500)]" : "text-[var(--ink-500)] hover:text-foreground")}>
                        {label(s.label)}
                    </Link>
                ))}
            </nav>
            <section id={active.id} data-testid={`settings-section-${active.id}`} className="space-y-8">
                {active.id === "organisation" && <OrganisationSection />}
                {active.id === "users" && (
                    <>
                        {hasPermission(role, "canManageUsers") && <UsersManager embedded />}
                        {hasPermission(role, "canAccessFinanceOps") && <StaffManager embedded />}
                    </>
                )}
                {active.id === "rent" && role && (
                    <>
                        {canConfigureFines(role) && <FinesSettings embedded />}
                        {canConfigureRentSettings(role) && <RentSettings embedded hideOnlinePaymentToggle />}
                    </>
                )}
                {active.id === "payments" && (
                    <>
                        <GatewaySettings embedded />
                        <OnlinePaymentSwitch />
                    </>
                )}
            </section>
        </div>
    );
}
