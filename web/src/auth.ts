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
                    const res = await fetch("http://localhost:8080/api/auth/login", {
                        method: "POST",
                        headers: { "Content-Type": "application/json" },
                        body: JSON.stringify({
                            email: credentials.email,
                            password: credentials.password
                        })
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
                        } as any;
                    }
                } catch (e) {
                    console.error("Auth Exception:", e);
                }

                return null;
            },
        }),
    ],
    callbacks: {
        async jwt({ token, user }) {
            if (user) {
                token.tenantId = (user as any).tenantId;
                token.role = (user as any).role;
                token.id = user.id;
                token.tenantIds = (user as any).tenantIds || [];
            }
            return token;
        },
        async session({ session, token }) {
            if (token) {
                (session.user as any).tenantId = token.tenantId;
                (session.user as any).role = token.role;
                (session.user as any).id = token.id;
                (session.user as any).tenantIds = token.tenantIds || [];
            }
            return session;
        },
    },
    pages: {
        signIn: "/auth/login",
    },
    session: {
        strategy: "jwt",
    },
};
