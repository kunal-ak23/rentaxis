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

/**
 * Review A M3: the lease as this screen holds it, at the version a cheque-grid write
 * left behind — the same object otherwise, so what reads the lease's other fields
 * (the draft editor's form) sees nothing change. Only `version` is replaced.
 */
export function withVersion<T extends { version?: number | null }>(lease: T, version: number): T {
    return lease.version === version ? lease : { ...lease, version };
}

/**
 * Two reads of the same lease that differ at most in `version` (a shallow compare:
 * `withVersion` keeps every other field's reference). The draft editor resets its
 * form from the lease only when this is false, so adopting a version does not
 * throw away what the user is typing.
 */
export function sameExceptVersion(a: object, b: object): boolean {
    if (a === b) return true;
    const ra = a as Record<string, unknown>;
    const rb = b as Record<string, unknown>;
    const keys = new Set([...Object.keys(ra), ...Object.keys(rb)]);
    for (const k of keys) {
        if (k === "version") continue;
        if (ra[k] !== rb[k]) return false;
    }
    return true;
}
