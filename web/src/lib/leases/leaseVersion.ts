// src/lib/leases/leaseVersion.ts
import { ApiError } from "@/lib/api/facilities";

/**
 * Break-it round 2 (contracts2) F2/F3: the server's answer to a write that named a
 * lease version it has moved past — 409 with code `lease.changed` ("This contract
 * changed since you opened it — review it again"). The screen reloads the lease and
 * lets the user review; it is never retried (see `isContentionError`).
 */
export function isLeaseChanged(e: unknown): boolean {
    if (!(e instanceof ApiError) || e.status !== 409 || !e.body) return false;
    try {
        const parsed: unknown = JSON.parse(e.body);
        return !!parsed && typeof parsed === "object" && (parsed as { code?: unknown }).code === "lease.changed";
    } catch {
        return false;
    }
}
