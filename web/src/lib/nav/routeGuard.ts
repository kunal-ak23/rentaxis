// src/lib/nav/routeGuard.ts
import type { UserRole } from "../rbac";
import { DASHBOARD_ROUTES, routeAllows, type RouteEntry } from "./routeRegistry";

/**
 * The layout-level page guard (break round 1, F7 and F8), driven by the route
 * registry so every dashboard page is covered, not just those that remembered
 * to check the role themselves. Pages that did not rendered their admin shell
 * — create buttons and a false "no users / no accounts" state — to a staff
 * user whose every call the backend was 403ing.
 */

const stripLocale = (pathname: string) => pathname.replace(/^\/(en|ar)(?=\/|$)/, "") || "/";

/** The registry entry for a pathname; a static segment beats a [param]. Null when unknown. */
export function findRoute(pathname: string): RouteEntry | null {
    const segs = stripLocale(pathname).replace(/\/+$/, "").split("/");
    let best: { entry: RouteEntry; dynamic: number } | null = null;
    for (const entry of DASHBOARD_ROUTES) {
        const pat = entry.path.split("/");
        if (pat.length !== segs.length) continue;
        let dynamic = 0;
        let ok = true;
        for (let i = 0; i < pat.length; i++) {
            if (pat[i].startsWith("[")) {
                if (!segs[i]) { ok = false; break; }
                dynamic++;
            } else if (pat[i] !== segs[i]) {
                ok = false;
                break;
            }
        }
        if (ok && (!best || dynamic < best.dynamic)) best = { entry, dynamic };
    }
    return best?.entry ?? null;
}

/** Pages that are not one organisation's data: usable by a super admin in Global View. */
const ORG_FREE = [/^\/superadmin(\/|$)/, /^\/dashboard\/(help|profile|notifications)(\/|$)/];

export type RouteDecision = "allow" | "denied" | "selectOrg";

/**
 * - "denied": the registry's role rule refuses this role;
 * - "selectOrg": a SUPER_ADMIN with no organisation selected (Global View) on
 *   a page that shows one organisation's data — calling its endpoints without
 *   an org either fails (fiscal-settings 500'd) or aggregates every org;
 * - "allow": otherwise, including paths the registry does not know.
 */
export function routeDecision(pathname: string, role: UserRole, hasActiveOrg: boolean): RouteDecision {
    const path = stripLocale(pathname);
    const entry = findRoute(path);
    if (entry && !routeAllows(entry, role)) return "denied";
    if (role === "SUPER_ADMIN" && !hasActiveOrg && path.startsWith("/dashboard") && !ORG_FREE.some(re => re.test(path))) {
        return "selectOrg";
    }
    return "allow";
}

/** The dashboard home, with or without its locale prefix. */
export function isDashboardHome(pathname: string | null): boolean {
    return /^(?:\/(?:en|ar))?\/dashboard\/?$/.test(pathname ?? "");
}
