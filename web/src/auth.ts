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
                    const res = await fetch(`${process.env.BACKEND_URL || "http://localhost:8080"}/api/auth/login`, {
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
                        };
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
        signIn: "/auth/login",
    },
    session: {
        strategy: "jwt",
    },
};
