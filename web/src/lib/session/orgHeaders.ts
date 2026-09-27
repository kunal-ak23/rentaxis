/**
 * Break round 1, F3: the active organisation lives in one cookie shared by
 * every tab, so a form open in tab A was submitted into the organisation tab B
 * had just switched to. The web client stamps each mutating /api/proxy request
 * with the organisation its page was loaded for, and the proxy refuses the
 * request (409) when that no longer matches the cookie.
 *
 * Shared by the proxy (server) and the fetch guard (browser): keep it free of
 * browser or Node-only imports.
 */
export const EXPECTED_TENANT_HEADER = "X-Expected-Tenant-Id";
/** Header value for "this page was loaded with no organisation selected" (SUPER_ADMIN Global View). */
export const NO_ORG = "none";
/** Set on the proxy's own 409 so the client can tell it from a backend conflict. */
export const ORG_MISMATCH_HEADER = "X-Org-Mismatch";
export const ORG_MISMATCH_CODE = "ORG_CHANGED";

export const MUTATING_METHODS: ReadonlySet<string> = new Set(["POST", "PUT", "PATCH", "DELETE"]);

/**
 * Set on the proxy's OWN 401s (no session, revoked session). Only these mean
 * "sign in again"; a 401 from the backend (e.g. "Organisation is not active"
 * for the org in the cookie) would loop sign-in → same cookie → 401 forever.
 */
export const SESSION_ENDED_HEADER = "X-Session-Ended";

/**
 * Stamped by the proxy on every response it forwards to the backend. Next
 * applies the middleware's response headers first and the backend's after
 * them (external rewrite), so backend headers cannot be stripped here; but a
 * header the middleware sets is always present on a forwarded response. The
 * client therefore trusts SESSION_ENDED_HEADER / ORG_MISMATCH_HEADER only on
 * a response WITHOUT this stamp: one the proxy answered itself.
 */
export const FORWARDED_HEADER = "X-Rentaxis-Forwarded";
