"use client";

import MvpSidebar from "@/components/ui/MvpSidebar";
import { usePathname } from "next/navigation";
import { useSession } from "next-auth/react";
import { useRouter } from "@/i18n/routing";
import { useEffect } from "react";
import { Loader2 } from "lucide-react";
import { TopHeader } from "@/components/ui/TopHeader";
import TourProvider from "@/components/tour/TourProvider";
import HelpFAB from "@/components/help/HelpFAB";
import { NavShellProvider } from "@/components/nav/NavShellContext";
import type { UserRole } from "@/lib/rbac";
import { SessionGuards } from "@/components/layout/SessionGuards";
import { AccessDeniedState, SelectOrgState } from "@/components/ui/PageStates";
import { isDashboardHome, routeDecision } from "@/lib/nav/routeGuard";
import { readActiveOrgCookie } from "@/lib/session/orgSync";

export default function AuthenticatedLayout({
    children,
}: {
    children: React.ReactNode;
}) {
    const pathname = usePathname();
    const { data: session, status } = useSession();
    const router = useRouter();

    useEffect(() => {
        if (status === "unauthenticated") {
            router.push("/auth/login");
        }
    }, [status, router]);

    // Review fix 5: a super admin with no organisation selected who opens the
    // dashboard home (e.g. straight after sign-in) lands on the organisation
    // list rather than a bare "Select an organisation" card.
    const role = session?.user?.role as UserRole | undefined;
    const decision = session && role
        ? routeDecision(pathname, role, readActiveOrgCookie() !== "")
        : "allow";
    const landOnOrgList = decision === "selectOrg" && isDashboardHome(pathname);
    useEffect(() => {
        if (landOnOrgList) router.replace("/superadmin/tenants");
    }, [landOnOrgList, router]);

    if (status === "loading") {
        return (
            <div className="min-h-screen bg-background flex items-center justify-center">
                <div className="flex flex-col items-center gap-3">
                    <Loader2 className="w-8 h-8 animate-spin text-primary opacity-60" />
                    <span className="text-xs text-muted font-medium tracking-wide">Loading...</span>
                </div>
            </div>
        );
    }

    if (!session) return null;

    // Break round 1: the route registry's role rules for every page (F8), and
    // a super admin in Global View is asked to pick an organisation rather
    // than mounting a page whose org-scoped calls fail or aggregate every
    // organisation (F7). The shell stays so the user can navigate or switch.
    const content = decision === "denied" ? <AccessDeniedState />
        : landOnOrgList ? null
        : decision === "selectOrg" ? <SelectOrgState />
        : children;

    return (
        <SessionGuards role={session.user?.role} homeTenantId={session.user?.tenantId}>
        <TourProvider role={role} autoStart={decision === "allow"}>
            <NavShellProvider>
            <div className="flex h-screen overflow-hidden bg-background">
                <MvpSidebar />
                <div className="flex flex-col flex-1 min-w-0">
                    <TopHeader />
                    {/*
                      * Extra bottom padding, distinct from the top's, so the
                      * last row of page content (e.g. a Post button pinned at
                      * the bottom-right of a form) can always scroll clear of
                      * HelpFAB (#44) — a fixed circular button (h-12 = 48px)
                      * offset bottom-6 right-6 (24px) that otherwise sits on
                      * top of it. pb-24 (96px) covers the button's height
                      * plus its own margin plus a small gap. Padding-bottom
                      * is direction-independent, so this needs no RTL
                      * variant even though the FAB itself is pinned to a
                      * physical corner.
                      */}
                    <main className="flex-1 overflow-y-auto thinscroll bg-background px-6 pt-6 pb-24 lg:px-8 lg:pt-7 lg:pb-28">
                        {content}
                    </main>
                </div>
            </div>
            </NavShellProvider>
            <HelpFAB />
        </TourProvider>
        </SessionGuards>
    );
}
