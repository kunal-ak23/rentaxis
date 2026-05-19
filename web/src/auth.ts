import { NextAuthOptions } from "next-auth";
import CredentialsProvider from "next-auth/providers/credentials";

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
                    const body: Record<string, string> = {
                        email: credentials.email,
                        password: credentials.password,
                    };
                    if ((credentials as { tenantId?: string }).tenantId) {
                        body.tenantId = (credentials as { tenantId: string }).tenantId;
                    }
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
            }
            return token;
        },
        async session({ session, token }) {
            if (token) {
                session.user.tenantId = token.tenantId as string;
                session.user.role = token.role as string;
                session.user.id = token.id as string;
                session.user.tenantIds = (token.tenantIds as string[]) || [];
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
    },
};
