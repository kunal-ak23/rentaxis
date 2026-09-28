import { AUTH_REASON_HEADER, EXPECTED_TENANT_HEADER, FORWARDED_HEADER, MUTATING_METHODS, NO_ORG, ORG_MISMATCH_HEADER, SESSION_ENDED_HEADER } from "./orgHeaders";

/**
 * One wrapper around `window.fetch` for every browser call to /api/proxy (and
 * /api/upload, which applies the proxy's rules itself).
 *
 * The dashboard makes ~150 raw `fetch("/api/proxy/…")` calls across ~75 files,
 * so session and organisation handling cannot live in each page. Installed
 * once by the authenticated layout (never on the auth pages), it:
 *  - stamps each mutating call with the organisation the page was loaded for
 *    (break round 1, F3; the proxy 409s a mismatch);
 *  - reports the proxy's own 401 — the session cookie expired, was cleared or
 *    revoked — so the layout can send the user to sign in (F6), instead of the page showing
 *    "Request failed (status 401)" or a false "nothing here" state;
 *  - reports the proxy's own org-mismatch 409 (F3).
 * The caller always receives the real response.
 */
export type ProxyFetchGuardOptions = {
    /** The organisation this page was loaded for; "" when none (Global View). */
    getExpectedOrg: () => string;
    /** The proxy's own 401 (X-Session-Ended): the session is gone. */
    onUnauthorized: () => void;
    onOrgMismatch: () => void;
    /**
     * A 401 the backend gave (e.g. "Organisation is not active" for the org
     * in the cookie), or its 403 NOT_A_MEMBER (the org in the cookie is no
     * longer one of the user's). Not a signed-out session: signing in again
     * would keep the same cookie and loop. `reason` is the backend's
     * X-Auth-Reason (null when absent); the caller decides between signing
     * out (USER_INACTIVE) and repairing the org selection.
     */
    onBackendUnauthorized?: (message: string, reason: string | null) => void;
};

const GUARD = Symbol.for("rentaxis.proxyFetchGuard");

/**
 * Break round 3, F1: a full navigation (a link to another document, a typed
 * URL, a reload) cancels every in-flight fetch of the page being left. Chrome
 * rejects each with `TypeError: Failed to fetch` after pagehide/unload, while
 * microtasks still run, so every page's `catch (err) { console.error(err) }`
 * logged a failure nobody will ever see. A rejection that lands while the page
 * is going away is swallowed: the promise simply never settles (the document is
 * being destroyed). A page put in the back/forward cache (`persisted`) is not
 * destroyed, so it is not treated as leaving; pageshow resets either way.
 * Safety net (review M1): the page becoming visible again also resets it — a
 * real unload only ever goes hidden, so this never un-silences one, but if some
 * engine fired a non-persisted pagehide and kept the page, failed requests
 * would otherwise hang (spinners forever) until the page was shown or became
 * visible again.
 *
 * Only fetch() and the Response body readers are covered. A caller reading
 * `res.body.getReader()` or a `res.clone()` directly is not (review M2); no
 * page does that today.
 * A real network failure — the page staying put — still rejects.
 */
function never<T>(): Promise<T> {
    return new Promise<T>(() => {});
}

const BODY_READERS = ["json", "text", "blob", "arrayBuffer", "formData"] as const;

/** Body reads cancelled by the unload are swallowed the same way (headers may land before it). */
function silenceBodyOnLeave(res: Response, leaving: () => boolean): Response {
    for (const name of BODY_READERS) {
        const read = res[name];
        if (typeof read !== "function") continue;
        (res as unknown as Record<string, () => Promise<unknown>>)[name] = () =>
            (read as () => Promise<unknown>).call(res).catch((err: unknown) => {
                if (leaving()) return never();
                throw err;
            });
    }
    return res;
}

function proxyPath(input: RequestInfo | URL): string | null {
    const raw = typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;
    let url: URL;
    try {
        url = new URL(raw, window.location.href);
    } catch {
        return null;
    }
    if (url.origin !== window.location.origin) return null;
    // /api/upload forwards to the backend itself with the same identity rules
    // (lib/session/backendIdentity.ts), so it gets the same expected-org stamp
    // and the same session/org-mismatch handling.
    return url.pathname.startsWith("/api/proxy/") || url.pathname === "/api/upload" ? url.pathname : null;
}

export function installProxyFetchGuard(opts: ProxyFetchGuardOptions): () => void {
    const original = window.fetch;
    let leaving = false;
    const isLeaving = () => leaving;
    const onPageHide = (e: PageTransitionEvent) => { if (!e.persisted) leaving = true; };
    const onPageShow = () => { leaving = false; };
    const onVisibility = () => { if (document.visibilityState === "visible") leaving = false; };
    window.addEventListener("pagehide", onPageHide);
    window.addEventListener("pageshow", onPageShow);
    document.addEventListener("visibilitychange", onVisibility);
    // The original fetch, with rejections (and body-read rejections) caused by leaving the page swallowed.
    const send = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> =>
        original(input, init).then(
            res => silenceBodyOnLeave(res, isLeaving),
            (err: unknown) => {
                if (leaving) return never<Response>();
                throw err;
            },
        );

    const guarded = async function (input: RequestInfo | URL, init?: RequestInit): Promise<Response> {
        if (!proxyPath(input)) return send(input, init);

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

        const res = await send(nextInput, nextInit);
        // The proxy's own signals count only on a response it answered itself
        // (not stamped as forwarded to the backend).
        const proxyOwn = !res.headers.has(FORWARDED_HEADER);
        const reason = res.headers.get(AUTH_REASON_HEADER);
        if (res.status === 401 || (res.status === 403 && reason === "NOT_A_MEMBER")) {
            if (res.status === 401 && proxyOwn && res.headers.get(SESSION_ENDED_HEADER) === "1") opts.onUnauthorized();
            else if (opts.onBackendUnauthorized) {
                const notify = opts.onBackendUnauthorized;
                // The body is read from a clone: the caller still gets the untouched response.
                void res.clone().text().catch(() => "").then(text => notify(text, reason));
            }
        } else if (res.status === 409 && proxyOwn && res.headers.get(ORG_MISMATCH_HEADER) === "1") {
            opts.onOrgMismatch();
        }
        return res;
    } as typeof fetch & { [GUARD]?: true };
    guarded[GUARD] = true;
    window.fetch = guarded;

    return () => {
        window.removeEventListener("pagehide", onPageHide);
        window.removeEventListener("pageshow", onPageShow);
        document.removeEventListener("visibilitychange", onVisibility);
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
 * A post-login destination from `?callbackUrl=`: a path on THIS origin, never
 * the auth pages. The single gate for every redirect parameter the web
 * consumes (today: the login page's callbackUrl; NextAuth's own redirect
 * callback is the v4 default, which already refuses other origins).
 *
 * Review fix (open redirect): checking only the leading characters is not
 * enough — the URL parser strips TAB/LF/CR, so "/\t/evil.example" became
 * "//evil.example" inside location.assign. Control characters and
 * backslashes are refused outright, then the value is parsed against this
 * origin and must still be on it.
 */
export function safeCallbackUrl(
    raw: string | null | undefined,
    origin: string | null = typeof window !== "undefined" ? window.location.origin : null,
): string | null {
    if (!raw || !origin) return null;
    if (/[\x00-\x1F\x7F\\]/.test(raw)) return null;
    if (!raw.startsWith("/") || raw.startsWith("//")) return null;
    // Fix round 2: dot segments ("." / ".." / "%2e%2e") collapse while the
    // URL is parsed, so "/..//evil.example" came out as "//evil.example".
    // No legitimate callback has one; refuse them outright.
    if (raw.split(/[?#]/)[0].split("/").some(seg => /^(?:\.|%2e){1,2}$/i.test(seg))) return null;
    let u: URL;
    try {
        u = new URL(raw, origin);
    } catch {
        return null;
    }
    if (u.origin !== origin) return null;
    // The auth pages (and NextAuth's /api/auth) are excluded on the decoded,
    // lower-cased path, so "/en/%61uth/login" or "/EN/AUTH/login" do not slip by.
    let decodedPath: string;
    try {
        decodedPath = decodeURIComponent(u.pathname).toLowerCase();
    } catch {
        return null;
    }
    if (/^\/(?:(?:en|ar)\/)?auth(?:\/|$)|^\/api\/auth(?:\/|$)/.test(decodedPath)) return null;
    const result = u.pathname + u.search + u.hash;
    // Validate the OUTPUT too, whatever the checks above let through.
    return isSameOriginPath(result, origin) ? result : null;
}

/**
 * The last gate before a value reaches location.assign: exactly one leading
 * "/" not followed by "/" or "\" (never protocol-relative), and it resolves
 * to `origin`.
 */
export function isSameOriginPath(path: string, origin: string): boolean {
    if (!/^\/(?![/\\])/.test(path)) return false;
    try {
        return new URL(path, origin).origin === origin;
    } catch {
        return false;
    }
}
