import { NextAuthOptions } from "next-auth";
import CredentialsProvider from "next-auth/providers/credentials";

/** How often an existing session is re-checked against current user state. */
export const REVALIDATE_INTERVAL_MS = Number(
    process.env.SESSION_REVALIDATE_INTERVAL_MS ?? 5 * 60 * 1000,
);

/** Upper bound on how long a session survives without any successful re-check. */
export const SESSION_MAX_AGE_SECONDS = Number(
    process.env.SESSION_MAX_AGE_SECONDS ?? 12 * 60 * 60,
);

export function shouldRevalidate(revalidatedAt: number | undefined, now = Date.now()): boolean {
    // No stamp means a token issued before this shipped — check it immediately.
    if (typeof revalidatedAt !== "number") return true;
    return now - revalidatedAt >= REVALIDATE_INTERVAL_MS;
}

type CurrentUser =
    | { status: "ok"; role: string; tenantId?: string }
    | { status: "revoked" }
    | { status: "unavailable" };

/**
 * Reads the user's *current* role and tenant from the backend.
 *
 * A 404 means the account is gone, which is the revocation signal. Anything
 * else — a network failure, a 5xx — is reported as unavailable so the caller
 * can fail open: a backend blip must not sign every user out.
 */
export async function fetchCurrentUser(
    userId: string | undefined,
    role?: string,
    homeTenantId?: string,
): Promise<CurrentUser> {
    if (!userId) return { status: "unavailable" };

    const base = process.env.BACKEND_URL || "http://localhost:8080";
    try {
        // /api/auth/me reads the caller from the backend's verified principal
        // (PR #342), which ApiSecurityFilter builds on the legacy path from the
        // full header set, the same one proxy.ts sends: user, role, and the home
        // tenant a non-SUPER_ADMIN is authorized against. The role sent is the
        // session's last-known one; the response carries the current one.
        const res = await fetch(`${base}/api/auth/me`, {
            headers: {
                "X-User-Id": userId,
                ...(role ? { "X-User-Role": role } : {}),
                ...(homeTenantId ? { "X-User-Tenant-Id": homeTenantId } : {}),
                ...(process.env.INTERNAL_PROXY_SECRET
                    ? { "X-Internal-Auth": process.env.INTERNAL_PROXY_SECRET }
                    : {}),
            },
            cache: "no-store",
        });

        if (res.status === 404) return { status: "revoked" };
        if (!res.ok) return { status: "unavailable" };

        const profile = await res.json();
        if (!profile?.role) return { status: "unavailable" };
        return { status: "ok", role: profile.role, tenantId: profile.tenantId ?? undefined };
    } catch {
        return { status: "unavailable" };
    }
}

export const authOptions: NextAuthOptions = {
    providers: [
        CredentialsProvider({
            name: "Credentials",
            credentials: {
                email: { label: "Email", type: "email" },
                password: { label: "Password", type: "password" },
            },
            async authorize(credentials) {
                if (!credentials?.email || !credentials?.password) return null;

                try {
                    // tenantId is optional; the login page resubmits with it
                    // set after the user picks an org from the 409 picker.
                    // NextAuth's CredentialsConfig types `credentials` from
                    // the `credentials:` field above (email, password) — we
                    // accept an extra field at runtime via an unknown cast.
                    const tenantId = (credentials as unknown as { tenantId?: string }).tenantId;
                    const body: Record<string, string> = {
                        email: credentials.email,
                        password: credentials.password,
                    };
                    if (tenantId) body.tenantId = tenantId;
                    const res = await fetch(`${process.env.BACKEND_URL || "http://localhost:8080"}/api/auth/login`, {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify(body),
                    });

                    if (res.ok) {
                        const user = await res.json();
                        // user object from backend: {id, email, name, role, tenantId, tenantIds}
                        return {
                            id: user.id,
                            email: user.email,
                            name: user.name,
                            role: user.role,
                            tenantId: user.tenantId,
                            tenantIds: user.tenantIds || [],
                        };
                    }

                    // 409 — email is registered in multiple tenants and the
                    // submitted password matched in more than one. The body
                    // carries the candidate list; surface it to the login
                    // page so the user can pick a tenant. NextAuth's
                    // credentials provider can't return structured data, so
                    // we encode the picker payload in the thrown error
                    // message — the login page parses it back out.
                    if (res.status === 409) {
                        const body = await res.json().catch(() => ({ tenants: [] }));
                        // Error code prefix recognized by /auth/login page.
                        throw new Error("LOGIN_AMBIGUOUS:" + JSON.stringify(body.tenants ?? []));
                    }
                } catch (e) {
                    // Re-throw the structured ambiguous-login signal; swallow
                    // anything else (network, JSON parse) as a generic 401.
                    if (e instanceof Error && e.message.startsWith("LOGIN_AMBIGUOUS:")) {
                        throw e;
                    }
                    console.error("Auth Exception:", e);
                }

                return null;
            },
        }),
    ],
    callbacks: {
        async jwt({ token, user }) {
            if (user) {
                token.tenantId = user.tenantId;
                token.role = user.role;
                token.id = user.id;
                token.tenantIds = user.tenantIds || [];
                token.revalidatedAt = Date.now();
                return token;
            }

            // Re-validate an existing session against current user state.
            //
            // The session is a JWT with no server-side lookup, so changing a
            // user's role, deactivating them, or removing a membership did not
            // touch an already-issued browser session: the old role kept
            // working for the life of the token, and the proxy forwarded it to
            // the backend verbatim. A demoted PROPERTY_MANAGER or an offboarded
            // TENANT_ADMIN kept full prior access — financial screens included —
            // long after an admin believed it was revoked.
            //
            // Checked at most once per REVALIDATE_INTERVAL_MS so this costs one
            // backend call per user per interval, not one per request.
            if (!shouldRevalidate(token.revalidatedAt as number | undefined)) {
                return token;
            }

            const current = await fetchCurrentUser(
                token.id as string | undefined,
                token.role as string | undefined,
                token.tenantId as string | undefined,
            );

            if (current.status === "revoked") {
                // The user no longer exists. Mark the token; proxy.ts refuses
                // every API call carrying a revoked token, and the session
                // callback surfaces it so the UI can sign out.
                token.revoked = true;
                token.revalidatedAt = Date.now();
                return token;
            }

            if (current.status === "ok") {
                token.role = current.role;
                if (current.tenantId) token.tenantId = current.tenantId;
                token.revoked = false;
                token.revalidatedAt = Date.now();
                return token;
            }

            // status === "unavailable": a transient backend/network failure.
            // Leave the token untouched AND leave revalidatedAt alone, so the
            // next request retries rather than waiting out another full
            // interval. Deliberately fail-open: a backend blip must not sign
            // every user out.
            return token;
        },
        async session({ session, token }) {
            if (token) {
                session.user.tenantId = token.tenantId as string;
                session.user.role = token.role as string;
                session.user.id = token.id as string;
                session.user.tenantIds = (token.tenantIds as string[]) || [];
                // Surfaced so the client can sign the user out rather than
                // leaving them on a dashboard whose every request 401s.
                session.revoked = token.revoked === true;
            }
            return session;
        },
    },
    pages: {
        // IMPORTANT: callers must invoke signIn("credentials", { ..., redirect: false }).
        // The credentials provider's authorize() throws `LOGIN_AMBIGUOUS:<json>`
        // for the multi-tenant disambiguation case. If `redirect: true` (the
        // NextAuth default) reaches this page, that error string — including
        // tenant IDs and names — is encoded into `?error=...` in the URL and
        // ends up in browser history, referer headers, and proxy/CDN logs.
        // The login page at this route uses `redirect: false`; new callers
        // must too.
        signIn: "/auth/login",
    },
    session: {
        strategy: "jwt",
        // NextAuth's default is 30 days. Combined with the revalidation above
        // this bounds how long a stale session can survive a total backend
        // outage, which is the only window where revalidation fails open.
        maxAge: SESSION_MAX_AGE_SECONDS,
    },
};
