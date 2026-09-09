import "next-auth";
import "next-auth/jwt";

declare module "next-auth" {
    interface Session {
        user: {
            id: string;
            email: string;
            name: string;
            role: string;
            tenantId: string;
            tenantIds: string[];
        };
        /** True once the backend reports the account no longer exists. */
        revoked?: boolean;
    }

    interface User {
        id: string;
        role: string;
        tenantId: string;
        tenantIds: string[];
    }
}

declare module "next-auth/jwt" {
    interface JWT {
        id: string;
        role: string;
        tenantId: string;
        tenantIds: string[];
        /** Epoch millis of the last successful re-check against the backend. */
        revalidatedAt?: number;
        /** True once the backend reports the account no longer exists. */
        revoked?: boolean;
    }
}
