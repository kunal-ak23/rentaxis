// src/lib/nav/settingsModel.ts
import { canConfigureFines, canConfigureGateway, canConfigureRentSettings, hasPermission, type UserRole } from "../rbac";
import type { Label } from "./types";

export type SettingsSectionId = "organisation" | "users" | "rent" | "payments";
export interface SettingsSection { id: SettingsSectionId; href: string; label: Label; testId: string }

/**
 * One Settings page, one section per former page group. Each section's gate
 * is the union of the gates of the pages it hosts, so nobody gains a screen:
 * Users & staff = canManageUsers (users list) ∪ canAccessFinanceOps (Staff);
 * Rent & fines = canConfigureFines ∪ canConfigureRentSettings; Payments =
 * canConfigureGateway. Organisation shows only the org's own name/slug and
 * rides on canConfigureGateway (the SA/TA set every other section needs).
 */
const SECTIONS: { id: SettingsSectionId; key: string; allow: (r: UserRole) => boolean }[] = [
    { id: "organisation", key: "sectionOrganisation", allow: canConfigureGateway },
    { id: "users", key: "sectionUsers", allow: r => hasPermission(r, "canManageUsers") || hasPermission(r, "canAccessFinanceOps") },
    { id: "rent", key: "sectionRent", allow: r => canConfigureFines(r) || canConfigureRentSettings(r) },
    { id: "payments", key: "sectionPayments", allow: canConfigureGateway },
];

export function buildSettingsSections(role: UserRole | undefined): SettingsSection[] {
    if (!role) return [];
    return SECTIONS.filter(s => s.allow(role)).map(s => ({
        id: s.id,
        href: `/dashboard/settings?section=${s.id}`,
        label: { ns: "SettingsPage", key: s.key },
        testId: `settings-nav-${s.id}`,
    }));
}
