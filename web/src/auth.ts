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
                // Here we will proxy login to Spring Boot API and get JWT
                // Mocking for Phase 3 setup:
                if (credentials?.email === "admin@rentaxis.com" && credentials?.password === "password") {
                    return {
                        id: "1",
                        name: "Admin User",
                        email: "admin@rentaxis.com",
                        tenantId: "mock-tenant-123"
                    } as any;
                }
                return null; // Reject login
            },
        }),
    ],
    callbacks: {
        async jwt({ token, user }) {
            if (user) {
                token.tenantId = (user as any).tenantId;
            }
            return token;
        },
        async session({ session, token }) {
            if (token) {
                (session.user as any).tenantId = token.tenantId;
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
