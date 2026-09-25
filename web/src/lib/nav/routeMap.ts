// src/lib/nav/routeMap.ts
/**
 * Every dashboard URL that moved in the admin UI simplification (spec
 * 2026-09-25 "No-regression safeguards" §1), and where it went.
 *
 * The middleware (src/proxy.ts) answers each `from` with a 308 to `to`,
 * keeping the locale prefix and every incoming query parameter; `query` adds
 * the parameters the new home needs (the Collections tab, the Settings
 * section) and wins over an incoming key of the same name. Paths are
 * locale-less; `:name` matches exactly one segment.
 */
export interface RouteMove {
    from: string;
    to: string;
    query?: Record<string, string>;
}

export const ROUTE_MOVES: RouteMove[] = [
    // TENANT_USER's "My Unit" never had a page (it 404'd); the link is gone.
    { from: "/dashboard/my-unit", to: "/dashboard" },
    // Accounting setup lives in the Accounting area now.
    { from: "/dashboard/settings/account-template", to: "/dashboard/finance/account-template" },
    { from: "/dashboard/settings/fiscal", to: "/dashboard/finance/fiscal" },
    { from: "/dashboard/settings/charge-types", to: "/dashboard/finance/charge-types" },
    // One Settings page with sections.
    { from: "/dashboard/settings/fines", to: "/dashboard/settings", query: { section: "rent" } },
    { from: "/dashboard/settings/rent-settings", to: "/dashboard/settings", query: { section: "rent" } },
    { from: "/dashboard/settings/gateway", to: "/dashboard/settings", query: { section: "payments" } },
    // /dashboard/staff does NOT move: Operations › Staff keeps linking to it, and
    // Settings › Users & staff embeds the same StaffManager component.
];

const LOCALE_PREFIX = /^\/(en|ar)(?=\/|$)/;

export function matchRoute(pattern: string, path: string): Record<string, string> | null {
    const p = pattern.split("/");
    const s = path.split("/");
    if (p.length !== s.length) return null;
    const params: Record<string, string> = {};
    for (let i = 0; i < p.length; i++) {
        if (p[i].startsWith(":")) {
            if (!s[i]) return null;
            params[p[i].slice(1)] = s[i];
        } else if (p[i] !== s[i]) {
            return null;
        }
    }
    return params;
}

function fill(pattern: string, params: Record<string, string>): string {
    return pattern.replace(/:([A-Za-z]+)/g, (_, k: string) => params[k] ?? `:${k}`);
}

/** The moved-to URL for `url`, or null when `url` did not move. */
export function legacyRedirect(url: URL): URL | null {
    const prefix = url.pathname.match(LOCALE_PREFIX)?.[0] ?? "";
    const rest = url.pathname.slice(prefix.length);
    const path = rest.length > 1 ? rest.replace(/\/+$/, "") : rest || "/";
    for (const move of ROUTE_MOVES) {
        const params = matchRoute(move.from, path);
        if (!params) continue;
        const target = new URL(url.toString());
        target.pathname = prefix + fill(move.to, params);
        for (const [k, v] of Object.entries(move.query ?? {})) target.searchParams.set(k, v);
        return target;
    }
    return null;
}

/**
 * A locale-less path with a sorted query, after following any move — the form
 * the RBAC parity test compares destinations in.
 */
export function canonicalHref(href: string): string {
    const url = new URL(href, "http://rentaxis.local");
    const moved = legacyRedirect(url) ?? url;
    const prefix = moved.pathname.match(LOCALE_PREFIX)?.[0] ?? "";
    const sorted = [...moved.searchParams.entries()].sort(([a], [b]) => a.localeCompare(b));
    const qs = new URLSearchParams(sorted).toString();
    return (moved.pathname.slice(prefix.length) || "/") + (qs ? `?${qs}` : "");
}
