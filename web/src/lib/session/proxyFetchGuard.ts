import { EXPECTED_TENANT_HEADER, MUTATING_METHODS, NO_ORG, ORG_MISMATCH_HEADER } from "./orgHeaders";

/**
 * One wrapper around `window.fetch` for every browser call to /api/proxy.
 *
 * The dashboard makes ~150 raw `fetch("/api/proxy/…")` calls across ~75 files,
 * so session and organisation handling cannot live in each page. Installed
 * once by the authenticated layout (never on the auth pages), it:
 *  - stamps each mutating call with the organisation the page was loaded for
 *    (break round 1, F3; the proxy 409s a mismatch);
 *  - reports a 401 — the session cookie expired, was cleared or revoked — so
 *    the layout can send the user to sign in (F6), instead of the page showing
 *    "Request failed (status 401)" or a false "nothing here" state;
 *  - reports the proxy's own org-mismatch 409 (F3).
 * The caller always receives the real response.
 */
export type ProxyFetchGuardOptions = {
    /** The organisation this page was loaded for; "" when none (Global View). */
    getExpectedOrg: () => string;
    onUnauthorized: () => void;
    onOrgMismatch: () => void;
};

const GUARD = Symbol.for("rentaxis.proxyFetchGuard");

function proxyPath(input: RequestInfo | URL): string | null {
    const raw = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    let url: URL;
    try {
        url = new URL(raw, window.location.href);
    } catch {
        return null;
    }
    if (url.origin !== window.location.origin) return null;
    return url.pathname.startsWith("/api/proxy/") ? url.pathname : null;
}

export function installProxyFetchGuard(opts: ProxyFetchGuardOptions): () => void {
    const original = window.fetch;
    const guarded = async function (input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
        if (!proxyPath(input)) return original(input, init);

        const method = (init?.method ?? (input instanceof Request ? input.method : "GET")).toUpperCase();
        let nextInput = input;
        let nextInit = init;
        if (MUTATING_METHODS.has(method)) {
            const expected = opts.getExpectedOrg() || NO_ORG;
            if (input instanceof Request && !init?.headers) {
                const headers = new Headers(input.headers);
                headers.set(EXPECTED_TENANT_HEADER, expected);
                nextInput = new Request(input, { headers });
            } else {
                const headers = new Headers(init?.headers ?? (input instanceof Request ? input.headers : undefined));
                headers.set(EXPECTED_TENANT_HEADER, expected);
                nextInit = { ...init, headers };
            }
        }

        const res = await original(nextInput, nextInit);
        if (res.status === 401) {
            opts.onUnauthorized();
        } else if (res.status === 409 && res.headers.get(ORG_MISMATCH_HEADER) === "1") {
            opts.onOrgMismatch();
        }
        return res;
    } as typeof fetch & { [GUARD]?: true };
    guarded[GUARD] = true;
    window.fetch = guarded;

    return () => {
        if (window.fetch === guarded) window.fetch = original;
    };
}

/**
 * Where to send a user whose session has gone, keeping the page they were on.
 * Null on the auth pages themselves, so a 401 there can never loop.
 */
export function loginUrlFor(loc: { pathname: string; search: string }): string | null {
    if (/^\/(?:(?:en|ar)\/)?auth(?:\/|$)/.test(loc.pathname)) return null;
    const locale = /^\/ar(?:\/|$)/.test(loc.pathname) ? "ar" : "en";
    return `/${locale}/auth/login?callbackUrl=${encodeURIComponent(loc.pathname + loc.search)}`;
}

/**
 * A post-login destination from `?callbackUrl=`: same-origin paths only (no
 * scheme, no protocol-relative `//host`), never back into the auth pages.
 */
export function safeCallbackUrl(raw: string | null | undefined): string | null {
    if (!raw) return null;
    if (!raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) return null;
    if (/^\/(?:(?:en|ar)\/)?auth(?:\/|$)/.test(raw)) return null;
    return raw;
}
