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
